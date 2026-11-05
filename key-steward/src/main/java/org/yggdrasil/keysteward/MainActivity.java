package org.yggdrasil.keysteward;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;

public final class MainActivity extends Activity {
    private static final int CREATE_FILE = 41;
    private static final int OPEN_FILE = 42;

    private EditText passwordField;
    private TextView status;
    private boolean creating;

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
        root.addView(passwordField, matchWrap());

        Button create = new Button(this);
        create.setText("Create encrypted PKCS#12 keystore");
        create.setOnClickListener(view -> beginCreate());
        root.addView(create, matchWrap());

        Button verify = new Button(this);
        verify.setText("Verify an existing keystore");
        verify.setOnClickListener(view -> beginVerify());
        root.addView(verify, matchWrap());

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

    private char[] readPassword() {
        String value = passwordField.getText().toString();
        if (value.length() < 12) {
            status.setText("Use a password of at least 12 characters.");
            return null;
        }
        return value.toCharArray();
    }

    private void beginCreate() {
        char[] password = readPassword();
        if (password == null) return;
        creating = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-pkcs12");
        intent.putExtra(Intent.EXTRA_TITLE, "builder-upload-key.p12");
        startActivityForResult(intent, CREATE_FILE);
    }

    private void beginVerify() {
        if (readPassword() == null) return;
        creating = false;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, OPEN_FILE);
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
                status.setText(formatResult("Keystore created and written. Re-open it to verify.",
                        result));
            } else if (requestCode == OPEN_FILE && !creating) {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    result = KeyStoreEngine.verify(input, password);
                }
                status.setText(formatResult("Keystore verified.", result));
            }
        } catch (Exception error) {
            status.setText("Operation failed safely: " + safeMessage(error));
        } finally {
            java.util.Arrays.fill(password, '\u0000');
            passwordField.setText("");
        }
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
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message;
    }
}
