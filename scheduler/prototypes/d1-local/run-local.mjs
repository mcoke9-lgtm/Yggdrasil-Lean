import { spawn, spawnSync } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
const port=8791, base="http://127.0.0.1:"+port, token="synthetic-local-test-token";
const wrangler="node_modules/wrangler/bin/wrangler.js";
const migration=spawnSync(process.execPath,[wrangler,"d1","migrations","apply","sch-001-disposable-local","--local"],{encoding:"utf8"});
if(migration.status!==0) throw new Error("Local D1 migration failed:\n"+migration.stdout+"\n"+migration.stderr);
const child=spawn(process.execPath,[wrangler,"dev","--local","--ip","127.0.0.1","--port",String(port),"--var","PROTOTYPE_MODE:local-test","--var","PROTOTYPE_TOKEN:"+token],{env:{...process.env,PROTOTYPE_TOKEN:token},stdio:["ignore","pipe","pipe"]});
let logs=""; child.stdout.on("data",b=>logs+=b); child.stderr.on("data",b=>logs+=b);
const headers={authorization:"Bearer "+token,"content-type":"application/json"};
const call=(path,options={})=>fetch(base+path,{...options,headers:{...headers,...(options.headers||{})}});
async function ready(){for(let i=0;i<120;i++){if(child.exitCode!==null)throw Error("Wrangler exited: "+logs);try{if((await fetch(base+"/health")).ok)return;}catch{}await delay(250);}throw Error("Wrangler readiness timeout: "+logs);}
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
tests.push(["D1L-14 consistent database-and-head rollback is detected by an external runner checkpoint",async()=>{
 const t="t14";await seed(t);
 const a=event("e14a",t);assert.equal((await append(a)).status,201);
 const b=event("e14b",t,{sequence:2,expected_previous_sequence:1,previous_event_hash:a.event_hash});
 assert.equal((await append(b)).status,201);
 const before=await state(t);
 // This checkpoint lives in the Node test-runner process, not in the Worker or D1 database.
 const checkpoint={task_id:t,sequence:before.head.sequence,generation:before.head.generation,
  latest_event_id:before.head.latest_event_id,latest_event_hash:before.head.latest_event_hash,
  task_fingerprint:before.head.task_fingerprint,event_count:before.events};
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
let failures=0;
try {await ready();for(const [name,fn] of tests){try{await fn();console.log("PASS "+name);}catch(e){failures++;console.error("FAIL "+name,e);}}console.log("RESULT "+(tests.length-failures)+"/"+tests.length+" passed");if(failures)process.exitCode=1;}
catch(e){console.error(e,logs);process.exitCode=1;}
finally{child.kill("SIGTERM");}
