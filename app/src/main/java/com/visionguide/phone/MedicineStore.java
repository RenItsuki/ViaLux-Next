package com.visionguide.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.HashMap;
import java.util.Map;

/**
 * MedicineStore — stores NFC tag → medicine details mappings.
 * Backed by EncryptedSharedPreferences (Jetpack Security).
 *
 * Stored data: tag ID (hex) → "name|strength" (user-entered only).
 * The store never holds dosage instructions, medical advice, or safety claims.
 * Falls back to plain SharedPreferences if EncryptedSharedPreferences fails
 * (e.g., on older emulators without a secure keystore).
 */
final class MedicineStore {
    private static final String TAG   = "MedicineStore";
    private static final String PREFS = "via_lux_medicine";
    private static final String SEP   = "|";       // separator between name and strength

    private SharedPreferences prefs;

    MedicineStore(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    context, PREFS, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable; using plain prefs. " + e.getMessage());
            prefs = context.getSharedPreferences(PREFS + "_plain", Context.MODE_PRIVATE);
        }
    }

    /** Store a medicine entry. name and strength come from user input only. */
    void register(String tagId, String name, String strength) {
        prefs.edit()
                .putString(tagId, name.trim() + SEP + strength.trim())
                .apply();
    }

    /** Remove a stored entry. */
    void remove(String tagId) {
        prefs.edit().remove(tagId).apply();
    }

    /** Returns a human-readable description, or null if the tag is not registered. */
    String lookup(String tagId) {
        String value = prefs.getString(tagId, null);
        if (value == null) return null;
        int idx = value.indexOf(SEP);
        if (idx < 0) return value;          // legacy single-field entry
        String name     = value.substring(0, idx).trim();
        String strength = value.substring(idx + 1).trim();
        if (strength.isEmpty()) return name;
        return name + ", " + strength;
    }

    /** Returns all stored tag IDs. */
    Map<String, String> all() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            result.put(entry.getKey(), lookup(entry.getKey()));
        }
        return result;
    }
}
