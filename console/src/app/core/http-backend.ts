import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, catchError, of, throwError } from 'rxjs';
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
import { API_BASE_URL, ApiError, SampleRows, TestForgeService } from './testforge.service';

/** The console talking to the Spring service over HTTP. */
@Injectable()
export class HttpBackend extends TestForgeService {
  readonly isDemo = false;

  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  listTargets(): Observable<Target[]> {
    return this.get('/api/v1/targets');
  }

  schema(targetId: string, refresh = false): Observable<SchemaSnapshot> {
    return this.get(`/api/v1/targets/${encodeURIComponent(targetId)}/schema?refresh=${refresh}`);
  }

  graph(targetId: string): Observable<SchemaGraph> {
    return this.get(`/api/v1/targets/${encodeURIComponent(targetId)}/graph`);
  }

  introspect(targetId: string): Observable<SchemaSnapshot> {
    return this.post(`/api/v1/targets/${encodeURIComponent(targetId)}/introspect`, {});
  }

  targetHealth(targetId: string): Observable<{ id: string; displayName: string; reachable: boolean }> {
    return this.get(`/api/v1/targets/${encodeURIComponent(targetId)}/health`);
  }

  requestDataset(body: DatasetRequestInput): Observable<Job> {
    return this.post('/api/v1/datasets', body);
  }

  previewPlan(body: DatasetRequestInput): Observable<GenerationPlan> {
    return this.post('/api/v1/datasets/preview', body);
  }

  regenerate(datasetId: string, requestedBy: string): Observable<Job> {
    return this.post(
      `/api/v1/datasets/${datasetId}/regenerate?requestedBy=${encodeURIComponent(requestedBy)}`,
      {},
    );
  }

  dataset(id: string): Observable<Dataset> {
    return this.get(`/api/v1/datasets/${id}`);
  }

  datasets(limit = 50): Observable<DatasetSummary[]> {
    return this.get(`/api/v1/datasets?limit=${limit}`);
  }

  datasetStats(): Observable<DatasetStats> {
    return this.get('/api/v1/datasets/stats');
  }

  masking(datasetId: string): Observable<MaskingRecord[]> {
    return this.get(`/api/v1/datasets/${datasetId}/masking`);
  }

  /**
   * The service seeds rows into an ephemeral database rather than returning
   * them, so there is nothing to preview here — the connection string is how
   * you look at the data.
   */
  sampleRows(): Observable<SampleRows[]> {
    return of([]);
  }

  job(id: string): Observable<Job> {
    return this.get(`/api/v1/jobs/${id}`);
  }

  jobs(limit = 50): Observable<Job[]> {
    return this.get(`/api/v1/jobs?limit=${limit}`);
  }

  leases(limit = 50): Observable<Lease[]> {
    return this.get(`/api/v1/leases?limit=${limit}`);
  }

  lease(id: string): Observable<Lease> {
    return this.get(`/api/v1/leases/${id}`);
  }

  leaseForDataset(datasetId: string): Observable<Lease> {
    return this.get(`/api/v1/leases/by-dataset/${datasetId}`);
  }

  renewLease(id: string): Observable<Lease> {
    return this.post(`/api/v1/leases/${id}/renew`, {});
  }

  releaseLease(id: string): Observable<Lease> {
    return this.request('delete', `/api/v1/leases/${id}`);
  }

  claimCredentials(id: string): Observable<{ connectionString: string }> {
    return this.post(`/api/v1/leases/${id}/credentials`, {});
  }

  credentialStatus(id: string): Observable<{ unclaimed: boolean }> {
    return this.get(`/api/v1/leases/${id}/credentials/status`);
  }

  // ------------------------------------------------------------ internals

  private get<T>(path: string): Observable<T> {
    return this.http.get<T>(this.base + path).pipe(catchError(toApiError));
  }

  private post<T>(path: string, body: unknown): Observable<T> {
    return this.http.post<T>(this.base + path, body).pipe(catchError(toApiError));
  }

  private request<T>(method: 'delete', path: string): Observable<T> {
    return this.http.delete<T>(this.base + path).pipe(catchError(toApiError));
  }
}

/**
 * Turns a transport failure into something worth showing.
 *
 * The service writes its problem details for the engineer who hit them, so the
 * detail beats the title and both beat the status code. A network failure gets
 * its own message: "Http failure response ...: 0 Unknown Error" tells nobody
 * that the backend simply is not running.
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
