import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError, TestForgeService } from '../../core/testforge.service';
import { DatasetSummary } from '../../core/api.types';
import { AgoPipe, CompactPipe, DurationPipe, statusPill } from '../../core/format';

@Component({
  selector: 'tf-dataset-list',
  imports: [RouterLink, CompactPipe, DurationPipe, AgoPipe],
  templateUrl: './dataset-list.html',
})
export class DatasetList {
  private readonly api = inject(TestForgeService);

  protected readonly datasets = signal<DatasetSummary[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly filter = signal('');

  protected readonly statusPill = statusPill;

  protected readonly visible = computed(() => {
    const needle = this.filter().trim().toLowerCase();
    if (!needle) {
      return this.datasets();
    }
    return this.datasets().filter(
      (dataset) =>
        dataset.name.toLowerCase().includes(needle) ||
        dataset.requestedBy.toLowerCase().includes(needle) ||
        dataset.targetId.toLowerCase().includes(needle),
    );
  });

  constructor() {
    this.api.datasets(100).subscribe({
      next: (datasets) => {
        this.datasets.set(datasets);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.loading.set(false);
      },
    });
  }
}
