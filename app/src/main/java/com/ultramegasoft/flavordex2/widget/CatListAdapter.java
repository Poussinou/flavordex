package com.ultramegasoft.flavordex2.widget;

import android.content.Context;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.ultramegasoft.flavordex2.FlavordexApp;
import com.ultramegasoft.flavordex2.R;
import com.ultramegasoft.flavordex2.provider.Tables;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Adapter for listing entry categories in a Spinner.
 *
 * @author Steve Guidetti
 */
public class CatListAdapter extends BaseAdapter {
    /**
     * The Context
     */
    private final Context mContext;

    /**
     * The layout resource ID to use for each list item
     */
    private final int mLayoutId;

    /**
     * The ID for the TextView within the layout
     */
    private final int mTextViewId;

    /**
     * List of categories sorted by display name
     */
    private final ArrayList<Category> mCats = new ArrayList<>();

    /**
     * @param context     The Context
     * @param cursor      The Cursor from the database query
     * @param layoutResId The layout resource ID to use for each list item
     * @param textViewId  The ID for the TextView within the layout
     */
    public CatListAdapter(Context context, Cursor cursor, int layoutResId, int textViewId) {
        mContext = context;
        mLayoutId = layoutResId;
        mTextViewId = textViewId;

        swapCursor(cursor);
    }

    /**
     * Set the Cursor backing this Adapter.
     *
     * @param newCursor The new Cursor
     */
    public final void swapCursor(Cursor newCursor) {
        mCats.clear();

        if(newCursor != null) {
            readCursor(newCursor, mCats);
        }

        notifyDataSetChanged();
    }

    /**
     * Read the new Cursor into the array and sort by name.
     *
     * @param cursor The Cursor
     * @param cats   The ArrayList to add data to
     */
    protected void readCursor(Cursor cursor, ArrayList<Category> cats) {
        cursor.moveToPosition(-1);
        while(cursor.moveToNext()) {
            cats.add(readCursorRow(cursor));
        }
        Collections.sort(cats);
    }

    /**
     * Read the current row into a Category object.
     *
     * @param cursor The Cursor
     * @return A Category read from the database row
     */
    protected Category readCursorRow(Cursor cursor) {
        final long id = cursor.getLong(cursor.getColumnIndex(Tables.Cats._ID));
        final String name = cursor.getString(cursor.getColumnIndex(Tables.Cats.NAME));
        final boolean preset = cursor.getInt(cursor.getColumnIndex(Tables.Cats.PRESET)) == 1;
        final int numEntries = cursor.getInt(cursor.getColumnIndex(Tables.Cats.NUM_ENTRIES));
        return new Category(mContext, id, name, preset, numEntries);
    }

    @Override
    public int getCount() {
        return mCats.size();
    }

    @Override
    public Category getItem(int position) {
        return mCats.get(position);
    }

    @Override
    public long getItemId(int position) {
        return mCats.get(position).id;
    }

    /**
     * Get the position of an item based on ID.
     *
     * @param id The database ID of the item
     * @return The index of the item, or -1 if the item does not exist
     */
    public int getItemIndex(long id) {
        for(int i = 0; i < mCats.size(); i++) {
            if(mCats.get(i).id == id) {
                return i;
            }
        }
        return ListView.INVALID_POSITION;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if(convertView == null) {
            convertView = LayoutInflater.from(mContext).inflate(mLayoutId, parent, false);
        }

        final Category cat = getItem(position);
        final String text = mContext.getString(R.string.list_item_cat, cat.realName, cat.numEntries);

        final TextView textView = (TextView)convertView.findViewById(mTextViewId);
        textView.setText(text);

        return convertView;
    }

    /**
     * Holder for data about a category which can be compared by name.
     */
    public static class Category implements Comparable<Category> {
        /**
         * The database ID
         */
        public long id;

        /**
         * The name of the category
         */
        public String name;

        /**
         * The display name of the category
         */
        public String realName;

        /**
         * Whether this is a preset category
         */
        public boolean preset;

        /**
         * The number of entries in this category
         */
        public int numEntries;

        /**
         * @param context    The Context
         * @param id         The database ID
         * @param name       The name of the category
         * @param preset     Whether this is a preset category
         * @param numEntries The number of entries in this category
         */
        public Category(Context context, long id, String name, boolean preset, int numEntries) {
            this.id = id;
            this.name = name;
            this.realName = FlavordexApp.getRealCatName(context, name);
            this.preset = preset;
            this.numEntries = numEntries;
        }

        @Override
        public int compareTo(@NonNull Category another) {
            return realName.compareTo(another.realName);
        }
    }
}
