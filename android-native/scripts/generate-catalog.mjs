import { readFileSync, writeFileSync } from 'node:fs';
import { defaultCostCatalog } from '../../BuildLedger_Web/cost-catalog.js';

const source = readFileSync(new URL('../../BuildLedger_Web/app.js', import.meta.url), 'utf8');
const options = source.match(/const workOptions=\[([^\]]+)\]/);
if (!options) throw Error('Legacy work stages missing');
const workStages = [...options[1].matchAll(/'([^']+)'/g)].map(match => match[1]);
const costCatalog = defaultCostCatalog();
if (workStages.length !== 14 || costCatalog.length < 200) throw Error('Unexpected legacy catalog size');
writeFileSync(new URL('../app/src/main/assets/default_catalog.json', import.meta.url),
  JSON.stringify({ version: 1, workStages, costCatalog }, null, 2) + '\n');
console.log(`Exported ${workStages.length} work stages and ${costCatalog.length} cost nodes`);
