package com.visionguide.phone;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.util.Log;

/**
 * NfcMedicineReader — handles NFC foreground dispatch in MainActivity.
 *
 * Lifecycle:
 *   Call enable(activity) in onResume.
 *   Call disable(activity) in onPause.
 *   Call handleIntent(intent, store) when the activity receives a new NFC intent
 *   (from onNewIntent or onCreate). Returns a spoken message or null.
 *
 * IMPORTANT: This class only reads back user-entered medicine name and strength.
 * It does NOT invent dosage instructions or make any safety claims.
 */
final class NfcMedicineReader {
    private static final String TAG = "NfcMedicineReader";

    private final NfcAdapter adapter;
    private PendingIntent pendingIntent;

    NfcMedicineReader(Activity activity) {
        adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter != null) {
            Intent intent = new Intent(activity, activity.getClass())
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pendingIntent = PendingIntent.getActivity(
                    activity, 0, intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }

    boolean isAvailable() {
        return adapter != null && adapter.isEnabled();
    }

    void enable(Activity activity) {
        if (adapter != null && pendingIntent != null) {
            try {
                adapter.enableForegroundDispatch(activity, pendingIntent, null, null);
            } catch (Exception e) {
                Log.w(TAG, "enableForegroundDispatch failed: " + e.getMessage());
            }
        }
    }

    void disable(Activity activity) {
        if (adapter != null) {
            try {
                adapter.disableForegroundDispatch(activity);
            } catch (Exception e) {
                Log.w(TAG, "disableForegroundDispatch failed: " + e.getMessage());
            }
        }
    }

    /**
     * Process an NFC intent. Returns a spoken message, or null if the intent
     * is not an NFC tag intent.
     *
     * Possible return values:
     *   "[Name], [strength]"             — tag registered, speaks the medicine details
     *   "Tag not registered. Open settings to register this medicine."
     *   "Tag could not be read."
     */
    String handleIntent(Intent intent, MedicineStore store) {
        String action = intent == null ? null : intent.getAction();
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            return null;    // not an NFC intent
        }
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return "Tag could not be read.";

        String tagId = bytesToHex(tag.getId());
        Log.d(TAG, "NFC tag detected: " + tagId);

        String medicine = store.lookup(tagId);
        if (medicine != null) {
            return medicine;
        }
        return "Tag not registered. Open settings to register this medicine.";
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
