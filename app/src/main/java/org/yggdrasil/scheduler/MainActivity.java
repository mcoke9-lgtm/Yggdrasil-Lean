package org.yggdrasil.scheduler;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.app.ActivityCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private SchedulerDb db;
    private LinearLayout root, taskList, history;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); db = new SchedulerDb(this); scheduleWorker(); buildUi(); refresh();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
    }
    @Override protected void onResume() { super.onResume(); if (db != null && taskList != null) refresh(); }
    private void scheduleWorker() {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SchedulerWorker.class, 15, TimeUnit.MINUTES).setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("yggdrasil-scheduler-poll", ExistingPeriodicWorkPolicy.KEEP, request);
    }
    private TextView text(String value, int size) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setPadding(0,8,0,8); return v; }
    private Button button(String label, Runnable action) { Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); return b; }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,24,24,32); scroll.addView(root); setContentView(scroll);
        root.addView(text("Yggdrasil Scheduler", 26));
        root.addView(text("Local-first Android host · background checks are periodic, not exact-time guarantees", 14));
        status = text("Persistent task store ready", 14); root.addView(status);
        root.addView(button("Add GitHub Actions monitor", this::showAddDialog));
        root.addView(text("Scheduled tasks", 20)); taskList = new LinearLayout(this); taskList.setOrientation(LinearLayout.VERTICAL); root.addView(taskList);
        root.addView(text("Recent activity", 20)); history = new LinearLayout(this); history.setOrientation(LinearLayout.VERTICAL); root.addView(history);
        root.addView(button("Refresh status", this::refresh));
        root.addView(button("Check now", () -> { Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(); androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(SchedulerWorker.class).setConstraints(c).build(); WorkManager.getInstance(this).enqueue(request); status.setText("Manual check queued; refresh in a moment"); }));
    }
    private EditText field(LinearLayout layout, String hint) { EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint); layout.addView(e); return e; }
    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(24,8,24,8);
        EditText repo = field(form,"GitHub repository: owner/name"); EditText branch = field(form,"Branch name"); EditText workflow = field(form,"Exact workflow name"); EditText name = field(form,"Monitor name");
        EditText time = field(form,"Local time HH:MM (24-hour)"); time.setText("09:00");
        new AlertDialog.Builder(this).setTitle("New daily GitHub monitor").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(dialog,which)->{
            try {
                String r=repo.getText().toString().trim(), b=branch.getText().toString().trim(), w=workflow.getText().toString().trim(), n=name.getText().toString().trim();
                if (!r.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || b.isEmpty() || w.isEmpty() || n.isEmpty()) throw new IllegalArgumentException("Fill every field and use owner/name repository format");
                String[] parts=time.getText().toString().split(":"); if(parts.length!=2) throw new IllegalArgumentException("Use HH:MM time"); int h=Integer.parseInt(parts[0]), m=Integer.parseInt(parts[1]); if(h<0||h>23||m<0||m>59) throw new IllegalArgumentException("Time must be between 00:00 and 23:59");
                String id="gh-"+UUID.randomUUID().toString().substring(0,8); db.addTask(id,n,r,b,w,h,m,ZoneId.systemDefault().getId()); status.setText("Saved locally · timezone " + ZoneId.systemDefault().getId()); refresh();
            } catch(Exception e) { new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("OK",null).show(); }
        }).show();
    }
    private void refresh() {
        taskList.removeAllViews();
        for(SchedulerDb.Task t:db.tasks()) {
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(0,8,0,12);
            card.addView(text(t.name + (t.enabled ? " · ACTIVE" : " · PAUSED"),18));
            card.addView(text(t.repo + " / " + t.branch + " / " + t.workflow,13));
            card.addView(text(String.format(java.util.Locale.US,"Daily %02d:%02d · %s · %s",t.hour,t.minute,t.zone,t.lastStatus),13));
            LinearLayout actions=new LinearLayout(this); actions.addView(button(t.enabled?"Pause":"Resume",()->{db.setEnabled(t.id,!t.enabled);refresh();})); actions.addView(button("Delete",()->new AlertDialog.Builder(this).setMessage("Delete this monitor?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{db.delete(t.id);refresh();}).show())); card.addView(actions); taskList.addView(card);
        }
        history.removeAllViews(); for(String item:db.recentRuns(25)) history.addView(text(item,12)); if(db.recentRuns(1).isEmpty()) history.addView(text("No activity yet",13));
    }
}
