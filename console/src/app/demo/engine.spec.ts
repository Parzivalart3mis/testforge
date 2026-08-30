import { describe, expect, it } from 'vitest';
import { SchemaSnapshot } from '../core/api.types';
import { DependencyGraph, qualified } from './graph';
import { generate, plan } from './generate';
import { MaskingEngine, isLuhnValid, ibanCheckDigits } from './mask';
import { Hmac, sha256 } from './crypto';
import fixture from './demo-schema.json';

/**
 * The browser engine has to hold the same invariants the service does, because
 * it is what a visitor to the hosted demo actually sees. These are the same
 * properties the Java suite asserts, checked against the same 22-table schema.
 */

const snapshot = fixture as unknown as SchemaSnapshot;

const request = (overrides: Record<string, unknown> = {}) => ({
  name: 'test dataset',
  requestedBy: 'test@example.com',
  targetId: 'demo-commerce',
  scale: 20,
  ...overrides,
});

describe('crypto', () => {
  it('computes the known SHA-256 digest of an empty input', () => {
    const hex = [...sha256(new Uint8Array(0))].map((b) => b.toString(16).padStart(2, '0')).join('');
    expect(hex).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  });

  it('computes the known SHA-256 digest of "abc"', () => {
    const hex = [...sha256(new TextEncoder().encode('abc'))]
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
    expect(hex).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });

  it('length-prefixes parts so boundaries cannot be forged', () => {
    const hmac = new Hmac('key');
    expect(hmac.asHex(32, 'ab', 'c')).not.toBe(hmac.asHex(32, 'a', 'bc'));
  });

  it('is deterministic and key-dependent', () => {
    const a = new Hmac('key-one');
    const b = new Hmac('key-two');
    expect(a.asHex(32, 'value')).toBe(a.asHex(32, 'value'));
    expect(a.asHex(32, 'value')).not.toBe(b.asHex(32, 'value'));
  });
});

describe('the demo schema fixture', () => {
  it('is the 22-table commerce schema produced by the real introspector', () => {
    expect(snapshot.tables).toHaveLength(22);
    expect(snapshot.fingerprint).toMatch(/^[0-9a-f]{32}$/);
  });

  it('carries the awkward cases the engine has to handle', () => {
    const customer = snapshot.tables.find((t) => t.ref.name === 'customer')!;
    const employee = snapshot.tables.find((t) => t.ref.name === 'employee')!;

    expect(customer.foreignKeys.some((fk) => fk.parent.name === 'order_header')).toBe(true);
    expect(employee.foreignKeys.some((fk) => fk.parent.name === 'employee')).toBe(true);
    expect(employee.columns.find((c) => c.name === 'full_name')?.generated).toBe(true);
  });
});

describe('foreign-key ordering', () => {
  const graph = new DependencyGraph(snapshot);
  const order = graph.order();

  it('orders every table', () => {
    expect(order.order).toHaveLength(22);
  });

  it('puts every parent before its children', () => {
    for (const fk of graph.edges) {
      if (order.deferred.some((d) => d.name === fk.name)) {
        continue;
      }
      expect(order.order.indexOf(qualified(fk.parent))).toBeLessThan(
        order.order.indexOf(qualified(fk.child)),
      );
    }
  });

  it('detects the customer/order_header cycle and breaks it at the nullable edge', () => {
    expect(order.cycles).toHaveLength(1);
    expect(order.cycles[0].sort()).toEqual(['public.customer', 'public.order_header']);
    expect(order.deferred).toHaveLength(1);
    expect(order.deferred[0].name).toBe('fk_customer_primary_order');
  });

  it('reports all three self-references without treating them as ordering constraints', () => {
    expect(graph.selfEdges).toHaveLength(3);
    expect(graph.edges.some((fk) => qualified(fk.child) === qualified(fk.parent))).toBe(false);
  });

  it('produces the same order on every run', () => {
    const first = new DependencyGraph(snapshot).order().order;
    for (let i = 0; i < 10; i++) {
      expect(new DependencyGraph(snapshot).order().order).toEqual(first);
    }
  });

  it('pulls required parents into a narrow selection', () => {
    const closure = DependencyGraph.requiredClosure(snapshot, new Set(['public.order_line']));
    expect(closure).toContain('public.order_header');
    expect(closure).toContain('public.customer');
    expect(closure).toContain('public.address');
  });
});

describe('planning', () => {
  it('covers every table and reports the broken cycle', () => {
    const result = plan(snapshot, request(), 42);

    expect(result.tables).toHaveLength(22);
    expect(result.totalRows).toBeGreaterThan(0);
    expect(result.warnings.some((w) => w.includes('Broke a foreign-key cycle'))).toBe(true);
  });

  it('masks the sensitive columns and never a key', () => {
    const result = plan(snapshot, request(), 42);
    const summary = result.tables.flatMap((table) =>
      table.columns.map((column) => ({ table: table.table.name, ...column })),
    );

    const email = summary.find((c) => c.table === 'customer' && c.column === 'email')!;
    expect(email.mask).toBe('EMAIL');

    const card = summary.find((c) => c.table === 'payment' && c.column === 'card_number')!;
    expect(card.mask).toBe('CREDIT_CARD');

    const foreignKeys = summary.filter((c) => c.role === 'FOREIGN_KEY');
    expect(foreignKeys.length).toBeGreaterThan(0);
    expect(foreignKeys.every((c) => c.mask === 'PRESERVE')).toBe(true);
  });

  it('caps a table at what its narrowest unique column can hold', () => {
    // country.iso_code is char(2) UNIQUE: 1296 distinct values, whatever is asked for.
    const result = plan(snapshot, request({ scale: 5000 }), 42);
    const country = result.tables.find((t) => t.table.name === 'country')!;

    expect(country.rowCount).toBeLessThanOrEqual(1296);
    expect(result.warnings.some((w) => w.includes('iso_code'))).toBe(true);
  });
});

describe('generation', () => {
  const generationPlan = plan(snapshot, request(), 20260829);
  const dataset = generate(snapshot, generationPlan, 'dataset-a', 'test-key', 60);

  const rowsOf = (table: string) => dataset.rowsByTable.get(`public.${table}`) ?? [];

  it('produces rows for every table', () => {
    expect(dataset.rowsByTable.size).toBe(22);
    for (const [table, rows] of dataset.rowsByTable) {
      expect(rows.length, table).toBeGreaterThan(0);
    }
  });

  it('resolves every foreign key to a parent that was actually generated', () => {
    for (const tablePlan of generationPlan.tables) {
      const id = qualified(tablePlan.table);
      const table = snapshot.tables.find((t) => qualified(t.ref) === id)!;

      for (const fk of table.foreignKeys) {
        const columnPlan = tablePlan.columns.find((c) => c.column === fk.childColumns[0]);
        if (columnPlan?.role === 'DEFERRED_FOREIGN_KEY' || columnPlan?.role === 'DANGLING_FOREIGN_KEY') {
          continue;
        }
        const parentRows = dataset.rowsByTable.get(qualified(fk.parent)) ?? [];
        const parentKeys = new Set(parentRows.map((row) => fk.parentColumns.map((c) => row[c]).join('|')));

        for (const row of dataset.rowsByTable.get(id) ?? []) {
          const key = fk.childColumns.map((c) => row[c]);
          if (key.some((value) => value === null || value === undefined)) {
            continue;
          }
          expect(parentKeys, `${id}.${fk.childColumns.join(',')} -> ${qualified(fk.parent)}`)
            .toContain(key.join('|'));
        }
      }
    }
  });

  it('seeds the cycle-breaking column as NULL', () => {
    for (const row of rowsOf('customer')) {
      expect(row['primary_order_id']).toBeNull();
    }
  });

  it('points self-references only at earlier rows', () => {
    const seen = new Set<unknown>();
    for (const row of rowsOf('employee')) {
      if (row['manager_id'] !== null) {
        expect(seen).toContain(row['manager_id']);
      }
      seen.add(row['id']);
    }
  });

  it('produces an identical dataset from the same seed', () => {
    const again = generate(snapshot, plan(snapshot, request(), 20260829), 'dataset-a', 'test-key', 60);
    expect([...again.rowsByTable.get('public.customer')!]).toEqual([...rowsOf('customer')]);
  });

  it('produces a different dataset from a different seed', () => {
    const other = generate(snapshot, plan(snapshot, request(), 999), 'dataset-a', 'test-key', 60);
    expect(other.rowsByTable.get('public.customer')).not.toEqual(rowsOf('customer'));
  });

  it('scopes masking to the dataset, so two datasets cannot be correlated', () => {
    const other = generate(snapshot, generationPlan, 'dataset-b', 'test-key', 60);
    expect(other.rowsByTable.get('public.customer')).not.toEqual(rowsOf('customer'));
  });

  it('keeps primary keys unique', () => {
    for (const [table, rows] of dataset.rowsByTable) {
      const meta = snapshot.tables.find((t) => qualified(t.ref) === table)!;
      if (!meta.primaryKey) {
        continue;
      }
      const keys = rows.map((row) => meta.primaryKey!.columns.map((c) => row[c]).join('|'));
      expect(new Set(keys).size, table).toBe(keys.length);
    }
  });

  it('keeps single-column unique constraints distinct', () => {
    for (const [table, rows] of dataset.rowsByTable) {
      const meta = snapshot.tables.find((t) => qualified(t.ref) === table)!;
      for (const unique of meta.uniques ?? []) {
        if (unique.columns.length !== 1) {
          continue;
        }
        const values = rows.map((row) => row[unique.columns[0]]).filter((v) => v !== null);
        expect(new Set(values).size, `${table}.${unique.columns[0]}`).toBe(values.length);
      }
    }
  });

  it('never leaves a NOT NULL column empty', () => {
    for (const [table, rows] of dataset.rowsByTable) {
      const meta = snapshot.tables.find((t) => qualified(t.ref) === table)!;
      for (const column of meta.columns) {
        if (column.nullable || column.generated) {
          continue;
        }
        for (const row of rows) {
          expect(row[column.name], `${table}.${column.name}`).not.toBeNull();
        }
      }
    }
  });

  it('respects the rating check constraint', () => {
    for (const row of rowsOf('review')) {
      expect(row['rating']).toBeGreaterThanOrEqual(1);
      expect(row['rating']).toBeLessThanOrEqual(5);
    }
  });

  it('masks emails to reserved domains that cannot receive mail', () => {
    for (const row of rowsOf('customer')) {
      expect(String(row['email'])).toMatch(/@[a-z.]*example\.(com|net|org)$/);
    }
  });

  it('masks national ids into the reserved 900-999 range', () => {
    for (const row of rowsOf('customer')) {
      const value = row['national_id'];
      if (value !== null) {
        expect(Number(String(value).slice(0, 3))).toBeGreaterThanOrEqual(900);
      }
    }
  });

  it('produces Luhn-valid card numbers', () => {
    const cards = rowsOf('payment')
      .map((row) => row['card_number'])
      .filter((value): value is string => typeof value === 'string');

    expect(cards.length).toBeGreaterThan(0);
    for (const card of cards) {
      expect(isLuhnValid(card), card).toBe(true);
    }
  });

  it('masks IP addresses into documentation ranges rather than invalid octets', () => {
    for (const row of rowsOf('audit_log')) {
      const ip = row['client_ip'];
      if (typeof ip === 'string') {
        expect(ip).toMatch(/^(192\.0\.2|198\.51\.100|203\.0\.113)\.\d{1,3}$/);
        for (const octet of ip.split('.')) {
          expect(Number(octet)).toBeLessThanOrEqual(255);
        }
      }
    }
  });
});

describe('masking strategies', () => {
  const engine = new MaskingEngine('demo-key');
  const context = { datasetSalt: 'ds' };

  it('produces a Luhn-valid card of the same length', () => {
    const masked = engine.maskText('CREDIT_CARD', '4111111111111111', context);
    expect(masked).toHaveLength(16);
    expect(isLuhnValid(masked)).toBe(true);
    expect(masked).not.toBe('4111111111111111');
  });

  it('produces a checksum-valid IBAN in the same country', () => {
    const masked = engine.maskText('IBAN', 'GB82WEST12345698765432', context);
    expect(masked.startsWith('GB')).toBe(true);
    expect(ibanCheckDigits(masked.slice(0, 2), masked.slice(4))).toBe(masked.slice(2, 4));
  });

  it('keeps a phone number the same shape', () => {
    const masked = engine.maskText('PHONE', '+1 (555) 867-5309', context);
    expect(masked).toMatch(/^\+\d \(\d{3}\) \d{3}-\d{4}$/);
    expect(masked).not.toBe('+1 (555) 867-5309');
  });

  it('masks an IP address to a valid one rather than substituting digits', () => {
    const masked = engine.maskText('PARTIAL', '198.51.100.42', context);
    expect(masked).toMatch(/^(192\.0\.2|198\.51\.100|203\.0\.113)\.\d{1,3}$/);
  });

  it('shifts every date in a row by the same offset, so intervals survive', () => {
    const row = { datasetSalt: 'ds', rowKey: 'customer:42' };
    const signup = engine.mask('DATE_SHIFT', '2026-01-10', row) as string;
    const firstOrder = engine.mask('DATE_SHIFT', '2026-01-13', row) as string;

    const days = (Date.parse(firstOrder) - Date.parse(signup)) / 86_400_000;
    expect(days).toBe(3);
    expect(signup).not.toBe('2026-01-10');
  });

  it('keeps distinct emails distinct across many values', () => {
    const masked = new Set<string>();
    for (let i = 0; i < 2000; i++) {
      masked.add(engine.maskText('EMAIL', `user${i}@corp.example`, context));
    }
    expect(masked.size).toBe(2000);
  });
});
