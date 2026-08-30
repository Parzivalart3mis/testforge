import { describe, expect, it, vi } from 'vitest';
import { firstValueFrom } from 'rxjs';
import { DemoBackend } from './demo-backend';
import { ApiError } from '../core/testforge.service';

/**
 * The path a visitor to the hosted demo actually takes: pick a target, preview
 * a plan, request a dataset, watch it finish, collect the connection string,
 * renew, release.
 */

const request = {
  name: 'checkout fixtures',
  requestedBy: 'visitor@example.com',
  targetId: 'demo-commerce',
  scale: 15,
  ttlMinutes: 60,
};

/** Runs the simulated pipeline to completion without waiting out its timers. */
async function completeJob(backend: DemoBackend, jobId: string) {
  await vi.advanceTimersByTimeAsync(10_000);
  return firstValueFrom(backend.job(jobId));
}

describe('DemoBackend', () => {
  it('reports itself as the browser engine', () => {
    expect(new DemoBackend().isDemo).toBe(true);
  });

  it('lists the demo target', async () => {
    vi.useFakeTimers();
    try {
      const backend = new DemoBackend();
      const promise = firstValueFrom(backend.listTargets());
      await vi.advanceTimersByTimeAsync(200);

      const targets = await promise;
      expect(targets).toHaveLength(1);
      expect(targets[0].id).toBe('demo-commerce');
    } finally {
      vi.useRealTimers();
    }
  });

  it('returns a graph with the cycle detected and the levels populated', async () => {
    vi.useFakeTimers();
    try {
      const backend = new DemoBackend();
      const promise = firstValueFrom(backend.graph());
      await vi.advanceTimersByTimeAsync(500);

      const graph = await promise;
      expect(graph.nodes).toHaveLength(22);
      expect(graph.edges.length).toBeGreaterThan(25);
      expect(graph.cycles).toHaveLength(1);
      expect(graph.edges.some((edge) => edge.deferred)).toBe(true);
      expect(Object.keys(graph.levels).length).toBeGreaterThan(3);
    } finally {
      vi.useRealTimers();
    }
  });

  it('previews a plan without creating a dataset', async () => {
    vi.useFakeTimers();
    try {
      const backend = new DemoBackend();
      const promise = firstValueFrom(backend.previewPlan(request));
      await vi.advanceTimersByTimeAsync(600);
      const plan = await promise;

      expect(plan.tables).toHaveLength(22);
      expect(plan.maskedColumns).toBeGreaterThan(0);

      const listing = firstValueFrom(backend.datasets());
      await vi.advanceTimersByTimeAsync(200);
      expect(await listing).toHaveLength(0);
    } finally {
      vi.useRealTimers();
    }
  });

  it('runs a dataset request through to a lease and a single-use credential', async () => {
    vi.useFakeTimers();
    try {
      const backend = new DemoBackend();

      const accepted = firstValueFrom(backend.requestDataset(request));
      await vi.advanceTimersByTimeAsync(400);
      const job = await accepted;
      expect(job.status).toBe('PENDING');

      const finished = await completeJob(backend, job.id);
      expect(finished.status).toBe('SUCCEEDED');
      expect(finished.percent).toBe(100);
      expect(finished.metrics['rows.seeded']).toBeGreaterThan(0);
      // Every phase should have produced an event.
      expect(finished.events.length).toBeGreaterThan(6);

      const datasetPromise = firstValueFrom(backend.dataset(job.datasetId));
      await vi.advanceTimersByTimeAsync(200);
      const dataset = await datasetPromise;
      expect(dataset.status).toBe('SUCCEEDED');
      expect(dataset.plan?.tables).toHaveLength(22);

      const leasePromise = firstValueFrom(backend.leaseForDataset(job.datasetId));
      await vi.advanceTimersByTimeAsync(200);
      const lease = await leasePromise;
      expect(lease.state).toBe('ACTIVE');
      expect(lease.databaseName).toMatch(/^tf_/);
      // A listing must never carry the credential.
      expect(lease.connectionString).toBeNull();

      const firstClaim = firstValueFrom(backend.claimCredentials(lease.id));
      await vi.advanceTimersByTimeAsync(500);
      const { connectionString } = await firstClaim;
      expect(connectionString).toContain('user=');
      expect(connectionString).toContain('password=');

      // Shown once, then destroyed.
      const secondClaim = expect(
        firstValueFrom(backend.claimCredentials(lease.id)),
      ).rejects.toBeInstanceOf(ApiError);
      await vi.advanceTimersByTimeAsync(500);
      await secondClaim;
    } finally {
      vi.useRealTimers();
    }
  });

  it('hands back a populated sample for every table', async () => {
    // Referential integrity is asserted over the whole generated dataset in
    // engine.spec; this sample is the top 25 rows per table, so an order here
    // can legitimately reference a customer outside the customer slice.
    vi.useFakeTimers();
    try {
      const backend = new DemoBackend();
      const accepted = firstValueFrom(backend.requestDataset(request));
      await vi.advanceTimersByTimeAsync(400);
      const job = await accepted;
      await completeJob(backend, job.id);

      const samplesPromise = firstValueFrom(backend.sampleRows(job.datasetId));
      await vi.advanceTimersByTimeAsync(200);
      const samples = await samplesPromise;

      expect(samples).toHaveLength(22);
      for (const sample of samples) {
        expect(sample.rows.length, sample.table).toBeGreaterThan(0);
        expect(sample.columns.length, sample.table).toBeGreaterThan(0);
        expect(sample.totalRows, sample.table).toBeGreaterThanOrEqual(sample.rows.length);
        // Every displayed column is a real column of the row.
        for (const column of sample.columns) {
          expect(sample.rows[0], `${sample.table}.${column}`).toHaveProperty(column);
        }
      }

      // A NOT NULL foreign key is always populated, whichever parent it picked.
      const orders = samples.find((sample) => sample.table === 'order_header')!;
      for (const row of orders.rows) {
        expect(row['customer_id']).not.toBeNull();
      }
    } finally {
      vi.useRealTimers();
    }
  });

  it('renews and then releases a lease', async () => {
    vi.useFakeTimers();
    try {
      const backend = new DemoBackend();
      const accepted = firstValueFrom(backend.requestDataset(request));
      await vi.advanceTimersByTimeAsync(400);
      const job = await accepted;
      await completeJob(backend, job.id);

      const leasePromise = firstValueFrom(backend.leaseForDataset(job.datasetId));
      await vi.advanceTimersByTimeAsync(200);
      const lease = await leasePromise;

      const renewPromise = firstValueFrom(backend.renewLease(lease.id));
      await vi.advanceTimersByTimeAsync(400);
      const renewed = await renewPromise;
      expect(renewed.renewals).toBe(1);
      expect(renewed.renewalsRemaining).toBe(5);
      expect(Date.parse(renewed.expiresAt)).toBeGreaterThan(Date.parse(lease.expiresAt));

      const releasePromise = firstValueFrom(backend.releaseLease(lease.id));
      await vi.advanceTimersByTimeAsync(400);
      const released = await releasePromise;
      expect(released.state).toBe('RELEASED');
      expect(released.closedAt).not.toBeNull();

      // A released lease has no usable credential.
      const claim = expect(
        firstValueFrom(backend.claimCredentials(lease.id)),
      ).rejects.toBeInstanceOf(ApiError);
      await vi.advanceTimersByTimeAsync(400);
      await claim;
    } finally {
      vi.useRealTimers();
    }
  });

  it('regenerates a dataset with the same seed', async () => {
    vi.useFakeTimers();
    try {
      const backend = new DemoBackend();
      const accepted = firstValueFrom(backend.requestDataset(request));
      await vi.advanceTimersByTimeAsync(400);
      const original = await accepted;
      await completeJob(backend, original.id);

      const datasetPromise = firstValueFrom(backend.dataset(original.datasetId));
      await vi.advanceTimersByTimeAsync(200);
      const dataset = await datasetPromise;

      const replayPromise = firstValueFrom(
        backend.regenerate(original.datasetId, 'someone@example.com'),
      );
      await vi.advanceTimersByTimeAsync(400);
      const replayJob = await replayPromise;
      await completeJob(backend, replayJob.id);

      const replayedPromise = firstValueFrom(backend.dataset(replayJob.datasetId));
      await vi.advanceTimersByTimeAsync(200);
      const replayed = await replayedPromise;

      expect(replayed.seed).toBe(dataset.seed);
      expect(replayed.totalRows).toBe(dataset.totalRows);
    } finally {
      vi.useRealTimers();
    }
  });

  it('reports statistics across datasets', async () => {
    vi.useFakeTimers();
    try {
      const backend = new DemoBackend();
      const accepted = firstValueFrom(backend.requestDataset(request));
      await vi.advanceTimersByTimeAsync(400);
      const job = await accepted;
      await completeJob(backend, job.id);

      const statsPromise = firstValueFrom(backend.datasetStats());
      await vi.advanceTimersByTimeAsync(200);
      const stats = await statsPromise;

      expect(stats.total).toBe(1);
      expect(stats.succeeded).toBe(1);
      expect(stats.rowsGenerated).toBeGreaterThan(0);
    } finally {
      vi.useRealTimers();
    }
  });
});
