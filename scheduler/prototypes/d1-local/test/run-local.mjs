import { spawn, spawnSync } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
const port=8791, base="http://127.0.0.1:"+port, token="synthetic-local-test-token";
const wrangler="node_modules/wrangler/bin/wrangler.js";
const migration=spawnSync(process.execPath,[wrangler,"d1","migrations","apply","sch-001-disposable-local","--local","--yes"],{encoding:"utf8"});
if(migration.status!==0) throw new Error("Local D1 migration failed:\n"+migration.stdout+"\n"+migration.stderr);
const child=spawn(process.execPath,[wrangler,"dev","--local","--ip","127.0.0.1","--port",String(port),"--var","PROTOTYPE_MODE:local-test"],{env:{...process.env,PROTOTYPE_TOKEN:token},stdio:["ignore","pipe","pipe"]});
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
let failures=0;
try {await ready();for(const [name,fn] of tests){try{await fn();console.log("PASS "+name);}catch(e){failures++;console.error("FAIL "+name,e);}}console.log("RESULT "+(tests.length-failures)+"/"+tests.length+" passed");if(failures)process.exitCode=1;}
catch(e){console.error(e,logs);process.exitCode=1;}
finally{child.kill("SIGTERM");}
