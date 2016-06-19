package com.ultramegasoft.flavordex2.backend;

import java.util.HashMap;

/**
 * Model for a response to a push request.
 *
 * @author Steve Guidetti
 */
public class UpdateResponse {
    private HashMap<String, Boolean> catStatuses;
    private HashMap<String, Boolean> entryStatuses;
    private HashMap<String, Long> entryIds;

    public HashMap<String, Boolean> getCatStatuses() {
        return catStatuses;
    }

    public void setCatStatuses(HashMap<String, Boolean> catStatuses) {
        this.catStatuses = catStatuses;
    }

    public HashMap<String, Boolean> getEntryStatuses() {
        return entryStatuses;
    }

    public void setEntryStatuses(HashMap<String, Boolean> entryStatuses) {
        this.entryStatuses = entryStatuses;
    }

    public HashMap<String, Long> getEntryIds() {
        return entryIds;
    }

    public void setEntryIds(HashMap<String, Long> entryIds) {
        this.entryIds = entryIds;
    }
}
