/*
 * Copyright 2015 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.data.store;

import android.content.Context;
import android.os.HandlerThread;
import android.text.TextUtils;
import org.matrix.androidsdk.util.Log;

import org.matrix.androidsdk.HomeserverConnectionConfig;
import org.matrix.androidsdk.data.EventTimeline;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomAccountData;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.data.RoomSummary;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.ReceiptData;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.ThirdPartyIdentifier;
import org.matrix.androidsdk.rest.model.TokensChunkResponse;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.util.ContentUtils;
import org.matrix.androidsdk.util.MXOsHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * An in-file IMXStore.
 */
public class MXFileStore extends MXMemoryStore {
    private static final String LOG_TAG = "MXFileStore";

    // some constant values
    private static final int MXFILE_VERSION = 4;

    // ensure that there is enough messages to fill a tablet screen
    private static final int MAX_STORED_MESSAGES_COUNT = 50;

    private static final String MXFILE_STORE_FOLDER = "MXFileStore";
    private static final String MXFILE_STORE_METADATA_FILE_NAME = "MXFileStore";

    private static final String MXFILE_STORE_GZ_ROOMS_MESSAGES_FOLDER = "messages_gz";
    private static final String MXFILE_STORE_ROOMS_TOKENS_FOLDER = "tokens";
    private static final String MXFILE_STORE_GZ_ROOMS_STATE_FOLDER = "state_gz";
    private static final String MXFILE_STORE_ROOMS_SUMMARY_FOLDER = "summary";
    private static final String MXFILE_STORE_ROOMS_RECEIPT_FOLDER = "receipts";
    private static final String MXFILE_STORE_ROOMS_ACCOUNT_DATA_FOLDER = "accountData";
    private static final String MXFILE_STORE_USER_FOLDER = "users";

    // the data is read from the file system
    private boolean mIsReady = false;

    // the store is currently opening
    private boolean mIsOpening = false;

    // List of rooms to save on [MXStore commit]
    // filled with roomId
    private ArrayList<String> mRoomsToCommitForMessages;
    private ArrayList<String> mRoomsToCommitForStates;
    private ArrayList<String> mRoomsToCommitForSummaries;
    private ArrayList<String> mRoomsToCommitForAccountData;
    private ArrayList<String> mRoomsToCommitForReceipts;
    private ArrayList<String> mUserIdsToCommit;

    // Flag to indicate metaData needs to be store
    private boolean mMetaDataHasChanged = false;

    // The path of the MXFileStore folders
    private File mStoreFolderFile = null;
    private File mGzStoreRoomsMessagesFolderFile = null;
    private File mStoreRoomsTokensFolderFile = null;
    private File mGzStoreRoomsStateFolderFile = null;
    private File mStoreRoomsSummaryFolderFile = null;
    private File mStoreRoomsMessagesReceiptsFolderFile = null;
    private File mStoreRoomsAccountDataFolderFile = null;
    private File mStoreUserFolderFile = null;

    // the background thread
    private HandlerThread mHandlerThread = null;
    private MXOsHandler mFileStoreHandler = null;

    private boolean mIsKilled = false;

    private boolean mIsNewStorage = false;

    private boolean mAreUsersLoaded = false;

    // the read receipts are asynchronously loaded
    // keep a list of the remaining receipts to load
    private final ArrayList<String> mRoomReceiptsToLoad = new ArrayList<>();

    /**
     * Create the file store dirtrees
     */
    private void createDirTree(String userId) {
        // data path
        // MXFileStore/userID/
        // MXFileStore/userID/MXFileStore
        // MXFileStore/userID/Messages/
        // MXFileStore/userID/Tokens/
        // MXFileStore/userID/States/
        // MXFileStore/userID/Summaries/
        // MXFileStore/userID/receipt/<room Id>/receipts
        // MXFileStore/userID/accountData/
        // MXFileStore/userID/users/

        // create the dirtree
        mStoreFolderFile = new File(new File(mContext.getApplicationContext().getFilesDir(), MXFILE_STORE_FOLDER), userId);

        if (!mStoreFolderFile.exists()) {
            mStoreFolderFile.mkdirs();
        }

        mGzStoreRoomsMessagesFolderFile = new File(mStoreFolderFile, MXFILE_STORE_GZ_ROOMS_MESSAGES_FOLDER);
        if (!mGzStoreRoomsMessagesFolderFile.exists()) {
            mGzStoreRoomsMessagesFolderFile.mkdirs();
        }

        mStoreRoomsTokensFolderFile = new File(mStoreFolderFile, MXFILE_STORE_ROOMS_TOKENS_FOLDER);
        if (!mStoreRoomsTokensFolderFile.exists()) {
            mStoreRoomsTokensFolderFile.mkdirs();
        }

        mGzStoreRoomsStateFolderFile = new File(mStoreFolderFile, MXFILE_STORE_GZ_ROOMS_STATE_FOLDER);
        if (!mGzStoreRoomsStateFolderFile.exists()) {
            mGzStoreRoomsStateFolderFile.mkdirs();
        }

        mStoreRoomsSummaryFolderFile = new File(mStoreFolderFile, MXFILE_STORE_ROOMS_SUMMARY_FOLDER);
        if (!mStoreRoomsSummaryFolderFile.exists()) {
            mStoreRoomsSummaryFolderFile.mkdirs();
        }

        mStoreRoomsMessagesReceiptsFolderFile = new File(mStoreFolderFile, MXFILE_STORE_ROOMS_RECEIPT_FOLDER);
        if (!mStoreRoomsMessagesReceiptsFolderFile.exists()) {
            mStoreRoomsMessagesReceiptsFolderFile.mkdirs();
        }

        mStoreRoomsAccountDataFolderFile = new File(mStoreFolderFile, MXFILE_STORE_ROOMS_ACCOUNT_DATA_FOLDER);
        if (!mStoreRoomsAccountDataFolderFile.exists()) {
            mStoreRoomsAccountDataFolderFile.mkdirs();
        }

        mStoreUserFolderFile = new File(mStoreFolderFile, MXFILE_STORE_USER_FOLDER);
        if (!mStoreUserFolderFile.exists()) {
            mStoreUserFolderFile.mkdirs();
        }
    }

    /**
     * Default constructor
     * @param hsConfig the expected credentials
     * @param context the context.
     */
    public MXFileStore(HomeserverConnectionConfig hsConfig, Context context) {
        initCommon();
        setContext(context);

        mIsReady = false;
        mCredentials = hsConfig.getCredentials();

        mHandlerThread = new HandlerThread("MXFileStoreBackgroundThread_" + mCredentials.userId, Thread.MIN_PRIORITY);

        createDirTree(mCredentials.userId);

        // updated data
        mRoomsToCommitForMessages = new ArrayList<>();
        mRoomsToCommitForStates = new ArrayList<>();
        mRoomsToCommitForSummaries = new ArrayList<>();
        mRoomsToCommitForAccountData = new ArrayList<>();
        mRoomsToCommitForReceipts = new ArrayList<>();
        mUserIdsToCommit = new ArrayList<>();

        // check if the metadata file exists and if it is valid
        loadMetaData();

        if ( (null == mMetadata) ||
                (mMetadata.mVersion != MXFILE_VERSION) ||
                !TextUtils.equals(mMetadata.mUserId, mCredentials.userId) ||
                !TextUtils.equals(mMetadata.mAccessToken, mCredentials.accessToken)) {
            deleteAllData(true);
        }

        // create the metadata file if it does not exist
        if (null == mMetadata) {
            mIsNewStorage = true;
            mIsOpening = true;
            mHandlerThread.start();
            mFileStoreHandler = new MXOsHandler(mHandlerThread.getLooper());

            mMetadata = new MXFileStoreMetaData();
            mMetadata.mUserId = mCredentials.userId;
            mMetadata.mAccessToken = mCredentials.accessToken;
            mMetadata.mVersion = MXFILE_VERSION;
            mMetaDataHasChanged = true;
            saveMetaData();

            mEventStreamToken = null;

            mIsOpening = false;
            // nothing to load so ready to work
            mIsReady = true;
        }
    }

    /**
     * Killed the background thread.
     * @param isKilled killed status
     */
    private void setIsKilled(boolean isKilled) {
        synchronized (this) {
            mIsKilled = isKilled;
        }
    }

    /**
     * @return true if the background thread is killed.
     */
    private boolean isKilled() {
        boolean isKilled;

        synchronized (this) {
            isKilled = mIsKilled;
        }

        return isKilled;
    }

    /**
     * Save changes in the store.
     * If the store uses permanent storage like database or file, it is the optimised time
     * to commit the last changes.
     */
    @Override
    public void commit() {
        // Save data only if metaData exists
        if ((null != mMetadata) && !isKilled()) {
            Log.d(LOG_TAG, "++ Commit");
            saveUsers();
            saveRoomsMessages();
            saveRoomStates();
            saveSummaries();
            saveRoomsAccountData();
            saveReceipts();
            saveMetaData();
            Log.d(LOG_TAG, "-- Commit");
        }
    }

    /**
     * Open the store.
     */
    @Override
    public void open() {
        super.open();

        // avoid concurrency call.
        synchronized (this) {
            if (!mIsReady && !mIsOpening && (null != mMetadata) && (null != mHandlerThread)) {
                mIsOpening = true;

                Log.e(LOG_TAG, "Open the store.");

                // creation the background handler.
                if (null == mFileStoreHandler) {
                    // avoid already started exception
                    // never succeeded to reproduce but it was reported in GA.
                    try {
                        mHandlerThread.start();
                    } catch (IllegalThreadStateException e) {
                        Log.e(LOG_TAG, "mHandlerThread is already started.");
                        // already started
                        return;
                    }
                    mFileStoreHandler = new MXOsHandler(mHandlerThread.getLooper());
                }

                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        mFileStoreHandler.post(new Runnable() {
                            public void run() {
                                Log.e(LOG_TAG, "Open the store in the background thread.");

                                String errorDescription = null;
                                boolean succeed = true;

                                if (!succeed) {
                                    errorDescription = "The latest save did not work properly";
                                    Log.e(LOG_TAG, errorDescription);
                                }

                                if (succeed) {
                                    succeed &= loadRoomsMessages();
                                    if (!succeed) {
                                        errorDescription = "loadRoomsMessages fails";
                                        Log.e(LOG_TAG, errorDescription);
                                    } else {
                                        Log.e(LOG_TAG, "loadRoomsMessages succeeds");
                                    }
                                }

                                if (succeed) {
                                    succeed &= loadRoomsState();

                                    if (!succeed) {
                                        errorDescription = "loadRoomsState fails";
                                        Log.e(LOG_TAG, errorDescription);
                                    } else {
                                        Log.e(LOG_TAG, "loadRoomsState succeeds");
                                        long t0 = System.currentTimeMillis();
                                        Log.e(LOG_TAG, "Retrieve the users from the roomstate");

                                        Collection<Room> rooms = getRooms();

                                        for(Room room : rooms) {
                                            Collection<RoomMember> members = room.getLiveState().getMembers();
                                            for(RoomMember member : members) {
                                                updateUserWithRoomMemberEvent(member);
                                            }
                                        }

                                        long delta = System.currentTimeMillis() - t0;
                                        Log.e(LOG_TAG, "Retrieve " +  mUsers.size() + " users with the room states in " + delta + "  ms");
                                    }
                                }

                                if (succeed) {
                                    succeed &= loadSummaries();

                                    if (!succeed) {
                                        errorDescription = "loadSummaries fails";
                                        Log.e(LOG_TAG, errorDescription);
                                    } else {
                                        Log.e(LOG_TAG, "loadSummaries succeeds");
                                    }
                                }

                                if (succeed) {
                                    succeed &= loadRoomsAccountData();

                                    if (!succeed) {
                                        errorDescription = "loadRoomsAccountData fails";
                                        Log.e(LOG_TAG, errorDescription);
                                    } else {
                                        Log.e(LOG_TAG, "loadRoomsAccountData succeeds");
                                    }
                                }

                                // do not expect having empty list
                                // assume that something is corrupted
                                if (!succeed) {

                                    Log.e(LOG_TAG, "Fail to open the store in background");

                                    // delete all data set mMetadata to null
                                    // backup it to restore it
                                    // the behaviour should be the same as first login
                                    MXFileStoreMetaData tmpMetadata = mMetadata;

                                    deleteAllData(true);

                                    mRoomsToCommitForMessages = new ArrayList<>();
                                    mRoomsToCommitForStates = new ArrayList<>();
                                    mRoomsToCommitForSummaries = new ArrayList<>();
                                    mRoomsToCommitForReceipts = new ArrayList<>();

                                    mMetadata = tmpMetadata;

                                    // reported by GA
                                    // i don't see which path could have triggered this issue
                                    // mMetadata should only be null at file store loading
                                    if (null == mMetadata) {
                                        mMetadata = new MXFileStoreMetaData();
                                        mMetadata.mUserId = mCredentials.userId;
                                        mMetadata.mAccessToken = mCredentials.accessToken;
                                        mMetadata.mVersion = MXFILE_VERSION;
                                        mMetaDataHasChanged = true;
                                    } else {
                                        mMetadata.mEventStreamToken = null;
                                    }

                                    //  the event stream token is put to zero to ensure ta
                                    mEventStreamToken = null;
                                }

                                synchronized (this) {
                                    mIsReady = true;
                                }
                                mIsOpening = false;

                                // post processing
                                Log.e(LOG_TAG, "Management post processing.");
                                dispatchPostProcess(mCredentials.userId);

                                if (!succeed && !mIsNewStorage) {
                                    Log.e(LOG_TAG, "The store is corrupted.");
                                    dispatchOnStoreCorrupted(mCredentials.userId, errorDescription);
                                } else {
                                    // extract the room states
                                    mRoomReceiptsToLoad.addAll(listFiles(mStoreRoomsMessagesReceiptsFolderFile.list()));

                                    Log.e(LOG_TAG, "The store is opened.");
                                    dispatchOnStoreReady(mCredentials.userId);

                                    // load the following items with delay
                                    // theses items are not required to be ready
                                    
                                    // load the receipts
                                    loadReceipts();

                                    // load the users
                                    loadUsers();
                                }
                            }
                        });
                    }
                };

                Thread t = new Thread(r);
                t.start();
            } else if (mIsReady) {
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        Log.e(LOG_TAG, "Management post processing.");
                        dispatchPostProcess(mCredentials.userId);
                        Log.e(LOG_TAG, "The store is opened.");
                        dispatchOnStoreReady(mCredentials.userId);
                    }
                };

                Thread t = new Thread(r);
                t.start();
            }
        }
    }

    /**
     * Close the store.
     * Any pending operation must be complete in this call.
     */
    @Override
    public void close() {
        Log.d(LOG_TAG, "Close the store");

        super.close();
        setIsKilled(true);
        mHandlerThread.quit();
        mHandlerThread = null;
    }

    /**
     * Clear the store.
     * Any pending operation must be complete in this call.
     */
    @Override
    public void clear() {
        Log.d(LOG_TAG, "Clear the store");
        super.clear();
        deleteAllData(false);
    }

    /**
     * Clear the filesystem storage.
     * @param init true to init the filesystem dirtree
     */
    private void deleteAllData(boolean init) {
        // delete the dedicated directories
        try {
            ContentUtils.deleteDirectory(mStoreFolderFile);
            if (init) {
                createDirTree(mCredentials.userId);
            }
        } catch(Exception e) {
            Log.e(LOG_TAG, "deleteAllData failed " + e.getMessage());
        }

        if (init) {
            initCommon();
        }
        mMetadata = null;
        mEventStreamToken = null;
        mAreUsersLoaded = true;
    }

    /**
     * Indicate if the MXStore implementation stores data permanently.
     * Permanent storage allows the SDK to make less requests at the startup.
     * @return true if permanent.
     */
    @Override
    public boolean isPermanent() {
        return true;
    }

    /**
     * Check if the initial load is performed.
     * @return true if it is ready.
     */
    @Override
    public boolean isReady() {
        synchronized (this) {
            return mIsReady;
        }
    }

    /**
     * @return true if the store is corrupted.
     */
    @Override
    public boolean isCorrupted() {
        return false;
    }

    /**
     * Delete a directory with its content
     * @param directory the base directory
     * @return the cache file size
     */
    private long directorySize(File directory) {
        long directorySize = 0;

        if (directory.exists()) {
            File[] files = directory.listFiles();

            if (null != files) {
                for(int i=0; i<files.length; i++) {
                    if(files[i].isDirectory()) {
                        directorySize += directorySize(files[i]);
                    }
                    else {
                        directorySize += files[i].length();
                    }
                }
            }
        }

        return directorySize;
    }

    /**
     * Returns to disk usage size in bytes.
     * @return disk usage size
     */
    @Override
    public long diskUsage() {
        return directorySize(mStoreFolderFile);
    }

    /**
     * Set the event stream token.
     * @param token the event stream token
     */
    @Override
    public void setEventStreamToken(String token) {
        Log.d(LOG_TAG, "Set token to " + token);
        super.setEventStreamToken(token);
        mMetaDataHasChanged = true;
    }

    @Override
    public void setDisplayName(String displayName) {
        // privacy
        //Log.d(LOG_TAG, "Set setDisplayName to " + displayName);
        Log.d(LOG_TAG, "Set setDisplayName ");
        mMetaDataHasChanged = true;
        super.setDisplayName(displayName);
    }

    @Override
    public void setAvatarURL(String avatarURL) {
        // privacy
        //Log.d(LOG_TAG, "Set setAvatarURL to " + avatarURL);
        Log.d(LOG_TAG, "Set setAvatarURL");
        mMetaDataHasChanged = true;
        super.setAvatarURL(avatarURL);
    }

    @Override
    public void setThirdPartyIdentifiers(List<ThirdPartyIdentifier> identifiers) {
        // privacy
        //Log.d(LOG_TAG, "Set setThirdPartyIdentifiers to " + identifiers);
        Log.d(LOG_TAG, "Set setThirdPartyIdentifiers");
        mMetaDataHasChanged = true;
        super.setThirdPartyIdentifiers(identifiers);
    }

    @Override
    public void setIgnoredUserIdsList(List<String> users) {
        Log.d(LOG_TAG, "## setIgnoredUsers() : " + users);
        mMetaDataHasChanged = true;
        super.setIgnoredUserIdsList(users);
    }

    @Override
    public void setDirectChatRoomsDict(Map<String, List<String>> directChatRoomsDict) {
        Log.d(LOG_TAG, "## setDirectChatRoomsDict() : " + directChatRoomsDict);
        mMetaDataHasChanged = true;
        super.setDirectChatRoomsDict(directChatRoomsDict);
    }

    @Override
    public void storeUser(User user) {
        if (!TextUtils.equals(mCredentials.userId, user.user_id)) {
            mUserIdsToCommit.add(user.user_id);
        }
        super.storeUser(user);
    }

    @Override
    public void storeRoomEvents(String roomId, TokensChunkResponse<Event> eventsResponse, EventTimeline.Direction direction) {
        boolean canStore = true;

        // do not flush the room messages file
        // when the user reads the room history and the events list size reaches its max size.
        if (direction == EventTimeline.Direction.BACKWARDS) {
            LinkedHashMap<String, Event> events = mRoomEvents.get(roomId);

            if (null != events) {
                canStore = (events.size() < MAX_STORED_MESSAGES_COUNT);

                if (!canStore) {
                    Log.d(LOG_TAG, "storeRoomEvents : do not flush because reaching the max size");
                }
            }
        }

        super.storeRoomEvents(roomId, eventsResponse, direction);

        if (canStore && (mRoomsToCommitForMessages.indexOf(roomId) < 0)) {
            mRoomsToCommitForMessages.add(roomId);
        }
    }

    /**
     * Store a live room event.
     * @param event The event to be stored.
     */
    @Override
    public void storeLiveRoomEvent(Event event) {
        super.storeLiveRoomEvent(event);

        if (mRoomsToCommitForMessages.indexOf(event.roomId) < 0) {
            mRoomsToCommitForMessages.add(event.roomId);
        }
    }

    @Override
    public void deleteEvent(Event event) {
        super.deleteEvent(event);

        if (mRoomsToCommitForMessages.indexOf(event.roomId) < 0) {
            mRoomsToCommitForMessages.add(event.roomId);
        }
    }

    /**
     * Delete the room messages and token files.
     * @param roomId the room id.
     */
    private void deleteRoomMessagesFiles(String roomId) {
        // messages list
        File messagesListFile = new File(mGzStoreRoomsMessagesFolderFile, roomId);

        // remove the files
        if (messagesListFile.exists()) {
            try {
                messagesListFile.delete();
            } catch (Exception e) {
                Log.d(LOG_TAG,"deleteRoomMessagesFiles - messagesListFile failed " + e.getLocalizedMessage());
            }
        }

        File tokenFile = new File(mStoreRoomsTokensFolderFile, roomId);
        if (tokenFile.exists()) {
            try {
                tokenFile.delete();
            } catch (Exception e) {
                Log.d(LOG_TAG,"deleteRoomMessagesFiles - tokenFile failed " + e.getLocalizedMessage());
            }
        }
    }

    @Override
    public void deleteRoom(String roomId) {
        Log.d(LOG_TAG, "deleteRoom " + roomId);

        super.deleteRoom(roomId);
        deleteRoomMessagesFiles(roomId);
        deleteRoomStateFile(roomId);
        deleteRoomSummaryFile(roomId);
        deleteRoomReceiptsFile(roomId);
        deleteRoomAccountDataFile(roomId);
    }

    @Override
    public void deleteAllRoomMessages(String roomId, boolean keepUnsent) {
        Log.d(LOG_TAG, "deleteAllRoomMessages " + roomId);

        super.deleteAllRoomMessages(roomId, keepUnsent);
        if (!keepUnsent) {
            deleteRoomMessagesFiles(roomId);
        }

        deleteRoomSummaryFile(roomId);

        if (mRoomsToCommitForMessages.indexOf(roomId) < 0) {
            mRoomsToCommitForMessages.add(roomId);
        }

        if (mRoomsToCommitForSummaries.indexOf(roomId) < 0) {
            mRoomsToCommitForSummaries.add(roomId);
        }
    }

    @Override
    public void storeLiveStateForRoom(String roomId) {
        super.storeLiveStateForRoom(roomId);

        if (mRoomsToCommitForStates.indexOf(roomId) < 0) {
            mRoomsToCommitForStates.add(roomId);
        }
    }

    //================================================================================
    // Summary management
    //================================================================================

    @Override
    public void flushSummary(RoomSummary summary) {
        super.flushSummary(summary);

        if (mRoomsToCommitForSummaries.indexOf(summary.getRoomId()) < 0) {
            mRoomsToCommitForSummaries.add(summary.getRoomId());
            saveSummaries();
        }
    }

    @Override
    public void flushSummaries() {
        super.flushSummaries();

        // add any existing roomid to the list to save all
        Collection<String> roomIds = mRoomSummaries.keySet();

        for(String roomId : roomIds) {
            if (mRoomsToCommitForSummaries.indexOf(roomId) < 0) {
                mRoomsToCommitForSummaries.add(roomId);
            }
        }

        saveSummaries();
    }

    @Override
    public RoomSummary storeSummary(String roomId, Event event, RoomState roomState, String selfUserId) {
        RoomSummary summary = super.storeSummary(roomId, event, roomState, selfUserId);

        if (mRoomsToCommitForSummaries.indexOf(roomId) < 0) {
            mRoomsToCommitForSummaries.add(roomId);
        }

        return summary;
    }

    //================================================================================
    // users management
    //================================================================================

    /**
     * Flush users list
     */
    private void saveUsers() {
        if (!mAreUsersLoaded) {
            // please wait
            return;
        }

        // some updated rooms ?
        if  ((mUserIdsToCommit.size() > 0) && (null != mFileStoreHandler)) {
            // get the list
            final ArrayList<String> fUserIds = mUserIdsToCommit;
            mUserIdsToCommit = new ArrayList<>();

            try {
                final ArrayList<User> fUsers;

                synchronized (mUsers) {
                    fUsers = new ArrayList<>(mUsers.values());
                }

                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        mFileStoreHandler.post(new Runnable() {
                            public void run() {
                                if (!isKilled()) {
                                    Log.d(LOG_TAG, "saveUsers " + fUserIds.size() + " users (" + fUsers.size() + " known ones)");

                                    long start = System.currentTimeMillis();

                                    // the users are split into groups to save time
                                    HashMap<Integer, ArrayList<User>> usersGroups = new HashMap<>();

                                    // finds the group for each updated user
                                    for (String userId : fUserIds) {
                                        User user;

                                        synchronized (mUsers) {
                                            user = mUsers.get(userId);
                                        }

                                        if (null != user) {
                                            int hashCode = user.getStorageHashKey();

                                            if (!usersGroups.containsKey(hashCode)) {
                                                usersGroups.put(hashCode, new ArrayList<User>());
                                            }
                                        }
                                    }

                                    // gather the user to the dedicated group if they need to be updated
                                    for (User user : fUsers) {
                                        if (usersGroups.containsKey(user.getStorageHashKey())) {
                                            usersGroups.get(user.getStorageHashKey()).add(user);
                                        }
                                    }

                                    // save the groups
                                    for (int hashKey : usersGroups.keySet()) {
                                        writeObject("saveUser " + hashKey, new File(mStoreUserFolderFile, hashKey + ""), usersGroups.get(hashKey));
                                    }

                                    Log.d(LOG_TAG, "saveUsers done in " + (System.currentTimeMillis() - start) + " ms");
                                }
                            }
                        });
                    }
                };

                Thread t = new Thread(r);
                t.start();
            } catch (OutOfMemoryError oom) {
                Log.e(LOG_TAG, "saveUser : cannot clone the users list" + oom.getMessage());
            }
        }
    }

    /**
     * Load the user information from the filesystem..
     */
    private void loadUsers() {
        List<String> filenames = listFiles(mStoreUserFolderFile.list());
        long start = System.currentTimeMillis();

        ArrayList<User> users = new ArrayList<>();

        // list the files
        for(String filename : filenames) {
            File messagesListFile = new File(mStoreUserFolderFile, filename);
            Object usersAsVoid = readObject("loadUsers " + filename, messagesListFile);

            if (null != usersAsVoid) {
                try {
                    users.addAll((List<User>) usersAsVoid);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "loadUsers failed : " + e.toString());
                }
            }
        }

        // update the hash map
        for(User user : users) {
            synchronized (mUsers) {
                User currentUser = mUsers.get(user.user_id);

                if ((null == currentUser) || // not defined
                        currentUser.mIsRetrievedFromRoomMember || // tmp user until retrieved it
                        (currentUser.getLatestPresenceTs() < user.getLatestPresenceTs())) // newer presence
                {
                    mUsers.put(user.user_id, user);
                }
            }
        }

        Log.e(LOG_TAG, "loadUsers (" + filenames.size() + " files) : retrieve " + mUsers.size() + " users in " + (System.currentTimeMillis() - start) + "ms");

        mAreUsersLoaded = true;

        // save any pending save
        saveUsers();
    }

    //================================================================================
    // Room messages management
    //================================================================================

    private void saveRoomMessages(String roomId) {
        LinkedHashMap<String, Event> eventsHash;
        synchronized (mRoomEventsLock) {
            eventsHash = mRoomEvents.get(roomId);
        }

        String token = mRoomTokens.get(roomId);

        // the list exists ?
        if ((null != eventsHash) && (null != token)) {
            LinkedHashMap<String, Event> hashCopy = new LinkedHashMap<>();
            ArrayList<Event> eventsList;

            synchronized (mRoomEventsLock) {
                eventsList = new ArrayList<>(eventsHash.values());
            }

            int startIndex = 0;

            // try to reduce the number of stored messages
            // it does not make sense to keep the full history.

            // the method consists in saving messages until finding the oldest known token.
            // At initial sync, it is not saved so keep the whole history.
            // if the user back paginates, the token is stored in the event.
            // if some messages are received, the token is stored in the event.
            if (eventsList.size() > MAX_STORED_MESSAGES_COUNT) {
                startIndex = eventsList.size() - MAX_STORED_MESSAGES_COUNT;

                // search backward the first known token
                for (; !eventsList.get(startIndex).hasToken() && (startIndex > 0); startIndex--)
                    ;

                // avoid saving huge messages count
                // with a very verbosed room, the messages token
                if ((eventsList.size() - startIndex) > (2 * MAX_STORED_MESSAGES_COUNT)) {
                    Log.d(LOG_TAG, "saveRoomsMessage (" + roomId + ") : too many messages, try reducing more");

                    // start from 10 messages
                    startIndex = eventsList.size() - 10;

                    // search backward the first known token
                    for (; !eventsList.get(startIndex).hasToken() && (startIndex > 0); startIndex--)
                        ;
                }

                if (startIndex > 0) {
                    Log.d(LOG_TAG, "saveRoomsMessage (" + roomId + ") :  reduce the number of messages " + eventsList.size() + " -> " + (eventsList.size() - startIndex));
                }
            }

            long t0 = System.currentTimeMillis();

            for (int index = startIndex; index < eventsList.size(); index++) {
                Event event = eventsList.get(index);
                event.prepareSerialization();
                hashCopy.put(event.eventId, event);
            }

            if (!writeObject("saveRoomsMessage " + roomId, new File(mGzStoreRoomsMessagesFolderFile, roomId), hashCopy)) {
                return;
            }

            if (!writeObject("saveRoomsMessage " + roomId, new File(mStoreRoomsTokensFolderFile, roomId), token)) {
                return;
            }

            Log.d(LOG_TAG, "saveRoomsMessage (" + roomId + ") : " + eventsList.size() + " messages saved in " +  (System.currentTimeMillis() - t0) + " ms");
        } else {
            deleteRoomMessagesFiles(roomId);
        }
    }

    /**
     * Flush updates rooms messages list files.
     */
    private void saveRoomsMessages() {
        // some updated rooms ?
        if  ((mRoomsToCommitForMessages.size() > 0) && (null != mFileStoreHandler)) {
            // get the list
            final ArrayList<String> fRoomsToCommitForMessages = mRoomsToCommitForMessages;
            mRoomsToCommitForMessages = new ArrayList<>();

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mFileStoreHandler.post(new Runnable() {
                        public void run() {
                            if (!isKilled()) {
                                long start = System.currentTimeMillis();

                                for (String roomId : fRoomsToCommitForMessages) {
                                    saveRoomMessages(roomId);
                                }

                                Log.d(LOG_TAG, "saveRoomsMessages : " + fRoomsToCommitForMessages.size() + " rooms in " + (System.currentTimeMillis() - start) + " ms");
                            }
                        }
                    });
                }
            };

            Thread t = new Thread(r);
            t.start();
        }
    }

    /**
     * Load room messages from the filesystem.
     * @param roomId the room id.
     * @return true if succeed.
     */
    private boolean loadRoomMessages(final String roomId) {
        boolean succeeded = true;
        boolean shouldSave = false;
        LinkedHashMap<String, Event> events = null;

        File messagesListFile = new File(mGzStoreRoomsMessagesFolderFile, roomId);

        if (messagesListFile.exists()) {
            Object eventsAsVoid = readObject("events " + roomId , messagesListFile);

            if (null != eventsAsVoid) {
                try {
                    events = (LinkedHashMap<String, Event>) eventsAsVoid;
                } catch (Exception e) {
                    Log.e(LOG_TAG, "loadRoomMessages " + roomId +  "failed : " + e.getMessage());
                    return false;
                }

                ArrayList<String> eventIds = mRoomEventIds.get(roomId);

                if (null == eventIds) {
                    eventIds = new ArrayList<>();
                    mRoomEventIds.put(roomId, eventIds);
                }

                long undeliverableTs = 1L << 50;

                // finalizes the deserialization
                for (Event event : events.values()) {
                    // if a message was not sent, mark at as UNDELIVERABLE
                    if ((event.mSentState == Event.SentState.UNDELIVERABLE) ||
                            (event.mSentState == Event.SentState.UNSENT) ||
                            (event.mSentState == Event.SentState.SENDING) ||
                            (event.mSentState == Event.SentState.WAITING_RETRY) ||
                            (event.mSentState == Event.SentState.ENCRYPTING)) {
                        event.mSentState = Event.SentState.UNDELIVERABLE;
                        event.originServerTs = undeliverableTs++;
                        shouldSave = true;
                    }

                    event.finalizeDeserialization();

                    eventIds.add(event.eventId);
                }
            } else {
                return false;
            }
        }

        // succeeds to extract the message list
        if (null != events) {
            // create the room object
            Room room = new Room();
            room.init(roomId, null);
            // do not wait that the live state update
            room.setReadyState(true);
            storeRoom(room);

            mRoomEvents.put(roomId, events);
        }

        if (shouldSave) {
            saveRoomMessages(roomId);
        }

        return succeeded;
    }

    /**
     * Load the room token from the file system.
     * @param roomId the room id.
     * @return true if it succeeds.
     */
    private boolean loadRoomToken(final String roomId) {
        boolean succeed = true;

        Room room = getRoom(roomId);

        // should always be true
        if (null != room) {
            String token = null;

            try {
                File messagesListFile = new File(mStoreRoomsTokensFolderFile, roomId);
                Object tokenAsVoid = readObject("loadRoomToken " + roomId, messagesListFile);

                if (null == tokenAsVoid) {
                    succeed = false;
                } else {
                    token = (String)tokenAsVoid;

                    // check if the oldest event has a token.
                    LinkedHashMap<String, Event> eventsHash = mRoomEvents.get(roomId);
                    if ((null != eventsHash) && (eventsHash.size() > 0)) {
                        Event event = eventsHash.values().iterator().next();

                        // the room history could have been reduced to save memory
                        // so, if the oldest messages has a token, use it instead of the stored token.
                        if (null != event.mToken) {
                            token = event.mToken;
                        }
                    }
                }
            } catch (Exception e) {
                succeed = false;
                Log.e(LOG_TAG, "loadRoomToken failed : " + e.toString());
            }

            if (null != token) {
                mRoomTokens.put(roomId, token);
            } else {
                deleteRoom(roomId);
            }
        } else {
            try {
                File messagesListFile = new File(mStoreRoomsTokensFolderFile, roomId);
                messagesListFile.delete();
            } catch (Exception e) {
                Log.e(LOG_TAG, "loadRoomToken failed with error " + e.getMessage());
            }
        }

        return succeed;
    }

    /**
     * Load room messages from the filesystem.
     * @return  true if the operation succeeds.
     */
    private boolean loadRoomsMessages() {
        boolean succeed = true;

        try {
            // extract the messages list
            List<String> filenames = listFiles(mGzStoreRoomsMessagesFolderFile.list());

            long start = System.currentTimeMillis();

            for(String filename :filenames) {
                if (succeed) {
                    succeed &= loadRoomMessages(filename);
                }
            }

            if (succeed) {
                Log.d(LOG_TAG, "loadRoomMessages : " + filenames.size() + " rooms in " + (System.currentTimeMillis() - start) + " ms");
            }

            // extract the tokens list
            filenames = listFiles(mStoreRoomsTokensFolderFile.list());

            start = System.currentTimeMillis();

            for(String filename :filenames) {
                if (succeed) {
                    succeed &= loadRoomToken(filename);
                }
            }

            if (succeed) {
                Log.d(LOG_TAG, "loadRoomToken : " + filenames.size() + " rooms in " + (System.currentTimeMillis() - start) + " ms");
            }

        } catch (Exception e) {
            succeed = false;
            Log.e(LOG_TAG, "loadRoomToken failed : " + e.getLocalizedMessage());
        }

        return succeed;
    }

    //================================================================================
    // Room states management
    //================================================================================

    /**
     * Delete the room state file.
     * @param roomId the room id.
     */
    private void deleteRoomStateFile(String roomId) {
        // states list
        File statesFile = new File(mGzStoreRoomsStateFolderFile, roomId);

        if (statesFile.exists()) {
            try {
                statesFile.delete();
            } catch (Exception e) {
                Log.e(LOG_TAG, "deleteRoomStateFile failed with error " + e.getMessage());
            }
        }

    }

    /**
     * Save the room state.
     * @param roomId the room id.
     */
    private void saveRoomState(String roomId) {
        File roomStateFile = new File(mGzStoreRoomsStateFolderFile, roomId);
        Room room = mRooms.get(roomId);

        if (null != room) {
            long start1 = System.currentTimeMillis();
            writeObject("saveRoomsState " + roomId, roomStateFile, room.getState());
            Log.d(LOG_TAG, "saveRoomsState " + room.getState().getMembers().size() + " : " + (System.currentTimeMillis() - start1) + " ms");
        } else {
            deleteRoomStateFile(roomId);
        }
    }

    /**
     * Flush the room state files.
     */
    private void saveRoomStates() {
        if ((mRoomsToCommitForStates.size() > 0) && (null != mFileStoreHandler)) {
            // get the list
            final ArrayList<String> fRoomsToCommitForStates = mRoomsToCommitForStates;
            mRoomsToCommitForStates = new ArrayList<>();

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mFileStoreHandler.post(new Runnable() {
                        public void run() {
                            if (!isKilled()) {
                                long start = System.currentTimeMillis();

                                for (String roomId : fRoomsToCommitForStates) {
                                    saveRoomState(roomId);
                                }

                                Log.d(LOG_TAG, "saveRoomsState : " + fRoomsToCommitForStates.size() + " rooms in " + (System.currentTimeMillis() - start) + " ms");
                            }
                        }
                    });
                }
            };

            Thread t = new Thread(r);
            t.start();
        }
    }

    /**
     * Load a room state from the file system.
     * @param roomId the room id.
     * @return true if the operation succeeds.
     */
    private boolean loadRoomState(final String roomId) {
        boolean succeed = true;

        Room room = getRoom(roomId);

        // should always be true
        if (null != room) {
            RoomState liveState = null;

            try {
                // the room state is not zipped
                File roomStateFile = new File(mGzStoreRoomsStateFolderFile, roomId);

                // new format
                if (roomStateFile.exists()) {
                    Object roomStateAsObject = readObject("loadRoomState " + roomId, roomStateFile);

                    if (null == roomStateAsObject) {
                        succeed = false;
                    } else {
                        liveState = (RoomState) roomStateAsObject;
                    }
                }
            } catch (Exception e) {
                succeed = false;
                Log.e(LOG_TAG, "loadRoomState failed : " + e.getLocalizedMessage());
            }

            if (null != liveState) {
                room.getLiveTimeLine().setState(liveState);
            } else {
                deleteRoom(roomId);
            }
        } else {
            try {
                File messagesListFile = new File(mGzStoreRoomsStateFolderFile, roomId);
                messagesListFile.delete();

            } catch (Exception e) {
                Log.e(LOG_TAG, "loadRoomState failed to delete a file : " + e.getLocalizedMessage());
            }
        }

        return succeed;
    }

    /**
     * Load room state from the file system.
     * @return true if the operation succeeds.
     */
    private boolean loadRoomsState() {
        boolean succeed = true;

        try {
            long start = System.currentTimeMillis();

            List<String> filenames = listFiles(mGzStoreRoomsStateFolderFile.list());

            for(String filename : filenames) {
                if (succeed) {
                    succeed &= loadRoomState(filename);
                }
            }

            Log.d(LOG_TAG, "loadRoomsState " + filenames.size() + " rooms in " + (System.currentTimeMillis() - start) + " ms");

        } catch (Exception e) {
            succeed = false;
            Log.e(LOG_TAG, "loadRoomsState failed : " + e.getLocalizedMessage());
        }

        return succeed;
    }

    //================================================================================
    // AccountData management
    //================================================================================

    /**
     * Delete the room account data file.
     * @param roomId the room id.
     */
    private void deleteRoomAccountDataFile(String roomId) {
        File file = new File(mStoreRoomsAccountDataFolderFile, roomId);

        // remove the files
        if (file.exists()) {
            try {
                file.delete();
            } catch (Exception e) {
                Log.e(LOG_TAG, "deleteRoomAccountDataFile failed : " + e.getLocalizedMessage());
            }
        }
    }

    /**
     * Flush the pending account data.
     */
    private void saveRoomsAccountData() {
        if ((mRoomsToCommitForAccountData.size() > 0) && (null != mFileStoreHandler)) {
            // get the list
            final ArrayList<String> fRoomsToCommitForAccountData = mRoomsToCommitForAccountData;
            mRoomsToCommitForAccountData = new ArrayList<>();

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mFileStoreHandler.post(new Runnable() {
                        public void run() {
                            if (!isKilled()) {
                                long start = System.currentTimeMillis();

                                for (String roomId : fRoomsToCommitForAccountData) {
                                    RoomAccountData accountData = mRoomAccountData.get(roomId);

                                    if (null != accountData) {
                                        writeObject("saveRoomsAccountData " + roomId, new File(mStoreRoomsAccountDataFolderFile, roomId), accountData);
                                    } else {
                                        deleteRoomAccountDataFile(roomId);
                                    }
                                }

                                Log.d(LOG_TAG, "saveSummaries : " + fRoomsToCommitForAccountData.size() + " account data in " + (System.currentTimeMillis() - start) + " ms");
                            }
                        }
                    });
                }
            };

            Thread t = new Thread(r);
            t.start();
        }
    }

    /***
     * Load the account Data of a dedicated room.
     * @param roomId the room Id
     * @return true if the operation succeeds.
     */
    private boolean loadRoomAccountData(final String roomId) {
        boolean succeeded = true;
        RoomAccountData roomAccountData = null;

        try {
            File accountDataFile = new File(mStoreRoomsAccountDataFolderFile, roomId);

            if (accountDataFile.exists()) {
                Object accountAsVoid = readObject("loadRoomAccountData " + roomId, accountDataFile);

                if (null == accountAsVoid) {
                    Log.e(LOG_TAG, "loadRoomAccountData failed");
                    return false;
                }

                roomAccountData = (RoomAccountData)accountAsVoid;
            }
        } catch (Exception e){
            succeeded = false;
            Log.e(LOG_TAG, "loadRoomAccountData failed : " + e.toString());
        }

        // succeeds to extract the message list
        if (null != roomAccountData) {
            Room room = getRoom(roomId);

            if (null != room) {
                room.setAccountData(roomAccountData);
            }
        }

        return succeeded;
    }

    /**
     * Load room accountData from the filesystem.
     * @return true if the operation succeeds.
     */
    private boolean loadRoomsAccountData() {
        boolean succeed = true;

        try {
            // extract the messages list
            List<String> filenames = listFiles(mStoreRoomsAccountDataFolderFile.list());

            long start = System.currentTimeMillis();

            for(String filename : filenames) {
                succeed &= loadRoomAccountData(filename);
            }

            if (succeed) {
                Log.d(LOG_TAG, "loadRoomsAccountData : " + filenames.size() + " rooms in " + (System.currentTimeMillis() - start) + " ms");
            }
        } catch (Exception e) {
            succeed = false;
            Log.e(LOG_TAG, "loadRoomsAccountData failed : " + e.getLocalizedMessage());
        }

        return succeed;
    }

    @Override
    public void storeAccountData(String roomId, RoomAccountData accountData) {
        super.storeAccountData(roomId, accountData);

        if (null != roomId) {
            Room room = mRooms.get(roomId);

            // sanity checks
            if ((room != null) && (null != accountData)) {
                if (mRoomsToCommitForAccountData.indexOf(roomId) < 0) {
                    mRoomsToCommitForAccountData.add(roomId);
                }
            }
        }
    }

    //================================================================================
    // Summary management
    //================================================================================

    /**
     * Delete the room summary file.
     * @param roomId the room id.
     */
    private void deleteRoomSummaryFile(String roomId) {
        // states list
        File statesFile = new File(mStoreRoomsSummaryFolderFile, roomId);

        // remove the files
        if (statesFile.exists()) {
            try {
                statesFile.delete();
            } catch (Exception e) {
                Log.e(LOG_TAG, "deleteRoomSummaryFile failed : " + e.getLocalizedMessage());
            }
        }
    }

    /**
     * Flush the pending summaries.
     */
    private void saveSummaries() {
        if ((mRoomsToCommitForSummaries.size() > 0) && (null != mFileStoreHandler)) {
            // get the list
            final ArrayList<String> fRoomsToCommitForSummaries = mRoomsToCommitForSummaries;
            mRoomsToCommitForSummaries = new ArrayList<>();

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mFileStoreHandler.post(new Runnable() {
                        public void run() {
                            if (!isKilled()) {
                                long start = System.currentTimeMillis();

                                for (String roomId : fRoomsToCommitForSummaries) {
                                    try {
                                        File roomSummaryFile = new File(mStoreRoomsSummaryFolderFile, roomId);
                                        RoomSummary roomSummary = mRoomSummaries.get(roomId);

                                        if (null != roomSummary) {
                                            roomSummary.getLatestReceivedEvent().prepareSerialization();
                                            writeObject("saveSummaries " + roomId, roomSummaryFile, roomSummary);
                                        } else {
                                            deleteRoomSummaryFile(roomId);
                                        }
                                    } catch (OutOfMemoryError oom) {
                                        dispatchOOM(oom);
                                    } catch (Exception e) {
                                        Log.e(LOG_TAG, "saveSummaries failed : " + e.getLocalizedMessage());
                                        // Toast.makeText(mContext, "saveSummaries failed " + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                                    }
                                }

                                Log.d(LOG_TAG, "saveSummaries : " + fRoomsToCommitForSummaries.size() + " summaries in " + (System.currentTimeMillis() - start) + " ms");
                            }
                        }
                    });
                }
            };

            Thread t = new Thread(r);
            t.start();
        }
    }

    /**
     * Load the room summary from the files system.
     * @param roomId the room id.
     * @return true if the operation succeeds;
     */
    private boolean loadSummary(final String roomId) {
        boolean succeed = true;

        // do not check if the room exists here.
        // if the user is invited to a room, the room object is not created until it is joined.
        RoomSummary summary = null;

        try {
            File messagesListFile = new File(mStoreRoomsSummaryFolderFile, roomId);
            Object summaryAsVoid = readObject("loadSummary " + roomId, messagesListFile);

            if (null == summaryAsVoid) {
                Log.e(LOG_TAG, "loadSummary failed");
                return false;
            }

            summary = (RoomSummary)summaryAsVoid;
        } catch (Exception e){
            succeed = false;
            Log.e(LOG_TAG, "loadSummary failed : " + e.getMessage());
        }

        if (null != summary) {
            summary.getLatestReceivedEvent().finalizeDeserialization();

            Room room = getRoom(summary.getRoomId());

            // the room state is not saved in the summary.
            // it is restored from the room
            if (null != room) {
                summary.setLatestRoomState(room.getState());
            }

            mRoomSummaries.put(roomId, summary);
        }

        return succeed;
    }

    /**
     * Load room summaries from the file system.
     * @return true if the operation succeeds.
     */
    private boolean loadSummaries() {
        boolean succeed = true;
        try {
            // extract the room states
            List<String> filenames = listFiles(mStoreRoomsSummaryFolderFile.list());

            long start = System.currentTimeMillis();

            for(String filename : filenames) {
                succeed &= loadSummary(filename);
            }

            Log.d(LOG_TAG, "loadSummaries " + filenames.size() + " rooms in " + (System.currentTimeMillis() - start) + " ms");
        }
        catch (Exception e) {
            succeed = false;
            Log.e(LOG_TAG, "loadSummaries failed : " + e.getLocalizedMessage());
        }

        return succeed;
    }

    //================================================================================
    // Metadata management
    //================================================================================

    /**
     * Load the metadata info from the file system.
     */
    private void loadMetaData() {
        long start = System.currentTimeMillis();

        // init members
        mEventStreamToken = null;
        mMetadata = null;

        File metaDataFile = new File(mStoreFolderFile, MXFILE_STORE_METADATA_FILE_NAME);

        if (metaDataFile.exists()) {
            Object metadataAsVoid = readObject("loadMetaData", metaDataFile);

            if (null != metadataAsVoid) {
                try {
                    mMetadata = (MXFileStoreMetaData)metadataAsVoid;

                    // remove pending \n
                    if (null != mMetadata.mUserDisplayName) {
                        mMetadata.mUserDisplayName.trim();
                    }

                    // extract the latest event stream token
                    mEventStreamToken = mMetadata.mEventStreamToken;
                } catch(Exception e) {
                    Log.e(LOG_TAG, "## loadMetaData() : is corrupted");
                    return;
                }
            }
        }

        Log.d(LOG_TAG, "loadMetaData : " + (System.currentTimeMillis() - start) + " ms");
    }

    /**
     * flush the metadata info from the file system.
     */
    private void saveMetaData() {
        if ((mMetaDataHasChanged) && (null != mFileStoreHandler) && (null != mMetadata)) {
            mMetaDataHasChanged = false;

            final MXFileStoreMetaData fMetadata = mMetadata.deepCopy();

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mFileStoreHandler.post(new Runnable() {
                        public void run() {
                            if (!mIsKilled) {
                                long start = System.currentTimeMillis();
                                writeObject("saveMetaData", new File(mStoreFolderFile, MXFILE_STORE_METADATA_FILE_NAME), fMetadata);
                                Log.d(LOG_TAG, "saveMetaData : " + (System.currentTimeMillis() - start) + " ms");
                            }
                        }
                    });
                }
            };

            Thread t = new Thread(r);
            t.start();
        }
    }

    //================================================================================
    // Event receipts management
    //================================================================================

    @Override
    public List<ReceiptData> getEventReceipts(String roomId, String eventId, boolean excludeSelf, boolean sort) {
        synchronized (mRoomReceiptsToLoad) {
            int pos = mRoomReceiptsToLoad.indexOf(roomId);

            // the user requires the receipts asap
            if (pos >= 2) {
                mRoomReceiptsToLoad.remove(roomId);
                // index 0 is the current managed one
                mRoomReceiptsToLoad.add(1, roomId);
            }
        }

        return super.getEventReceipts(roomId, eventId, excludeSelf, sort);
    }

    /**
     * Store the receipt for an user in a room
     * @param receipt The event
     * @param roomId The roomId
     * @return true if the receipt has been stored
     */
    @Override
    public boolean storeReceipt(ReceiptData receipt, String roomId) {
        boolean res = super.storeReceipt(receipt, roomId);

        if (res) {
            synchronized (this) {
                if (mRoomsToCommitForReceipts.indexOf(roomId) < 0) {
                    mRoomsToCommitForReceipts.add(roomId);
                }
            }
        }

        return res;
    }

    /***
     * Load the events receipts.
     * @param roomId the room Id
     * @return true if the operation succeeds.
     */
    private boolean loadReceipts(String roomId) {
        Map<String, ReceiptData> receiptsMap = null;
        File file = new File(mStoreRoomsMessagesReceiptsFolderFile, roomId);

        if (file.exists()) {
            Object receiptsAsVoid = readObject("loadReceipts " + roomId, file);

            if (null != receiptsAsVoid) {
                try {
                    List<ReceiptData> receipts = (List<ReceiptData>)receiptsAsVoid;

                    receiptsMap = new HashMap<>();

                    for(ReceiptData r : receipts) {
                        receiptsMap.put(r.userId, r);
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "loadReceipts failed : " + e.getMessage());
                    return false;
                }
            } else {
                return false;
            }
        }

        if (null != receiptsMap) {
            Map<String, ReceiptData> currentReceiptMap;

            synchronized (mReceiptsByRoomIdLock) {
                currentReceiptMap = mReceiptsByRoomId.get(roomId);
                mReceiptsByRoomId.put(roomId, receiptsMap);
            }

            // merge the current read receipts
            if (null != currentReceiptMap) {
                Collection<ReceiptData> receipts = currentReceiptMap.values();

                for(ReceiptData receipt : receipts) {
                    storeReceipt(receipt, roomId);
                }
            }

            dispatchOnReadReceiptsLoaded(roomId);
        }

        return true;
    }

    /**
     * Load event receipts from the file system.
     * @return true if the operation succeeds.
     */
    private boolean loadReceipts() {
        boolean succeed = true;
        try {
            int count = mRoomReceiptsToLoad.size();
            long start = System.currentTimeMillis();

            while(mRoomReceiptsToLoad.size() > 0) {
                String roomId;
                synchronized (mRoomReceiptsToLoad) {
                    roomId = mRoomReceiptsToLoad.get(0);
                }

                loadReceipts(roomId);

                synchronized (mRoomReceiptsToLoad) {
                    mRoomReceiptsToLoad.remove(0);
                }
            }

            saveReceipts();
            Log.d(LOG_TAG, "loadReceipts " + count + " rooms in " + (System.currentTimeMillis() - start) + " ms");
        }
        catch (Exception e) {
            succeed = false;
            //Toast.makeText(mContext, "loadReceipts failed" + e, Toast.LENGTH_LONG).show();
            Log.e(LOG_TAG, "loadReceipts failed : " + e.getLocalizedMessage());
        }

        return succeed;
    }

    /**
     * Flush the events receipts
     * @param roomId the roomId.
     */
    private void saveReceipts(final String roomId) {
        synchronized (mRoomReceiptsToLoad) {
            // please wait
            if (mRoomReceiptsToLoad.contains(roomId)) {
                return;
            }
        }

        final List<ReceiptData> receipts;

        synchronized (mReceiptsByRoomIdLock) {
            if (mReceiptsByRoomId.containsKey(roomId)) {
                receipts = new ArrayList<>(mReceiptsByRoomId.get(roomId).values());
            } else {
                receipts = null;
            }
        }

        // sanity check
        if (null == receipts) {
            return;
        }

        Runnable r = new Runnable() {
            @Override
            public void run() {
                mFileStoreHandler.post(new Runnable() {
                    public void run() {
                        if (!mIsKilled) {
                            long start = System.currentTimeMillis();
                            writeObject("saveReceipts " + roomId, new File(mStoreRoomsMessagesReceiptsFolderFile, roomId), receipts);
                            Log.d(LOG_TAG, "saveReceipts : roomId " + roomId + " eventId : " + (System.currentTimeMillis() - start) + " ms");
                        }
                    }
                });
            }
        };

        Thread t = new Thread(r);
        t.start();
    }

    /**
     * Save the events receipts.
     */
    private void saveReceipts() {
        synchronized (this) {
            ArrayList<String> roomsToCommit = mRoomsToCommitForReceipts;

            for (String roomId : roomsToCommit) {
                saveReceipts(roomId);
            }

            mRoomsToCommitForReceipts.clear();
        }
    }

    /**
     * Delete the room receipts
     * @param roomId the room id.
     */
    private void deleteRoomReceiptsFile(String roomId) {
        File receiptsFile = new File(mStoreRoomsMessagesReceiptsFolderFile, roomId);

        // remove the files
        if (receiptsFile.exists()) {
            try {
                receiptsFile.delete();
            } catch (Exception e) {
                Log.d(LOG_TAG,"deleteReceiptsFile - failed " + e.getLocalizedMessage());
            }
        }
    }

    //================================================================================
    // read/write methods
    //================================================================================

    /**
     * Write an object in a dedicated file.
     * @param description the operation description
     * @param file the file
     * @param object the object to save
     * @return true if the operation succeeds
     */
    private boolean writeObject(String description, File file, Object object) {
        String parent = file.getParent();
        String name = file.getName();

        File tmpFile = new File(parent, name + ".tmp");

        if (tmpFile.exists()) {
            tmpFile.delete();
        }

        if (file.exists()) {
            file.renameTo(tmpFile);
        }

        boolean succeed = false;
        try {
            FileOutputStream fos = new FileOutputStream(file);
            GZIPOutputStream gz = new GZIPOutputStream(fos);
            ObjectOutputStream out = new ObjectOutputStream(gz);

            out.writeObject(object);
            out.close();

            succeed = true;
        } catch (OutOfMemoryError oom) {
            dispatchOOM(oom);
        } catch (Exception e) {
            Log.e(LOG_TAG, "## writeObject()  " + description + " : failed " + e.getMessage());
        }

        if (succeed) {
            tmpFile.delete();
        } else {
            tmpFile.renameTo(file);
        }

        return succeed;
    }

    /**
     * Read an object from a dedicated file
     * @param description the operation description
     * @param file the file
     * @return the read object if it can be retrieved
     */
    private Object readObject(String description, File file) {
        String parent = file.getParent();
        String name = file.getName();

        File tmpFile = new File(parent, name + ".tmp");

        if (tmpFile.exists()) {
            Log.e(LOG_TAG, "## readObject : rescue from a tmp file " + tmpFile.getName());
            file = tmpFile;
        }

        Object object = null;
        try {
            FileInputStream fis = new FileInputStream(file);
            GZIPInputStream gz = new GZIPInputStream(fis);
            ObjectInputStream ois = new ObjectInputStream(gz);
            object = ois.readObject();
            ois.close();
        } catch (OutOfMemoryError oom) {
            dispatchOOM(oom);
        } catch (Exception e) {
            Log.e(LOG_TAG, "## readObject()  " + description + " : failed " + e.getMessage());
        }
        return object;
    }


    /**
     * Remove the tmp files from a filename list
     * @param names the names list
     * @return the filtered list
     */
    private static final List<String> listFiles(String[] names) {
        ArrayList<String> filteredFilenames = new ArrayList<>();
        ArrayList<String> tmpFilenames = new ArrayList<>();

        for(int i = 0; i < names.length; i++) {
            String name = names[i];

            if (!name.endsWith(".tmp")) {
                filteredFilenames.add(name);
            } else {
                tmpFilenames.add(name.substring(0, name.length() - ".tmp".length()));
            }
        }

        // check if the tmp file is not alone i.e the matched file was not saved (app crash...)
        for(String tmpFileName : tmpFilenames) {
            if (!filteredFilenames.contains(tmpFileName)) {
                Log.e(LOG_TAG, "## listFiles() : " + tmpFileName + " does not exist but a tmp file has been retrieved");
                filteredFilenames.add(tmpFileName);
            }
         }

        return filteredFilenames;
    }
}