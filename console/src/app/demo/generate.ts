import {
  ColumnMeta,
  ColumnPlan,
  ColumnRole,
  DatasetRequestInput,
  GenerationPlan,
  MaskStrategy,
  SchemaSnapshot,
  TableMeta,
  TablePlan,
} from '../core/api.types';
import { CORPORA, pick } from './corpora';
import { DependencyGraph, qualified } from './graph';
import { MaskingEngine } from './mask';
import { Rng, cellSeed, tableSeed } from './random';
import { luhnCheckDigit, ibanCheckDigits } from './mask';

/**
 * Planning and row generation, ported from the service.
 *
 * The two properties this has to preserve are the ones the whole platform rests
 * on: every foreign key resolves to a parent that was actually generated, and
 * the same seed produces the same dataset.
 */

/** Classes the service marks sensitive, and which therefore mask by default. */
const SENSITIVE = new Set([
  'GIVEN_NAME', 'FAMILY_NAME', 'FULL_NAME', 'USERNAME', 'EMAIL', 'PHONE', 'SSN',
  'NATIONAL_ID', 'CREDIT_CARD', 'IBAN', 'DATE_OF_BIRTH', 'STREET_ADDRESS', 'CITY',
  'REGION', 'POSTAL_CODE', 'LATITUDE', 'LONGITUDE', 'IP_ADDRESS', 'MAC_ADDRESS',
  'PASSWORD_HASH', 'API_TOKEN',
]);

const EPOCH = Date.UTC(2026, 0, 1);
const RADIX = 36;

export function defaultStrategyFor(dataClass: string): MaskStrategy {
  switch (dataClass) {
    case 'EMAIL': return 'EMAIL';
    case 'PHONE': return 'PHONE';
    case 'GIVEN_NAME':
    case 'FAMILY_NAME':
    case 'FULL_NAME': return 'NAME';
    case 'SSN':
    case 'NATIONAL_ID': return 'SSN';
    case 'CREDIT_CARD': return 'CREDIT_CARD';
    case 'IBAN': return 'IBAN';
    case 'DATE_OF_BIRTH': return 'DATE_SHIFT';
    case 'LATITUDE':
    case 'LONGITUDE': return 'NUMERIC_JITTER';
    case 'PASSWORD_HASH':
    case 'API_TOKEN': return 'HASH';
    case 'USERNAME': return 'TOKENIZE';
    case 'STREET_ADDRESS':
    case 'CITY':
    case 'REGION':
    case 'POSTAL_CODE':
    case 'IP_ADDRESS':
    case 'MAC_ADDRESS': return 'PARTIAL';
    default: return 'HASH';
  }
}

function baseType(udtName: string | null): string {
  if (!udtName) {
    return '';
  }
  const lower = udtName.toLowerCase();
  return lower.startsWith('_') ? lower.slice(1) : lower;
}

function isTextual(column: ColumnMeta): boolean {
  return ['text', 'varchar', 'bpchar', 'char', 'citext', 'name'].includes(baseType(column.udtName));
}

// --------------------------------------------------------------- check bounds

interface Bounds {
  min?: number;
  max?: number;
  allowed?: string[];
}

/**
 * The two check-constraint shapes a generator can act on: range comparisons,
 * which is how PostgreSQL normalises BETWEEN, and ANY(ARRAY[...]) membership.
 * Anything else is ignored rather than guessed at.
 */
export function interpretChecks(table: TableMeta): Map<string, Bounds> {
  const bounds = new Map<string, Bounds>();

  for (const check of table.checks ?? []) {
    const expression = check.expression ?? '';

    const membership = /"?(\w+)"?\s*=\s*ANY\s*\(\s*ARRAY\s*\[(.*?)]/gs;
    let match: RegExpExecArray | null;
    while ((match = membership.exec(expression)) !== null) {
      const literals = [...match[2].matchAll(/'((?:[^']|'')*)'/g)].map((m) => m[1].replace(/''/g, "'"));
      if (literals.length > 0) {
        bounds.set(match[1], { ...bounds.get(match[1]), allowed: literals });
      }
    }

    const comparison = /"?(\w+)"?\s*(>=|<=|>|<)\s*\(?\s*'?(-?\d+(?:\.\d+)?)'?\s*\)?/g;
    while ((match = comparison.exec(expression)) !== null) {
      const [, column, operator, raw] = match;
      const operand = Number(raw);
      const current = bounds.get(column) ?? {};

      if (operator === '>=' || operator === '>') {
        const candidate = operator === '>' ? operand + 1 : operand;
        current.min = current.min === undefined ? candidate : Math.max(current.min, candidate);
      } else {
        const candidate = operator === '<' ? operand - 1 : operand;
        current.max = current.max === undefined ? candidate : Math.min(current.max, candidate);
      }
      bounds.set(column, current);
    }
  }
  return bounds;
}

// ----------------------------------------------------------------- generators

type Generator = (rng: Rng, row: number) => unknown;

function truncate(value: unknown, maxLength: number | null): unknown {
  return typeof value === 'string' && maxLength && value.length > maxLength
    ? value.slice(0, maxLength)
    : value;
}

/**
 * A value guaranteed distinct per row and guaranteed to fit. Below a readable
 * width a unique value cannot carry both meaning and distinctness, so it
 * becomes a base-36 encoding of the row index and nothing else.
 */
function uniqueText(maxLength: number | null): Generator {
  const length = maxLength ?? 32;
  if (length >= 12) {
    return (rng, row) => {
      const suffix = row.toString(RADIX);
      const room = length - suffix.length - 1;
      const word = pick(CORPORA.lorem, rng.int(1 << 20));
      return `${word.length > room ? word.slice(0, room) : word}-${suffix}`;
    };
  }
  return (_rng, row) => {
    const encoded = row.toString(RADIX);
    return encoded.length >= length
      ? encoded.slice(-length)
      : '0'.repeat(length - encoded.length) + encoded;
  };
}

function moneyGenerator(scale: number | null): Generator {
  const digits = Math.min(scale ?? 2, 4);
  return (rng) => {
    const units = rng.next() < 0.9 ? rng.between(100, 50_000) : rng.between(50_000, 5_000_000);
    return (units / 100).toFixed(digits);
  };
}

function dateGenerator(daysBack: number, daysForward: number, withTime: boolean): Generator {
  return (rng) => {
    const shifted = EPOCH + (rng.between(-daysBack, daysForward)) * 86_400_000;
    const date = new Date(withTime ? shifted + rng.int(86_400) * 1000 : shifted);
    return withTime ? date.toISOString() : date.toISOString().slice(0, 10);
  };
}

/** Chooses the generator: semantics first, SQL type second. */
export function resolveGenerator(table: TableMeta, column: ColumnMeta): Generator {
  const unique = isUniqueColumn(table, column.name);
  const length = column.maxLength;
  const bounds = interpretChecks(table).get(column.name);

  // A CHECK constraint is the more specific fact and the one a database would
  // enforce, so it overrides the semantic guess.
  if (bounds?.allowed && isTextual(column)) {
    return (rng) => pick(bounds.allowed!, rng.int(1 << 20));
  }
  if (bounds && (bounds.min !== undefined || bounds.max !== undefined)) {
    const type = baseType(column.udtName);
    if (['int2', 'int4', 'int8'].includes(type)) {
      const min = bounds.min ?? 1;
      const max = bounds.max ?? Math.max(min + 1, 1000);
      return (rng) => rng.between(min, max);
    }
    if (['numeric', 'decimal', 'float4', 'float8'].includes(type)) {
      const min = bounds.min ?? 0;
      const max = bounds.max ?? min + 100_000;
      const digits = column.numericScale ?? 2;
      return (rng) => (min + rng.next() * (max - min)).toFixed(digits);
    }
  }

  if (column.enumLabels?.length) {
    return (rng) => pick(column.enumLabels, rng.int(1 << 20));
  }
  if (column.arrayElementType) {
    return (rng) => Array.from({ length: rng.int(4) }, () => pick(CORPORA.lorem, rng.int(1 << 20)));
  }

  // A unique textual column overrides semantics: every other text generator
  // draws from a fixed corpus or truncates, and either eventually collides.
  if (unique && isTextual(column) && column.dataClass !== 'EMAIL') {
    return uniqueText(length);
  }

  const semantic = semanticGenerator(column, unique, length);
  return semantic ?? typeGenerator(column, unique, length);
}

function semanticGenerator(column: ColumnMeta, unique: boolean, length: number | null): Generator | null {
  const wrap = (generator: Generator): Generator => (rng, row) => truncate(generator(rng, row), length);

  switch (column.dataClass) {
    case 'EMAIL':
      return (rng, row) => {
        const given = pick(CORPORA.givenNames, rng.int(1 << 20)).toLowerCase();
        const family = pick(CORPORA.familyNames, rng.int(1 << 20)).toLowerCase();
        const domain = pick(CORPORA.emailDomains, rng.int(1 << 20));
        const suffix = unique ? row.toString(RADIX) : rng.int(1_000_000).toString(RADIX);
        const candidate = `${given}.${family}${suffix}@${domain}`;
        if (!length || candidate.length <= length) {
          return candidate;
        }
        const room = length - suffix.length - 1 - domain.length;
        const name = (given + family).slice(0, Math.max(1, room));
        return `${name}${suffix}@${domain}`;
      };
    case 'GIVEN_NAME': return wrap((rng) => pick(CORPORA.givenNames, rng.int(1 << 20)));
    case 'FAMILY_NAME': return wrap((rng) => pick(CORPORA.familyNames, rng.int(1 << 20)));
    case 'FULL_NAME':
      return wrap((rng) => `${pick(CORPORA.givenNames, rng.int(1 << 20))} ${pick(CORPORA.familyNames, rng.int(1 << 20))}`);
    case 'USERNAME': return uniqueText(length);
    case 'PHONE':
      return wrap((rng) => `+1 (${rng.between(200, 899)}) ${rng.between(200, 899)}-${String(rng.int(10000)).padStart(4, '0')}`);
    case 'SSN':
    case 'NATIONAL_ID':
      return (rng) =>
        `${rng.between(900, 999)}-${String(rng.between(1, 99)).padStart(2, '0')}-${String(rng.between(1, 9999)).padStart(4, '0')}`;
    case 'CREDIT_CARD':
      return (rng) => {
        const payload = [4, ...Array.from({ length: 14 }, () => rng.int(10))];
        return payload.join('') + luhnCheckDigit(payload);
      };
    case 'IBAN':
      return (rng) => {
        const country = pick(CORPORA.countryCodes, rng.int(1 << 20));
        const bban = Array.from({ length: 18 }, () => rng.int(10)).join('');
        return country + ibanCheckDigits(country, bban) + bban;
      };
    case 'DATE_OF_BIRTH':
      return (rng) =>
        new Date(EPOCH - (18 * 365 + rng.int(62 * 365)) * 86_400_000).toISOString().slice(0, 10);
    case 'STREET_ADDRESS':
      return wrap((rng) => `${rng.between(1, 9999)} ${pick(CORPORA.streetNames, rng.int(1 << 20))} ${pick(CORPORA.streetTypes, rng.int(1 << 20))}`);
    case 'CITY': return wrap((rng) => pick(CORPORA.cities, rng.int(1 << 20)));
    case 'REGION': return wrap((rng) => pick(CORPORA.regions, rng.int(1 << 20)));
    case 'POSTAL_CODE': return wrap((rng) => String(rng.int(100000)).padStart(5, '0'));
    case 'COUNTRY':
      return wrap((rng) =>
        length && length <= 3 ? pick(CORPORA.countryCodes, rng.int(1 << 20)) : pick(CORPORA.countries, rng.int(1 << 20)));
    case 'LATITUDE': return (rng) => (rng.next() * 180 - 90).toFixed(6);
    case 'LONGITUDE': return (rng) => (rng.next() * 360 - 180).toFixed(6);
    case 'IP_ADDRESS':
      return (rng) => (rng.bool() ? `198.51.100.${rng.between(1, 254)}` : `203.0.113.${rng.between(1, 254)}`);
    case 'MAC_ADDRESS':
      return (rng) => `02:${Array.from({ length: 5 }, () => rng.int(256).toString(16).padStart(2, '0')).join(':')}`;
    case 'URL':
      return wrap((rng) => `https://${pick(CORPORA.emailDomains, rng.int(1 << 20))}/${pick(CORPORA.lorem, rng.int(1 << 20))}`);
    case 'PASSWORD_HASH':
      return wrap((rng) => '$2b$12$' + Array.from({ length: 40 }, () => rng.int(16).toString(16)).join(''));
    case 'API_TOKEN':
      return wrap((rng) => 'tfk_' + Array.from({ length: 32 }, () => rng.int(16).toString(16)).join(''));
    case 'COMPANY': return wrap((rng) => pick(CORPORA.companies, rng.int(1 << 20)));
    case 'JOB_TITLE': return wrap((rng) => pick(CORPORA.jobTitles, rng.int(1 << 20)));
    case 'DEPARTMENT': return wrap((rng) => pick(CORPORA.departments, rng.int(1 << 20)));
    case 'PRODUCT_NAME': return wrap((rng) => pick(CORPORA.products, rng.int(1 << 20)));
    case 'CURRENCY_CODE': return wrap((rng) => pick(CORPORA.currencies, rng.int(1 << 20)));
    case 'MONETARY_AMOUNT': return moneyGenerator(column.numericScale);
    case 'QUANTITY': return (rng) => rng.between(1, 500);
    case 'PERCENTAGE': return (rng) => (rng.next() * 100).toFixed(2);
    case 'STATUS_FLAG': return wrap((rng) => pick(CORPORA.statuses, rng.int(1 << 20)));
    case 'BOOLEAN_FLAG': return (rng) => rng.chance(30);
    case 'CREATED_TIMESTAMP': return dateGenerator(365, 0, baseType(column.udtName) !== 'date');
    case 'UPDATED_TIMESTAMP': return dateGenerator(180, 0, baseType(column.udtName) !== 'date');
    case 'TIMESTAMP': return dateGenerator(365, 90, true);
    case 'DATE': return dateGenerator(365, 90, false);
    case 'SLUG': return unique ? uniqueText(length) : wrap((rng) => `${pick(CORPORA.lorem, rng.int(1 << 20))}-${pick(CORPORA.lorem, rng.int(1 << 20))}`);
    case 'TITLE':
      return wrap((rng) => {
        const word = pick(CORPORA.lorem, rng.int(1 << 20));
        return word[0].toUpperCase() + word.slice(1) + ' ' + pick(CORPORA.lorem, rng.int(1 << 20));
      });
    case 'FREE_TEXT':
      return wrap((rng) => {
        const words = Array.from({ length: rng.between(8, 20) }, () => pick(CORPORA.lorem, rng.int(1 << 20)));
        return words.join(' ').replace(/^./, (c) => c.toUpperCase()) + '.';
      });
    case 'JSON_DOCUMENT':
      return (rng, row) => JSON.stringify({ source: 'testforge', row, score: rng.int(100) });
    default:
      return null;
  }
}

function typeGenerator(column: ColumnMeta, unique: boolean, length: number | null): Generator {
  switch (baseType(column.udtName)) {
    case 'bool': return (rng) => rng.bool();
    case 'int2': return (rng, row) => (unique ? row + 1 : rng.between(1, 30_000));
    case 'int4': return (rng, row) => (unique ? row + 1 : rng.between(1, 1_000_000));
    case 'int8': return (rng, row) => (unique ? row + 1 : rng.between(1, 1_000_000_000));
    case 'numeric':
    case 'decimal':
    case 'float4':
    case 'float8':
    case 'money': return moneyGenerator(column.numericScale);
    case 'uuid':
      return (rng) =>
        'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
          const value = rng.int(16);
          return (c === 'x' ? value : (value & 0x3) | 0x8).toString(16);
        });
    case 'date': return dateGenerator(365, 90, false);
    case 'timestamp':
    case 'timestamptz': return dateGenerator(365, 90, true);
    case 'time':
    case 'timetz':
      return (rng) => `${String(rng.int(24)).padStart(2, '0')}:${String(rng.int(60)).padStart(2, '0')}:00`;
    case 'interval': return (rng) => `${rng.between(1, 90)} days`;
    case 'json':
    case 'jsonb': return (rng, row) => JSON.stringify({ source: 'testforge', row, score: rng.int(100) });
    case 'inet':
    case 'cidr': return (rng) => `198.51.100.${rng.between(1, 254)}`;
    default:
      return unique
        ? uniqueText(length)
        : (rng) => truncate(`${pick(CORPORA.lorem, rng.int(1 << 20))}-${rng.int(100_000)}`, length);
  }
}

function isUniqueColumn(table: TableMeta, column: string): boolean {
  if (table.primaryKey?.columns.length === 1 && table.primaryKey.columns[0] === column) {
    return true;
  }
  return (table.uniques ?? []).some((u) => u.columns.length === 1 && u.columns[0] === column);
}

// -------------------------------------------------------------------- planner

export function plan(
  snapshot: SchemaSnapshot,
  request: DatasetRequestInput,
  seed: number,
): GenerationPlan {
  const warnings: string[] = [];
  const all = new Set(snapshot.tables.map((t) => qualified(t.ref)));

  let requested = new Set(all);
  if (request.includeTables?.length) {
    requested = new Set(
      request.includeTables.map((name) => (name.includes('.') ? name : `${snapshot.schema}.${name}`)),
    );
  }

  const closure = DependencyGraph.requiredClosure(snapshot, requested);
  const added = [...closure].filter((id) => !requested.has(id));
  if (added.length > 0) {
    warnings.push(
      `Added ${added.length} table(s) the selection depends on through NOT NULL foreign keys: ` +
        `${added.join(', ')}. Without them the requested rows cannot be inserted.`,
    );
  }

  const graph = new DependencyGraph(snapshot, closure);
  const order = graph.order();
  warnings.push(...order.notes);

  const scale = request.scale ?? 100;
  const counts = new Map<string, number>();
  const byId = new Map(snapshot.tables.map((t) => [qualified(t.ref), t]));

  for (const id of order.order) {
    const table = byId.get(id)!;
    const parents = graph.parents.get(id) ?? new Set();

    let rows: number;
    if (parents.size === 0) {
      rows = scale;
    } else {
      const largest = Math.max(...[...parents].map((p) => counts.get(p) ?? scale));
      const fanout = 1 + (tableSeed(seed, id) % 3);
      rows = Math.max(1, largest * fanout);
    }
    rows = capToUniqueCapacity(table, rows, warnings);
    counts.set(id, Math.min(rows, 200_000));
  }

  const tables: TablePlan[] = [];
  let totalRows = 0;
  let maskedColumns = 0;

  order.order.forEach((id, position) => {
    const table = byId.get(id)!;
    const rows = counts.get(id) ?? scale;
    const columns = planColumns(table, order, request, warnings);

    maskedColumns += columns.filter((c) => c.mask !== 'PRESERVE').length;
    totalRows += rows;

    tables.push({
      table: table.ref,
      order: position,
      depth: order.depth.get(id) ?? 0,
      rowCount: rows,
      columns,
      deferredEdges: table.foreignKeys.filter((fk) =>
        order.deferred.some((d) => d.name === fk.name && qualified(d.child) === id),
      ),
      selfEdges: table.foreignKeys.filter((fk) => qualified(fk.parent) === id),
    });
  });

  return {
    seed,
    schema: snapshot.schema,
    snapshotFingerprint: snapshot.fingerprint,
    tables,
    totalRows,
    maskedColumns,
    warnings,
  };
}

/** A narrow unique column caps the table at what it can actually hold. */
function capToUniqueCapacity(table: TableMeta, requested: number, warnings: string[]): number {
  let capacity = Number.MAX_SAFE_INTEGER;
  let limiting = '';

  for (const unique of table.uniques ?? []) {
    if (unique.columns.length !== 1) {
      continue;
    }
    const column = table.columns.find((c) => c.name === unique.columns[0]);
    if (!column) {
      continue;
    }
    const type = baseType(column.udtName);
    let columnCapacity = Number.MAX_SAFE_INTEGER;
    if (type === 'int2') {
      columnCapacity = 32_767;
    } else if (['varchar', 'bpchar', 'char'].includes(type) && column.maxLength && column.maxLength < 13) {
      columnCapacity = Math.pow(RADIX, column.maxLength);
    }
    if (columnCapacity < capacity) {
      capacity = columnCapacity;
      limiting = column.name;
    }
  }

  if (capacity < requested) {
    warnings.push(
      `Capped ${qualified(table.ref)} at ${capacity} rows: its unique column ${limiting} ` +
        'cannot hold more distinct values than that.',
    );
    return capacity;
  }
  return requested;
}

function planColumns(
  table: TableMeta,
  order: ReturnType<DependencyGraph['order']>,
  request: DatasetRequestInput,
  warnings: string[],
): ColumnPlan[] {
  const id = qualified(table.ref);
  const maskByDefault = request.maskSensitiveByDefault !== false;

  return table.columns.map((column) => {
    const fk = table.foreignKeys.find((f) => f.childColumns.includes(column.name));
    let role: ColumnRole = 'VALUE';

    if (column.generated) {
      role = 'DATABASE_GENERATED';
    } else if (fk) {
      if (qualified(fk.parent) === id) {
        role = 'SELF_REFERENCE';
      } else if (order.deferred.some((d) => d.name === fk.name)) {
        role = 'DEFERRED_FOREIGN_KEY';
      } else if (order.dangling.some((d) => d.name === fk.name)) {
        role = 'DANGLING_FOREIGN_KEY';
      } else {
        role = 'FOREIGN_KEY';
      }
    } else if (table.primaryKey?.columns.includes(column.name)) {
      role = 'PRIMARY_KEY';
    }

    let mask: MaskStrategy = 'PRESERVE';
    let maskSource = 'not sensitive';

    const rule = (request.maskingRules ?? []).find(
      (r) => globMatches(r.table, id) && globMatches(r.column, column.name),
    );
    if (rule) {
      mask = rule.strategy;
      maskSource = `rule ${rule.table}.${rule.column}`;
    } else if (maskByDefault && SENSITIVE.has(column.dataClass)) {
      mask = defaultStrategyFor(column.dataClass);
      maskSource = `sensitive class ${column.dataClass}`;
    }

    // Masking a key would break the referential integrity the platform exists
    // to guarantee.
    if (mask !== 'PRESERVE' && (role === 'PRIMARY_KEY' || role === 'FOREIGN_KEY')) {
      warnings.push(
        `Ignoring masking on ${id}.${column.name}: it is a key column, and masking it would break referential integrity.`,
      );
      mask = 'PRESERVE';
      maskSource = 'key column';
    }

    return {
      column: column.name,
      sqlType: column.formattedType,
      dataClass: column.dataClass,
      role,
      generator: column.enumLabels?.length
        ? `enum(${column.enumLabels.length} labels)`
        : column.dataClass.toLowerCase(),
      mask,
      maskSource,
      unique: isUniqueColumn(table, column.name),
      nullable: column.nullable,
      parentTable: fk ? qualified(fk.parent) : null,
      parentColumn: fk ? fk.parentColumns[fk.childColumns.indexOf(column.name)] : null,
    };
  });
}

function globMatches(pattern: string | undefined, value: string): boolean {
  if (!pattern || pattern === '*') {
    return true;
  }
  const lower = pattern.toLowerCase();
  const target = value.toLowerCase();
  if (!lower.includes('*')) {
    return target === lower || target.endsWith('.' + lower);
  }
  const regex = new RegExp('^' + lower.split('*').map(escapeRegex).join('.*') + '$');
  return regex.test(target);
}

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// ------------------------------------------------------------- row generation

export interface GeneratedDataset {
  rowsByTable: Map<string, Record<string, unknown>[]>;
  totalRows: number;
  elapsedMs: number;
}

/**
 * Generates the dataset.
 *
 * Tables are generated in the plan's order, and each registers its keys so
 * children draw from what exists rather than inventing a reference. That is
 * where referential consistency comes from - it is a property of the order, not
 * something checked afterwards.
 *
 * @param sampleLimit rows actually materialised per table; the plan's counts can
 *                    run to hundreds of thousands, which no browser should try
 *                    to hold in an array to render twenty of them.
 */
export function generate(
  snapshot: SchemaSnapshot,
  generationPlan: GenerationPlan,
  datasetId: string,
  maskingKey: string,
  sampleLimit = 500,
): GeneratedDataset {
  const started = performance.now();
  const masking = new MaskingEngine(maskingKey);
  const byId = new Map(snapshot.tables.map((t) => [qualified(t.ref), t]));
  const rowsByTable = new Map<string, Record<string, unknown>[]>();
  const keyPool = new Map<string, unknown[][]>();
  let totalRows = 0;

  for (const tablePlan of generationPlan.tables) {
    const id = qualified(tablePlan.table);
    const table = byId.get(id)!;
    const produce = Math.min(tablePlan.rowCount, sampleLimit);
    const rows: Record<string, unknown>[] = [];
    const generators = new Map<string, Generator>();
    const claimed = new Set<string>();

    for (const columnPlan of tablePlan.columns) {
      const column = table.columns.find((c) => c.name === columnPlan.column);
      if (column && (columnPlan.role === 'VALUE' || columnPlan.role === 'PRIMARY_KEY')) {
        generators.set(column.name, resolveGenerator(table, column));
      }
    }

    for (let rowIndex = 0; rowIndex < produce; rowIndex++) {
      const row: Record<string, unknown> = {};

      // Foreign keys first: a composite key made of them needs its parent
      // values in place before the primary key can be assembled.
      for (const fk of table.foreignKeys) {
        const parentId = qualified(fk.parent);
        const columnPlan = tablePlan.columns.find((c) => c.column === fk.childColumns[0]);
        const role = columnPlan?.role;

        if (role === 'DEFERRED_FOREIGN_KEY' || role === 'DANGLING_FOREIGN_KEY') {
          fk.childColumns.forEach((name) => (row[name] = null));
          continue;
        }

        const rng = new Rng(cellSeed(generationPlan.seed, id, `fk:${fk.name}`, rowIndex));
        const pool = keyPool.get(`${parentId}|${fk.parentColumns.join(',')}`) ?? [];
        let parentKey: unknown[] | null = null;

        if (parentId === id) {
          // A self-reference points at an already-generated row, or nothing.
          parentKey = rowIndex > 0 && !rng.chance(20) ? pool[rng.int(Math.min(rowIndex, pool.length))] ?? null : null;
        } else if (pool.length > 0) {
          parentKey = pool[rng.int(pool.length)];
        }

        fk.childColumns.forEach((name, i) => (row[name] = parentKey ? parentKey[i] : null));
      }

      for (const columnPlan of tablePlan.columns) {
        const name = columnPlan.column;
        if (name in row || columnPlan.role === 'DATABASE_GENERATED') {
          continue;
        }
        const column = table.columns.find((c) => c.name === name)!;
        const rng = new Rng(cellSeed(generationPlan.seed, id, name, rowIndex));

        let value: unknown;
        if (columnPlan.role === 'PRIMARY_KEY') {
          value = ['int2', 'int4', 'int8'].includes(baseType(column.udtName))
            ? rowIndex + 1
            : generators.get(name)?.(rng, rowIndex) ?? rowIndex + 1;
        } else {
          const generator = generators.get(name);
          value = generator ? generator(rng, rowIndex) : null;
          // Nullable non-unique columns get some NULLs, so a seeded dataset
          // exercises the null paths a fully populated one never would.
          if (value !== null && column.nullable && !columnPlan.unique && rng.chance(12)) {
            value = null;
          }
        }

        if (value !== null && columnPlan.mask !== 'PRESERVE') {
          value = masking.mask(columnPlan.mask, value, {
            datasetSalt: datasetId,
            rowKey: `${id}#${rowIndex}`,
          });
        }
        row[name] = value;
      }

      // Uniqueness across the whole primary key, not just single columns.
      if (table.primaryKey) {
        const key = table.primaryKey.columns.map((c) => row[c]).join(' ');
        if (claimed.has(key)) {
          continue;
        }
        claimed.add(key);
      }

      rows.push(row);

      // Register this row's keys for any child that references them.
      for (const parentColumns of referencedColumnSets(snapshot, id)) {
        const values = parentColumns.map((c) => row[c]);
        if (values.every((v) => v !== null && v !== undefined)) {
          const poolKey = `${id}|${parentColumns.join(',')}`;
          if (!keyPool.has(poolKey)) {
            keyPool.set(poolKey, []);
          }
          keyPool.get(poolKey)!.push(values);
        }
      }
    }

    rowsByTable.set(id, rows);
    totalRows += tablePlan.rowCount;
  }

  return { rowsByTable, totalRows, elapsedMs: performance.now() - started };
}

const referencedCache = new WeakMap<SchemaSnapshot, Map<string, string[][]>>();

/** Which column sets of a table some child actually references. */
function referencedColumnSets(snapshot: SchemaSnapshot, tableId: string): string[][] {
  let cache = referencedCache.get(snapshot);
  if (!cache) {
    cache = new Map();
    for (const table of snapshot.tables) {
      for (const fk of table.foreignKeys) {
        const parent = qualified(fk.parent);
        const existing = cache.get(parent) ?? [];
        if (!existing.some((set) => set.join(',') === fk.parentColumns.join(','))) {
          existing.push(fk.parentColumns);
        }
        cache.set(parent, existing);
      }
    }
    referencedCache.set(snapshot, cache);
  }
  return cache.get(tableId) ?? [];
}
