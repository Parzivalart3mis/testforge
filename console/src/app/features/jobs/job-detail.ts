import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError, TestForgeService } from '../../core/testforge.service';
import { Job, Lease } from '../../core/api.types';
import { AgoPipe, DurationPipe, statusPill } from '../../core/format';

/** How often a running job is polled. Fast enough to feel live, slow enough to be free. */
const POLL_INTERVAL_MS = 1200;

@Component({
  selector: 'tf-job-detail',
  imports: [RouterLink, DurationPipe, AgoPipe],
  templateUrl: './job-detail.html',
  styleUrl: './job-detail.css',
})
export class JobDetail {
  /** Bound from the route by withComponentInputBinding. */
  readonly id = input.required<string>();

  private readonly api = inject(TestForgeService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly job = signal<Job | null>(null);
  protected readonly lease = signal<Lease | null>(null);
  protected readonly connectionString = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly claiming = signal(false);
  protected readonly copied = signal(false);

  protected readonly statusPill = statusPill;

  private timer: ReturnType<typeof setTimeout> | null = null;

  protected readonly metrics = computed(() => {
    const job = this.job();
    return job ? Object.entries(job.metrics).sort(([a], [b]) => a.localeCompare(b)) : [];
  });

  protected readonly progressClass = computed(() => {
    const status = this.job()?.status;
    if (status === 'SUCCEEDED') {
      return 'progress-bar done';
    }
    if (status === 'FAILED' || status === 'CANCELLED') {
      return 'progress-bar failed';
    }
    return 'progress-bar';
  });

  constructor() {
    effect(() => {
      const id = this.id();
      this.stopPolling();
      this.poll(id);
    });

    this.destroyRef.onDestroy(() => this.stopPolling());
  }

  /**
   * Polls until the job reaches a terminal status, then stops.
   *
   * A self-scheduling timeout rather than an interval: it cannot pile up
   * requests if the service is slow, and it stops on its own the moment the run
   * is finished rather than polling a completed job forever.
   */
  private poll(id: string): void {
    this.api.job(id).subscribe({
      next: (job) => {
        this.job.set(job);
        this.error.set(null);

        if (job.status === 'SUCCEEDED' && !this.lease()) {
          this.loadLease(job.datasetId);
        }
        if (job.status === 'PENDING' || job.status === 'RUNNING') {
          this.timer = setTimeout(() => this.poll(id), POLL_INTERVAL_MS);
        }
      },
      error: (err: ApiError) => {
        this.error.set(err.message);
        // Keep trying on a transient failure, but back off so a service that is
        // down does not get hammered.
        this.timer = setTimeout(() => this.poll(id), POLL_INTERVAL_MS * 4);
      },
    });
  }

  private stopPolling(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private loadLease(datasetId: string): void {
    this.api.leaseForDataset(datasetId).subscribe({
      next: (lease) => this.lease.set(lease),
      error: () => {
        /* A dataset without a lease is normal for a failed run. */
      },
    });
  }

  /**
   * Collects the connection string. The service destroys its copy on the way
   * out, so this is a one-shot: the button disappears afterwards rather than
   * offering a second attempt that would fail.
   */
  protected claim(): void {
    const lease = this.lease();
    if (!lease) {
      return;
    }
    this.claiming.set(true);
    this.api.claimCredentials(lease.id).subscribe({
      next: (result) => {
        this.connectionString.set(result.connectionString);
        this.claiming.set(false);
      },
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.claiming.set(false);
      },
    });
  }

  protected copy(): void {
    const value = this.connectionString();
    if (!value) {
      return;
    }
    navigator.clipboard?.writeText(value).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    });
  }
}
