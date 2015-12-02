package com.ultramegasoft.flavordex2;

import android.os.Bundle;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;

/**
 * Lite implementation of the main application Activity. Adds a banner ad.
 *
 * @author Steve Guidetti
 * @see BaseEntryListActivity
 */
public class EntryListActivity extends BaseEntryListActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final AdView adView = (AdView)findViewById(R.id.ad_banner);
        if(adView != null) {
            final AdRequest adRequest = new AdRequest.Builder()
                    .addTestDevice(AdRequest.DEVICE_ID_EMULATOR)
                    .build();
            adView.loadAd(adRequest);
        }
    }
}
