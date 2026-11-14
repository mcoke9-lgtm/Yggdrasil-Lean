package org.yggdrasil.memorygate;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView status = new TextView(this);
        status.setText("Memory Gate prototype\n\nAccess is closed.\nNo memory source or client interface is connected.");
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(32, 32, 32, 32);

        setContentView(status);
    }
}
