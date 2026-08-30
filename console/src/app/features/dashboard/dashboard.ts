import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TestForgeService, ApiError } from '../../core/testforge.service';
import { DatasetStats, DatasetSummary, Lease, Target } from '../../core/api.types';
import { AgoPipe, CompactPipe, DurationPipe, RemainingPipe, leasePill, statusPill } from '../../core/format';

@Component({
  selector: 'tf-dashboard',
  imports: [RouterLink, CompactPipe, DurationPipe, AgoPipe, RemainingPipe],
  templateUrl: './dashboard.html',
})
export class Dashboard {
  private readonly api = inject(TestForgeService);

  protected readonly stats = signal<DatasetStats | null>(null);
  protected readonly datasets = signal<DatasetSummary[]>([]);
  protected readonly leases = signal<Lease[]>([]);
  protected readonly targets = signal<Target[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(true);

  protected readonly statusPill = statusPill;
  protected readonly leasePill = leasePill;

  protected readonly activeLeases = computed(() =>
    this.leases().filter((lease) => lease.state === 'ACTIVE'),
  );

  /** Leases inside their final hour, which is when a holder still has time to renew. */
  protected readonly expiringSoon = computed(() =>
    this.activeLeases().filter((lease) => lease.remainingSeconds < 3600),
  );

  protected readonly successRate = computed(() => {
    const stats = this.stats();
    if (!stats || stats.succeeded + stats.failed === 0) {
      return null;
    }
    return Math.round((stats.succeeded * 100) / (stats.succeeded + stats.failed));
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.api.datasetStats().subscribe({
      next: (stats) => this.stats.set(stats),
      error: (err: ApiError) => this.fail(err),
    });
    this.api.datasets(8).subscribe({
      next: (datasets) => {
        this.datasets.set(datasets);
        this.loading.set(false);
      },
      error: (err: ApiError) => this.fail(err),
    });
    this.api.leases(20).subscribe({
      next: (leases) => this.leases.set(leases),
      error: (err: ApiError) => this.fail(err),
    });
    this.api.listTargets().subscribe({
      next: (targets) => this.targets.set(targets),
      error: (err: ApiError) => this.fail(err),
    });
  }

  private fail(err: ApiError): void {
    this.loading.set(false);
    // The first failure is the informative one; later ones are usually the same
    // cause reported by a different request.
    if (!this.error()) {
      this.error.set(err.message);
    }
  }
}
