import { Injectable } from '@angular/core';
import { Observable, defer, delay, from, map, of, switchMap, throwError } from 'rxjs';
import {
  Dataset,
  DatasetRequestInput,
  DatasetStats,
  DatasetSummary,
  GenerationPlan,
  Job,
  JobPhase,
  Lease,
  MaskingRecord,
  SchemaGraph,
  SchemaSnapshot,
  Target,
} from '../core/api.types';
import { ApiError, SampleRows, TestForgeService } from '../core/testforge.service';
import { DependencyGraph, qualified } from './graph';
import { GeneratedDataset, generate, plan } from './generate';

/**
 * The whole platform, running in the browser.
 *
 * The hosted demo has no backend to talk to, so this runs the same algorithms
 * the service does — foreign-key ordering with cycle breaking, planning,
 * deterministic masking, referentially consistent generation — against a schema
 * fixture produced by the real introspector.
 *
 * Two things are necessarily simulated, because they need a database:
 * provisioning and seeding. Rows are generated for real and shown, but nothing
 * is written anywhere, and the connection strings are decorative. Everything
 * upstream of that — the graph, the plan, the masking decisions, the values
 * themselves — is genuinely computed.
 */

/** Phases the simulated pipeline walks through, with how long each appears to take. */
const PIPELINE: { phase: JobPhase; message: string; ms: number }[] = [
  { phase: 'INTROSPECTING', message: 'Reading the target schema', ms: 450 },
  { phase: 'PLANNING', message: 'Ordering the foreign-key graph', ms: 400 },
  { phase: 'PROVISIONING', message: 'Creating the ephemeral database', ms: 700 },
  { phase: 'APPLYING_DDL', message: 'Applying the schema', ms: 500 },
  { phase: 'GENERATING', message: 'Generating rows', ms: 900 },
  { phase: 'SEEDING', message: 'Seeding rows', ms: 1100 },
  { phase: 'VERIFYING', message: 'Verifying referential integrity', ms: 400 },
  { phase: 'LEASING', message: 'Issuing the lease', ms: 300 },
];

const DEMO_TARGET: Target = {
  id: 'demo-commerce',
  displayName: 'Demo Commerce (22 tables)',
  schema: 'public',
  jdbcUrl: 'jdbc:postgresql://demo.internal:5432/testforge_demo',
};

/** Rows materialised per table. The plan's counts are honest; holding them all is not the point. */
const SAMPLE_LIMIT = 200;

const MASKING_KEY = 'testforge-browser-demo-key';

interface DemoRecord {
  dataset: Dataset;
  plan: GenerationPlan;
  generated: GeneratedDataset;
  lease: Lease | null;
  credential: string | null;
  masking: MaskingRecord[];
}

@Injectable()
export class DemoBackend extends TestForgeService {
  readonly isDemo = true;

  private snapshot: SchemaSnapshot | null = null;
  private readonly datasetsById = new Map<string, DemoRecord>();
  private readonly jobsById = new Map<string, Job>();
  private timers: ReturnType<typeof setTimeout>[] = [];

  // ------------------------------------------------------------- schema

  /**
   * The fixture is 135 KB of introspected metadata, so it is fetched on first
   * use rather than bundled into the initial chunk.
   */
  private loadSnapshot(): Observable<SchemaSnapshot> {
    if (this.snapshot) {
      return of(this.snapshot);
    }
    return from(import('./demo-schema.json')).pipe(
      map((module) => {
        this.snapshot = (module.default ?? module) as unknown as SchemaSnapshot;
        return this.snapshot;
      }),
    );
  }

  listTargets(): Observable<Target[]> {
    return of([DEMO_TARGET]).pipe(delay(80));
  }

  schema(): Observable<SchemaSnapshot> {
    return this.loadSnapshot().pipe(delay(120));
  }

  introspect(): Observable<SchemaSnapshot> {
    // An unchanged schema keeps its fingerprint, which is the point of the
    // fingerprint, so re-introspecting returns the identical snapshot.
    return this.loadSnapshot().pipe(delay(600));
  }

  targetHealth(): Observable<{ id: string; displayName: string; reachable: boolean }> {
    return of({ id: DEMO_TARGET.id, displayName: DEMO_TARGET.displayName, reachable: true });
  }

  graph(): Observable<SchemaGraph> {
    return this.loadSnapshot().pipe(
      map((snapshot) => {
        const graph = new DependencyGraph(snapshot);
        const order = graph.order();

        const levels: Record<string, string[]> = {};
        for (const [table, depth] of order.depth) {
          (levels[String(depth)] ??= []).push(table);
        }

        return {
          schema: snapshot.schema,
          fingerprint: snapshot.fingerprint,
          nodes: order.order.map((id) => {
            const table = snapshot.tables.find((t) => qualified(t.ref) === id)!;
            return {
              id,
              name: table.ref.name,
              depth: order.depth.get(id) ?? 0,
              seedOrder: order.order.indexOf(id),
              columns: table.columns.length,
              foreignKeys: table.foreignKeys.length,
              referencedBy: graph.childCount(id),
              sensitiveColumns: table.columns.filter((c) => SENSITIVE_CLASSES.has(c.dataClass)).length,
            };
          }),
          edges: graph.edges.map((fk) => ({
            from: qualified(fk.child),
            to: qualified(fk.parent),
            constraint: fk.name,
            fromColumns: fk.childColumns,
            toColumns: fk.parentColumns,
            deferred: order.deferred.some((d) => d.name === fk.name),
          })),
          maxDepth: Math.max(0, ...order.depth.values()),
          levels,
          cycles: order.cycles,
          notes: order.notes,
        } satisfies SchemaGraph;
      }),
      delay(150),
    );
  }

  // ------------------------------------------------------------ datasets

  previewPlan(body: DatasetRequestInput): Observable<GenerationPlan> {
    return this.loadSnapshot().pipe(
      map((snapshot) => plan(snapshot, body, body.seed ?? randomSeed())),
      delay(350),
    );
  }

  requestDataset(body: DatasetRequestInput): Observable<Job> {
    return this.loadSnapshot().pipe(
      map((snapshot) => {
        const seed = body.seed ?? randomSeed();
        const datasetId = uuid();
        const jobId = uuid();
        const now = new Date();

        const generationPlan = plan(snapshot, body, seed);
        // Generated eagerly. It takes well under a second, and having the rows
        // in hand means the progress the console shows is reporting real work
        // rather than describing work that has not happened.
        const generated = generate(snapshot, generationPlan, datasetId, MASKING_KEY, SAMPLE_LIMIT);

        const dataset: Dataset = {
          id: datasetId,
          name: body.name,
          description: body.description ?? null,
          requestedBy: body.requestedBy,
          targetId: body.targetId,
          schema: snapshot.schema,
          snapshotId: null,
          seed,
          scale: body.scale ?? 100,
          plan: generationPlan,
          status: 'RUNNING',
          totalRows: generationPlan.totalRows,
          maskedColumns: generationPlan.maskedColumns,
          durationMs: null,
          error: null,
          snapshotUri: null,
          createdAt: now.toISOString(),
          completedAt: null,
        };

        const job: Job = {
          id: jobId,
          datasetId,
          requestedBy: body.requestedBy,
          status: 'PENDING',
          phase: 'QUEUED',
          phaseLabel: 'Queued',
          percent: 0,
          message: 'Job accepted',
          error: null,
          createdAt: now.toISOString(),
          startedAt: null,
          finishedAt: null,
          elapsedMs: 0,
          metrics: {},
          events: [{ phase: 'QUEUED', label: 'Queued', message: 'Job accepted', at: now.toISOString() }],
        };

        this.datasetsById.set(datasetId, {
          dataset,
          plan: generationPlan,
          generated,
          lease: null,
          credential: null,
          masking: maskingRecords(datasetId, generationPlan),
        });
        this.jobsById.set(jobId, job);
        this.runPipeline(jobId, datasetId, body);

        return job;
      }),
      delay(200),
    );
  }

  /** Walks the job through the pipeline on a timer, so progress is watchable. */
  private runPipeline(jobId: string, datasetId: string, body: DatasetRequestInput): void {
    const record = this.datasetsById.get(datasetId)!;
    const totalWeight = PIPELINE.reduce((sum, step) => sum + step.ms, 0);
    let elapsed = 0;

    PIPELINE.forEach((step) => {
      elapsed += step.ms;
      const at = elapsed;
      const percent = Math.round((at / totalWeight) * 96);

      this.timers.push(
        setTimeout(() => {
          const job = this.jobsById.get(jobId);
          if (!job) {
            return;
          }
          const now = new Date().toISOString();
          this.jobsById.set(jobId, {
            ...job,
            status: 'RUNNING',
            phase: step.phase,
            phaseLabel: step.message,
            percent,
            message: step.message,
            startedAt: job.startedAt ?? now,
            elapsedMs: at,
            metrics: metricsFor(step.phase, record),
            events: [...job.events, { phase: step.phase, label: step.message, message: step.message, at: now }],
          });
        }, at),
      );
    });

    this.timers.push(
      setTimeout(() => {
        const job = this.jobsById.get(jobId);
        if (!job) {
          return;
        }
        const now = new Date();
        const lease = makeLease(datasetId, body, record.dataset.name);

        record.lease = lease;
        record.credential = `${lease.jdbcUrl}?user=${lease.username}&password=${randomPassword()}`;
        record.dataset = {
          ...record.dataset,
          status: 'SUCCEEDED',
          durationMs: totalWeight,
          completedAt: now.toISOString(),
        };

        this.jobsById.set(jobId, {
          ...job,
          status: 'SUCCEEDED',
          phase: 'DONE',
          phaseLabel: 'Complete',
          percent: 100,
          message: `Seeded ${record.plan.totalRows.toLocaleString()} rows across ${record.plan.tables.length} tables`,
          finishedAt: now.toISOString(),
          elapsedMs: totalWeight,
          metrics: metricsFor('DONE', record),
          events: [
            ...job.events,
            { phase: 'DONE', label: 'Complete', message: 'Dataset ready', at: now.toISOString() },
          ],
        });
      }, totalWeight + 250),
    );
  }

  regenerate(datasetId: string, requestedBy: string): Observable<Job> {
    const record = this.datasetsById.get(datasetId);
    if (!record) {
      return throwError(() => new ApiError(`No dataset ${datasetId}`, 404));
    }
    return this.requestDataset({
      name: `${record.dataset.name} (regenerated)`,
      description: `Regenerated from dataset ${datasetId}`,
      requestedBy,
      targetId: record.dataset.targetId,
      scale: record.dataset.scale,
      seed: record.dataset.seed,
      maskSensitiveByDefault: true,
    });
  }

  dataset(id: string): Observable<Dataset> {
    const record = this.datasetsById.get(id);
    return record
      ? of(record.dataset).pipe(delay(60))
      : throwError(() => new ApiError(`No dataset ${id}`, 404));
  }

  datasets(limit = 50): Observable<DatasetSummary[]> {
    return of(
      [...this.datasetsById.values()]
        .map((record) => record.dataset as DatasetSummary)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .slice(0, limit),
    ).pipe(delay(60));
  }

  datasetStats(): Observable<DatasetStats> {
    const all = [...this.datasetsById.values()].map((record) => record.dataset);
    const succeeded = all.filter((d) => d.status === 'SUCCEEDED');
    const durations = succeeded.map((d) => d.durationMs ?? 0).filter(Boolean);

    return of({
      total: all.length,
      succeeded: succeeded.length,
      failed: all.filter((d) => d.status === 'FAILED').length,
      inFlight: all.filter((d) => d.status === 'RUNNING' || d.status === 'PENDING').length,
      rowsGenerated: succeeded.reduce((sum, d) => sum + d.totalRows, 0),
      avgDurationMs: durations.length
        ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length)
        : 0,
    }).pipe(delay(60));
  }

  masking(datasetId: string): Observable<MaskingRecord[]> {
    return of(this.datasetsById.get(datasetId)?.masking ?? []).pipe(delay(60));
  }

  sampleRows(datasetId: string): Observable<SampleRows[]> {
    return defer(() => {
      const record = this.datasetsById.get(datasetId);
      if (!record) {
        return of([]);
      }
      const samples: SampleRows[] = record.plan.tables.map((tablePlan) => {
        const id = qualified(tablePlan.table);
        const rows = record.generated.rowsByTable.get(id) ?? [];
        return {
          table: tablePlan.table.name,
          columns: tablePlan.columns.filter((c) => c.role !== 'DATABASE_GENERATED').map((c) => c.column),
          rows: rows.slice(0, 25),
          totalRows: tablePlan.rowCount,
        };
      });
      return of(samples);
    }).pipe(delay(80));
  }

  // -------------------------------------------------------------- jobs

  job(id: string): Observable<Job> {
    const job = this.jobsById.get(id);
    return job ? of(job) : throwError(() => new ApiError(`No job ${id}`, 404));
  }

  jobs(limit = 50): Observable<Job[]> {
    return of(
      [...this.jobsById.values()]
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .slice(0, limit),
    );
  }

  // ------------------------------------------------------------ leases

  leases(limit = 50): Observable<Lease[]> {
    return of(
      [...this.datasetsById.values()]
        .map((record) => record.lease)
        .filter((lease): lease is Lease => lease !== null)
        .map((lease) => withRemaining(lease))
        .sort((a, b) => b.issuedAt.localeCompare(a.issuedAt))
        .slice(0, limit),
    ).pipe(delay(60));
  }

  lease(id: string): Observable<Lease> {
    const record = this.findByLease(id);
    return record?.lease
      ? of(withRemaining(record.lease))
      : throwError(() => new ApiError(`No lease ${id}`, 404));
  }

  leaseForDataset(datasetId: string): Observable<Lease> {
    const lease = this.datasetsById.get(datasetId)?.lease;
    return lease
      ? of(withRemaining(lease))
      : throwError(() => new ApiError(`No lease for dataset ${datasetId}`, 404));
  }

  renewLease(id: string): Observable<Lease> {
    const record = this.findByLease(id);
    if (!record?.lease) {
      return throwError(() => new ApiError(`No lease ${id}`, 404));
    }
    if (record.lease.renewalsRemaining === 0) {
      return throwError(() =>
        new ApiError(
          `Lease ${id} has been renewed the maximum number of times. Request a new dataset rather than holding this one indefinitely.`,
          409,
        ),
      );
    }
    record.lease = {
      ...record.lease,
      renewals: record.lease.renewals + 1,
      renewalsRemaining: record.lease.renewalsRemaining - 1,
      expiresAt: new Date(Date.parse(record.lease.expiresAt) + 2 * 3600_000).toISOString(),
    };
    return of(withRemaining(record.lease)).pipe(delay(200));
  }

  releaseLease(id: string): Observable<Lease> {
    const record = this.findByLease(id);
    if (!record?.lease) {
      return throwError(() => new ApiError(`No lease ${id}`, 404));
    }
    record.lease = {
      ...record.lease,
      state: 'RELEASED',
      closedAt: new Date().toISOString(),
    };
    record.credential = null;
    return of(withRemaining(record.lease)).pipe(delay(250));
  }

  /** Single-use, exactly as the service behaves: the copy is destroyed on the way out. */
  claimCredentials(id: string): Observable<{ connectionString: string }> {
    return defer(() => {
      const record = this.findByLease(id);
      if (!record?.lease) {
        return throwError(() => new ApiError(`No lease ${id}`, 404));
      }
      if (record.lease.state !== 'ACTIVE') {
        return throwError(
          () => new ApiError(`Lease ${id} is ${record.lease!.state}, so it has no usable connection string`, 409),
        );
      }
      if (!record.credential) {
        return throwError(
          () =>
            new ApiError(
              `The connection string for lease ${id} has already been collected. It is shown once and then destroyed; rotate the lease to get a new one.`,
              409,
            ),
        );
      }
      const connectionString = record.credential;
      record.credential = null;
      return of({ connectionString });
    }).pipe(delay(250));
  }

  credentialStatus(id: string): Observable<{ unclaimed: boolean }> {
    return of({ unclaimed: this.findByLease(id)?.credential !== null });
  }

  private findByLease(leaseId: string): DemoRecord | undefined {
    return [...this.datasetsById.values()].find((record) => record.lease?.id === leaseId);
  }

  /** Cancels any in-flight simulated pipeline, so a reload does not leave timers running. */
  dispose(): void {
    this.timers.forEach(clearTimeout);
    this.timers = [];
  }
}

// --------------------------------------------------------------- helpers

const SENSITIVE_CLASSES = new Set([
  'GIVEN_NAME', 'FAMILY_NAME', 'FULL_NAME', 'USERNAME', 'EMAIL', 'PHONE', 'SSN',
  'NATIONAL_ID', 'CREDIT_CARD', 'IBAN', 'DATE_OF_BIRTH', 'STREET_ADDRESS', 'CITY',
  'REGION', 'POSTAL_CODE', 'LATITUDE', 'LONGITUDE', 'IP_ADDRESS', 'MAC_ADDRESS',
  'PASSWORD_HASH', 'API_TOKEN',
]);

function maskingRecords(datasetId: string, generationPlan: GenerationPlan): MaskingRecord[] {
  return generationPlan.tables.flatMap((table) =>
    table.columns
      .filter((column) => column.mask !== 'PRESERVE')
      .map((column) => ({
        datasetId,
        table: `${table.table.schema}.${table.table.name}`,
        column: column.column,
        dataClass: column.dataClass,
        strategy: column.mask,
        ruleSource: column.maskSource,
      })),
  );
}

/** Counters that accumulate as the pipeline advances, mirroring what the service records. */
function metricsFor(phase: JobPhase, record: DemoRecord): Record<string, number> {
  const metrics: Record<string, number> = {};
  const order: JobPhase[] = [
    'INTROSPECTING', 'PLANNING', 'PROVISIONING', 'APPLYING_DDL',
    'GENERATING', 'SEEDING', 'VERIFYING', 'LEASING', 'DONE',
  ];
  const reached = (target: JobPhase) => order.indexOf(phase) >= order.indexOf(target);

  if (reached('INTROSPECTING')) {
    metrics['tables.introspected'] = 22;
    metrics['foreignKeys.introspected'] = 31;
  }
  if (reached('PLANNING')) {
    metrics['tables.planned'] = record.plan.tables.length;
    metrics['rows.planned'] = record.plan.totalRows;
    metrics['columns.masked'] = record.plan.maskedColumns;
  }
  if (reached('SEEDING')) {
    metrics['rows.seeded'] = record.plan.totalRows;
  }
  if (reached('VERIFYING')) {
    metrics['rows.verified'] = record.plan.totalRows;
  }
  return metrics;
}

function makeLease(datasetId: string, body: DatasetRequestInput, name: string): Lease {
  const now = new Date();
  const ttlMinutes = body.ttlMinutes ?? 240;
  const slug = name.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 24) || 'ds';
  const database = `tf_${slug}_${datasetId.replace(/-/g, '').slice(0, 10)}`;

  return {
    id: uuid(),
    datasetId,
    databaseName: database,
    jdbcUrl: `jdbc:postgresql://demo.internal:5432/${database}`,
    username: `${database}_r`,
    connectionString: null,
    holder: body.requestedBy,
    state: 'ACTIVE',
    renewals: 0,
    renewalsRemaining: 6,
    issuedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + ttlMinutes * 60_000).toISOString(),
    remainingSeconds: ttlMinutes * 60,
    closedAt: null,
  };
}

/** Recomputes the countdown at read time, so it ticks down as the page sits open. */
function withRemaining(lease: Lease): Lease {
  return {
    ...lease,
    remainingSeconds: Math.max(0, Math.round((Date.parse(lease.expiresAt) - Date.now()) / 1000)),
  };
}

function uuid(): string {
  return crypto.randomUUID();
}

function randomSeed(): number {
  return Math.floor(Math.random() * 2 ** 31);
}

function randomPassword(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(18));
  return btoa(String.fromCharCode(...bytes)).replace(/[+/=]/g, '').slice(0, 24);
}
