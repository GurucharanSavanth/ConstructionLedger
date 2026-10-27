import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { packSnapshot, archiveFormatTest, emptyState } from './convert-legacy-archive.mjs';
import { mapLegacy, convertArchive, convertFile, decryptNative, encryptNative, MAX_INPUT } from './convert-legacy.mjs';

const password='fixture-password-123',nativePassword='native-fixture-password-123',now=1767312000000;
const digest=bytes=>createHash('sha256').update(bytes).digest('hex');
const evidence=new URL('../../.tools/native-migration/evidence/release-v3/converter-20261015/',import.meta.url);
function fixture() {
    const state=emptyState(); state.project.name='Converter differential'; state.project.budget=200000;
    const cost=state.settings.costCatalog.find(x=>x.type==='detail');
    state.contracts.push({id:'contract1',title:'Contract',amount:20000,date:'2026-01-02',vendor:' Vendor ',work:'Custom stage',substage:'Substage',place:'Ground',costDetailId:cost.id});
    state.plans.push({id:'plan1',title:'Instalment',amount:9000,due:'2026-02-02',contractId:'contract1',vendor:'vendor',remind:3,work:'Custom stage',substage:'Substage',place:'Ground',costDetailId:cost.id});
    state.bills.push({id:'bill1',title:'Invoice',total:11120,date:'2026-01-02',vendor:'Vendor',invoice:'INV-42',extras:1120,work:'Custom stage',substage:'Substage',place:'Ground',costDetailId:cost.id,lines:[{label:'Bricks',mode:'amount',directAmountPaise:5000,value:5000,quantityUnitId:'piece',unitId:'piece',costNature:'Material'},{label:'Labour',mode:'amount',directAmountPaise:5000,value:5000,costNature:'Labour'}]});
    state.payments.push({id:'pay1',amount:4000,date:'2026-01-03',planId:'plan1',vendor:'vendor',reference:'REF-1'});
    state.allocations.push({id:'alloc1',paymentId:'pay1',billId:'bill1',amount:1500},{id:'alloc2',paymentId:'pay1',billId:'bill1',amount:2500});
    state.entries.push({id:'entry1',title:'Work note',date:'2026-01-02',notes:'Checked'});
    state.audit.push({id:'audit1',targetId:'bill1',type:'CREATE',at:'2026-01-02T00:00:00.000Z'});
    const sub=state.settings.costCatalog.find(x=>x.id===cost.parentId);
    for(const item of [...state.contracts,...state.plans,...state.bills]) Object.assign(item,{costSubheadId:sub.id,costHeadId:sub.parentId});
    const bytes=Buffer.from('%PDF-1.4\nconverter fixture\n');
    state.files.push({id:'pdf1',name:'Receipt 中文.pdf',type:'application/pdf',size:bytes.length,sha256:digest(bytes),importedAt:'2026-01-02T00:00:00.000Z',linkId:'bill1',category:'Receipts',revision:'v1'});
    return {state,assets:[{id:'pdf1',blob:new Blob([bytes],{type:'application/pdf'})}]};
}
const archive=async f=>Buffer.from(await (await packSnapshot(f.state,f.assets,password)).arrayBuffer());

test('complex fixture preserves native mapping, grouped settlements, evidence and encrypted output',async()=> {
    const f=fixture(),input=await archive(f),result=await convertArchive(input,password,nativePassword,now),r=result.records;
    assert.equal(r.expenses[0].amountPaise,11120); assert.equal(r.expenses[0].invoiceNumber,'INV-42');
    assert.deepEqual(r.expense_lines.map(l=>l.amountPaise),[5560,5560]); assert.equal(r.expense_lines[0].quantityUnit,'piece');
    assert.equal(r.payment_allocations.length,1); assert.equal(r.payment_allocations[0].amountPaise,4000);
    assert.equal(r.import_records.filter(x=>x.source==='BuildLedgerWeb/allocation').length,2);
    assert.equal(r.payments[0].instalmentId,'legacy:plan:plan1'); assert.equal(r.attachments[0].id,'pdf1');
    assert.equal(r.attachment_links[0].expenseId,'legacy:bill:bill1'); assert.equal(r.reminders[0].leadDays,3);
    assert.equal(r.commitments[0].amountPaise,20000); assert.equal(r.audit_records[0].createdAt,now);
    assert.equal(r.expenses[0].amountPaise-r.payment_allocations[0].amountPaise,7120);
    assert.equal(r.payments[0].amountPaise-r.payment_allocations[0].amountPaise,0);
    assert.equal(r.instalments[0].amountPaise-r.payments[0].amountPaise,5000);
    const zip=archiveFormatTest.zipParse(decryptNative(result.encrypted,nativePassword));
    assert.equal(digest(zip.get('records.json')),result.manifest.recordsSha256);
    assert.equal(digest(zip.get('files/pdf1')),f.state.files[0].sha256);
    await mkdir(evidence,{recursive:true}); await writeFile(new URL('complex-legacy.zip',evidence),input); await writeFile(new URL('complex-native.blbackup',evidence),result.encrypted); await writeFile(new URL('complex-expected.json',evidence),JSON.stringify(r));
    await writeFile(new URL('complex-receipt.json',evidence),JSON.stringify({timestamp:new Date().toISOString(),counts:result.manifest.counts,sha256:digest(result.encrypted),syntheticOnly:true}));
});

test('state below and above 6 MiB with multiple files above 12 MiB converts without device heap limit changes',async()=> {
    for(const count of [4900,6200]) {
        const f=fixture(); f.state.entries=Array.from({length:count},(_,i)=>({id:`entry${i}`,date:'2026-01-02',title:'Synthetic work',notes:'x'.repeat(1024)}));
        const files=[Buffer.alloc(13*1024*1024,65),Buffer.alloc(13*1024*1024,66)];
        f.state.files=files.map((b,i)=>({id:`large${i}`,name:`Large ${i}.bin`,type:'application/octet-stream',size:b.length,sha256:digest(b),importedAt:'2026-01-02T00:00:00Z'})); f.assets=files.map((b,i)=>({id:`large${i}`,blob:new Blob([b])}));
        const stateBytes=Buffer.byteLength(JSON.stringify(f.state)); assert.equal(stateBytes>6*1024*1024,count===6200);
        const input=await archive(f),result=await convertArchive(input,password,nativePassword,now);
        assert.equal(result.records.activity_entries.length,count); assert.equal(result.manifest.files.length,2);
        const zip=archiveFormatTest.zipParse(decryptNative(result.encrypted,nativePassword)); for(let i=0;i<2;i++) assert.equal(digest(zip.get(`files/large${i}`)),digest(files[i]));
        await writeFile(new URL(`large-${count}-native.blbackup`,evidence),result.encrypted);
        await writeFile(new URL(`large-${count}-receipt.json`,evidence),JSON.stringify({stateBytes,inputBytes:input.length,outputBytes:result.encrypted.length,counts:result.manifest.counts,sha256:digest(result.encrypted),recorded:11120,paid:4000,outstanding:7120,syntheticOnly:true}));
    }
});

test('wrong password, damaged ciphertext, truncated ZIP, unsupported header and hash mismatch are rejected',async()=> {
    const input=await archive(fixture()); await assert.rejects(convertArchive(input,'wrong-password',nativePassword));
    await assert.rejects(convertArchive(input.subarray(0,input.length-25),password,nativePassword));
    const original=archiveFormatTest.zipParse(input);
    for(const change of ['cipher','version','kdf','hash','duplicate-path','extra']) {
        const entries=new Map([...original].map(([k,v])=>[k,Buffer.from(v)])),header=JSON.parse(entries.get('header.json'));
        if(change==='cipher') entries.get('state.enc')[0]^=1;
        if(change==='version') header.format=99;
        if(change==='kdf') header.iterations=1;
        if(change==='hash') header.objects[0].sha256='0'.repeat(64);
        if(change==='extra') entries.set('unknown.bin',Buffer.from('x'));
        entries.set('header.json',Buffer.from(JSON.stringify(header)));
        const values=[...entries]; if(change==='duplicate-path') values.push(['state.enc',entries.get('state.enc')]);
        const modified=Buffer.from(await archiveFormatTest.zipCreate(values).arrayBuffer()); await assert.rejects(convertArchive(modified,password,nativePassword),change);
    }
});

test('duplicate records, orphan parents, cycles, unsupported state and overallocations fail before output',()=> {
    for(const change of ['duplicate','parent','cycle','version','currency','allocation','schedule','date','money']) {
        const {state}=fixture();
        if(change==='duplicate') state.bills.push({...state.bills[0]});
        if(change==='parent') state.files[0].parentId='missing';
        if(change==='cycle') state.files[0].parentId=state.files[0].id;
        if(change==='version') state.schema=2;
        if(change==='currency') state.project.currency='USD';
        if(change==='allocation') state.allocations[0].amount=20000;
        if(change==='schedule') state.plans[0].amount=20001;
        if(change==='date') state.bills[0].date='2026-02-30';
        if(change==='money') state.bills[0].total=Number.MAX_SAFE_INTEGER+1;
        assert.throws(()=>mapLegacy(state,now),undefined,change);
    }
});

test('proportional near safe integer boundary stays exact',()=> {
    const {state}=fixture(); state.bills[0].total=Number.MAX_SAFE_INTEGER; state.bills[0].lines[0].value=4503599627370495; state.bills[0].lines[1].value=4503599627370496;
    const r=mapLegacy(state,now); assert.deepEqual(r.expense_lines.map(x=>x.amountPaise),[4503599627370495,4503599627370496]);
});

test('multi-frame Unicode envelope rejects wrong password, tag changes, header changes and reordering',()=> {
    const bytes=Buffer.alloc(2*1024*1024+17,65),unicode='κλειδί-密码-😀-123',encrypted=encryptNative(bytes,unicode);
    assert.deepEqual(decryptNative(encrypted,unicode),bytes); assert.throws(()=>decryptNative(encrypted,password)); assert.throws(()=>decryptNative(encrypted.subarray(0,-1),unicode));
    for(const offset of [28,44,44+1024*1024]) { const bad=Buffer.from(encrypted); bad[offset]^=1; assert.throws(()=>decryptNative(bad,unicode)); }
    const bad=Buffer.from(encrypted),a=Buffer.from(bad.subarray(44,44+1024*1024+16)); bad.copy(bad,44,44+1024*1024+16,44+2*(1024*1024+16)); a.copy(bad,44+1024*1024+16); assert.throws(()=>decryptNative(bad,unicode));
    assert.throws(()=>encryptNative(bytes,'broken-\ud800-password'));
});

test('file conversion preserves source and refuses existing destination; failures publish no plaintext',async()=> {
    await mkdir(evidence,{recursive:true});
    const dir=await mkdtemp(new URL('converter-test-',evidence));
    try { const source=join(dir,'source.zip'),destination=join(dir,'output.blbackup'),input=await archive(fixture()); await writeFile(source,input); await writeFile(destination,'existing'); await assert.rejects(convertFile(source,destination,password,nativePassword),{code:'EEXIST'}); assert.equal(await readFile(destination,'utf8'),'existing'); assert.deepEqual(await readFile(source),input); const missing=join(dir,'failed.blbackup'); await assert.rejects(convertFile(source,missing,'wrong-password',nativePassword)); await assert.rejects(readFile(missing),{code:'ENOENT'}); assert.deepEqual(await readFile(source),input); await assert.rejects(convertArchive(Buffer.alloc(MAX_INPUT+1),password,nativePassword),/64 MiB/); }
    finally { await rm(dir,{recursive:true,force:true}); }
});



test('planning notes and calculation evidence are preserved in schema5',async()=> {
    const f=fixture();
    f.state.contracts[0].notes='Agreed labour rate';
    f.state.contracts[0].calculation={mode:'calculated',qty:'200',rate:'1',unitId:'piece',quantityUnitId:'piece',multiplier:'1'};
    f.state.plans[0].notes='Inspect before payment';
    const input=await archive(f),result=await convertArchive(input,password,nativePassword,now);
    await mkdir(evidence,{recursive:true});
    await writeFile(new URL('planning-metadata-legacy.zip',evidence),input);
    await writeFile(new URL('planning-metadata-native.blbackup',evidence),result.encrypted);
    assert.equal(result.manifest.schema,5);
    assert.equal(result.records.commitments[0].note,'Agreed labour rate');
    assert.equal(JSON.parse(result.records.commitments[0].calculationJson).quantity,'200');
    assert.equal(result.records.instalments[0].note,'Inspect before payment');
});
