import { InjectionToken } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Dataset,
  DatasetRequestInput,
  DatasetStats,
  DatasetSummary,
  GenerationPlan,
  Job,
  Lease,
  MaskingRecord,
  ProblemDetail,
  SchemaGraph,
  SchemaSnapshot,
  Target,
} from './api.types';

/**
 * Where the service lives.
 *
 * Resolved at runtime from a global the host page sets rather than baked in at
 * build time, so one static bundle can be pointed at a local service without
 * rebuilding. Left blank, the console runs its own engine in the browser
 * instead — which is how the hosted demo works, since there is no backend for
 * it to reach.
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => {
    const configured = (globalThis as Record<string, unknown>)['__TESTFORGE_API__'];
    return typeof configured === 'string' ? configured.replace(/\/$/, '') : '';
  },
});

/** A failure worth showing a person, extracted from a problem detail. */
export class ApiError extends Error {
  constructor(
    override readonly message: string,
    readonly status: number,
    readonly problem?: ProblemDetail,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  get fieldErrors(): Record<string, string> {
    return this.problem?.fields ?? {};
  }
}

/** One table's generated rows, for the console's data preview. */
export interface SampleRows {
  table: string;
  columns: string[];
  rows: Record<string, unknown>[];
  totalRows: number;
}

/**
 * Everything the console needs from a backend.
 *
 * Two implementations satisfy it: an HTTP client against the Spring service,
 * and an in-browser engine for the hosted demo. Keeping the surface abstract
 * means no component knows or cares which one it is talking to.
 */
export abstract class TestForgeService {
  /** Whether this backend is the in-browser engine, so the console can say so. */
  abstract readonly isDemo: boolean;

  abstract listTargets(): Observable<Target[]>;
  abstract schema(targetId: string, refresh?: boolean): Observable<SchemaSnapshot>;
  abstract graph(targetId: string): Observable<SchemaGraph>;
  abstract introspect(targetId: string): Observable<SchemaSnapshot>;
  abstract targetHealth(
    targetId: string,
  ): Observable<{ id: string; displayName: string; reachable: boolean }>;

  abstract requestDataset(body: DatasetRequestInput): Observable<Job>;
  abstract previewPlan(body: DatasetRequestInput): Observable<GenerationPlan>;
  abstract regenerate(datasetId: string, requestedBy: string): Observable<Job>;
  abstract dataset(id: string): Observable<Dataset>;
  abstract datasets(limit?: number): Observable<DatasetSummary[]>;
  abstract datasetStats(): Observable<DatasetStats>;
  abstract masking(datasetId: string): Observable<MaskingRecord[]>;

  /** Generated rows, when the backend can supply them. The HTTP one cannot. */
  abstract sampleRows(datasetId: string): Observable<SampleRows[]>;

  abstract job(id: string): Observable<Job>;
  abstract jobs(limit?: number): Observable<Job[]>;

  abstract leases(limit?: number): Observable<Lease[]>;
  abstract lease(id: string): Observable<Lease>;
  abstract leaseForDataset(datasetId: string): Observable<Lease>;
  abstract renewLease(id: string): Observable<Lease>;
  abstract releaseLease(id: string): Observable<Lease>;
  abstract claimCredentials(id: string): Observable<{ connectionString: string }>;
  abstract credentialStatus(id: string): Observable<{ unclaimed: boolean }>;
}
