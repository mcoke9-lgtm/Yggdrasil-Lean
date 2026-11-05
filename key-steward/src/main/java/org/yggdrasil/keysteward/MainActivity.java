package org.yggdrasil.keysteward;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Local-only signing-key steward. No network permission, analytics, logging or key upload.
 * Private bytes exist only in memory until the user exports them through Android's document picker.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_SAVE = 11;
    private static final int REQUEST_OPEN = 12;
    private EditText password;
    private EditText confirmation;
    private TextView status;
    private Button copyButton;
    private Button clearClipboardButton;
    private byte[] pendingBytes;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (pendingBytes != null) copyButton.setEnabled(true);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipDescription currentClip = clipboard.getPrimaryClipDescription();
        if (currentClip != null && "BUILDER_KEYSTORE_BASE64".contentEquals(currentClip.getLabel())) {
            clearClipboardButton.setEnabled(true);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Builder Key Steward");
        title.setTextSize(24);
        root.addView(title);

        TextView warning = new TextView(this);
        warning.setText("OFFLINE KEY GENERATION\nThis utility has no Internet permission. Your private signing key is generated on this phone and is never uploaded. Save the .p12 file in a secure local location and keep a separate offline backup. Remember the password: this app does not store it. Never share the file or its Base64 value.");
        warning.setTextSize(15);
        warning.setPadding(0, dp(12), 0, dp(18));
        root.addView(warning);

        TextView alias = new TextView(this);
        alias.setText("Signing alias: " + KeystoreMaterial.ALIAS
                + "\nFormat: PKCS#12 · RSA 3072 · SHA256withRSA · 25-year certificate");
        root.addView(alias);

        password = new EditText(this);
        password.setSingleLine(true);
        password.setHint("Strong keystore password (16+ characters)");
        password.setInputType(129);
        root.addView(password);

        confirmation = new EditText(this);
        confirmation.setSingleLine(true);
        confirmation.setHint("Confirm password");
        confirmation.setInputType(129);
        root.addView(confirmation);

        Button generate = new Button(this);
        generate.setText("Generate permanent signing key");
        generate.setOnClickListener(v -> generateAndSave());
        root.addView(generate);

        Button open = new Button(this);
        open.setText("Load and verify an existing .p12 backup");
        open.setOnClickListener(v -> openExisting());
        root.addView(open);

        copyButton = new Button(this);
        copyButton.setText("Copy Base64 for GitHub secret");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copyBase64());
        root.addView(copyButton);

        clearClipboardButton = new Button(this);
        clearClipboardButton.setText("Clear clipboard after saving the GitHub secret");
        clearClipboardButton.setEnabled(false);
        clearClipboardButton.setOnClickListener(v -> clearClipboard());
        root.addView(clearClipboardButton);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, dp(16), 0, dp(8));
        status.setText("No key loaded. Generate a new key or load an existing backup.");
        root.addView(status);
        setContentView(scroll);
    }

    private void generateAndSave() {
        String first = password.getText().toString();
        String second = confirmation.getText().toString();
        if (first.length() < 16 || !first.equals(second)) {
            status.setText("Use a password of at least 16 characters and confirm it exactly.");
            return;
        }
        try {
            char[] secret = first.toCharArray();
            KeystoreMaterial.Generated generated = KeystoreMaterial.generate(secret);
            Arrays.fill(secret, '\0');
            pendingBytes = generated.keystoreBytes;
            password.setText("");
            confirmation.setText("");
            status.setText("Key generated and reloaded successfully. Remember this password: enter the same value for GitHub secrets BUILDER_KEYSTORE_PASSWORD and BUILDER_KEY_PASSWORD.\nCertificate SHA-256:\n"
                    + generated.certificateSha256
                    + "\n\nChoose a secure local destination for the .p12 backup.");
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/x-pkcs12");
            intent.putExtra(Intent.EXTRA_TITLE, "yggdrasil-builder-signing.p12");
            startActivityForResult(intent, REQUEST_SAVE);
        } catch (Exception failure) {
            status.setText("Key generation failed. No key was exported. Check device support and try again.");
            clearPending();
        }
    }

    private void openExisting() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OPEN);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_SAVE) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null || pendingBytes == null) throw new IllegalStateException();
                out.write(pendingBytes);
                out.flush();
                copyButton.setEnabled(true);
                status.setText(status.getText() + "\nBackup exported. Keep this file private. "
                        + "Next, copy its Base64 into GitHub's BUILDER_KEYSTORE_BASE64 secret. Then enter the same password for both password secrets.");
            } catch (Exception failure) {
                status.setText("Could not write the backup. The generated key remains in memory; choose another destination or try again.");
            }
        } else if (requestCode == REQUEST_OPEN) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 1024 * 1024) {
                        throw new IllegalArgumentException("Keystore file is unexpectedly large.");
                    }
                    out.write(buffer, 0, count);
                }
                byte[] candidate = out.toByteArray();
                char[] secret = password.getText().toString().toCharArray();
                String fingerprint = KeystoreMaterial.inspect(candidate, secret);
                Arrays.fill(secret, '\0');
                clearPending();
                pendingBytes = candidate;
                copyButton.setEnabled(true);
                status.setText("Existing backup verified. Alias: " + KeystoreMaterial.ALIAS
                        + "\nCertificate SHA-256:\n" + fingerprint
                        + "\nCopy Base64 only when ready to add the GitHub secret.");
            } catch (Exception failure) {
                status.setText("Could not verify this backup. Check the password and file; no key was loaded.");
                clearPending();
            }
        }
    }

    private void copyBase64() {
        if (pendingBytes == null) return;
        String encoded = Base64.encodeToString(pendingBytes, Base64.NO_WRAP);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("BUILDER_KEYSTORE_BASE64", encoded);
        Bundle extras = new Bundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        clearPending();
        copyButton.setEnabled(false);
        clearClipboardButton.setEnabled(true);
        status.setText("Base64 copied to this phone's clipboard. Paste it directly into GitHub secret BUILDER_KEYSTORE_BASE64. Do not paste it into chat or a public location. Then return here and clear the clipboard.");
    }

    private void clearClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
        clearClipboardButton.setEnabled(false);
        status.setText("Clipboard cleared. The exported keystore backup remains in the location you selected.");
    }

    private void clearPending() {
        if (pendingBytes != null) Arrays.fill(pendingBytes, (byte) 0);
        pendingBytes = null;
        if (copyButton != null) copyButton.setEnabled(false);
    }

    @Override protected void onDestroy() {
        clearPending();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
