import test from 'node:test';
import assert from 'node:assert/strict';
import { mapLegacy } from './convert-legacy.mjs';

function fixture(line, total = 50000) {
    return {
        schema: 1, project: { name: 'Synthetic unit import', currency: 'INR' }, settings: {},
        bills: [{ id: 'steel', title: 'Steel', date: '2026-10-22', total,
            lines: [{ label: 'Steel', mode: 'calculated', qty: '10', rate: '50', value: 50000, ...line }] }],
        payments: [], allocations: [], plans: [], contracts: [], entries: [], files: [], audit: []
    };
}

for (const [name, quantity] of [['missing', {}], ['null', { quantityUnitId: null }], ['blank', { quantityUnitId: '' }],
    ['whitespace', { quantityUnitId: '  ' }]]) {
    test(`${name} quantity unit inherits the rate unit without changing posted money`, () => {
        for (const unitId of ['kg', 'metre', 'm', 'sqft', 'cft', 'piece', 'bag', 'litre']) {
            const source = fixture({ unitId, ...quantity });
            const before = JSON.stringify(source);
            const mapped = mapLegacy(source, 0);
            const line = mapped.expense_lines[0];
            assert.equal(line.quantityUnit, unitId, `${name}: ${unitId}`);
            assert.equal(line.rateUnit, unitId);
            assert.equal(line.amountPaise, 50000);
            assert.equal(mapped.expenses[0].amountPaise, 50000);
            assert.deepEqual(JSON.parse(line.legacyJson), source.bills[0].lines[0]);
            assert.equal(JSON.stringify(source), before);
        }
    });
}

test('explicit quantity units and supported conversion pairs remain unchanged', () => {
    const pairs = ['kg', 'metre', 'sqft', 'cft', 'piece', 'bag', 'litre'].map(unit => [unit, unit]);
    pairs.push(['sqft', 'sq100'], ['kg', 'tonne'], ['tonne', 'kg']);
    for (const [quantityUnitId, unitId] of pairs) {
        const mapped = mapLegacy(fixture({ quantityUnitId, unitId }, 50011), 0);
        assert.equal(mapped.expense_lines[0].quantityUnit, quantityUnitId);
        assert.equal(mapped.expense_lines[0].rateUnit, unitId);
        assert.equal(mapped.expense_lines[0].amountPaise, 50011);
        assert.equal(mapped.expenses[0].amountPaise, 50011);
    }
});

test('absent rate units use unit while explicit unknown units retain source evidence', () => {
    for (const units of [{}, { unitId: null }, { unitId: '' }, { unitId: '  ' }]) {
        const line = mapLegacy(fixture(units), 0).expense_lines[0];
        assert.equal(line.quantityUnit, 'unit');
        assert.equal(line.rateUnit, 'unit');
    }
    for (const [quantityUnitId, unitId] of [['unknown-unit', 'kg'], ['kg', 'unknown-unit'], ['kg', 'litre']]) {
        const line = mapLegacy(fixture({ quantityUnitId, unitId }), 0).expense_lines[0];
        assert.equal(line.quantityUnit, quantityUnitId);
        assert.equal(line.rateUnit, unitId);
        assert.equal(JSON.parse(line.legacyJson).quantityUnitId, quantityUnitId);
        assert.equal(line.amountPaise, 50000);
    }
});
