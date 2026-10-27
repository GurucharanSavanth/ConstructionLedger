// Legacy ZIP/AES-GCM format helpers retained from the preserved web archive implementation.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const defaultCatalog = JSON.parse(await readFile(new URL('../app/src/main/assets/default_catalog.json', import.meta.url), 'utf8')).costCatalog;
const units = new Set(['direct','kg','tonne','bag','piece','box','set','sqft','sq100','running-foot','cft','m3','metre','litre','tank','trip','load','bathroom','point','hour','day','month','visit','lump-sum','custom']);
const natures = new Set(['Material','Labour','Professional Service','Equipment Purchase','Equipment Hire','Utility / Connection','Transport','Fee / Approval','Other']);
const safe = n => { assert(Number.isSafeInteger(n), 'Unsafe financial total'); return n; };
const add = (a,b) => safe(a+b);
const sum = values => values.reduce(add,0);
export function proportional(total,weights) {
    if (!weights.length) return [];
    assert(Number.isSafeInteger(total)&&total>=0&&weights.every(w=>Number.isSafeInteger(w)&&w>=0),'Invalid allocation weights');
    const weight=sum(weights); if (!weight) return [total,...weights.slice(1).map(()=>0)];
    const last=weights.findLastIndex(w=>w>0); let used=0;
    return weights.map((w,i)=> { if (!w) return 0; if (i===last) return total-used; const amount=Number(BigInt(total)*BigInt(w)/BigInt(weight)); used=add(used,amount); return amount; });
}
export function emptyState() { return {schema:1,project:{name:'My House Construction',budget:0,currency:'INR'},bills:[],payments:[],allocations:[],plans:[],contracts:[],entries:[],files:[],audit:[],settings:{lastBackupAt:null,costCatalog:structuredClone(defaultCatalog)},rev:0}; }
function scaled(value,digits,maxDigits) { const s=String(value??'').trim(); assert(new RegExp(`^\\d{1,${maxDigits}}(\\.\\d{1,${digits}})?$`).test(s),'Invalid calculation input'); const [whole,fraction='']=s.split('.'); return BigInt(whole)*10n**BigInt(digits)+BigInt((fraction+'0'.repeat(digits)).slice(0,digits)); }
function lineValue(line) {
    if(line.mode==='amount'||line.directAmountPaise!==undefined) { assert(Number.isSafeInteger(line.directAmountPaise)&&line.directAmountPaise>=0,'Invalid direct item amount'); return line.directAmountPaise; }
    const quantity=scaled(line.qty,3,9),multiplier=scaled(line.multiplier??'1',3,9),rate=scaled(String(line.rate??'').replaceAll(',',''),2,12);
    assert(quantity>0n&&multiplier>0n,'Quantity and multiplier must be positive');
    for(const id of [line.unitId,line.quantityUnitId]) assert(!id||units.has(id),'Unknown calculation unit');
    let numerator=quantity*rate*multiplier,denominator=1000000n;
    if(line.quantityUnitId&&line.quantityUnitId!==line.unitId) { if(line.quantityUnitId==='sqft'&&line.unitId==='sq100') denominator*=100n; else if(line.quantityUnitId==='kg'&&line.unitId==='tonne') denominator*=1000n; else if(line.quantityUnitId==='tonne'&&line.unitId==='kg') numerator*=1000n; else throw Error('Unsupported unit conversion'); }
    return safe(Number((numerator+denominator/2n)/denominator));
}
function validateCatalog(catalog) {
    assert(Array.isArray(catalog)&&catalog.length<=2500,'Invalid cost catalog'); const ids=new Map(),names=new Set();
    for(const c of catalog) {
        assert(c&&Object.keys(c).every(key=>['id','label','parentId','type','order','suggestedUnit','description','active','origin','aliases'].includes(key)),'Unknown cost category property');
        assert(typeof c.id==='string'&&/^[A-Za-z0-9_-]{1,100}$/.test(c.id)&&!ids.has(c.id),'Invalid or duplicate cost category ID');
        assert(typeof c.label==='string'&&c.label.trim()&&c.label.length<=140&&['head','subhead','detail'].includes(c.type),'Invalid cost category label or type');
        assert(c.parentId===null||typeof c.parentId==='string','Invalid cost category parent');
        assert(Number.isSafeInteger(c.order)&&c.order>=0&&c.order<=1000000&&typeof c.active==='boolean'&&['system','user'].includes(c.origin),'Invalid cost category properties');
        assert(c.description===undefined||typeof c.description==='string'&&c.description.length<=1000,'Invalid cost description');
        assert(c.suggestedUnit==null||units.has(c.suggestedUnit),'Unknown suggested unit');
        assert(c.aliases===undefined||Array.isArray(c.aliases)&&c.aliases.length<=20&&c.aliases.every(a=>typeof a==='string'&&a.length<=140),'Invalid category aliases');
        const key=JSON.stringify([c.parentId,c.label.normalize('NFKC').trim().replace(/\s+/g,' ').toLocaleLowerCase('en-IN')]); assert(!names.has(key),'Duplicate category label'); names.add(key); ids.set(c.id,c);
    }
    for(const c of catalog) assert(c.type==='head'?c.parentId===null:ids.get(c.parentId)?.type===(c.type==='subhead'?'head':'subhead'),'Invalid cost category parent');
    return ids;
}
function classification(record,catalog) {
    let parent=null;
    for(const [key,type] of [['costHeadId','head'],['costSubheadId','subhead'],['costDetailId','detail']]) { const id=record[key]; if(id!=null&&id!=='') { const node=catalog.get(id); assert(node&&node.type===type&&node.parentId===parent,'Unknown or mismatched cost classification'); parent=id; } else parent=null; }
    assert(record.costNature==null||record.costNature===''||natures.has(record.costNature),'Unknown cost nature');
    for(const key of ['unitId','quantityUnitId']) assert(record[key]==null||units.has(record[key]),'Unknown calculation unit');
    assert(record.customUnit==null||typeof record.customUnit==='string'&&record.customUnit.length<=90,'Invalid custom unit');
}
export function verifyState(state) {
    assert(state?.schema===1&&state.project&&typeof state.project.name==='string','Unsupported or damaged backup version');
    const index={};
    for(const name of ['bills','payments','allocations','plans','contracts','entries','files','audit']) { assert(Array.isArray(state[name]),`Backup missing ${name}`); index[name]=new Map(); for(const row of state[name]) { assert(row&&typeof row.id==='string'&&row.id&&!index[name].has(row.id),`Duplicate ${name} ID`); index[name].set(row.id,row); } }
    const validDate=s=>typeof s==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(s)&&Number.isFinite(Date.parse(s))&&new Date(s).toISOString().slice(0,10)===s;
    for(const r of [...state.bills,...state.payments,...state.entries]) assert(r.date===undefined||validDate(r.date),'Invalid entry date');
    for(const p of state.plans) assert(validDate(p.due),'Invalid planned date');
    for(const [rows,key] of [[state.bills,'total'],[state.payments,'amount'],[state.plans,'amount'],[state.contracts,'amount']]) for(const row of rows) assert(Number.isSafeInteger(row[key])&&row[key]>0,'Invalid financial amount');
    const balances={payment:new Map(),bill:new Map(),plan:new Map()},increment=(map,id,amount)=>map.set(id,add(map.get(id)||0,amount));
    const sameVendor=(a,b)=>!a.vendor||!b.vendor||String(a.vendor).trim().toLowerCase()===String(b.vendor).trim().toLowerCase();
    for(const a of state.allocations) { assert(index.payments.has(a.paymentId)&&index.bills.has(a.billId)&&Number.isSafeInteger(a.amount)&&a.amount>0,'Invalid payment allocation'); assert(sameVendor(index.payments.get(a.paymentId),index.bills.get(a.billId)),'Payment recipient does not match bill vendor'); increment(balances.payment,a.paymentId,a.amount); increment(balances.bill,a.billId,a.amount); }
    for(const p of state.payments) { assert(!p.planId||index.plans.has(p.planId),'Missing linked plan'); assert((balances.payment.get(p.id)||0)<=p.amount,'Payment overallocated'); if(p.planId) { assert(sameVendor(p,index.plans.get(p.planId)),'Payment recipient differs from instalment'); increment(balances.plan,p.planId,p.amount); } }
    for(const b of state.bills) assert((balances.bill.get(b.id)||0)<=b.total,'Bill overpaid');
    for(const p of state.plans) assert((balances.plan.get(p.id)||0)<=p.amount,'Plan overpaid');
    assert(!state.settings||!('costCatalog' in state.settings)||Array.isArray(state.settings.costCatalog),'Invalid saved cost catalog');
    const saved=state.settings?.costCatalog??[]; validateCatalog(saved); const merged=[...saved,...defaultCatalog.filter(c=>!saved.some(x=>x.id===c.id))],catalog=validateCatalog(merged);
    for(const b of state.bills) {
        classification(b,catalog);
        if(b.lines?.length) { for(const line of b.lines) assert(line.value===lineValue(line),'Bill has inconsistent line-item value'); assert(safe(add(add(sum(b.lines.map(lineValue)),b.extras||0),b.tax||0)-(b.discount||0))===b.total,'Bill line totals do not reconcile'); }
        for(const line of b.lines||[]) { classification(line,catalog); if(line.multiplier!=null) assert(scaled(line.multiplier,3,9)>0n,'Multiplier must be positive'); if(line.qty!=null) scaled(line.qty,3,9); if(line.rate!=null) scaled(String(line.rate).replaceAll(',',''),2,12); }
        for(const key of ['extras','tax','discount']) assert(b[key]===undefined||Number.isSafeInteger(b[key])&&b[key]>=0,'Invalid invoice charges');
    }
    for(const r of [...state.plans,...state.contracts]) classification(r,catalog);
    for(const c of state.contracts) { if(c.calculation) { classification(c.calculation,catalog); assert(lineValue(c.calculation)===c.amount,'Contract calculation mismatch'); } if(c.scheduleComplete) assert(sum(state.plans.filter(p=>p.contractId===c.id).map(p=>p.amount))===c.amount,'Contract instalments do not reconcile'); }
    for(const f of state.files) assert(typeof f.sha256==='string'&&/^[a-f0-9]{64}$/.test(f.sha256),'Invalid file metadata');
    return true;
}
const enc=new TextEncoder(),dec=new TextDecoder();const LIMIT=1024*1024*850;
const digest=async bytes=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(b=>b.toString(16).padStart(2,'0')).join('');
async function bytesOf(b){return new Uint8Array(await b.arrayBuffer())}
function b64(bytes){let s='';for(let i=0;i<bytes.length;i+=8192)s+=String.fromCharCode(...bytes.subarray(i,i+8192));return btoa(s)}
function fromB64(s){return Uint8Array.from(atob(s),x=>x.charCodeAt(0))}
async function keyFor(password,salt){if(typeof password!=='string'||password.length<10)throw Error('Use a recovery password of at least 10 characters.');const base=await crypto.subtle.importKey('raw',enc.encode(password),'PBKDF2',false,['deriveKey']);return await crypto.subtle.deriveKey({name:'PBKDF2',salt,iterations:250000,hash:'SHA-256'},base,{name:'AES-GCM',length:256},false,['encrypt','decrypt'])}
async function encrypt(data,key){const iv=crypto.getRandomValues(new Uint8Array(12));const ciphertext=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv},key,data));return {iv:b64(iv),bytes:ciphertext}}
async function decrypt(data,key,iv){try{return new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM',iv:fromB64(iv)},key,data))}catch{throw Error('Incorrect password or damaged backup archive.')}}
const crcTable=Array.from({length:256},(_,i)=>{let c=i;for(let j=0;j<8;j++)c=c&1?0xedb88320^(c>>>1):c>>>1;return c>>>0});function crc32(b){let c=0xffffffff;for(const k of b)c=crcTable[(c^k)&255]^(c>>>8);return(c^0xffffffff)>>>0}
function put16(d,o,v){d.setUint16(o,v,true)}function put32(d,o,v){d.setUint32(o,v,true)}function zipCreate(entries){const blobs=[],meta=[];let pos=0;for(const [name,data] of entries){const filename=enc.encode(name),b=data instanceof Uint8Array?data:enc.encode(data);if(b.length>0xffffffff||pos>0xffffffff)throw Error('Backup too large for this version of ZIP.');const header=new Uint8Array(30+filename.length),v=new DataView(header.buffer);put32(v,0,0x04034b50);put16(v,4,20);put16(v,6,0x800);put16(v,8,0);const crc=crc32(b);put32(v,14,crc);put32(v,18,b.length);put32(v,22,b.length);put16(v,26,filename.length);header.set(filename,30);blobs.push(header,b);meta.push({name:filename,crc,size:b.length,pos});pos+=header.length+b.length}const central=[];let cdSize=0;for(const m of meta){const h=new Uint8Array(46+m.name.length),v=new DataView(h.buffer);put32(v,0,0x02014b50);put16(v,4,20);put16(v,6,20);put16(v,8,0x800);put16(v,10,0);put32(v,16,m.crc);put32(v,20,m.size);put32(v,24,m.size);put16(v,28,m.name.length);put32(v,42,m.pos);h.set(m.name,46);central.push(h);cdSize+=h.length}const end=new Uint8Array(22),e=new DataView(end.buffer);put32(e,0,0x06054b50);put16(e,8,meta.length);put16(e,10,meta.length);put32(e,12,cdSize);put32(e,16,pos);return new Blob([...blobs,...central,end],{type:'application/zip'})}
function zipParse(data){if(data.byteLength>LIMIT)throw Error('Backup exceeds the safe browser import limit (850 MB).');const b=data instanceof Uint8Array?data:new Uint8Array(data),v=new DataView(b.buffer,b.byteOffset,b.byteLength);let end=-1;for(let p=b.length-22;p>=Math.max(0,b.length-65557);p--){if(v.getUint32(p,true)===0x06054b50){end=p;break}}if(end<0)throw Error('This is not a valid BuildLedger ZIP.');const count=v.getUint16(end+10,true),off=v.getUint32(end+16,true),out=new Map();if(count>10000)throw Error('Archive contains too many entries.');let pos=off;for(let i=0;i<count;i++){if(pos+46>b.length||v.getUint32(pos,true)!==0x02014b50)throw Error('Damaged archive index.');const flags=v.getUint16(pos+8,true),method=v.getUint16(pos+10,true),crc=v.getUint32(pos+16,true),size=v.getUint32(pos+20,true),len=v.getUint16(pos+28,true),ex=v.getUint16(pos+30,true),com=v.getUint16(pos+32,true),local=v.getUint32(pos+42,true);if(method!==0||!(flags&0x800))throw Error('Unsupported ZIP format.');const name=dec.decode(b.subarray(pos+46,pos+46+len));if(out.has(name)||name.includes('..')||name.startsWith('/')||name.includes('\\'))throw Error('Unsafe archive entry names.');if(local+30>b.length||v.getUint32(local,true)!==0x04034b50)throw Error('Damaged archive entry.');const start=local+30+v.getUint16(local+26,true)+v.getUint16(local+28,true);if(start+size>b.length)throw Error('Truncated archive entry.');const body=b.subarray(start,start+size);if(crc32(body)!==crc)throw Error('Corrupt backup archive (checksum mismatch).');out.set(name,body);pos+=46+len+ex+com}return out}
export async function packSnapshot(state,files,password,progress=()=>{}){verifyState(state);const found=new Map(files.map(f=>[f.id,f]));for(const m of state.files)if(!found.has(m.id))throw Error('Cannot back up because an original file is missing: '+m.name);const total=files.reduce((a,f)=>a+(f.blob?.size||0),0);if(total>LIMIT)throw Error('The document vault exceeds this browser version’s 850 MB safe export limit. Use smaller archives or a dedicated storage backend.');const salt=crypto.getRandomValues(new Uint8Array(16)),key=await keyFor(password,salt),entries=[];const dbPlain=enc.encode(JSON.stringify(state));const db=await encrypt(dbPlain,key);let head={product:'BuildLedger',format:1,encrypted:true,kdf:'PBKDF2-SHA256',iterations:250000,salt:b64(salt),database:{iv:db.iv,sha256:await digest(dbPlain)},objects:[]};entries.push(['state.enc',db.bytes]);for(let i=0;i<state.files.length;i++){const meta=state.files[i],source=found.get(meta.id);const bytes=await bytesOf(source.blob);const hash=await digest(bytes);if(hash!==meta.sha256)throw Error('File checksum mismatch: '+meta.name);const crypt=await encrypt(bytes,key);const path=`objects/${meta.id}.enc`;head.objects.push({id:meta.id,path,iv:crypt.iv,sha256:hash,size:bytes.length,type:meta.type||'application/octet-stream'});entries.push([path,crypt.bytes]);progress(i+1,state.files.length)}entries.unshift(['header.json',enc.encode(JSON.stringify(head))]);let archive=zipCreate(entries);if(archive.size>LIMIT)throw Error('Archive is too large to safely export in this browser.');return archive}
export async function unpackSnapshot(file,password,progress=()=>{}){const entries=zipParse(new Uint8Array(await file.arrayBuffer()));const headerData=entries.get('header.json');if(!headerData)throw Error('Not a BuildLedger backup');const h=JSON.parse(dec.decode(headerData));if(h.product!=='BuildLedger'||h.format!==1||h.encrypted!==true||!Array.isArray(h.objects))throw Error('Unsupported BuildLedger backup format');const key=await keyFor(password,fromB64(h.salt));const dbData=entries.get('state.enc');if(!dbData)throw Error('Backup database missing');const dbRaw=await decrypt(dbData,key,h.database.iv);if(await digest(dbRaw)!==h.database.sha256)throw Error('Database checksum mismatch');const state=JSON.parse(dec.decode(dbRaw));verifyState(state);if(h.objects.length!==state.files.length)throw Error('Backup does not contain every original file');let assets=[];const byId=new Map(h.objects.map(o=>[o.id,o]));if(byId.size!==h.objects.length)throw Error('Duplicate backup asset');for(let i=0;i<state.files.length;i++){const meta=state.files[i],m=byId.get(meta.id);if(!m||!entries.has(m.path)||m.sha256!==meta.sha256)throw Error('Missing original document: '+meta.name);if(typeof meta.type!=='string'||(m.type!==meta.type&&m.type!=='application/octet-stream'))throw Error('Document content-type mismatch: '+meta.name);const plain=await decrypt(entries.get(m.path),key,m.iv);if(plain.length!==m.size||plain.length!==meta.size||await digest(plain)!==m.sha256)throw Error('Document checksum mismatch: '+meta.name);assets.push({id:meta.id,blob:new Blob([plain],{type:meta.type||'application/octet-stream'})});progress(i+1,state.files.length)}return {state,assets}}
export const archiveFormatTest={zipCreate,zipParse,encrypt,decrypt,keyFor,b64,fromB64,digest};
