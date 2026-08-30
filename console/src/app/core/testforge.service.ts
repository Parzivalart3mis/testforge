import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable, InjectionToken } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
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
 * Resolved at runtime from a global the host page can set, rather than baked in
 * at build time. The console is a static bundle; being able to point the same
 * artefact at a local service or a deployed one without rebuilding is the whole
 * reason it is a static bundle.
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => {
    const configured = (globalThis as Record<string, unknown>)['__TESTFORGE_API__'];
    return typeof configured === 'string' && configured.length > 0
      ? configured.replace(/\/$/, '')
      : 'http://localhost:8080';
  },
});

/** A failure the console can show a person, extracted from a problem detail. */
export class ApiError extends Error {
  constructor(
    override readonly message: string,
    readonly status: number,
    readonly problem?: ProblemDetail,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  /** Field-level validation messages, when the failure was a rejected form. */
  get fieldErrors(): Record<string, string> {
    return this.problem?.fields ?? {};
  }
}

@Injectable({ providedIn: 'root' })
export class TestForgeService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  // ------------------------------------------------------------- targets

  listTargets(): Observable<Target[]> {
    return this.get<Target[]>('/api/v1/targets');
  }

  schema(targetId: string, refresh = false): Observable<SchemaSnapshot> {
    return this.get<SchemaSnapshot>(
      `/api/v1/targets/${encodeURIComponent(targetId)}/schema?refresh=${refresh}`,
    );
  }

  graph(targetId: string): Observable<SchemaGraph> {
    return this.get<SchemaGraph>(`/api/v1/targets/${encodeURIComponent(targetId)}/graph`);
  }

  introspect(targetId: string): Observable<SchemaSnapshot> {
    return this.post<SchemaSnapshot>(
      `/api/v1/targets/${encodeURIComponent(targetId)}/introspect`,
      {},
    );
  }

  targetHealth(targetId: string): Observable<{ id: string; displayName: string; reachable: boolean }> {
    return this.get(`/api/v1/targets/${encodeURIComponent(targetId)}/health`);
  }

  // ------------------------------------------------------------ datasets

  requestDataset(body: DatasetRequestInput): Observable<Job> {
    return this.post<Job>('/api/v1/datasets', body);
  }

  previewPlan(body: DatasetRequestInput): Observable<GenerationPlan> {
    return this.post<GenerationPlan>('/api/v1/datasets/preview', body);
  }

  regenerate(datasetId: string, requestedBy: string): Observable<Job> {
    return this.post<Job>(
      `/api/v1/datasets/${datasetId}/regenerate?requestedBy=${encodeURIComponent(requestedBy)}`,
      {},
    );
  }

  dataset(id: string): Observable<Dataset> {
    return this.get<Dataset>(`/api/v1/datasets/${id}`);
  }

  datasets(limit = 50): Observable<DatasetSummary[]> {
    return this.get<DatasetSummary[]>(`/api/v1/datasets?limit=${limit}`);
  }

  datasetStats(): Observable<DatasetStats> {
    return this.get<DatasetStats>('/api/v1/datasets/stats');
  }

  masking(datasetId: string): Observable<MaskingRecord[]> {
    return this.get<MaskingRecord[]>(`/api/v1/datasets/${datasetId}/masking`);
  }

  // ---------------------------------------------------------------- jobs

  job(id: string): Observable<Job> {
    return this.get<Job>(`/api/v1/jobs/${id}`);
  }

  jobs(limit = 50): Observable<Job[]> {
    return this.get<Job[]>(`/api/v1/jobs?limit=${limit}`);
  }

  // -------------------------------------------------------------- leases

  leases(limit = 50): Observable<Lease[]> {
    return this.get<Lease[]>(`/api/v1/leases?limit=${limit}`);
  }

  lease(id: string): Observable<Lease> {
    return this.get<Lease>(`/api/v1/leases/${id}`);
  }

  leaseForDataset(datasetId: string): Observable<Lease> {
    return this.get<Lease>(`/api/v1/leases/by-dataset/${datasetId}`);
  }

  renewLease(id: string): Observable<Lease> {
    return this.post<Lease>(`/api/v1/leases/${id}/renew`, {});
  }

  releaseLease(id: string): Observable<Lease> {
    return this.delete<Lease>(`/api/v1/leases/${id}`);
  }

  /**
   * Collects the connection string. Succeeds once per lease: the service
   * destroys its copy on the way out, so the console must show the result
   * immediately rather than re-fetching it later.
   */
  claimCredentials(id: string): Observable<{ connectionString: string }> {
    return this.post<{ connectionString: string }>(`/api/v1/leases/${id}/credentials`, {});
  }

  credentialStatus(id: string): Observable<{ unclaimed: boolean }> {
    return this.get<{ unclaimed: boolean }>(`/api/v1/leases/${id}/credentials/status`);
  }

  // ------------------------------------------------------------ internals

  private get<T>(path: string): Observable<T> {
    return this.http.get<T>(this.base + path).pipe(catchError(toApiError));
  }

  private post<T>(path: string, body: unknown): Observable<T> {
    return this.http.post<T>(this.base + path, body).pipe(catchError(toApiError));
  }

  private delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(this.base + path).pipe(catchError(toApiError));
  }
}

/**
 * Turns a transport failure into something worth showing.
 *
 * The service writes its problem details for the engineer who hit them, so the
 * detail is preferred over the title and both are preferred over the HTTP
 * status. A network failure gets its own message, because "Http failure
 * response for ...: 0 Unknown Error" tells a person nothing about the fact that
 * the backend is not running.
 */
function toApiError(error: unknown) {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return throwError(
        () =>
          new ApiError(
            'Could not reach the TestForge service. Check that it is running and that this origin is allowed by CORS.',
            0,
          ),
      );
    }
    const problem = error.error as ProblemDetail | undefined;
    const message =
      problem?.detail ?? problem?.title ?? error.message ?? `Request failed with ${error.status}`;
    return throwError(() => new ApiError(message, error.status, problem));
  }
  return throwError(() => new ApiError(String(error), -1));
}
