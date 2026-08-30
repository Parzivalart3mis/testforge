import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError, TestForgeService } from '../../core/testforge.service';
import { Lease } from '../../core/api.types';
import { AgoPipe, RemainingPipe, leasePill } from '../../core/format';

@Component({
  selector: 'tf-lease-list',
  imports: [RouterLink, AgoPipe, RemainingPipe],
  templateUrl: './lease-list.html',
})
export class LeaseList {
  private readonly api = inject(TestForgeService);

  protected readonly leases = signal<Lease[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly showClosed = signal(false);
  protected readonly busy = signal<string | null>(null);
  protected readonly revealed = signal<Record<string, string>>({});

  protected readonly leasePill = leasePill;

  protected readonly visible = computed(() =>
    this.showClosed() ? this.leases() : this.leases().filter((lease) => lease.state === 'ACTIVE'),
  );

  protected readonly activeCount = computed(
    () => this.leases().filter((lease) => lease.state === 'ACTIVE').length,
  );

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.api.leases(100).subscribe({
      next: (leases) => {
        this.leases.set(leases);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.loading.set(false);
      },
    });
  }

  protected renew(lease: Lease): void {
    this.act(lease.id, this.api.renewLease(lease.id), 'Lease extended.');
  }

  /**
   * Releasing drops the database immediately. Confirmed first: the rows are
   * gone the moment this returns, and anything mid-test against them fails.
   */
  protected release(lease: Lease): void {
    const ok = confirm(
      `Release ${lease.databaseName}?\n\nThe database is dropped immediately and its rows cannot be recovered. ` +
        `The dataset stays reproducible from its seed, so it can be requested again.`,
    );
    if (ok) {
      this.act(lease.id, this.api.releaseLease(lease.id), 'Lease released and database dropped.');
    }
  }

  protected reveal(lease: Lease): void {
    this.busy.set(lease.id);
    this.api.claimCredentials(lease.id).subscribe({
      next: (result) => {
        this.revealed.update((current) => ({ ...current, [lease.id]: result.connectionString }));
        this.busy.set(null);
        this.notice.set('Connection string revealed. It has been destroyed server-side.');
      },
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.busy.set(null);
      },
    });
  }

  protected copy(value: string): void {
    navigator.clipboard?.writeText(value);
    this.notice.set('Copied to clipboard.');
  }

  private act(id: string, request: ReturnType<TestForgeService['renewLease']>, message: string): void {
    this.busy.set(id);
    this.error.set(null);
    request.subscribe({
      next: () => {
        this.busy.set(null);
        this.notice.set(message);
        this.load();
      },
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.busy.set(null);
      },
    });
  }
}
