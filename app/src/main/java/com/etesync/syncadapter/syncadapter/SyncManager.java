/*
* Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the GNU Public License v3.0
* which accompanies this distribution, and is available at
* http://www.gnu.org/licenses/gpl.html
*/
package com.etesync.syncadapter.syncadapter;

import android.accounts.Account;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;

import com.etesync.syncadapter.AccountSettings;
import com.etesync.syncadapter.App;
import com.etesync.syncadapter.Constants;
import com.etesync.syncadapter.HttpClient;
import com.etesync.syncadapter.InvalidAccountException;
import com.etesync.syncadapter.NotificationHelper;
import com.etesync.syncadapter.R;
import com.etesync.syncadapter.journalmanager.Exceptions;
import com.etesync.syncadapter.journalmanager.JournalEntryManager;
import com.etesync.syncadapter.model.CollectionInfo;
import com.etesync.syncadapter.model.EntryEntity;
import com.etesync.syncadapter.model.JournalEntity;
import com.etesync.syncadapter.model.SyncEntry;
import com.etesync.syncadapter.resource.LocalCollection;
import com.etesync.syncadapter.resource.LocalResource;
import com.etesync.syncadapter.ui.DebugInfoActivity;

import org.apache.commons.collections4.ListUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import at.bitfire.ical4android.CalendarStorageException;
import at.bitfire.ical4android.InvalidCalendarException;
import at.bitfire.vcard4android.ContactsStorageException;
import io.requery.Persistable;
import io.requery.sql.EntityDataStore;
import okhttp3.OkHttpClient;

import static com.etesync.syncadapter.Constants.KEY_ACCOUNT;

abstract public class SyncManager {
    protected final NotificationHelper notificationManager;
    protected final String journalUid;

    protected final Context context;
    protected final Account account;
    protected final Bundle extras;
    protected final String authority;
    protected final SyncResult syncResult;
    protected final CollectionInfo.Type serviceType;

    protected final AccountSettings settings;
    protected LocalCollection localCollection;

    protected OkHttpClient httpClient;

    protected JournalEntryManager journal;
    private JournalEntity _journalEntity;

    private EntityDataStore<Persistable> data;

    /**
     * remote CTag (uuid of the last entry on the server). We update it when we fetch/push and save when everything works.
     */
    private String remoteCTag = null;

    /**
     * Syncable local journal entries.
     */
    private List<JournalEntryManager.Entry> localEntries;

    /**
     * Syncable remote journal entries (fetch from server).
     */
    private List<JournalEntryManager.Entry> remoteEntries;

    /**
     * Dirty and deleted resources. We need to save them so we safely ignore ones that were added after we started.
     */
    private List<LocalResource> localDeleted;
    private LocalResource[] localDirty;

    public SyncManager(Context context, Account account, AccountSettings settings, Bundle extras, String authority, SyncResult syncResult, String journalUid, CollectionInfo.Type serviceType) throws InvalidAccountException {
        this.context = context;
        this.account = account;
        this.settings = settings;
        this.extras = extras;
        this.authority = authority;
        this.syncResult = syncResult;
        this.serviceType = serviceType;

        // create HttpClient with given logger
        httpClient = HttpClient.create(context, account);

        // dismiss previous error notifications
        this.journalUid = journalUid;
        notificationManager = new NotificationHelper(context, journalUid, notificationId());
        notificationManager.cancel();

        data = ((App) context.getApplicationContext()).getData();
    }

    protected abstract int notificationId();

    protected abstract String getSyncErrorTitle();

    @TargetApi(21)
    public void performSync() {
        int syncPhase = R.string.sync_phase_prepare;
        try {
            App.log.info("Sync phase: " + context.getString(syncPhase));
            if (!prepare()) {
                App.log.info("No reason to synchronize, aborting");
                return;
            }

            if (Thread.interrupted())
                return;
            syncPhase = R.string.sync_phase_query_capabilities;
            App.log.info("Sync phase: " + context.getString(syncPhase));
            queryCapabilities();

            if (Thread.interrupted())
                return;
            syncPhase = R.string.sync_phase_prepare_local;
            App.log.info("Sync phase: " + context.getString(syncPhase));
            prepareLocal();

            if (Thread.interrupted())
                return;
            syncPhase = R.string.sync_phase_fetch_entries;
            App.log.info("Sync phase: " + context.getString(syncPhase));
            fetchEntries();

            if (Thread.interrupted())
                return;
            syncPhase = R.string.sync_phase_apply_remote_entries;
            App.log.info("Sync phase: " + context.getString(syncPhase));
            applyRemoteEntries();

            /* Create journal entries out of local changes. */
            if (Thread.interrupted())
                return;
            syncPhase = R.string.sync_phase_create_local_entries;
            App.log.info("Sync phase: " + context.getString(syncPhase));
            createLocalEntries();

            if (Thread.interrupted())
                return;
            syncPhase = R.string.sync_phase_apply_local_entries;
            App.log.info("Sync phase: " + context.getString(syncPhase));
            applyLocalEntries();

            if (Thread.interrupted())
                return;
            syncPhase = R.string.sync_phase_push_entries;
            App.log.info("Sync phase: " + context.getString(syncPhase));
            pushEntries();

            /* Cleanup and finalize changes */
            if (Thread.interrupted())
                return;
            syncPhase = R.string.sync_phase_post_processing;
            App.log.info("Sync phase: " + context.getString(syncPhase));
            postProcess();
        } catch (IOException e) {
            App.log.log(Level.WARNING, "I/O exception during sync, trying again later", e);
            syncResult.stats.numIoExceptions++;
        } catch (Exceptions.ServiceUnavailableException e) {
            syncResult.stats.numIoExceptions++;
            syncResult.delayUntil = (e.retryAfter > 0) ? e.retryAfter : Constants.DEFAULT_RETRY_DELAY;
        } catch (InterruptedException e) {

        } catch (Exception | OutOfMemoryError e) {
            if (e instanceof Exceptions.UnauthorizedException) {
                syncResult.stats.numAuthExceptions++;
            } else if (e instanceof Exceptions.HttpException) {
                syncResult.stats.numParseExceptions++;
            } else if (e instanceof CalendarStorageException || e instanceof ContactsStorageException) {
                syncResult.databaseError = true;
            } else if (e instanceof Exceptions.IntegrityException) {
                syncResult.stats.numParseExceptions++;
            } else {
                syncResult.stats.numParseExceptions++;
            }

            notificationManager.setThrowable(e);

            final Intent detailsIntent = notificationManager.getDetailsIntent();
            detailsIntent.putExtra(KEY_ACCOUNT, account);
            if (!(e instanceof Exceptions.UnauthorizedException)) {
                detailsIntent.putExtra(DebugInfoActivity.KEY_AUTHORITY, authority);
                detailsIntent.putExtra(DebugInfoActivity.KEY_PHASE, syncPhase);
            }

            notificationManager.notify(getSyncErrorTitle(), context.getString(syncPhase));
        }
    }


    /**
     * Prepares synchronization (for instance, allocates necessary resources).
     *
     * @return whether actual synchronization is required / can be made. true = synchronization
     * shall be continued, false = synchronization can be skipped
     */
    protected boolean prepare() throws ContactsStorageException, CalendarStorageException {
        return true;
    }

    abstract protected void processSyncEntry(SyncEntry cEntry) throws IOException, ContactsStorageException, CalendarStorageException, InvalidCalendarException;

    private JournalEntity getJournalEntity() {
        if (_journalEntity == null)
            _journalEntity = data.select(JournalEntity.class).where(JournalEntity.UID.eq(journal.getUid())).limit(1).get().first();
        return _journalEntity;
    }

    private void persistSyncEntry(String uid, SyncEntry syncEntry) {
        EntryEntity entry = new EntryEntity();
        entry.setUid(uid);
        entry.setContent(syncEntry);
        entry.setJournal(getJournalEntity());
        data.insert(entry);
    }

    protected void applyLocalEntries() throws IOException, ContactsStorageException, CalendarStorageException, Exceptions.HttpException, InvalidCalendarException, InterruptedException {
        // FIXME: Need a better strategy
        // We re-apply local entries so our changes override whatever was written in the remote.
        String strTotal = String.valueOf(localEntries.size());
        int i = 0;

        for (JournalEntryManager.Entry entry : localEntries) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            i++;
            App.log.info("Processing (" + String.valueOf(i) + "/" + strTotal + ") " + entry.toString());

            SyncEntry cEntry = SyncEntry.fromJournalEntry(settings.password(), entry);
            persistSyncEntry(entry.getUuid(), cEntry);
            if (cEntry.isAction(SyncEntry.Actions.DELETE)) {
                continue;
            }
            App.log.info("Processing resource for journal entry");
            processSyncEntry(cEntry);
        }
    }

    protected void queryCapabilities() throws IOException, CalendarStorageException, ContactsStorageException {
    }

    protected void fetchEntries() throws Exceptions.HttpException, ContactsStorageException, CalendarStorageException, Exceptions.IntegrityException {
        int count = data.count(EntryEntity.class).where(EntryEntity.JOURNAL.eq(getJournalEntity())).get().value();
        if ((remoteCTag != null) && (count == 0)) {
            // If we are updating an existing installation with no saved journal, we need to add
            remoteEntries = journal.getEntries(settings.password(), null);
            int i = 0;
            for (JournalEntryManager.Entry entry : remoteEntries) {
                SyncEntry cEntry = SyncEntry.fromJournalEntry(settings.password(), entry);
                persistSyncEntry(entry.getUuid(), cEntry);
                i++;
                if (remoteCTag.equals(entry.getUuid())) {
                    remoteEntries.subList(0, i).clear();
                    break;
                }
            }
        } else {
            remoteEntries = journal.getEntries(settings.password(), remoteCTag);
        }

        App.log.info("Fetched " + String.valueOf(remoteEntries.size()) + " entries");
    }

    protected void applyRemoteEntries() throws IOException, ContactsStorageException, CalendarStorageException, InvalidCalendarException, InterruptedException {
        // Process new vcards from server
        String strTotal = String.valueOf(remoteEntries.size());
        int i = 0;

        try {
            for (JournalEntryManager.Entry entry : remoteEntries) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                i++;
                App.log.info("Processing (" + String.valueOf(i) + "/" + strTotal + ") " + entry.toString());

                SyncEntry cEntry = SyncEntry.fromJournalEntry(settings.password(), entry);
                App.log.info("Processing resource for journal entry");
                processSyncEntry(cEntry);

                persistSyncEntry(entry.getUuid(), cEntry);

                remoteCTag = entry.getUuid();
            }
        } finally {
            saveSyncTag();
        }
    }

    protected void pushEntries() throws Exceptions.HttpException, IOException, ContactsStorageException, CalendarStorageException {
        // upload dirty contacts
        final int MAX_PUSH = 30;
        int pushed = 0;
        // FIXME: Deal with failure (someone else uploaded before we go here)
        try {
            if (!localEntries.isEmpty()) {
                for (List<JournalEntryManager.Entry> entries : ListUtils.partition(localEntries, MAX_PUSH)) {
                    journal.putEntries(entries, remoteCTag);
                    remoteCTag = entries.get(entries.size() - 1).getUuid();
                    pushed += entries.size();
                }
            }
        } finally {
            // FIXME: A bit fragile, we assume the order in createLocalEntries
            for (LocalResource local : localDeleted) {
                if (pushed-- <= 0) {
                    break;
                }
                local.delete();
            }

            for (LocalResource local : localDirty) {
                if (pushed-- <= 0) {
                    break;
                }
                App.log.info("Added/changed resource with UUID: " + local.getUuid());
                local.clearDirty(local.getUuid());
            }

            if (pushed > 0) {
                App.log.severe("Unprocessed localentries left, this should never happen!");
            }

            localDirty = null;
            localDeleted = null;

            saveSyncTag();
        }
    }

    protected void createLocalEntries() throws CalendarStorageException, ContactsStorageException, IOException {
        localEntries = new LinkedList<>();

        // Not saving, just creating a fake one until we load it from a local db
        JournalEntryManager.Entry previousEntry = (remoteCTag != null) ? JournalEntryManager.Entry.getFakeWithUid(remoteCTag) : null;

        for (LocalResource local : localDeleted) {
            SyncEntry entry = new SyncEntry(local.getContent(), SyncEntry.Actions.DELETE);
            JournalEntryManager.Entry tmp = new JournalEntryManager.Entry();
            tmp.update(settings.password(), entry.toJson(), previousEntry);
            previousEntry = tmp;
            localEntries.add(previousEntry);
        }

        for (LocalResource local : localDirty) {
            SyncEntry.Actions action;
            if (local.isLocalOnly()) {
                action = SyncEntry.Actions.ADD;
            } else {
                action = SyncEntry.Actions.CHANGE;
            }

            SyncEntry entry = new SyncEntry(local.getContent(), action);
            JournalEntryManager.Entry tmp = new JournalEntryManager.Entry();
            tmp.update(settings.password(), entry.toJson(), previousEntry);
            previousEntry = tmp;
            localEntries.add(previousEntry);
        }
    }

    /**
     */
    protected void prepareLocal() throws CalendarStorageException, ContactsStorageException, FileNotFoundException {
        localDeleted = processLocallyDeleted();
        localDirty = localCollection.getDirty();
        // This is done after fetching the local dirty so all the ones we are using will be prepared
        prepareDirty();

        remoteCTag = localCollection.getCTag();
    }


    /**
     * Delete unpublished locally deleted, and return the rest.
     * Checks Thread.interrupted() before each request to allow quick sync cancellation.
     */
    private List<LocalResource> processLocallyDeleted() throws CalendarStorageException, ContactsStorageException {
        LocalResource[] localList = localCollection.getDeleted();
        List<LocalResource> ret = new ArrayList<>(localList.length);

        for (LocalResource local : localList) {
            if (Thread.interrupted())
                return ret;

            App.log.info(local.getUuid() + " has been deleted locally -> deleting from server");
            ret.add(local);

            syncResult.stats.numDeletes++;
        }

        return ret;
    }

    protected void prepareDirty() throws CalendarStorageException, ContactsStorageException {
        // assign file names and UIDs to new entries
        App.log.info("Looking for local entries without a uuid");
        for (LocalResource local : localDirty) {
            if (local.getUuid() != null) {
                continue;
            }
            String uuid = UUID.randomUUID().toString();
            App.log.fine("Found local record #" + local.getId() + " without file name; assigning file name/UID based on " + uuid);
            local.updateFileNameAndUID(uuid);
        }
    }

    /**
     * For post-processing of entries, for instance assigning groups.
     */
    protected void postProcess() throws CalendarStorageException, ContactsStorageException {
    }

    private void saveSyncTag() throws CalendarStorageException, ContactsStorageException {
        App.log.info("Saving CTag=" + remoteCTag);
        localCollection.setCTag(remoteCTag);
    }


}
