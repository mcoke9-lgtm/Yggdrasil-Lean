package org.yggdrasil.keysteward;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ClipDescription;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.text.DateFormat;

public final class MainActivity extends Activity {
    private static final int CREATE_FILE = 41;
    private static final int OPEN_FILE = 42;
    private static final int EXPORT_BASE64 = 43;
    private static final int MAX_GITHUB_SECRET_CHARS = 48 * 1024;

    private EditText passwordField;
    private EditText confirmField;
    private TextView status;
    private boolean creating;
    private boolean exporting;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 32, 28, 24);

        TextView heading = new TextView(this);
        heading.setText("Builder Key Steward");
        heading.setTextSize(24);
        root.addView(heading, matchWrap());

        TextView explanation = new TextView(this);
        explanation.setText("Create and verify an encrypted APK signing keystore. "
                + "This app works locally and does not upload keys.");
        explanation.setTextSize(16);
        explanation.setPadding(0, 16, 0, 20);
        root.addView(explanation, matchWrap());

        passwordField = new EditText(this);
        passwordField.setSingleLine(true);
        passwordField.setHint("Keystore password (12+ characters)");
        passwordField.setInputType(129);
        passwordField.setSaveEnabled(false);
        passwordField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(passwordField, matchWrap());

        confirmField = new EditText(this);
        confirmField.setSingleLine(true);
        confirmField.setHint("Confirm password for new keystore");
        confirmField.setInputType(129);
        confirmField.setSaveEnabled(false);
        confirmField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(confirmField, matchWrap());

        Button create = new Button(this);
        create.setText("Create encrypted PKCS#12 keystore");
        create.setOnClickListener(view -> beginCreate());
        root.addView(create, matchWrap());

        Button verify = new Button(this);
        verify.setText("Verify an existing keystore");
        verify.setOnClickListener(view -> beginVerify());
        root.addView(verify, matchWrap());

        Button export = new Button(this);
        export.setText("Copy verified Base64 for GitHub secret");
        export.setOnClickListener(view -> beginBase64Export());
        root.addView(export, matchWrap());

        TextView warning = new TextView(this);
        warning.setText("Sensitive action: this copies the encrypted keystore to your clipboard. "
                + "Only paste it into the BUILDER_KEYSTORE_BASE64 GitHub Actions secret. "
                + "The app schedules a clipboard clear after 60 seconds; if the app is stopped or killed first, clear manually.");
        warning.setTextSize(13);
        warning.setPadding(0, 12, 0, 0);
        root.addView(warning, matchWrap());

        status = new TextView(this);
        status.setText("No key has been created. Test with a disposable key first.");
        status.setTextSize(14);
        status.setPadding(0, 20, 0, 0);
        root.addView(status, matchWrap());

        setContentView(root);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private boolean hasValidPassword() {
        if (passwordField.getText().length() < 12) {
            status.setText("Use a password of at least 12 characters.");
            return false;
        }
        return true;
    }

    private char[] readPassword() {
        int length = passwordField.getText().length();
        char[] value = new char[length];
        passwordField.getText().getChars(0, length, value, 0);
        return value;
    }

    private void beginCreate() {
        if (!hasValidPassword()) return;
        if (!TextUtils.equals(passwordField.getText(), confirmField.getText())) {
            status.setText("The password entries do not match.");
            return;
        }
        creating = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-pkcs12");
        intent.putExtra(Intent.EXTRA_TITLE, "builder-upload-key.p12");
        startActivityForResult(intent, CREATE_FILE);
    }

    private void beginVerify() {
        if (!hasValidPassword()) return;
        creating = false;
        exporting = false;
        openKeystorePicker(OPEN_FILE);
    }

    private void beginBase64Export() {
        if (!hasValidPassword()) return;
        creating = false;
        exporting = true;
        status.setText("Select the encrypted PKCS#12 file. Its password and key will be verified before copying.");
        openKeystorePicker(EXPORT_BASE64);
    }

    private void openKeystorePicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        char[] password = readPassword();
        if (password == null) return;
        try {
            KeyStoreEngine.Verification result;
            if (requestCode == CREATE_FILE && creating) {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    result = KeyStoreEngine.create(output, password);
                }
                KeyStoreEngine.Verification reopened;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    reopened = KeyStoreEngine.verify(input, password);
                }
                if (!result.fingerprint.equals(reopened.fingerprint)) {
                    throw new IllegalStateException("Saved keystore fingerprint did not match");
                }
                status.setText(formatResult("Keystore created, reopened, and verified.",
                        reopened));
            } else if ((requestCode == OPEN_FILE || requestCode == EXPORT_BASE64) && !creating) {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    result = KeyStoreEngine.verify(input, password);
                }
                if (requestCode == EXPORT_BASE64 && exporting) {
                    copyVerifiedKeystore(uri, password, result);
                } else {
                    status.setText(formatResult("Keystore verified.", result));
                }
            }
        } catch (Exception error) {
            status.setText("Operation failed safely: " + safeMessage(error));
        } finally {
            java.util.Arrays.fill(password, '\u0000');
            passwordField.setText("");
            confirmField.setText("");
        }
    }

    private void copyVerifiedKeystore(Uri uri, char[] password,
                                      KeyStoreEngine.Verification verification) throws Exception {
        byte[] bytes;
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("Unable to open selected file");
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 36 * 1024) {
                    throw new IllegalArgumentException("Keystore is too large for a GitHub secret");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        byte[] encodedBytes = Base64.encode(bytes, Base64.NO_WRAP);
        java.util.Arrays.fill(bytes, (byte) 0);
        if (encodedBytes.length > MAX_GITHUB_SECRET_CHARS) {
            java.util.Arrays.fill(encodedBytes, (byte) 0);
            throw new IllegalArgumentException("Base64 value exceeds GitHub's 48 KB secret limit");
        }

        String encoded = new String(encodedBytes, java.nio.charset.StandardCharsets.US_ASCII);
        java.util.Arrays.fill(encodedBytes, (byte) 0);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("BUILDER_KEYSTORE_BASE64", encoded);
        if (Build.VERSION.SDK_INT >= 33) {
            clip.getDescription().getExtras().putBoolean(
                    ClipDescription.EXTRA_IS_SENSITIVE, true);
        }
        clipboard.setPrimaryClip(clip);
        final String label = "BUILDER_KEYSTORE_BASE64";
        handler.postDelayed(() -> {
            if (clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClipDescription() != null
                    && label.contentEquals(clipboard.getPrimaryClipDescription().getLabel())) {
                clipboard.clearPrimaryClip();
            }
        }, 60_000L);
        status.setText("Verified keystore copied to clipboard. Fingerprint: "
                + verification.fingerprint
                + "\nPaste only into GitHub Actions secret BUILDER_KEYSTORE_BASE64. "
                + "Clipboard clears in 60 seconds unless replaced.");
    }

    private String formatResult(String heading, KeyStoreEngine.Verification result) {
        DateFormat dates = DateFormat.getDateInstance(DateFormat.MEDIUM);
        return heading + "\n\nAlias: " + KeyStoreEngine.ALIAS
                + "\nAlgorithm: " + result.algorithm
                + "\nSubject: " + result.subject
                + "\nValid: " + dates.format(result.notBefore) + " to "
                + dates.format(result.notAfter)
                + "\nCertificate SHA-256: " + result.fingerprint;
    }

    private String safeMessage(Exception error) {
        return "Could not complete the operation. Check the password and selected file, then try again.";
    }
}
