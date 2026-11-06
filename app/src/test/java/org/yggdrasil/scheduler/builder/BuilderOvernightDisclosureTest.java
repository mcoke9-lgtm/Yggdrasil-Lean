package org.yggdrasil.scheduler.builder;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
public class BuilderOvernightDisclosureTest {
 @Test public void approvalFingerprintBindsAllNineRoutesAndExactPrompt() {
  List<BuilderDisclosureFingerprint.Route> routes=new ArrayList<>();
  for(BuilderRole role:BuilderRole.values()) {
   String id=role.name().toLowerCase();
   routes.add(new BuilderDisclosureFingerprint.Route(role.name(),id,
     "https://"+id+".example/v1","model-"+id));
  }
  String first=BuilderDisclosureFingerprint.digest(
    BuilderDisclosureFingerprint.createOvernight(routes,"research task"));
  String same=BuilderDisclosureFingerprint.digest(
    BuilderDisclosureFingerprint.createOvernight(routes,"research task"));
  String changedPrompt=BuilderDisclosureFingerprint.digest(
    BuilderDisclosureFingerprint.createOvernight(routes,"different task"));
  assertEquals(first,same);
  assertNotEquals(first,changedPrompt);
  assertEquals(9,routes.size());
 }
}