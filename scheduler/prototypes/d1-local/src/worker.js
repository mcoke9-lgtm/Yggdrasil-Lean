const FIELDS = ["event_id","task_id","sequence","generation","event_type","task_fingerprint","expected_previous_sequence","expected_previous_generation","expected_previous_owner","new_owner","previous_event_hash","event_hash","authorization_principal","authorization_decision_id","authorization_evidence_digest","transfer_id","witness_timestamp","schema_version","receipt_id"];
const reply = (data, status=200) => Response.json(data,{status,headers:{"cache-control":"no-store"}});
const canonical = e => JSON.stringify(Object.fromEntries(FIELDS.map(k=>[k,e[k] ?? null])));
async function sha(value) {
 const bytes = new TextEncoder().encode(value);
 const hash = await crypto.subtle.digest("SHA-256",bytes);
 return Array.from(new Uint8Array(hash)).map(x=>x.toString(16).padStart(2,"0")).join("");
}
async function prior(db,event,digest) {
 const row=await db.prepare("SELECT * FROM authority_events WHERE event_id=?").bind(event.event_id).first();
 if(!row) return null;
 const same=canonical(row)===canonical(event) && await sha(canonical(row))===digest;
 return same ? reply({status:"REPLAY",receipt_id:row.receipt_id,sequence:row.sequence,generation:row.generation})
             : reply({error:"IDEMPOTENCY_KEY_REUSE_CONFLICT"},409);
}
export default { async fetch(request,env) {
 const url=new URL(request.url);
 if(request.headers.get("authorization")!=="Bearer "+env.PROTOTYPE_TOKEN) return reply({error:"UNAUTHORIZED"},401);
 if(request.method==="POST" && url.pathname==="/__test/seed") {
  const b=await request.json();
  await env.DB.prepare("INSERT INTO task_heads(task_id,task_fingerprint,sequence,generation,authority_owner,updated_at) VALUES(?,?,0,0,'source',?)")
   .bind(b.task_id,b.task_fingerprint,new Date().toISOString()).run();
  return reply({seeded:true});
 }
 if(request.method==="GET" && url.pathname==="/__test/state") {
  const id=url.searchParams.get("task_id");
  const head=await env.DB.prepare("SELECT * FROM task_heads WHERE task_id=?").bind(id).first();
  const count=await env.DB.prepare("SELECT COUNT(*) AS n FROM authority_events WHERE task_id=?").bind(id).first();
  return reply({head,events:count.n});
 }
 if(request.method==="GET" && url.pathname==="/v1/events") {
  const id=url.searchParams.get("event_id");
  const row=await env.DB.prepare("SELECT event_id,task_id,sequence,generation,event_hash,receipt_id FROM authority_events WHERE event_id=?").bind(id).first();
  return row ? reply({status:"COMMITTED",...row}) : reply({status:"NOT_FOUND"},404);
 }
 if(request.method==="POST" && url.pathname==="/v1/events") {
  let e; try { e=await request.json(); } catch { return reply({error:"INVALID_JSON"},400); }
  if(!e || FIELDS.some(k=>!(k in e)) || typeof e.event_id!=="string" || !e.event_id ||
     typeof e.task_id!=="string" || !e.task_id || !Number.isInteger(e.sequence) ||
     !Number.isInteger(e.generation) || !["REVIEW","TRANSFER"].includes(e.event_type))
    return reply({error:"INVALID_EVENT"},400);
  const body=canonical(e), d=await sha(body);
  const found=await prior(env.DB,e,d); if(found) return found;
  try {
   await env.DB.prepare("INSERT INTO authority_events("+FIELDS.join(",")+") VALUES("+FIELDS.map(()=>"?").join(",")+")")
    .bind(...FIELDS.map(k=>e[k] ?? null)).run();
  } catch(err) {
   const retry=await prior(env.DB,e,d); if(retry) return retry;
   const msg=String(err && err.message || err);
   if(msg.includes("STALE_OR_INVALID_EXPECTATION") || msg.includes("UNIQUE constraint failed")) return reply({error:"STALE_OR_CONFLICT"},409);
   return reply({error:"WITNESS_WRITE_FAILED"},503);
  }
  const receipt=await env.DB.prepare("SELECT receipt_id,sequence,generation FROM authority_events WHERE event_id=?").bind(e.event_id).first();
  return receipt ? reply({status:"COMMITTED",...receipt},201) : reply({status:"OUTCOME_UNKNOWN",event_id:e.event_id},503);
 }
 return reply({error:"NOT_FOUND"},404);
}};
