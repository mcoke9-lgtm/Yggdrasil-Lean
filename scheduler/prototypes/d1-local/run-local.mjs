import { spawn, spawnSync } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import assert from "node:assert/strict";
import { randomUUID, createHash } from "node:crypto";
import { mkdtemp, rm, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
const port=8791, base="http://127.0.0.1:"+port, token="synthetic-local-test-token";
const checkpointPort=8792, checkpointBase="http://127.0.0.1:"+checkpointPort;
const checkpointDir=await mkdtemp(join(tmpdir(),"sch001-checkpoint-"));
const checkpointFile=join(checkpointDir,"anchor.jsonl");
const wrangler="node_modules/wrangler/bin/wrangler.js";
const migration=spawnSync(process.execPath,[wrangler,"d1","migrations","apply","sch-001-disposable-local","--local"],{encoding:"utf8"});
if(migration.status!==0) throw new Error("Local D1 migration failed:\n"+migration.stdout+"\n"+migration.stderr);
let child, checkpointChild;
let logs="", checkpointLogs="";
let firstCheckpointSnapshot="";
let fullCheckpointSnapshot="";
let latestCheckpointReceipt=null;
const sha256=value=>createHash("sha256").update(value).digest("hex");
function startWorker(){
 child=spawn(process.execPath,[wrangler,"dev","--local","--ip","127.0.0.1","--port",String(port),"--var","PROTOTYPE_MODE:local-test","--var","PROTOTYPE_TOKEN:"+token],{env:{...process.env,PROTOTYPE_TOKEN:token},stdio:["ignore","pipe","pipe"]});
 child.stdout.on("data",b=>logs+=b);child.stderr.on("data",b=>logs+=b);
}
function startCheckpoint(){
 checkpointLogs="";
 checkpointChild=spawn(process.execPath,["checkpoint-service.mjs"],{cwd:process.cwd(),env:{...process.env,CHECKPOINT_FILE:checkpointFile,CHECKPOINT_PORT:String(checkpointPort)},stdio:["ignore","pipe","pipe"]});
 checkpointChild.stdout.on("data",b=>checkpointLogs+=b);checkpointChild.stderr.on("data",b=>checkpointLogs+=b);
}
async function stopProcess(proc){
 if(!proc||proc.exitCode!==null)return;
 await new Promise(resolve=>{proc.once("exit",resolve);proc.kill("SIGTERM");});
}
async function waitForExit(proc,timeoutMs=5000){
 if(proc.exitCode!==null)return;
 const outcome=await Promise.race([
  new Promise(resolve=>proc.once("exit",()=>resolve("EXIT"))),
  delay(timeoutMs).then(()=> "TIMEOUT")
 ]);
 if(outcome==="TIMEOUT")throw new Error("Process did not exit within "+timeoutMs+"ms");
}
startWorker();
const headers={authorization:"Bearer "+token,"content-type":"application/json"};
const call=(path,options={})=>fetch(base+path,{...options,headers:{...headers,...(options.headers||{})}});
const checkpointCall=(path,options={})=>fetch(checkpointBase+path,{...options,headers:{"content-type":"application/json",...(options.headers||{})}});
async function waitReady(url,proc,label,getLogs){for(let i=0;i<120;i++){if(proc.exitCode!==null)throw Error(label+" exited: "+getLogs());try{if((await fetch(url+"/health")).ok)return;}catch{}await delay(250);}throw Error(label+" readiness timeout: "+getLogs());}
async function ready(){return waitReady(base,child,"Wrangler",()=>logs);}
async function checkpointReady(){return waitReady(checkpointBase,checkpointChild,"Checkpoint service",()=>checkpointLogs);}
async function appendCheckpoint(taskId,expectedAnchorSequence,checkpoint,previousEventHash){
 const r=await checkpointCall("/v1/append",{method:"POST",body:JSON.stringify({task_id:taskId,expected_anchor_sequence:expectedAnchorSequence,checkpoint,previous_event_hash:previousEventHash})});
 return {status:r.status,body:await r.json()};
}
async function getCheckpoint(taskId){
 const r=await checkpointCall("/v1/checkpoint?task_id="+encodeURIComponent(taskId));
 return {status:r.status,body:await r.json()};
}
async function seed(id){const r=await call("/__test/seed",{method:"POST",body:JSON.stringify({task_id:id,task_fingerprint:"fp-"+id})});assert.equal(r.status,200,await r.text());}
async function state(id){const r=await call("/__test/state?task_id="+id);assert.equal(r.status,200);return r.json();}
function event(id,task,extra={}){return {event_id:id,task_id:task,sequence:1,generation:0,event_type:"REVIEW",task_fingerprint:"fp-"+task,expected_previous_sequence:0,expected_previous_generation:0,expected_previous_owner:"source",new_owner:"source",previous_event_hash:null,event_hash:"hash-"+id,authorization_principal:"synthetic-operator",authorization_decision_id:"decision-"+id,authorization_evidence_digest:"evidence-"+id,transfer_id:null,witness_timestamp:new Date().toISOString(),schema_version:1,receipt_id:"receipt-"+id,...extra};}
async function append(e){return call("/v1/events",{method:"POST",body:JSON.stringify(e)});}
const tests=[];
tests.push(["D1L-01 valid append/head advance",async()=>{const t="t1";await seed(t);assert.equal((await append(event("e1",t))).status,201);const s=await state(t);assert.equal(s.head.sequence,1);assert.equal(s.events,1);}]);
tests.push(["D1L-02 competing stale writers",async()=>{const t="t2";await seed(t);const rs=await Promise.all([append(event("e2a",t)),append(event("e2b",t))]);assert.equal(rs.filter(r=>r.status===201).length,1);assert.equal(rs.filter(r=>r.status===409).length,1);assert.equal((await state(t)).events,1);}]);
tests.push(["D1L-03 identical retry returns original receipt",async()=>{const t="t3";await seed(t);const e=event("e3",t);assert.equal((await append(e)).status,201);const r=await append(e);assert.equal(r.status,200);assert.equal((await r.json()).receipt_id,e.receipt_id);assert.equal((await state(t)).events,1);}]);
tests.push(["D1L-04 altered payload reusing ID rejected",async()=>{const t="t4";await seed(t);const e=event("e4",t);assert.equal((await append(e)).status,201);assert.equal((await append({...e,event_hash:"altered"})).status,409);assert.equal((await state(t)).events,1);}]);
tests.push(["D1L-05 stale expected sequence leaves state unchanged",async()=>{const t="t5";await seed(t);assert.equal((await append(event("e5",t,{expected_previous_sequence:7}))).status,409);const s=await state(t);assert.equal(s.head.sequence,0);assert.equal(s.events,0);}]);
tests.push(["D1L-06 missing authentication rejected",async()=>{const t="t6";await seed(t);const r=await fetch(base+"/v1/events",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(event("e6",t))});assert.equal(r.status,401);assert.equal((await state(t)).events,0);}]);
tests.push(["D1L-07 receipt query by original event ID",async()=>{const t="t7";await seed(t);const e=event("e7",t);assert.equal((await append(e)).status,201);const r=await call("/v1/events?event_id=e7");assert.equal(r.status,200);assert.equal((await r.json()).receipt_id,e.receipt_id);}]);
tests.push(["D1L-08 transfer increments generation and changes owner",async()=>{const t="t8";await seed(t);const e=event("e8",t,{event_type:"TRANSFER",generation:1,new_owner:"destination",transfer_id:"transfer-8"});assert.equal((await append(e)).status,201);const s=await state(t);assert.equal(s.head.generation,1);assert.equal(s.head.authority_owner,"destination");}]);
tests.push(["D1L-09 event UPDATE and DELETE are blocked",async()=>{const t="t9";await seed(t);const e=event("e9",t);assert.equal((await append(e)).status,201);const r=await call("/__test/mutation",{method:"POST",body:JSON.stringify({event_id:e.event_id})});const b=await r.json();assert.equal(b.updateBlocked,true);assert.equal(b.deleteBlocked,true);assert.equal((await state(t)).events,1); }]);
tests.push(["D1L-10 sequential events preserve hash-chain continuity",async()=>{const t="t10";await seed(t);const a=event("e10a",t);assert.equal((await append(a)).status,201);const b=event("e10b",t,{sequence:2,expected_previous_sequence:1,previous_event_hash:a.event_hash});assert.equal((await append(b)).status,201);const s=await state(t);assert.equal(s.head.sequence,2);assert.equal(s.head.latest_event_hash,b.event_hash);assert.equal(s.events,2); }]);
tests.push(["D1L-11 incorrect previous hash is rejected",async()=>{const t="t11";await seed(t);const a=event("e11a",t);assert.equal((await append(a)).status,201);const b=event("e11b",t,{sequence:2,expected_previous_sequence:1,previous_event_hash:"not-the-prior-hash"});assert.equal((await append(b)).status,409);const s=await state(t);assert.equal(s.head.sequence,1);assert.equal(s.events,1); }]);
tests.push(["D1L-12 failed multi-statement batch rolls back event and head",async()=>{const t="t12";await seed(t);const e=event("e12",t);const r=await call("/__test/rollback",{method:"POST",body:JSON.stringify(e)});assert.equal(r.status,200);assert.equal((await r.json()).rolledBack,true);const s=await state(t);assert.equal(s.head.sequence,0);assert.equal(s.events,0); }]);
tests.push(["D1L-13 simulated old-head restore is detected against in-memory checkpoint",async()=>{const t="t13";await seed(t);const e=event("e13",t);assert.equal((await append(e)).status,201);const r=await call("/__test/restore-detection",{method:"POST",body:JSON.stringify({task_id:t})});const b=await r.json();assert.equal(b.simulated,true);assert.equal(b.rollbackDetected,true);assert.equal(b.checkpoint.sequence,1);assert.equal(b.restored.sequence,0); }]);
tests.push(["D1L-14 monotonic external checkpoint detects coordinated database-and-head rollback",async()=>{
 const t="t14";await seed(t);
 const a=event("e14a",t);assert.equal((await append(a)).status,201);
 const afterA=await state(t);
 // The test runner holds this anchor outside the Worker and D1 process.
 const checkpointStore={current:{task_id:t,sequence:afterA.head.sequence,generation:afterA.head.generation,
  latest_event_id:afterA.head.latest_event_id,latest_event_hash:afterA.head.latest_event_hash,
  task_fingerprint:afterA.head.task_fingerprint,event_count:afterA.events}};
 assert.equal(checkpointStore.current.sequence,1);
 const b=event("e14b",t,{sequence:2,expected_previous_sequence:1,previous_event_hash:a.event_hash});
 assert.equal((await append(b)).status,201);
 const afterB=await state(t);
 const next={task_id:t,sequence:afterB.head.sequence,generation:afterB.head.generation,
  latest_event_id:afterB.head.latest_event_id,latest_event_hash:afterB.head.latest_event_hash,
  task_fingerprint:afterB.head.task_fingerprint,event_count:afterB.events};
 assert.equal(next.sequence,checkpointStore.current.sequence+1);
 assert.equal(b.previous_event_hash,checkpointStore.current.latest_event_hash);
 checkpointStore.current=next;
 assert.throws(()=>{if(1<=checkpointStore.current.sequence)throw new Error("CHECKPOINT_NOT_MONOTONIC");},/CHECKPOINT_NOT_MONOTONIC/);
 const checkpoint=checkpointStore.current;
 assert.equal(checkpoint.sequence,2);assert.equal(checkpoint.event_count,2);
 const restore=await call("/__test/restore-both",{method:"POST",body:JSON.stringify({task_id:t})});
 assert.equal(restore.status,200);assert.equal((await restore.json()).restored,true);
 const compare=await call("/__test/compare-checkpoint",{method:"POST",body:JSON.stringify(checkpoint)});
 assert.equal(compare.status,409);
 const result=await compare.json();
 assert.equal(result.status,"WITNESS_ROLLBACK_OR_LOSS");
 assert.equal(result.rollbackDetected,true);
 assert.equal(result.checkpoint.sequence,2);
 assert.equal(result.restored.sequence,0);
 assert.equal(result.restored.event_count,0);
 }]);
tests.push(["D1L-15 durable checkpoint survives witness and Worker process restarts and detects coordinated rollback",async()=>{
 const t="t15";startCheckpoint();await checkpointReady();await seed(t);
 const a=event("e15a",t);assert.equal((await append(a)).status,201);
 let snapshot=await state(t);
 const cp1={task_id:t,sequence:1,generation:snapshot.head.generation,latest_event_id:snapshot.head.latest_event_id,
  latest_event_hash:snapshot.head.latest_event_hash,task_fingerprint:snapshot.head.task_fingerprint,event_count:snapshot.events};
 const first=await appendCheckpoint(t,0,cp1,null);assert.equal(first.status,201);
 firstCheckpointSnapshot=await readFile(checkpointFile,"utf8");
 const b=event("e15b",t,{sequence:2,expected_previous_sequence:1,previous_event_hash:a.event_hash});
 assert.equal((await append(b)).status,201);
 snapshot=await state(t);
 const cp2={task_id:t,sequence:2,generation:snapshot.head.generation,latest_event_id:snapshot.head.latest_event_id,
  latest_event_hash:snapshot.head.latest_event_hash,task_fingerprint:snapshot.head.task_fingerprint,event_count:snapshot.events};
 const second=await appendCheckpoint(t,1,cp2,cp1.latest_event_hash);assert.equal(second.status,201);
 latestCheckpointReceipt=second.body.receipt;
 fullCheckpointSnapshot=await readFile(checkpointFile,"utf8");
 const stale=await appendCheckpoint(t,1,cp1,null);assert.equal(stale.status,409);
 // Stop and restart the independent checkpoint process; it must replay and verify its fsynced journal.
 await stopProcess(checkpointChild);startCheckpoint();await checkpointReady();
 const durable=await getCheckpoint(t);assert.equal(durable.status,200);assert.equal(durable.body.anchor_sequence,2);
 assert.equal(durable.body.checkpoint.latest_event_hash,cp2.latest_event_hash);
 // Restart the local Worker while preserving the same Wrangler D1 state directory.
 await stopProcess(child);startWorker();await ready();
 const before=await state(t);assert.equal(before.head.sequence,2);assert.equal(before.events,2);
 // Simulate privileged coordinated restore after both services have restarted.
 const restore=await call("/__test/restore-both",{method:"POST",body:JSON.stringify({task_id:t})});
 assert.equal(restore.status,200);
 const compare=await call("/__test/compare-checkpoint",{method:"POST",body:JSON.stringify(durable.body.checkpoint)});
 assert.equal(compare.status,409);
 const result=await compare.json();assert.equal(result.status,"WITNESS_ROLLBACK_OR_LOSS");
 assert.equal(result.checkpoint.sequence,2);assert.equal(result.restored.sequence,0);
 }]);
tests.push(["D1L-16 journal byte corruption fails closed on witness restart",async()=>{
 await stopProcess(checkpointChild);
 const original=fullCheckpointSnapshot;
 const corrupted=original.replace('"task_id":"t15"','"task_id":"x15"');
 assert.notEqual(corrupted,original);
 await writeFile(checkpointFile,corrupted,{mode:0o600});
 startCheckpoint();await waitForExit(checkpointChild);
 assert.ok(checkpointChild.exitCode!==null,"corrupted journal must prevent service startup");
 assert.match(checkpointLogs,/CHECKPOINT_JOURNAL_INTEGRITY_FAILURE|CHECKPOINT_JOURNAL_MONOTONICITY_FAILURE/);
}]);
tests.push(["D1L-17 truncated final record fails closed on witness restart",async()=>{
 await stopProcess(checkpointChild);
 const original=fullCheckpointSnapshot;
 const lines=original.trimEnd().split("\n");assert.equal(lines.length,2);
 await writeFile(checkpointFile,lines[0]+"\n"+lines[1].slice(0,Math.max(1,Math.floor(lines[1].length/2))),{mode:0o600});
 startCheckpoint();await waitForExit(checkpointChild);
 assert.ok(checkpointChild.exitCode!==null,"truncated JSONL record must prevent service startup");
 assert.match(checkpointLogs,/Unexpected end of JSON input|Unterminated string in JSON|CHECKPOINT_JOURNAL/);
}]);
tests.push(["D1L-18 valid older journal replacement is internally accepted but disagrees with retained receipt",async()=>{
 await stopProcess(checkpointChild);
 await writeFile(checkpointFile,firstCheckpointSnapshot,{mode:0o600});
 startCheckpoint();await checkpointReady();
 const replaced=await getCheckpoint("t15");
 assert.equal(replaced.status,200);
 assert.equal(replaced.body.checkpoint.sequence,1);
 assert.equal(replaced.body.anchor_sequence,1);
 assert.equal(latestCheckpointReceipt.checkpoint.sequence,2);
 assert.notEqual(replaced.body.checkpoint.latest_event_hash,latestCheckpointReceipt.checkpoint.latest_event_hash);
}]);
tests.push(["D1L-19 privileged full-chain rewrite with recomputed hashes passes local replay but conflicts with retained receipt",async()=>{
 await stopProcess(checkpointChild);
 const lines=(await readFile(checkpointFile,"utf8")).trimEnd().split("\n");
 assert.equal(lines.length,1,"the previous case deliberately installed the valid older snapshot");
 const first=JSON.parse(lines[0]);
 // Reconstruct a syntactically and cryptographically valid second record using a forged event hash.
 const forgedCheckpoint={...first.checkpoint,sequence:2,latest_event_id:"privileged-forged-event",latest_event_hash:"privileged-rewrite-forged-event-hash",event_count:2};
 const body={task_id:"t15",anchor_sequence:2,previous_record_hash:first.record_hash,
  previous_event_hash:first.checkpoint.latest_event_hash,checkpoint:forgedCheckpoint,accepted_at:new Date().toISOString()};
 const forged={...body,record_hash:sha256(JSON.stringify(body))};
 await writeFile(checkpointFile,lines[0]+"\n"+JSON.stringify(forged)+"\n",{mode:0o600});
 startCheckpoint();await checkpointReady();
 const rewritten=await getCheckpoint("t15");
 assert.equal(rewritten.status,200,"recomputed local hash chain should replay successfully");
 assert.equal(rewritten.body.checkpoint.sequence,2);
 assert.equal(rewritten.body.checkpoint.latest_event_hash,"privileged-rewrite-forged-event-hash");
 assert.equal(latestCheckpointReceipt.checkpoint.sequence,2);
 assert.notEqual(rewritten.body.checkpoint.latest_event_hash,latestCheckpointReceipt.checkpoint.latest_event_hash);
}]);
tests.push(["D1L-20 interleaved task checkpoints survive anchor restart and replay",async()=>{
 await stopProcess(checkpointChild);
 await writeFile(checkpointFile,"",{mode:0o600});
 startCheckpoint();await checkpointReady();
 const a1={task_id:"t20a",sequence:1,generation:0,latest_event_id:"a1",latest_event_hash:"hash-a1",task_fingerprint:"fp-a"};
 const b1={task_id:"t20b",sequence:1,generation:0,latest_event_id:"b1",latest_event_hash:"hash-b1",task_fingerprint:"fp-b"};
 const a2={task_id:"t20a",sequence:2,generation:0,latest_event_id:"a2",latest_event_hash:"hash-a2",task_fingerprint:"fp-a"};
 const b2={task_id:"t20b",sequence:2,generation:1,latest_event_id:"b2",latest_event_hash:"hash-b2",task_fingerprint:"fp-b"};
 assert.equal((await appendCheckpoint("t20a",0,a1,null)).status,201);
 assert.equal((await appendCheckpoint("t20b",0,b1,null)).status,201);
 assert.equal((await appendCheckpoint("t20a",1,a2,"hash-a1")).status,201);
 assert.equal((await appendCheckpoint("t20b",1,b2,"hash-b1")).status,201);
 await stopProcess(checkpointChild);startCheckpoint();await checkpointReady();
 const ar=await getCheckpoint("t20a"),br=await getCheckpoint("t20b");
 assert.equal(ar.status,200);assert.equal(br.status,200);
 assert.equal(ar.body.anchor_sequence,2);assert.equal(br.body.anchor_sequence,2);
 assert.equal(ar.body.checkpoint.latest_event_hash,"hash-a2");
 assert.equal(br.body.checkpoint.latest_event_hash,"hash-b2");
}]);
tests.push(["D1L-21 simultaneous competing anchor appends accept exactly one sequence",async()=>{
 await stopProcess(checkpointChild);
 await writeFile(checkpointFile,"",{mode:0o600});
 startCheckpoint();await checkpointReady();
 const left={task_id:"t21",sequence:1,generation:0,latest_event_id:"left",latest_event_hash:"hash-left",task_fingerprint:"fp-21"};
 const right={task_id:"t21",sequence:1,generation:0,latest_event_id:"right",latest_event_hash:"hash-right",task_fingerprint:"fp-21"};
 const results=await Promise.all([
  appendCheckpoint("t21",0,left,null),
  appendCheckpoint("t21",0,right,null)
 ]);
 assert.equal(results.filter(x=>x.status===201).length,1);
 assert.equal(results.filter(x=>x.status===409).length,1);
 const stored=await getCheckpoint("t21");
 assert.equal(stored.status,200);
 assert.equal(stored.body.anchor_sequence,1);
 assert.ok(["hash-left","hash-right"].includes(stored.body.checkpoint.latest_event_hash));
}]);
let failures=0;
try {await ready();for(const [name,fn] of tests){try{await fn();console.log("PASS "+name);}catch(e){failures++;console.error("FAIL "+name,e);}}console.log("RESULT "+(tests.length-failures)+"/"+tests.length+" passed");if(failures)process.exitCode=1;}
catch(e){console.error(e,logs);process.exitCode=1;}
finally{await stopProcess(child);await stopProcess(checkpointChild);await rm(checkpointDir,{recursive:true,force:true});}
