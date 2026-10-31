package com.visionguide.phone;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * MedicineRegisterActivity — lets the user tap an NFC tag and assign a medicine
 * name and strength to it. Opened from the main screen via the NFC menu button.
 *
 * The activity stores only the user-entered name and strength.
 * It does not store or suggest dosage instructions.
 */
public final class MedicineRegisterActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private MedicineStore store;

    private TextView statusText;
    private EditText nameEdit;
    private EditText strengthEdit;
    private String pendingTagId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new MedicineStore(this);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null) {
            Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            pendingIntent = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && pendingIntent != null) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                pendingTagId = bytesToHex(tag.getId());
                statusText.setText("Tag detected: " + pendingTagId + "\nEnter medicine name and strength, then press Register.");
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(7, 12, 18));
        root.setPadding(dp(20), dp(30), dp(20), dp(20));
        root.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Register Medicine Tag");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));

        statusText = new TextView(this);
        statusText.setText("Hold an NFC tag to the back of the phone.");
        statusText.setTextColor(0xFFB8C7D8);
        statusText.setTextSize(15f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(8), 0, dp(16));

        nameEdit = editField("Medicine name (e.g. Paracetamol)");
        strengthEdit = editField("Strength (e.g. 500 mg)");

        TextView note = new TextView(this);
        note.setText("Note: Only the name and strength you enter are stored. This app does not store dosage instructions or provide medical advice.");
        note.setTextColor(0xFF9FB0C3);
        note.setTextSize(12f);
        note.setPadding(0, dp(8), 0, dp(12));

        Button registerBtn = new Button(this);
        registerBtn.setText("Register Tag");
        registerBtn.setTextColor(Color.WHITE);
        registerBtn.setAllCaps(false);
        registerBtn.setOnClickListener(v -> {
            if (pendingTagId == null || pendingTagId.isEmpty()) {
                Toast.makeText(this, "Please tap an NFC tag first.", Toast.LENGTH_SHORT).show();
                return;
            }
            String name     = nameEdit.getText().toString().trim();
            String strength = strengthEdit.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter a medicine name.", Toast.LENGTH_SHORT).show();
                return;
            }
            store.register(pendingTagId, name, strength);
            Toast.makeText(this, "Registered: " + name + (strength.isEmpty() ? "" : " " + strength), Toast.LENGTH_LONG).show();
            statusText.setText("Registered! Hold another tag, or go back.");
            pendingTagId = null;
            nameEdit.setText("");
            strengthEdit.setText("");
        });

        Button backBtn = new Button(this);
        backBtn.setText("Back");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setAllCaps(false);
        backBtn.setOnClickListener(v -> finish());

        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        full.setMargins(0, dp(6), 0, dp(6));

        root.addView(title,       full);
        root.addView(statusText,  full);
        root.addView(nameEdit,    full);
        root.addView(strengthEdit, full);
        root.addView(note,        full);
        root.addView(registerBtn, full);
        root.addView(backBtn,     full);
        setContentView(root);
    }

    private EditText editField(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(0xFF607080);
        et.setTextColor(Color.WHITE);
        et.setTextSize(16f);
        return et;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
