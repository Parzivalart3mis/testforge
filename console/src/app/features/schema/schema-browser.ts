import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError, TestForgeService } from '../../core/testforge.service';
import { SchemaGraph, SchemaSnapshot, TableMeta, Target } from '../../core/api.types';

@Component({
  selector: 'tf-schema-browser',
  imports: [RouterLink],
  templateUrl: './schema-browser.html',
  styleUrl: './schema-browser.css',
})
export class SchemaBrowser {
  /** Optional route param; falls back to the first registered target. */
  readonly targetId = input<string | undefined>(undefined);

  private readonly api = inject(TestForgeService);

  protected readonly targets = signal<Target[]>([]);
  protected readonly snapshot = signal<SchemaSnapshot | null>(null);
  protected readonly graph = signal<SchemaGraph | null>(null);
  protected readonly selected = signal<string | null>(null);
  protected readonly filter = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly refreshing = signal(false);

  protected readonly activeTarget = signal<string>('');

  /** Levels as an ordered array; every table in one level can be seeded in parallel. */
  protected readonly levels = computed(() => {
    const graph = this.graph();
    if (!graph) {
      return [];
    }
    return Object.entries(graph.levels)
      .map(([depth, tables]) => ({ depth: Number(depth), tables }))
      .sort((a, b) => a.depth - b.depth);
  });

  protected readonly visibleTables = computed(() => {
    const snapshot = this.snapshot();
    if (!snapshot) {
      return [];
    }
    const needle = this.filter().trim().toLowerCase();
    const tables = needle
      ? snapshot.tables.filter(
          (table) =>
            table.ref.name.toLowerCase().includes(needle) ||
            table.columns.some((column) => column.name.toLowerCase().includes(needle)),
        )
      : snapshot.tables;

    // Ordered by seed position so the list reads as the order rows are written.
    const order = new Map(this.graph()?.nodes.map((node) => [node.name, node.seedOrder]) ?? []);
    return [...tables].sort(
      (a, b) => (order.get(a.ref.name) ?? 0) - (order.get(b.ref.name) ?? 0),
    );
  });

  protected readonly selectedTable = computed<TableMeta | null>(() => {
    const name = this.selected();
    return this.snapshot()?.tables.find((table) => table.ref.name === name) ?? null;
  });

  /** Foreign keys pointing at the selected table, which the snapshot only records outbound. */
  protected readonly inboundEdges = computed(() => {
    const name = this.selected();
    const graph = this.graph();
    if (!name || !graph) {
      return [];
    }
    return graph.edges.filter((edge) => edge.to.endsWith('.' + name));
  });

  protected readonly nodeByName = computed(
    () => new Map(this.graph()?.nodes.map((node) => [node.name, node]) ?? []),
  );

  protected readonly sensitiveCount = computed(() =>
    (this.graph()?.nodes ?? []).reduce((sum, node) => sum + node.sensitiveColumns, 0),
  );

  constructor() {
    this.api.listTargets().subscribe({
      next: (targets) => {
        this.targets.set(targets);
        if (!this.activeTarget() && targets.length > 0) {
          this.select(this.targetId() ?? targets[0].id);
        }
      },
      error: (err: ApiError) => this.error.set(err.message),
    });

    effect(() => {
      const routed = this.targetId();
      if (routed && routed !== this.activeTarget()) {
        this.select(routed);
      }
    });
  }

  protected select(targetId: string): void {
    this.activeTarget.set(targetId);
    this.load();
  }

  protected load(): void {
    const targetId = this.activeTarget();
    if (!targetId) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.snapshot.set(null);
    this.graph.set(null);
    this.selected.set(null);

    this.api.schema(targetId).subscribe({
      next: (snapshot) => {
        this.snapshot.set(snapshot);
        this.loading.set(false);
      },
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.loading.set(false);
      },
    });

    this.api.graph(targetId).subscribe({
      next: (graph) => this.graph.set(graph),
      error: (err: ApiError) => this.error.set(err.message),
    });
  }

  /** Re-reads the target's catalog. An unchanged schema keeps its fingerprint. */
  protected refresh(): void {
    const targetId = this.activeTarget();
    if (!targetId) {
      return;
    }
    this.refreshing.set(true);
    this.api.introspect(targetId).subscribe({
      next: () => {
        this.refreshing.set(false);
        this.load();
      },
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.refreshing.set(false);
      },
    });
  }

  protected toggle(name: string): void {
    this.selected.set(this.selected() === name ? null : name);
  }

  protected onFilter(value: string): void {
    this.filter.set(value);
  }
}
