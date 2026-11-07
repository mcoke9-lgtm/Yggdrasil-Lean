import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { open, readFile } from "node:fs/promises";
import { createServer as httpServer } from "node:http";
const file=process.env.CHECKPOINT_FILE;
const port=Number(process.env.CHECKPOINT_PORT||8792);
if(!file) throw new Error("CHECKPOINT_FILE is required");
const hash=value=>createHash("sha256").update(value).digest("hex");
const canonical=value=>JSON.stringify(value);
const heads=new Map();
let priorRecordHash="0".repeat(64);
let queue=Promise.resolve();
async function load(){
 let raw="";try{raw=await readFile(file,"utf8");}catch(e){if(e.code!=="ENOENT")throw e;}
 for(const line of raw.split("\n").filter(Boolean)){
  const rec=JSON.parse(line);const {record_hash,...body}=rec;
  if(hash(canonical(body))!==record_hash||body.previous_record_hash!==priorRecordHash)throw new Error("CHECKPOINT_JOURNAL_INTEGRITY_FAILURE");
  const old=heads.get(body.task_id);
  const cp=body.checkpoint;
  if(old){
   if(body.anchor_sequence!==old.anchor_sequence+1||cp.sequence!==old.checkpoint.sequence+1||
      cp.latest_event_hash!==body.previous_event_hash||cp.task_fingerprint!==old.checkpoint.task_fingerprint||
      cp.generation<old.checkpoint.generation||cp.generation>old.checkpoint.generation+1)
    throw new Error("CHECKPOINT_JOURNAL_MONOTONICITY_FAILURE");
  } else if(body.anchor_sequence!==1||cp.sequence!==1||cp.previous_event_hash!==null) throw new Error("CHECKPOINT_JOURNAL_INITIAL_STATE_FAILURE");
  heads.set(body.task_id,body);priorRecordHash=record_hash;
 }
}
async function append(body){
 const old=heads.get(body.task_id);const current=old?old.anchor_sequence:0;
 if(body.expected_anchor_sequence!==current) return {status:409,error:"STALE_ANCHOR_VERSION",current_anchor_sequence:current};
 const cp=body.checkpoint;
 if(!cp||cp.task_id!==body.task_id||!Number.isInteger(cp.sequence)||!Number.isInteger(cp.generation))return {status:400,error:"INVALID_CHECKPOINT"};
 if(old){
  if(cp.sequence!==old.checkpoint.sequence+1||body.previous_event_hash!==old.checkpoint.latest_event_hash||
    cp.task_fingerprint!==old.checkpoint.task_fingerprint||cp.generation<old.checkpoint.generation||
    cp.generation>old.checkpoint.generation+1)return {status:409,error:"CHECKPOINT_NOT_MONOTONIC"};
 }else if(cp.sequence!==1||body.previous_event_hash!==null)return {status:409,error:"INVALID_INITIAL_CHECKPOINT"};
 const recordBody={task_id:body.task_id,anchor_sequence:current+1,previous_record_hash:priorRecordHash,
  previous_event_hash:body.previous_event_hash??null,checkpoint:cp,accepted_at:new Date().toISOString()};
 const record={...recordBody,record_hash:hash(canonical(recordBody))};
 const handle=await open(file,"a",0o600);
 try{await handle.write(canonical(record)+"\n");await handle.sync();}finally{await handle.close();}
 heads.set(body.task_id,record);priorRecordHash=record.record_hash;
 return {status:201,receipt:{task_id:body.task_id,anchor_sequence:record.anchor_sequence,record_hash:record.record_hash,checkpoint:cp}};
}
await load();
const server=httpServer(async(req,res)=>{
 const send=(status,obj)=>{res.writeHead(status,{"content-type":"application/json","cache-control":"no-store"});res.end(JSON.stringify(obj));};
 try{
  const url=new URL(req.url,"http://127.0.0.1");
  if(req.method==="GET"&&url.pathname==="/health")return send(200,{ok:true});
  if(req.method==="GET"&&url.pathname==="/v1/checkpoint"){
   const rec=heads.get(url.searchParams.get("task_id"));return rec?send(200,{anchor_sequence:rec.anchor_sequence,checkpoint:rec.checkpoint,record_hash:rec.record_hash}):send(404,{error:"NOT_FOUND"});
  }
  if(req.method==="POST"&&url.pathname==="/v1/append"){
   let raw="";for await(const chunk of req)raw+=chunk;
   let body;try{body=JSON.parse(raw);}catch{return send(400,{error:"INVALID_JSON"});}
   let result;const work=queue.then(()=>append(body));queue=work.catch(()=>{});result=await work;return send(result.status,result);
  }
  return send(404,{error:"NOT_FOUND"});
 }catch(e){return send(500,{error:"CHECKPOINT_FAILURE",message:String(e.message||e)});}
});
server.listen(port,"127.0.0.1");
