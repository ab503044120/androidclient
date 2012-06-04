/*
 * Kontalk Android client
 * Copyright (C) 2011 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.data;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.kontalk.provider.MyUsers.Users;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;


/**
 * A simple contact.
 * @author Daniele Ricci
 * @version 1.0
 */
public class Contact {
    private final static String TAG = Contact.class.getSimpleName();

    private final static String[] ALL_CONTACTS_PROJECTION = {
        Users._ID,
        Users.CONTACT_ID,
        Users.LOOKUP_KEY,
        Users.DISPLAY_NAME,
        Users.NUMBER,
        Users.HASH,
        Users.REGISTERED
    };

    public static final int COLUMN_ID = 0;
    public static final int COLUMN_CONTACT_ID = 1;
    public static final int COLUMN_LOOKUP_KEY = 2;
    public static final int COLUMN_DISPLAY_NAME = 3;
    public static final int COLUMN_NUMBER = 4;
    public static final int COLUMN_HASH = 5;
    public static final int COLUMN_REGISTERED = 6;

    /** The aggregated Contact id identified by this object. */
    private final long mContactId;

    private String mNumber;
    private String mName;
    private String mHash;

    private String mLookupKey;
    private Uri mContactUri;
    private boolean mRegistered;

    private BitmapDrawable mAvatar;
    private byte [] mAvatarData;

    /**
     * Contact cache.
     * @author Daniele Ricci
     */
    private final static class ContactCache extends HashMap<String, Contact> {
        private static final long serialVersionUID = 2788447346920511692L;

        private final class ContactsObserver extends ContentObserver {
            private Context mContext;
            private String mUserId;

            public ContactsObserver(Context context, String userId) {
                super(null);
                mContext = context;
                mUserId = userId;
            }

            @Override
            public void onChange(boolean selfChange) {
                synchronized (ContactCache.this) {
                    remove(mContext, mUserId);
                    get(mContext, mUserId);
                }
            }
        }

        private Map<String, ContactsObserver> mObservers;

        public ContactCache() {
            mObservers = new HashMap<String, ContactsObserver>();
        }

        public Contact get(Context context, String userId) {
            Contact c = get(userId);
            if (c == null) {
                c = _findByUserId(context, userId);
                if (c != null) {
                    // retrieve a previous observer if present
                    ContactsObserver observer = mObservers.get(userId);
                    if (observer == null) {
                        // create a new observer
                        observer = new ContactsObserver(
                                context.getApplicationContext(),userId);
                        mObservers.put(userId, observer);
                    }
                    // register for changes
                    context.getContentResolver()
                        .registerContentObserver(c.getUri(), false, observer);

                    // put the contact in the cache
                    put(userId, c);
                }
            }

            return c;
        }

        public Contact remove(Context context, String userId) {
            Contact c = remove(userId);
            if (c != null) {
                ContactsObserver observer = mObservers.remove(userId);
                if (observer != null)
                    context.getContentResolver()
                        .unregisterContentObserver(observer);
            }

            return c;
        }
    }

    private final static ContactCache cache = new ContactCache();

    private Contact(long contactId, String lookupKey, String name, String number, String hash) {
        mContactId = contactId;
        mLookupKey = lookupKey;
        mName = name;
        mNumber = number;
        mHash = hash;
    }

    /** Returns the {@link Contacts} {@link Uri} identified by this object. */
    public Uri getUri() {
        if (mContactUri == null)
            mContactUri = ContactsContract.Contacts.getLookupUri(mContactId, mLookupKey);
        return mContactUri;
    }

    public long getId() {
        return mContactId;
    }

    public String getNumber() {
        return mNumber;
    }

    public String getName() {
        return mName;
    }

    public String getHash() {
        return mHash;
    }

    public boolean isRegistered() {
        return mRegistered;
    }

    public synchronized Drawable getAvatar(Context context, Drawable defaultValue) {
        if (mAvatar == null) {
            if (mAvatarData == null)
                mAvatarData = loadAvatarData(context, getUri());

            if (mAvatarData != null) {
                Bitmap b = BitmapFactory.decodeByteArray(mAvatarData, 0, mAvatarData.length);
                mAvatar = new BitmapDrawable(context.getResources(), b);
            }
        }
        return mAvatar != null ? mAvatar : defaultValue;
    }

    /** Frees resources taken by the avatar bitmap. */
    @Override
    protected void finalize() throws Throwable {
        if (mAvatar != null) {
            Bitmap b = mAvatar.getBitmap();
            mAvatar = null;
            mAvatarData = null;
            b.recycle();
        }
        super.finalize();
    }

    /** Builds a contact from a UsersProvider cursor. */
    public static Contact fromUsersCursor(Context context, Cursor cursor) {
        final long contactId = cursor.getLong(COLUMN_CONTACT_ID);
        final String key = cursor.getString(COLUMN_LOOKUP_KEY);
        final String name = cursor.getString(COLUMN_DISPLAY_NAME);
        final String number = cursor.getString(COLUMN_NUMBER);
        final String hash = cursor.getString(COLUMN_HASH);
        final boolean registered = (cursor.getInt(COLUMN_REGISTERED) != 0);

        Contact c = new Contact(contactId, key, name, number, hash);
        c.mRegistered = registered;
        return c;
    }

    public static String numberByUserId(Context context, String userId) {
        Cursor c = null;
        try {
            ContentResolver cres = context.getContentResolver();
            c = cres.query(Uri.withAppendedPath(Users.CONTENT_URI, userId),
                    new String[] { Users.NUMBER },
                    null, null, null);

            if (c.moveToFirst())
                return c.getString(0);
        }
        finally {
            if (c != null)
                c.close();
        }

        return null;
    }

    public static Contact findByUserId(Context context, String userId) {
        return cache.get(context, userId);
    }

    private static Contact _findByUserId(Context context, String userId) {
        ContentResolver cres = context.getContentResolver();
        Cursor c = cres.query(Uri.withAppendedPath(Users.CONTENT_URI, userId),
            new String[] {
                Users.NUMBER,
                Users.DISPLAY_NAME,
                Users.LOOKUP_KEY,
                Users.CONTACT_ID,
                Users.HASH,
                Users.REGISTERED
            }, null, null, null);

        if (c.moveToFirst()) {
            String number = c.getString(0);
            String name = c.getString(1);
            String key = c.getString(2);
            long cid = c.getLong(3);
            String hash = c.getString(4);
            boolean registered = (c.getInt(5) != 0);
            c.close();

            Contact contact = new Contact(cid, key, name, number, hash);
            contact.mRegistered = registered;
            return contact;
        }
        c.close();
        return null;
    }

    private static byte[] loadAvatarData(Context context, Uri contactUri) {
        byte[] data = null;

        Uri uri;
        try {
            long cid = ContentUris.parseId(contactUri);
            uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, cid);
        }
        catch (Exception e) {
            uri = contactUri;
        }

        InputStream avatarDataStream = Contacts.openContactPhotoInputStream(
                    context.getContentResolver(), uri);
        if (avatarDataStream != null) {
            try {
                    data = new byte[avatarDataStream.available()];
                    avatarDataStream.read(data, 0, data.length);
            }
            catch (IOException e) {
                Log.e(TAG, "cannot retrieve contact avatar", e);
            }
            finally {
                try {
                    avatarDataStream.close();
                }
                catch (IOException e) {}
            }
        }

        return data;
    }

    public static String getUserId(Context context, Uri rawContactUri) {
        Cursor c = context.getContentResolver().query(rawContactUri,
                new String[] {
                    RawContacts.SYNC3
                }, null, null, null);

        if (c.moveToFirst()) {
            return c.getString(0);
        }

        return null;
    }

    public static Cursor queryContacts(Context context) {
        return context.getContentResolver().query(Users.CONTENT_URI, ALL_CONTACTS_PROJECTION,
            Users.REGISTERED + " <> 0", null, Users.DISPLAY_NAME);
    }

}
