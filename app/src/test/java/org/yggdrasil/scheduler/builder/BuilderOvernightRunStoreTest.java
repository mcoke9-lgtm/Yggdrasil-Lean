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
 @Test public void cancellationCannotBeOverwrittenByLateCycleCompletion() {
  Context c=RuntimeEnvironment.getApplication();
  c.getSharedPreferences("builder_overnight_runs",Context.MODE_PRIVATE).edit().clear().commit();
  BuilderOvernightRunStore s=new BuilderOvernightRunStore(c);
  long t=1800000000000L;
  s.beginOrRecover("r3","t3","task",t);
  s.beginCycle("r3",t+1000);
  s.setStatus("r3","CANCELLED","user stopped",t+1500);
  try {
   s.finishCycle("r3","late response",t+2000);
   fail("Cancelled cycle must not be committed");
  } catch (IllegalStateException expected) { }
  assertEquals("CANCELLED",s.read("r3").optString("status"));
 }
 @Test public void terminalRunStatusCannotBeReplacedByCompletion() {
  Context c=RuntimeEnvironment.getApplication();
  c.getSharedPreferences("builder_overnight_runs",Context.MODE_PRIVATE).edit().clear().commit();
  BuilderOvernightRunStore s=new BuilderOvernightRunStore(c);
  long t=1800000000000L;
  s.beginOrRecover("r4","t4","task",t);
  s.setStatus("r4","CANCELLED","user stopped",t+1000);
  try {
   s.setStatus("r4","COMPLETED","late worker",t+2000);
   fail("Terminal state must be immutable");
  } catch (IllegalStateException expected) { }
  assertEquals("CANCELLED",s.read("r4").optString("status"));
 }

 @Test public void expiredRecoveryDoesNotRewriteCancelledRun() {
  Context c=RuntimeEnvironment.getApplication();
  c.getSharedPreferences("builder_overnight_runs",Context.MODE_PRIVATE).edit().clear().commit();
  BuilderOvernightRunStore s=new BuilderOvernightRunStore(c);
  long t=1800000000000L;
  s.beginOrRecover("r5","t5","task",t);
  s.setStatus("r5","CANCELLED","user stopped",t+1000);
  org.json.JSONObject state=s.beginOrRecover("r5","t5","task",
    t+BuilderOvernightRunStore.MAX_DURATION_MILLIS+1000);
  assertEquals("CANCELLED",state.optString("status"));
 }

}