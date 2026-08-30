import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiError, SampleRows, TestForgeService } from '../../core/testforge.service';
import { Dataset, Lease, MaskingRecord } from '../../core/api.types';
import { AgoPipe, CompactPipe, DurationPipe, leasePill, maskPill, statusPill } from '../../core/format';

@Component({
  selector: 'tf-dataset-detail',
  imports: [RouterLink, CompactPipe, DurationPipe, AgoPipe],
  templateUrl: './dataset-detail.html',
})
export class DatasetDetail {
  readonly id = input.required<string>();

  private readonly api = inject(TestForgeService);
  private readonly router = inject(Router);

  protected readonly dataset = signal<Dataset | null>(null);
  protected readonly masking = signal<MaskingRecord[]>([]);
  protected readonly lease = signal<Lease | null>(null);
  protected readonly samples = signal<SampleRows[]>([]);
  protected readonly selectedSample = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly regenerating = signal(false);

  protected readonly statusPill = statusPill;
  protected readonly leasePill = leasePill;
  protected readonly maskPill = maskPill;

  /**
   * Sensitive columns that were left unmasked. Empty in a healthy dataset;
   * anything here means an explicit rule overrode the sensitivity default, and
   * that is worth showing prominently rather than burying in a list.
   */
  protected readonly unmasked = computed(() =>
    this.masking().filter((record) => record.strategy === 'PRESERVE'),
  );

  constructor() {
    effect(() => {
      const id = this.id();
      this.load(id);
    });
  }

  private load(id: string): void {
    this.api.dataset(id).subscribe({
      next: (dataset) => this.dataset.set(dataset),
      error: (err: ApiError) => this.error.set(err.message),
    });
    this.api.masking(id).subscribe({
      next: (records) => this.masking.set(records),
      error: () => {
        /* A dataset that failed before planning has no masking record. */
      },
    });
    this.api.leaseForDataset(id).subscribe({
      next: (lease) => this.lease.set(lease),
      error: () => {
        /* Not every dataset has a lease. */
      },
    });
    // Only the browser engine can hand back rows; the service seeds them into a
    // database instead, and the connection string is how you look at those.
    this.api.sampleRows(id).subscribe({
      next: (samples) => {
        this.samples.set(samples);
        if (samples.length > 0 && !this.selectedSample()) {
          this.selectedSample.set(samples[0].table);
        }
      },
      error: () => this.samples.set([]),
    });
  }

  protected readonly activeSample = computed(() =>
    this.samples().find((sample) => sample.table === this.selectedSample()) ?? null,
  );

  /** Renders a generated value the way a database client would show it. */
  protected display(value: unknown): string {
    if (value === null || value === undefined) {
      return 'NULL';
    }
    if (Array.isArray(value)) {
      return `{${value.join(', ')}}`;
    }
    return String(value);
  }

  /** Replays the stored request and seed, producing an identical dataset. */
  protected regenerate(): void {
    const dataset = this.dataset();
    if (!dataset) {
      return;
    }
    this.regenerating.set(true);
    this.api.regenerate(dataset.id, dataset.requestedBy).subscribe({
      next: (job) => this.router.navigate(['/jobs', job.id]),
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.regenerating.set(false);
      },
    });
  }
}
