import assert from 'node:assert/strict';
import { createHash, createCipheriv, createDecipheriv, pbkdf2Sync, randomBytes } from 'node:crypto';
import { readFile, open, unlink, stat } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { unpackSnapshot, archiveFormatTest, proportional } from './convert-legacy-archive.mjs';

const MiB = 1024 * 1024, MAX = 850 * MiB, FRAME = MiB;
// shortcut: bounded host buffering supports 64 MiB input, use streaming only when larger archives are required.
export const MAX_INPUT = 64 * MiB;
const catalog = JSON.parse(await readFile(new URL('../app/src/main/assets/default_catalog.json', import.meta.url), 'utf8'));
const schema = JSON.parse(await readFile(new URL('../app/schemas/com.constructioncost.nativeapp.data.local.LedgerDatabase/5.json', import.meta.url), 'utf8')).database.entities;
const tables = schema.map(e => e.tableName);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const optional = (o, k) => o[k] == null || String(o[k]).trim() === '' ? null : String(o[k]);
const string = (o, k, fallback = '') => o[k] == null ? fallback : String(o[k]);
function integer(o, k, positive = false) { const n = o[k]; assert(Number.isSafeInteger(n) && n >= (positive ? 1 : 0), `Invalid legacy ${k}`); return n; }
function sum(values) { const n = values.reduce((a, b) => a + BigInt(b), 0n); assert(n <= BigInt(Number.MAX_SAFE_INTEGER), 'Unsafe aggregate amount'); return Number(n); }
function uuid(value) { const b = createHash('md5').update(value).digest(); b[6] = (b[6] & 15) | 48; b[8] = (b[8] & 63) | 128; const h = b.toString('hex'); return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`; }
function date(o, ...keys) { for (const key of keys) { const s = string(o, key).slice(0,10); if (/^\d{4}-\d{2}-\d{2}$/.test(s) && Number.isFinite(Date.parse(s)) && new Date(s).toISOString().slice(0,10) === s) return s; } throw Error('Legacy record lacks a valid date'); }
function timestamp(s) { if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(s || '')) return 0; try { date({value:s},'value'); } catch { return 0; } const n = Date.parse(s); return Number.isSafeInteger(n) ? n : 0; }

export function mapLegacy(state, now = Date.now()) {
    assert(state.schema === 1 && (state.project.currency ?? 'INR') === 'INR', 'Unsupported legacy ledger or currency');
    const names = ['bills','payments','allocations','plans','contracts','entries','files','audit'];
    for (const name of names) { assert(Array.isArray(state[name]), `Missing legacy ${name}`); const ids = state[name].map(x => { assert(typeof x.id === 'string' && x.id.length, 'Invalid legacy ID'); return x.id; }); assert(new Set(ids).size === ids.length, `Duplicate legacy ${name} ID`); }
    const records = Object.fromEntries(tables.map(t => [t, []]));
    const add = (table, id, fields) => records[table].push({ id, ...fields });
    const evidence = (kind, item) => add('import_records', `legacy:${kind}:${item.id}`, { source:`BuildLedgerWeb/${kind}`, originalJson:JSON.stringify(item), createdAt:now });
    const projectId = 'legacy-project';
    add('projects', projectId, {name:state.project.name?.trim() ? state.project.name : 'Imported construction project', revision:0, archived:false});
    const budget = state.project.budget === undefined ? 0 : integer(state.project,'budget');
    if (budget) add('budgets',projectId,{projectId,amountPaise:budget});
    add('import_records','legacy:project',{source:'BuildLedgerWeb/project',originalJson:JSON.stringify(state.project),createdAt:now});
    if (state.settings) add('import_records','legacy:settings',{source:'BuildLedgerWeb/settings',originalJson:JSON.stringify(state.settings),createdAt:now});
    const classifications = new Map();
    function category(id,name,kind,parentId=null,sortOrder=0,archived=false,nature='') { const nativeId=`legacy:${id}`; if (!classifications.has(nativeId)) { classifications.set(nativeId,kind); add('classifications',nativeId,{projectId,name,kind,parentId,sortOrder,archived,nature}); } return nativeId; }
    const costs = new Map(catalog.costCatalog.map(x=>[x.id,x]));
    for (const node of state.settings?.costCatalog || []) costs.set(node.id,node);
    for (const node of costs.values()) { const kind={head:'COST_HEAD',subhead:'COST_SUBHEAD',detail:'COST_DETAIL'}[node.type]; assert(kind,'Invalid cost category type'); category(`cost:${node.id}`,node.label,kind,optional(node,'parentId') ? `legacy:cost:${node.parentId}` : null,node.order||0,node.active===false); }
    const stages=new Map(catalog.workStages.map((name,i)=>[name.trim().toLowerCase(),category(`stage:${i+1}`,name,'STAGE',null,i)]));
    function stage(name) { const clean=name.trim(); if (!clean) return null; const key=clean.toLowerCase(); if (!stages.has(key)) stages.set(key,category(`stage:custom:${uuid(key)}`,clean,'STAGE')); return stages.get(key); }
    function workNode(work,substage,material) { const parent=stage(work); if (!parent) return null; if (!substage.trim()) return parent; const sub=category(`sub:${uuid(`${parent}/${substage.toLowerCase()}`)}`,substage,'SUBSTAGE',parent); return !material.trim() ? sub : category(`material:${uuid(`${sub}/${material.toLowerCase()}`)}`,material,'MATERIAL',sub); }
    for (const item of state.settings?.catalog || []) workNode(string(item,'stage'),string(item,'substage'),'');
    const vendors=new Map();
    function vendor(name) { const clean=name.trim(); if (!clean) return null; const key=clean.toLowerCase(); if (!vendors.has(key)) { const id=`legacy:vendor:${uuid(key)}`; vendors.set(key,id); add('vendors',id,{projectId,name:clean,note:''}); } return vendors.get(key); }
    const index=name=>new Map(state[name].map(x=>[x.id,x]));
    const contracts=index('contracts'),plans=index('plans'),bills=index('bills'),payments=index('payments');
    const rawCost=item=>['costDetailId','costSubheadId','costHeadId'].map(k=>optional(item,k)).find(Boolean);
    function planned(type,item) { const raw=rawCost(item); assert(!raw || classifications.has(`legacy:cost:${raw}`),'Unknown legacy planning cost classification'); const work=workNode(string(item,'work'),string(item,'substage'),''); const p=optional(item,'place'); const place=p ? category(`place:${uuid(p.toLowerCase())}`,p,'PLACE') : null; for (const id of [raw ? `legacy:cost:${raw}` : null,work,place].filter(Boolean)) add('planned_classifications',`legacy:planned:${type}:${item.id}:${id}`,{classificationId:id,commitmentId:type==='contract'?`legacy:contract:${item.id}`:null,instalmentId:type==='plan'?`legacy:plan:${item.id}`:null}); }
    for (const c of state.contracts) { add('commitments',`legacy:contract:${c.id}`,{projectId,title:c.title,amountPaise:integer(c,'amount',true),vendorId:vendor(string(c,'vendor')),date:date(c,'date','createdAt'),revision:0,archived:false,note:string(c,'notes'),calculationJson:c.calculation?JSON.stringify({quantity:String(c.calculation.qty),rate:String(c.calculation.rate),multiplier:string(c.calculation,'multiplier','1'),quantityUnit:optional(c.calculation,'quantityUnitId')||optional(c.calculation,'unitId')||'unit',rateUnit:optional(c.calculation,'unitId')||'unit',discountAmount:'0',discountPercent:'0',taxAmount:'0',taxPercent:'0',transport:'0',adjustment:'0'}):''}); planned('contract',c); evidence('contract',c); }
    for (const p of state.plans) { const c=optional(p,'contractId'); assert(!c||contracts.has(c),'Legacy instalment refers to a missing contract'); const days=p.remind??0; assert(Number.isInteger(days)&&days>=-1&&days<=365,'Invalid legacy reminder days'); add('instalments',`legacy:plan:${p.id}`,{projectId,title:p.title,amountPaise:integer(p,'amount',true),dueDate:date(p,'due'),commitmentId:c?`legacy:contract:${c}`:null,vendorId:vendor(string(p,'vendor')),cancelled:p.cancelled===true,remind:days>=0,revision:0,note:string(p,'notes')}); add('reminders',`legacy:reminder:${p.id}`,{instalmentId:`legacy:plan:${p.id}`,enabled:days>=0&&p.cancelled!==true,leadDays:Math.max(0,days)}); planned('plan',p); evidence('plan',p); }
    for (const bill of state.bills) {
        const total=integer(bill,'total',true),id=`legacy:bill:${bill.id}`;
        const lines=bill.lines?.length?bill.lines:[{label:string(bill,'title'),value:total}];
        const amounts=proportional(total,lines.map(l=>l.value!==undefined?integer(l,'value'):l.directAmountPaise!==undefined?integer(l,'directAmountPaise'):0));
        const description=string(bill,'title').trim()?string(bill,'title'):string(lines[0],'label','Imported expense');
        const invoiceNumber=string(bill,'invoice'); assert(invoiceNumber.length<=200&&!/[\x00-\x1f\x7f-\x9f]/.test(invoiceNumber),'Invalid legacy invoice reference');
        add('expenses',id,{projectId,description,amountPaise:total,date:date(bill,'date','recordedAt'),vendorId:vendor(string(bill,'vendor')),note:string(bill,'notes'),mode:'DIRECT_AMOUNT',calculationJson:'',revision:0,voided:false,commitmentId:null,instalmentId:null,idempotencyKey:id,requestJson:JSON.stringify({legacy:true,sourceId:bill.id}),invoiceNumber});
        const alloc=new Map();
        lines.forEach((line,i)=> { const amount=amounts[i],label=string(line,'label').trim()?string(line,'label'):description;
            add('expense_lines',`${id}:line:${i}`,{expenseId:id,description:label,amountPaise:amount,mode:line.mode==='calculated'?'CALCULATED':'DIRECT_AMOUNT',quantity:optional(line,'qty'),rate:optional(line,'rate'),multiplier:string(line,'multiplier','1'),quantityUnit:optional(line,'quantityUnitId')||optional(line,'unitId')||'unit',rateUnit:optional(line,'unitId')||'unit',legacyJson:JSON.stringify(line),nature:optional(line,'costNature')??optional(bill,'costNature')??''});
            if (!amount) return;
            const work=workNode(string(line,'work',string(bill,'work')),string(line,'substage',string(bill,'substage')),label),raw=rawCost(line)||rawCost(bill);
            const cost=raw&&classifications.has(`legacy:cost:${raw}`)?`legacy:cost:${raw}`:null,p=string(line,'place',string(bill,'place'));
            const place=p.trim()?category(`place:${uuid(p.toLowerCase())}`,p,'PLACE'):null;
            for (const [dimension,target] of [['WORK',work],['COST',cost],['PLACE',place]]) { const classificationId=target||category(`unclassified:${dimension}`,'Unclassified / Legacy',{WORK:'STAGE',COST:'COST_HEAD',PLACE:'PLACE'}[dimension]); const key=JSON.stringify([dimension,classificationId]); alloc.set(key,sum([alloc.get(key)||0,amount])); }
        });
        for (const [key,amountPaise] of alloc) { const [dimension,classificationId]=JSON.parse(key); add('classification_allocations',`${id}:${dimension}:${classificationId}`,{expenseId:id,classificationId,dimension,amountPaise}); } evidence('bill',bill);
    }
    for (const p of state.payments) { const planId=optional(p,'planId'); assert(!planId||plans.has(planId),'Legacy payment refers to a missing instalment'); const id=`legacy:payment:${p.id}`; add('payments',id,{projectId,amountPaise:integer(p,'amount',true),date:date(p,'date','recordedAt'),vendorId:vendor(string(p,'vendor')),reference:string(p,'reference'),note:string(p,'notes'),kind:'PAYMENT',originalPaymentId:null,instalmentId:planId?`legacy:plan:${planId}`:null,idempotencyKey:id,requestJson:JSON.stringify({legacy:true,sourceId:p.id})}); evidence('payment',p); }
    const grouped=new Map();
    for (const a of state.allocations) { assert(payments.has(a.paymentId)&&bills.has(a.billId),'Orphaned legacy payment allocation'); const key=JSON.stringify([a.paymentId,a.billId]); grouped.set(key,sum([grouped.get(key)||0,integer(a,'amount',true)])); evidence('allocation',a); }
    for (const [key,amountPaise] of grouped) { const [p,b]=JSON.parse(key); add('payment_allocations',`legacy:allocation:${p}:${b}`,{paymentId:`legacy:payment:${p}`,expenseId:`legacy:bill:${b}`,amountPaise}); }
    for (const e of state.entries) { add('activity_entries',`legacy:entry:${e.id}`,{projectId,title:string(e,'title','Work note'),date:date(e,'date','recordedAt'),note:string(e,'notes'),revision:0}); evidence('entry',e); }
    const entries=index('entries'),files=index('files');
    for (const f of state.files) { const parentId=optional(f,'parentId'); assert(!parentId||files.has(parentId),'Legacy document revision parent is missing'); const meta=(key,limit=200)=> { const value=f[key]??''; assert(typeof value==='string'&&value.length<=limit,`Invalid legacy file ${key}`); return value; }; add('attachments',f.id,{projectId,name:string(f,'name','Attachment'),mimeType:string(f,'type','application/octet-stream'),size:integer(f,'size',true),sha256:f.sha256,storageName:`${f.id}.bin`,createdAt:timestamp(string(f,'importedAt')),revision:0,parentId,category:meta('category'),status:meta('status'),revisionLabel:meta('revision'),work:meta('work'),place:meta('place'),notes:meta('notes',10000)});
        const link=string(f,'linkId'),expenseId=bills.has(link)?`legacy:bill:${link}`:null,paymentId=!expenseId&&payments.has(link)?`legacy:payment:${link}`:null,noteId=!expenseId&&!paymentId&&entries.has(link)?`legacy:entry:${link}`:null; if(expenseId||paymentId||noteId) add('attachment_links',`legacy:link:${f.id}`,{attachmentId:f.id,expenseId,paymentId,noteId}); evidence('file',f);
    }
    for (const a of state.audit) { add('audit_records',`legacy:audit:${a.id}`,{recordType:'legacy',recordId:string(a,'targetId',a.id),action:string(a,'type','LOG'),beforeJson:'null',afterJson:JSON.stringify({...a,_auditProjectId:projectId}),createdAt:timestamp(string(a,'at'))}); evidence('audit',a); }
    validateRecords(records);
    assert.equal(sum(records.expenses.map(x=>x.amountPaise)),sum(state.bills.map(x=>x.total)));
    assert.equal(sum(records.payments.map(x=>x.amountPaise)),sum(state.payments.map(x=>x.amount)));
    return records;
}

function validateRecords(records) {
    const db=new DatabaseSync(':memory:');
    try {
        db.exec('PRAGMA foreign_keys=OFF');
        for (const entity of schema) { db.exec(entity.createSql.replaceAll('${TABLE_NAME}',entity.tableName)); for(const index of entity.indices||[]) db.exec(index.createSql.replaceAll('${TABLE_NAME}',entity.tableName)); }
        let count=0;
        for (const entity of schema) { const fields=entity.fields,columns=fields.map(f=>f.columnName),insert=db.prepare(`INSERT INTO ${entity.tableName} (${columns.map(c=>`"${c}"`).join(',')}) VALUES (${columns.map(()=>'?').join(',')})`);
            for(const row of records[entity.tableName]) { count++; assert.deepEqual(Object.keys(row).sort(),[...columns].sort(),'Unexpected native columns'); let bytes=0; for(const f of fields) { const v=row[f.columnName]; assert(v!==undefined&&(v!==null||!f.notNull),'Invalid nullable column'); if(v!==null) assert(f.affinity==='TEXT'?typeof v==='string':typeof v==='boolean'||Number.isSafeInteger(v),'Invalid native value'); if(v!==null&&(f.columnName==='id'||f.columnName.endsWith('Id')||f.columnName==='idempotencyKey')) assert(typeof v==='string'&&v.trim()&&v.length<=200&&!/[\x00-\x1f\x7f-\x9f]/.test(v),'Invalid native identifier'); bytes+=typeof v==='string'?Buffer.byteLength(v)+16:24; } assert(bytes<=MiB,'Native row exceeds 1 MiB'); insert.run(...columns.map(c=>typeof row[c]==='boolean'?Number(row[c]):row[c])); }
        }
        assert(count<=1000000,'Too many native records'); assert.equal(db.prepare('PRAGMA foreign_key_check').all().length,0,'Missing native reference');
        for(const table of ['classifications','attachments']) { const byId=new Map(records[table].map(r=>[r.id,r])); for(const row of byId.values()) { const seen=new Set([row.id]); let parent=row.parentId; while(parent) { assert(!seen.has(parent),'Cyclic parent reference'); seen.add(parent); parent=byId.get(parent)?.parentId; } } }
        const parentKinds={STAGE:[null],SUBSTAGE:['STAGE'],MATERIAL:['SUBSTAGE'],COST_HEAD:[null],COST_SUBHEAD:['COST_HEAD'],COST_DETAIL:['COST_SUBHEAD'],PLACE:[null,'PLACE']}; const cats=new Map(records.classifications.map(x=>[x.id,x]));
        for(const c of cats.values()) assert(parentKinds[c.kind]?.includes(c.parentId?cats.get(c.parentId)?.kind:null),'Invalid classification parent type');
        for(const table of ['expenses','payments','commitments','instalments']) sum(records[table].map(r=>r.amountPaise));
        for(const line of records.expense_lines) assert(['','Material','Labour','Professional Service','Equipment Purchase','Equipment Hire','Utility / Connection','Transport','Fee / Approval','Other'].includes(line.nature),'Invalid cost nature');
        for(const a of records.attachments) { assert(/^[A-Za-z0-9_-]{1,180}\.bin$/.test(a.storageName),'Invalid attachment storage name'); for(const key of ['category','status','revisionLabel','work','place']) assert(a[key].length<=200&&!/[\x00-\x1f\x7f-\x9f]/.test(a[key]),'Invalid document details'); assert(a.notes.length<=10000,'Invalid document notes'); }
        for(const e of records.expenses) { assert.equal(sum(records.expense_lines.filter(l=>l.expenseId===e.id).map(l=>l.amountPaise)),e.amountPaise,'Expense lines do not reconcile'); for(const dimension of ['WORK','COST','PLACE']) assert.equal(sum(records.classification_allocations.filter(a=>a.expenseId===e.id&&a.dimension===dimension).map(a=>a.amountPaise)),e.amountPaise,'Classification totals do not reconcile'); assert(sum(records.payment_allocations.filter(a=>a.expenseId===e.id).map(a=>a.amountPaise))<=e.amountPaise,'Expense overpayment'); }
        for(const p of records.payments) assert(sum(records.payment_allocations.filter(a=>a.paymentId===p.id).map(a=>a.amountPaise))<=p.amountPaise,'Payment overallocated');
        for(const p of records.instalments) assert(sum(records.payments.filter(x=>x.instalmentId===p.id).map(x=>x.amountPaise))<=p.amountPaise,'Schedule overpayment');
        for(const c of records.commitments) assert(sum(records.instalments.filter(x=>x.commitmentId===c.id&&!x.cancelled).map(x=>x.amountPaise))<=c.amountPaise,'Schedule exceeds commitment');
        const references=[['payment_allocations','paymentId','payments','expenseId','expenses'],['instalments','id','instalments','commitmentId','commitments'],['payments','id','payments','instalmentId','instalments']];
        for(const [table,leftId,leftTable,rightId,rightTable] of references) { const left=new Map(records[leftTable].map(r=>[r.id,r])),right=new Map(records[rightTable].map(r=>[r.id,r])); for(const row of records[table]) { const a=left.get(row[leftId]),b=right.get(row[rightId]); if(a&&b) assert(!a.vendorId||!b.vendorId||a.vendorId===b.vendorId,'Mismatched settlement vendor'); } }
    } finally { db.close(); }
}

function passwordCheck(password) { assert(typeof password==='string'&&password.length>=10&&password.length<=1024&&password.isWellFormed(),'Password must contain 10 to 1024 well-formed characters'); }
export function encryptNative(zip,password) {
    passwordCheck(password); assert(zip.length>0&&zip.length+44+Math.ceil(zip.length/FRAME)*16<=MAX,'Native archive exceeds limit');
    const header=Buffer.alloc(44); header.write('BLNENC01'); header.writeUInt32BE(250000,8); randomBytes(16).copy(header,12); randomBytes(8).copy(header,28); header.writeBigUInt64BE(BigInt(zip.length),36);
    const key=pbkdf2Sync(password,header.subarray(12,28),250000,32,'sha256'),frames=[header];
    try { for(let off=0,i=0;off<zip.length;off+=FRAME,i++) { const index=Buffer.alloc(4); index.writeUInt32BE(i); const cipher=createCipheriv('aes-256-gcm',key,Buffer.concat([header.subarray(28,36),index])); cipher.setAAD(Buffer.concat([header,index])); frames.push(cipher.update(zip.subarray(off,off+FRAME)),cipher.final(),cipher.getAuthTag()); } return Buffer.concat(frames); } finally { key.fill(0); }
}
export function decryptNative(bytes,password) {
    passwordCheck(password); assert(bytes.length>=61&&bytes.subarray(0,8).toString()==='BLNENC01'&&bytes.readUInt32BE(8)===250000,'Invalid native envelope'); const header=bytes.subarray(0,44),length=Number(header.readBigUInt64BE(36)); assert(Number.isSafeInteger(length)&&length>0&&bytes.length===44+length+Math.ceil(length/FRAME)*16&&bytes.length<=MAX,'Native envelope length mismatch');
    const key=pbkdf2Sync(password,header.subarray(12,28),250000,32,'sha256'),plain=[];
    try { for(let off=44,remaining=length,i=0;remaining>0;i++) { const size=Math.min(remaining,FRAME),index=Buffer.alloc(4); index.writeUInt32BE(i); const decipher=createDecipheriv('aes-256-gcm',key,Buffer.concat([header.subarray(28,36),index])); decipher.setAAD(Buffer.concat([header,index])); decipher.setAuthTag(bytes.subarray(off+size,off+size+16)); const data=decipher.update(bytes.subarray(off,off+size)); plain.push(Buffer.concat([data,decipher.final()])); off+=size+16; remaining-=size; } return Buffer.concat(plain); } finally { key.fill(0); }
}

function validateDocument(bytes,mime) {
    assert(bytes.length>0&&bytes.length<=250*MiB,'Document exceeds 250 MiB'); assert(/^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}\/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}$/.test(mime),'Invalid document MIME type');
    const starts=hex=>bytes.subarray(0,hex.length/2).toString('hex')===hex;
    const valid=mime==='application/pdf'?starts('255044462d'):mime==='image/jpeg'?starts('ffd8ff'):mime==='image/png'?starts('89504e470d0a1a0a'):mime==='image/gif'?['GIF87a','GIF89a'].includes(bytes.subarray(0,6).toString()):mime==='image/webp'?bytes.subarray(0,4).toString()==='RIFF'&&bytes.subarray(8,12).toString()==='WEBP':['text/plain','text/csv'].includes(mime)?[...bytes.subarray(0,512)].every(b=>b>=32||[9,10,13].includes(b)):['application/msword','application/vnd.ms-excel','application/vnd.ms-powerpoint'].includes(mime)?starts('d0cf11e0a1b1ae1'):mime.startsWith('application/vnd.openxmlformats-officedocument.')?starts('504b0304'):true;
    assert(valid,'Document content does not match MIME type');
}
export async function convertArchive(input,legacyPassword,nativePassword,now=Date.now()) {
    passwordCheck(legacyPassword); passwordCheck(nativePassword); assert(input.length<=MAX_INPUT,'Desktop converter input exceeds 64 MiB buffering limit');
    const entries=archiveFormatTest.zipParse(input),headBytes=entries.get('header.json'); assert(headBytes?.length<=MiB,'Missing or oversized legacy header'); const header=JSON.parse(Buffer.from(headBytes).toString());
    const b64=(value,n)=>typeof value==='string'&&Buffer.from(value,'base64').length===n&&Buffer.from(value,'base64').toString('base64')===value;
    assert(header.product==='BuildLedger'&&header.format===1&&header.encrypted===true&&header.kdf==='PBKDF2-SHA256'&&header.iterations===250000&&b64(header.salt,16)&&b64(header.database?.iv,12)&&/^[a-f0-9]{64}$/i.test(header.database?.sha256),'Unsupported legacy encryption header');
    assert(Array.isArray(header.objects)&&header.objects.length<=1000,'Too many legacy files'); const expected=new Set(['header.json','state.enc']);
    for(const object of header.objects) { assert(/^[A-Za-z0-9_-]{1,180}$/.test(object.id)&&object.path===`objects/${object.id}.enc`&&!expected.has(object.path)&&b64(object.iv,12),'Invalid legacy object inventory'); expected.add(object.path); }
    assert.deepEqual([...entries.keys()].sort(),[...expected].sort(),'Unexpected archive entries');
    const {state,assets}=await unpackSnapshot(new Blob([input]),legacyPassword);
    const records=mapLegacy(state,now),json=Buffer.from(JSON.stringify(records)); assert(json.length<=256*MiB,'Native records exceed 256 MiB');
    const files=[],outputEntries=[];
    for(const asset of assets) { const info=records.attachments.find(x=>x.id===asset.id),bytes=Buffer.from(await asset.blob.arrayBuffer()); assert.equal(hash(bytes),info.sha256); validateDocument(bytes,info.mimeType); files.push({id:info.id,path:`files/${info.id}`,size:bytes.length,sha256:hash(bytes)}); outputEntries.push([`files/${info.id}`,bytes]); }
    const manifest={product:'BuildLedgerNative',format:1,schema:5,createdAt:now,counts:Object.fromEntries(tables.map(t=>[t,records[t].length])),files,recordsSha256:hash(json),settings:{project:'legacy-project',reminders:false,showArchived:false}};
    assert(Buffer.byteLength(JSON.stringify(manifest))<=MiB,'Native manifest exceeds limit');
    const zip=Buffer.from(await archiveFormatTest.zipCreate([['manifest.json',JSON.stringify(manifest)],['records.json',json],...outputEntries]).arrayBuffer());
    const encrypted=encryptNative(zip,nativePassword); assert.deepEqual(decryptNative(encrypted,nativePassword),zip);
    return {encrypted,records,manifest};
}

export async function convertFile(source,destination,legacyPassword,nativePassword) {
    assert((await stat(source)).size<=MAX_INPUT,'Desktop converter input exceeds 64 MiB buffering limit');
    const {encrypted,manifest}=await convertArchive(await readFile(source),legacyPassword,nativePassword);
    const output=await open(destination,'wx',0o600); let complete=false;
    try { await output.writeFile(encrypted); await output.sync(); assert.equal(hash(await readFile(destination)),hash(encrypted),'Saved output verification failed'); complete=true; return {counts:manifest.counts,files:manifest.files.length,sha256:hash(encrypted),bytes:encrypted.length}; }
    finally { await output.close(); if(!complete) await unlink(destination); }
}

if(process.argv[1]&&pathToFileURL(process.argv[1]).href===import.meta.url) {
    try { assert(process.argv.length===4,'Usage: node convert-legacy.mjs SOURCE DESTINATION; set BUILDLEDGER_LEGACY_PASSWORD and BUILDLEDGER_NATIVE_PASSWORD'); const oldPassword=process.env.BUILDLEDGER_LEGACY_PASSWORD,newPassword=process.env.BUILDLEDGER_NATIVE_PASSWORD; delete process.env.BUILDLEDGER_LEGACY_PASSWORD; delete process.env.BUILDLEDGER_NATIVE_PASSWORD; console.log(JSON.stringify(await convertFile(process.argv[2],process.argv[3],oldPassword,newPassword))); }
    catch(error) { console.error(error.message); process.exitCode=1; }
}
