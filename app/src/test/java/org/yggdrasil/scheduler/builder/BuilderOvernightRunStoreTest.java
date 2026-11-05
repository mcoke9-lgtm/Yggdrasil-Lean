package org.yggdrasil.scheduler.builder;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import android.content.Context;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BuilderOvernightRunStoreTest {
 @Test public void recordsEightHourWindowAndCompletedCycle() {
  Context c=RuntimeEnvironment.getApplication();
  c.getSharedPreferences("builder_overnight_runs",Context.MODE_PRIVATE).edit().clear().commit();
  BuilderOvernightRunStore s=new BuilderOvernightRunStore(c);
  long t=1800000000000L;
  org.json.JSONObject a=s.beginOrRecover("r1","t1","task",t);
  assertEquals(t+BuilderOvernightRunStore.MAX_DURATION_MILLIS,a.optLong("deadline_epoch_millis"));
  s.beginCycle("r1",t+1000);
  org.json.JSONObject b=s.finishCycle("r1","nine roles",t+2000);
  assertEquals(1,b.optInt("completed_cycles"));
  assertFalse(b.optBoolean("cycle_in_progress"));
 }
 @Test public void refusesReplayAfterInterruptedCycle() {
  Context c=RuntimeEnvironment.getApplication();
  c.getSharedPreferences("builder_overnight_runs",Context.MODE_PRIVATE).edit().clear().commit();
  BuilderOvernightRunStore s=new BuilderOvernightRunStore(c);
  long t=1800000000000L;
  s.beginOrRecover("r2","t2","task",t);
  s.beginCycle("r2",t+1000);
  org.json.JSONObject b=s.beginOrRecover("r2","t2","task",t+2000);
  assertEquals("INTERRUPTED",b.optString("status"));
 }
}