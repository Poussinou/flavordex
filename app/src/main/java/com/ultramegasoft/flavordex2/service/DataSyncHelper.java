package com.ultramegasoft.flavordex2.service;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.ultramegasoft.flavordex2.FlavordexApp;
import com.ultramegasoft.flavordex2.backend.sync.Sync;
import com.ultramegasoft.flavordex2.backend.sync.model.CatRecord;
import com.ultramegasoft.flavordex2.backend.sync.model.EntryRecord;
import com.ultramegasoft.flavordex2.backend.sync.model.ExtraRecord;
import com.ultramegasoft.flavordex2.backend.sync.model.FlavorRecord;
import com.ultramegasoft.flavordex2.backend.sync.model.PhotoRecord;
import com.ultramegasoft.flavordex2.backend.sync.model.UpdateRecord;
import com.ultramegasoft.flavordex2.backend.sync.model.UpdateResponse;
import com.ultramegasoft.flavordex2.provider.Tables;
import com.ultramegasoft.flavordex2.util.BackendUtils;
import com.ultramegasoft.flavordex2.util.PhotoUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/**
 * Helper for synchronizing journal data with the backend.
 *
 * @author Steve Guidetti
 */
public class DataSyncHelper {
    private static final String TAG = "DataSyncHelper";

    /**
     * The Context
     */
    private final Context mContext;

    /**
     * @param context The Context
     */
    public DataSyncHelper(Context context) {
        mContext = context;
    }

    /**
     * Sync data with the backend.
     *
     * @return Whether the sync completed successfully
     */
    public boolean sync() {
        Log.i(TAG, "Starting data sync service.");
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        final String accountName = prefs.getString(FlavordexApp.PREF_ACCOUNT_NAME, null);
        final long clientId = BackendUtils.getClientId(mContext);
        if(accountName == null || clientId == 0) {
            Log.i(TAG, "Client not registered. Aborting and disabling service.");
            prefs.edit().putBoolean(FlavordexApp.PREF_SYNC_DATA, false).apply();
            return false;
        }
        final GoogleAccountCredential credential = BackendUtils.getCredential(mContext);
        credential.setSelectedAccountName(accountName);

        final Sync sync = BackendUtils.getSync(credential);
        try {
            Log.i(TAG, "Syncing data...");
            pushUpdates(sync, clientId);
            fetchUpdates(sync, clientId);
            Log.i(TAG, "Syncing complete.");

            return true;
        } catch(IOException e) {
            Log.w(TAG, "Syncing with the backend failed", e);
        }

        return false;
    }

    /**
     * Send updated journal data to the backend.
     *
     * @param sync     The Sync endpoint client
     * @param clientId The client ID
     * @throws IOException
     */
    private void pushUpdates(Sync sync, long clientId) throws IOException {
        final ContentResolver cr = mContext.getContentResolver();

        final UpdateRecord record = new UpdateRecord();
        record.setCats(getUpdatedCats(cr));
        record.setEntries(getUpdatedEntries(cr));

        final UpdateResponse response = sync.pushUpdates(clientId, record).execute();

        if(response.getCatStatuses() != null) {
            final String where = Tables.Cats.UUID + " = ?";
            final String[] whereArgs = new String[1];
            final ContentValues values = new ContentValues();
            values.put(Tables.Cats.PUBLISHED, true);
            values.put(Tables.Cats.SYNCED, true);
            for(Map.Entry<String, Object> status : response.getCatStatuses().entrySet()) {
                if((boolean)status.getValue()) {
                    whereArgs[0] = status.getKey();
                    cr.update(Tables.Cats.CONTENT_URI, values, where, whereArgs);
                }
            }
        }

        if(response.getEntryStatuses() != null) {
            final String where = Tables.Entries.UUID + " = ?";
            final String[] whereArgs = new String[1];
            final ContentValues values = new ContentValues();
            values.put(Tables.Entries.PUBLISHED, true);
            values.put(Tables.Entries.SYNCED, true);
            for(Map.Entry<String, Object> status : response.getEntryStatuses().entrySet()) {
                if((boolean)status.getValue()) {
                    whereArgs[0] = status.getKey();
                    cr.update(Tables.Entries.CONTENT_URI, values, where, whereArgs);
                }
            }
        }

        cr.delete(Tables.Deleted.CONTENT_URI, null, null);
    }

    /**
     * Get all categories that have changed since the last sync with the backend.
     *
     * @param cr The ContentResolver
     * @return The list of updated categories
     */
    private static ArrayList<CatRecord> getUpdatedCats(ContentResolver cr) {
        final ArrayList<CatRecord> records = new ArrayList<>();
        CatRecord record;

        String where = Tables.Deleted.TYPE + " = " + Tables.Deleted.TYPE_CAT;
        Cursor cursor = cr.query(Tables.Deleted.CONTENT_URI, null, where, null, null);
        if(cursor != null) {
            try {
                while(cursor.moveToNext()) {
                    record = new CatRecord();
                    record.setDeleted(true);
                    record.setUuid(cursor.getString(cursor.getColumnIndex(Tables.Deleted.UUID)));
                    record.setUpdated(cursor.getLong(cursor.getColumnIndex(Tables.Deleted.TIME)));
                    records.add(record);
                }
            } finally {
                cursor.close();
            }
        }

        where = Tables.Cats.SYNCED + " = 0";
        cursor = cr.query(Tables.Cats.CONTENT_URI, null, where, null, null);
        if(cursor != null) {
            try {
                long id;
                while(cursor.moveToNext()) {
                    record = new CatRecord();
                    record.setUuid(cursor.getString(cursor.getColumnIndex(Tables.Cats.UUID)));
                    record.setName(cursor.getString(cursor.getColumnIndex(Tables.Cats.NAME)));
                    record.setUpdated(cursor.getLong(cursor.getColumnIndex(Tables.Cats.UPDATED)));

                    id = cursor.getLong(cursor.getColumnIndex(Tables.Cats._ID));
                    record.setExtras(getCatExtras(cr, id));
                    record.setFlavors(getCatFlavors(cr, id));

                    records.add(record);
                }
            } finally {
                cursor.close();
            }
        }

        return records;
    }

    /**
     * Get all the extra fields for a category from the local database.
     *
     * @param cr    The ContentResolver
     * @param catId The local database ID of the category
     * @return A list of extra records
     */
    private static ArrayList<ExtraRecord> getCatExtras(ContentResolver cr, long catId) {
        final Uri uri = Uri.withAppendedPath(Tables.Cats.CONTENT_ID_URI_BASE, catId + "/extras");
        final Cursor cursor = cr.query(uri, null, null, null, null);
        if(cursor != null) {
            try {
                final ArrayList<ExtraRecord> records = new ArrayList<>();
                ExtraRecord record;
                while(cursor.moveToNext()) {
                    record = new ExtraRecord();
                    record.setUuid(cursor.getString(cursor.getColumnIndex(Tables.Extras.UUID)));
                    record.setName(cursor.getString(cursor.getColumnIndex(Tables.Extras.NAME)));
                    record.setPos(cursor.getInt(cursor.getColumnIndex(Tables.Extras.POS)));
                    record.setDeleted(
                            cursor.getInt(cursor.getColumnIndex(Tables.Extras.DELETED)) == 1);
                    records.add(record);
                }

                return records;
            } finally {
                cursor.close();
            }
        }

        return null;
    }

    /**
     * Get all the flavors for a category from the local database.
     *
     * @param cr    The ContentResolver
     * @param catId The local database ID of the category
     * @return A list of flavor records
     */
    private static ArrayList<FlavorRecord> getCatFlavors(ContentResolver cr, long catId) {
        final Uri uri = Uri.withAppendedPath(Tables.Cats.CONTENT_ID_URI_BASE, catId + "/flavor");
        final Cursor cursor = cr.query(uri, null, null, null, null);
        if(cursor != null) {
            try {
                final ArrayList<FlavorRecord> records = new ArrayList<>();
                FlavorRecord record;
                while(cursor.moveToNext()) {
                    record = new FlavorRecord();
                    record.setName(cursor.getString(cursor.getColumnIndex(Tables.Flavors.NAME)));
                    record.setPos(cursor.getInt(cursor.getColumnIndex(Tables.Flavors.POS)));
                    records.add(record);
                }

                return records;
            } finally {
                cursor.close();
            }
        }

        return null;
    }

    /**
     * Get all entries that have changed since the last sync with the backend.
     *
     * @param cr The ContentResolver
     * @return The list of updated entries
     */
    private static ArrayList<EntryRecord> getUpdatedEntries(ContentResolver cr) {
        final ArrayList<EntryRecord> records = new ArrayList<>();
        EntryRecord record;

        String where = Tables.Deleted.TYPE + " = " + Tables.Deleted.TYPE_ENTRY;
        Cursor cursor = cr.query(Tables.Deleted.CONTENT_URI, null, where, null, null);
        if(cursor != null) {
            try {
                while(cursor.moveToNext()) {
                    record = new EntryRecord();
                    record.setDeleted(true);
                    record.setUuid(cursor.getString(cursor.getColumnIndex(Tables.Deleted.UUID)));
                    record.setUpdated(cursor.getLong(cursor.getColumnIndex(Tables.Deleted.TIME)));
                    records.add(record);
                }
            } finally {
                cursor.close();
            }
        }

        where = Tables.Entries.SYNCED + " = 0";
        cursor = cr.query(Tables.Entries.CONTENT_URI, null, where, null, null);
        if(cursor != null) {
            try {
                long id;
                while(cursor.moveToNext()) {
                    record = new EntryRecord();
                    record.setUuid(cursor.getString(cursor.getColumnIndex(Tables.Entries.UUID)));
                    record.setCatUuid(
                            cursor.getString(cursor.getColumnIndex(Tables.Entries.CAT_UUID)));
                    record.setTitle(cursor.getString(cursor.getColumnIndex(Tables.Entries.TITLE)));
                    record.setMaker(cursor.getString(cursor.getColumnIndex(Tables.Entries.MAKER)));
                    record.setOrigin(
                            cursor.getString(cursor.getColumnIndex(Tables.Entries.ORIGIN)));
                    record.setPrice(cursor.getString(cursor.getColumnIndex(Tables.Entries.PRICE)));
                    record.setLocation(
                            cursor.getString(cursor.getColumnIndex(Tables.Entries.LOCATION)));
                    record.setDate(cursor.getLong(cursor.getColumnIndex(Tables.Entries.DATE)));
                    record.setRating(cursor.getFloat(cursor.getColumnIndex(Tables.Entries.RATING)));
                    record.setNotes(cursor.getString(cursor.getColumnIndex(Tables.Entries.NOTES)));
                    record.setUpdated(
                            cursor.getLong(cursor.getColumnIndex(Tables.Entries.UPDATED)));

                    id = cursor.getLong(cursor.getColumnIndex(Tables.Entries._ID));
                    record.setExtras(getEntryExtras(cr, id));
                    record.setFlavors(getEntryFlavors(cr, id));
                    record.setPhotos(getEntryPhotos(cr, id));

                    records.add(record);
                }
            } finally {
                cursor.close();
            }
        }

        return records;
    }

    /**
     * Get all the extra fields for an entry from the local database.
     *
     * @param cr      The ContentResolver
     * @param entryId The local database ID of the entry
     * @return A list of extra records
     */
    private static ArrayList<ExtraRecord> getEntryExtras(ContentResolver cr, long entryId) {
        final Uri uri =
                Uri.withAppendedPath(Tables.Entries.CONTENT_ID_URI_BASE, entryId + "/extras");
        final Cursor cursor = cr.query(uri, null, null, null, null);
        if(cursor != null) {
            try {
                final ArrayList<ExtraRecord> records = new ArrayList<>();
                ExtraRecord record;
                while(cursor.moveToNext()) {
                    record = new ExtraRecord();
                    record.setUuid(cursor.getString(cursor.getColumnIndex(Tables.Extras.UUID)));
                    record.setValue(
                            cursor.getString(cursor.getColumnIndex(Tables.EntriesExtras.VALUE)));
                    record.setPos(cursor.getInt(cursor.getColumnIndex(Tables.Extras.POS)));
                    records.add(record);
                }

                return records;
            } finally {
                cursor.close();
            }
        }

        return null;
    }

    /**
     * Get all the flavors for an entry from the local database.
     *
     * @param cr      The ContentResolver
     * @param entryId The local database ID of the entry
     * @return A list of flavor records
     */
    private static ArrayList<FlavorRecord> getEntryFlavors(ContentResolver cr, long entryId) {
        final Uri uri =
                Uri.withAppendedPath(Tables.Entries.CONTENT_ID_URI_BASE, entryId + "/flavor");
        final Cursor cursor = cr.query(uri, null, null, null, null);
        if(cursor != null) {
            try {
                final ArrayList<FlavorRecord> records = new ArrayList<>();
                FlavorRecord record;
                while(cursor.moveToNext()) {
                    record = new FlavorRecord();
                    record.setName(
                            cursor.getString(cursor.getColumnIndex(Tables.EntriesFlavors.FLAVOR)));
                    record.setValue(
                            cursor.getInt(cursor.getColumnIndex(Tables.EntriesFlavors.VALUE)));
                    record.setPos(cursor.getInt(cursor.getColumnIndex(Tables.EntriesFlavors.POS)));
                    records.add(record);
                }

                return records;
            } finally {
                cursor.close();
            }
        }

        return null;
    }

    /**
     * Get all the photos for an entry from the local database.
     *
     * @param cr      The ContentResolver
     * @param entryId The local database ID of the entry
     * @return A list of photo records
     */
    private static ArrayList<PhotoRecord> getEntryPhotos(ContentResolver cr, long entryId) {
        final Uri uri =
                Uri.withAppendedPath(Tables.Entries.CONTENT_ID_URI_BASE, entryId + "/photos");
        final Cursor cursor = cr.query(uri, null, null, null, null);
        if(cursor != null) {
            try {
                final ArrayList<PhotoRecord> records = new ArrayList<>();
                PhotoRecord record;
                String hash;
                String path;
                while(cursor.moveToNext()) {
                    hash = cursor.getString(cursor.getColumnIndex(Tables.Photos.HASH));
                    if(hash == null) {
                        path = cursor.getString(cursor.getColumnIndex(Tables.Photos.PATH));
                        if(path != null) {
                            hash = PhotoUtils.getMD5Hash(new File(path));
                        }
                        if(hash == null) {
                            continue;
                        }
                    }
                    record = new PhotoRecord();
                    record.setHash(hash);
                    record.setDriveId(
                            cursor.getString(cursor.getColumnIndex(Tables.Photos.DRIVE_ID)));
                    record.setPos(cursor.getInt(cursor.getColumnIndex(Tables.Photos.POS)));
                    records.add(record);
                }

                return records;
            } finally {
                cursor.close();
            }
        }

        return null;
    }

    /**
     * Fetch all the changed records from the backend.
     *
     * @param sync     The Sync endpoint client
     * @param clientId The client ID
     * @throws IOException
     */
    private void fetchUpdates(Sync sync, long clientId) throws IOException {
        final ContentResolver cr = mContext.getContentResolver();
        final UpdateRecord record =
                sync.fetchUpdates(clientId, BackendUtils.getLastSync(mContext)).execute();

        if(record.getCats() != null) {
            for(CatRecord catRecord : record.getCats()) {
                parseCat(cr, catRecord);
            }
        }

        if(record.getEntries() != null) {
            for(EntryRecord entryRecord : record.getEntries()) {
                parseEntry(cr, entryRecord);
            }
        }
    }

    /**
     * Parse a category record and save the category to the local database.
     *
     * @param cr     The ContentResolver
     * @param record The category record
     */
    private static void parseCat(ContentResolver cr, CatRecord record) {
        final long catId = getCatId(cr, record.getUuid());
        Uri uri;
        final ContentValues values = new ContentValues();
        if(record.getDeleted()) {
            if(catId > 0) {
                uri = ContentUris.withAppendedId(Tables.Cats.CONTENT_ID_URI_BASE, catId);
                values.put(Tables.Cats.PUBLISHED, false);
                cr.update(uri, values, null, null);
                cr.delete(uri, null, null);
            }
        } else {
            values.put(Tables.Cats.NAME, record.getName());
            values.put(Tables.Cats.PUBLISHED, true);
            if(catId > 0) {
                uri = ContentUris.withAppendedId(Tables.Cats.CONTENT_ID_URI_BASE, catId);
                cr.update(uri, values, null, null);
            } else {
                uri = Tables.Cats.CONTENT_URI;
                values.put(Tables.Cats.UUID, record.getUuid());
                uri = cr.insert(uri, values);
            }

            parseCatExtras(cr, uri, record);
            parseCatFlavors(cr, uri, record);
        }
    }

    /**
     * Parse the extra fields from a category record and save them to the local database.
     *
     * @param cr     The ContentResolver
     * @param catUri The category Uri
     * @param record The category record
     */
    private static void parseCatExtras(ContentResolver cr, Uri catUri, CatRecord record) {
        final ArrayList<ExtraRecord> extras = (ArrayList<ExtraRecord>)record.getExtras();
        if(extras == null) {
            return;
        }

        Uri uri = Uri.withAppendedPath(catUri, "extras");
        cr.delete(uri, null, null);

        final ContentValues values = new ContentValues();
        long id;
        for(ExtraRecord extra : extras) {
            values.put(Tables.Extras.UUID, extra.getUuid());
            values.put(Tables.Extras.NAME, extra.getName());
            values.put(Tables.Extras.POS, extra.getPos());
            values.put(Tables.Extras.DELETED, extra.getDeleted());

            id = getExtraId(cr, extra.getUuid());
            if(id > 0) {
                uri = ContentUris.withAppendedId(Tables.Extras.CONTENT_ID_URI_BASE, id);
                cr.update(uri, values, null, null);
            } else {
                uri = Uri.withAppendedPath(catUri, "extras");
                cr.insert(uri, values);
            }
        }
    }

    /**
     * Parse the flavors from a category record and save them to the local database.
     *
     * @param cr     The ContentResolver
     * @param catUri The category Uri
     * @param record The category record
     */
    private static void parseCatFlavors(ContentResolver cr, Uri catUri, CatRecord record) {
        final ArrayList<FlavorRecord> flavors = (ArrayList<FlavorRecord>)record.getFlavors();
        if(flavors == null) {
            return;
        }

        final Uri uri = Uri.withAppendedPath(catUri, "flavor");
        cr.delete(uri, null, null);

        final ContentValues values = new ContentValues();
        for(FlavorRecord flavor : flavors) {
            values.put(Tables.Flavors.NAME, flavor.getName());
            values.put(Tables.Flavors.POS, flavor.getPos());
            cr.insert(uri, values);
        }
    }

    /**
     * Parse an entry record and save the entry to the local database.
     *
     * @param cr     The ContentResolver
     * @param record The entry record
     */
    private static void parseEntry(ContentResolver cr, EntryRecord record) {
        final long entryId = getEntryId(cr, record.getUuid());
        Uri uri;
        final ContentValues values = new ContentValues();
        if(record.getDeleted()) {
            if(entryId > 0) {
                uri = ContentUris.withAppendedId(Tables.Entries.CONTENT_ID_URI_BASE, entryId);
                values.put(Tables.Entries.PUBLISHED, false);
                cr.update(uri, values, null, null);
                cr.delete(uri, null, null);
            }
        } else {
            final long catId = getCatId(cr, record.getCatUuid());
            if(catId == 0) {
                return;
            }
            values.put(Tables.Entries.TITLE, record.getTitle());
            values.put(Tables.Entries.MAKER, record.getMaker());
            values.put(Tables.Entries.ORIGIN, record.getOrigin());
            values.put(Tables.Entries.PRICE, record.getPrice());
            values.put(Tables.Entries.LOCATION, record.getLocation());
            values.put(Tables.Entries.DATE, record.getDate());
            values.put(Tables.Entries.RATING, record.getRating());
            values.put(Tables.Entries.NOTES, record.getNotes());
            values.put(Tables.Entries.PUBLISHED, true);
            if(entryId > 0) {
                uri = ContentUris.withAppendedId(Tables.Entries.CONTENT_ID_URI_BASE, entryId);
                cr.update(uri, values, null, null);
            } else {
                uri = Tables.Entries.CONTENT_URI;
                values.put(Tables.Entries.CAT, catId);
                values.put(Tables.Entries.UUID, record.getUuid());
                uri = cr.insert(uri, values);
            }

            parseEntryExtras(cr, uri, record);
            parseEntryFlavors(cr, uri, record);
            parseEntryPhotos(cr, uri, record);
        }
    }

    /**
     * Parse the extra fields from an entry record and save them to the local database.
     *
     * @param cr       The ContentResolver
     * @param entryUri The entry Uri
     * @param record   The entry record
     */
    private static void parseEntryExtras(ContentResolver cr, Uri entryUri, EntryRecord record) {
        final ArrayList<ExtraRecord> extras = (ArrayList<ExtraRecord>)record.getExtras();
        if(extras == null) {
            return;
        }

        final Uri uri = Uri.withAppendedPath(entryUri, "extras");
        cr.delete(uri, null, null);

        long extraId;
        final ContentValues values = new ContentValues();
        for(ExtraRecord extra : extras) {
            extraId = getExtraId(cr, extra.getUuid());
            if(extraId > 0) {
                values.put(Tables.EntriesExtras.EXTRA, extraId);
                values.put(Tables.EntriesExtras.VALUE, extra.getValue());
                cr.insert(uri, values);
            }
        }
    }

    /**
     * Parse the flavors from an entry record and save them to the local database.
     *
     * @param cr       The ContentResolver
     * @param entryUri The entry Uri
     * @param record   The entry record
     */
    private static void parseEntryFlavors(ContentResolver cr, Uri entryUri, EntryRecord record) {
        final ArrayList<FlavorRecord> flavors = (ArrayList<FlavorRecord>)record.getFlavors();
        if(flavors == null) {
            return;
        }

        final Uri uri = Uri.withAppendedPath(entryUri, "flavor");
        cr.delete(uri, null, null);

        final ContentValues values = new ContentValues();
        for(FlavorRecord flavor : flavors) {
            values.put(Tables.EntriesFlavors.FLAVOR, flavor.getName());
            values.put(Tables.EntriesFlavors.VALUE, flavor.getValue());
            values.put(Tables.EntriesFlavors.POS, flavor.getPos());
            cr.insert(uri, values);
        }
    }

    /**
     * Parse the photos from an entry record and save them to the local database.
     *
     * @param cr       The ContentResolver
     * @param entryUri The entry Uri
     * @param record   The entry record
     */
    private static void parseEntryPhotos(ContentResolver cr, Uri entryUri, EntryRecord record) {
        final ArrayList<PhotoRecord> photos = (ArrayList<PhotoRecord>)record.getPhotos();
        if(photos == null) {
            return;
        }

        final ArrayList<String> photoHashes = new ArrayList<>();

        final Uri uri = Uri.withAppendedPath(entryUri, "photos");

        final ContentValues values = new ContentValues();
        final String where = Tables.Photos.HASH + " = ?";
        final String[] whereArgs = new String[1];
        for(PhotoRecord photo : photos) {
            photoHashes.add(photo.getHash());
            whereArgs[0] = photo.getHash();
            values.put(Tables.Photos.DRIVE_ID, photo.getDriveId());
            values.put(Tables.Photos.POS, photo.getPos());
            if(cr.update(uri, values, where, whereArgs) == 0) {
                values.put(Tables.Photos.HASH, photo.getHash());
                cr.insert(uri, values);
            }
        }

        final String[] projection = new String[] {
                Tables.Photos._ID,
                Tables.Photos.HASH
        };
        final Cursor cursor =
                cr.query(uri, projection, Tables.Photos.HASH + " NOT NULL", null, null);
        if(cursor != null) {
            try {
                long id;
                String hash;
                while(cursor.moveToNext()) {
                    hash = cursor.getString(cursor.getColumnIndex(Tables.Photos.HASH));
                    if(!photoHashes.contains(hash)) {
                        id = cursor.getLong(cursor.getColumnIndex(Tables.Photos._ID));
                        cr.delete(ContentUris.withAppendedId(Tables.Photos.CONTENT_ID_URI_BASE, id),
                                null, null);
                    }
                }
            } finally {
                cursor.close();
            }
        }
    }

    /**
     * Get the local database ID of a category based on the UUID.
     *
     * @param cr   The ContentResolver
     * @param uuid The UUID of the category
     * @return The local database ID of the category or 0 if not found
     */
    private static long getCatId(ContentResolver cr, String uuid) {
        if(uuid == null) {
            return 0;
        }
        final String[] projection = new String[] {Tables.Cats._ID};
        final String where = Tables.Cats.UUID + " = ?";
        final String[] whereArgs = new String[] {uuid};
        final Cursor cursor = cr.query(Tables.Cats.CONTENT_URI, projection, where, whereArgs, null);
        if(cursor != null) {
            try {
                if(cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
        }

        return 0;
    }

    /**
     * Get the local database ID of an entry based on the UUID.
     *
     * @param cr   The ContentResolver
     * @param uuid The UUID of the entry
     * @return The local database ID of the entry or 0 if not found
     */
    private static long getEntryId(ContentResolver cr, String uuid) {
        if(uuid == null) {
            return 0;
        }
        final String[] projection = new String[] {Tables.Entries._ID};
        final String where = Tables.Entries.UUID + " = ?";
        final String[] whereArgs = new String[] {uuid};
        final Cursor cursor =
                cr.query(Tables.Entries.CONTENT_URI, projection, where, whereArgs, null);
        if(cursor != null) {
            try {
                if(cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
        }

        return 0;
    }

    /**
     * Get the local database ID of an extra field based on the UUID.
     *
     * @param cr   The ContentResolver
     * @param uuid The UUID of the extra field
     * @return The local database ID of the extra field or 0 if not found
     */
    private static long getExtraId(ContentResolver cr, String uuid) {
        if(uuid == null) {
            return 0;
        }
        final String[] projection = new String[] {Tables.Extras._ID};
        final String where = Tables.Extras.UUID + " = ?";
        final String[] whereArgs = new String[] {uuid};
        final Cursor cursor =
                cr.query(Tables.Extras.CONTENT_URI, projection, where, whereArgs, null);
        if(cursor != null) {
            try {
                if(cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
        }

        return 0;
    }
}