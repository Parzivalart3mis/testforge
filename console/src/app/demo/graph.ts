import { ForeignKey, SchemaSnapshot, TableMeta } from '../core/api.types';

/**
 * The foreign-key graph and its ordering, ported from the service.
 *
 * This is a deliberate second implementation of the algorithm that matters
 * most, so the browser demo can show it working without a backend. It follows
 * the same three rules the Java version does:
 *
 *   - self-references are not ordering constraints, since rows within a table
 *     are generated in order and can point at an earlier one;
 *   - a cycle is broken by deferring one nullable edge, whose columns are
 *     seeded NULL and filled by a second pass;
 *   - a cycle in which every edge is NOT NULL is genuinely unsatisfiable.
 *
 * Ties break on table name, so the order is identical on every run. That is
 * what makes a seeded dataset reproducible rather than merely similar.
 */

export interface SeedOrder {
  order: string[];
  deferred: ForeignKey[];
  dangling: ForeignKey[];
  depth: Map<string, number>;
  cycles: string[][];
  notes: string[];
}

export function qualified(ref: { schema: string; name: string }): string {
  return `${ref.schema}.${ref.name}`;
}

export class DependencyGraph {
  readonly tables = new Map<string, TableMeta>();
  readonly parents = new Map<string, Set<string>>();
  readonly children = new Map<string, Set<string>>();
  readonly edges: ForeignKey[] = [];
  readonly dangling: ForeignKey[] = [];
  readonly selfEdges: ForeignKey[] = [];

  constructor(snapshot: SchemaSnapshot, included?: Set<string>) {
    for (const table of snapshot.tables) {
      const id = qualified(table.ref);
      if (!included || included.has(id)) {
        this.tables.set(id, table);
      }
    }
    for (const id of this.tables.keys()) {
      this.parents.set(id, new Set());
      this.children.set(id, new Set());
    }

    for (const table of this.tables.values()) {
      const child = qualified(table.ref);
      for (const fk of table.foreignKeys) {
        const parent = qualified(fk.parent);
        if (parent === child) {
          this.selfEdges.push(fk);
        } else if (!this.tables.has(parent)) {
          this.dangling.push(fk);
        } else {
          this.edges.push(fk);
          this.parents.get(child)!.add(parent);
          this.children.get(parent)!.add(child);
        }
      }
    }
  }

  /**
   * Expands a table set with everything it depends on through a NOT NULL
   * foreign key. Asking for order_line alone is never what someone means: the
   * rows cannot exist without their orders.
   */
  static requiredClosure(snapshot: SchemaSnapshot, requested: Set<string>): Set<string> {
    const byId = new Map(snapshot.tables.map((table) => [qualified(table.ref), table]));
    const closure = new Set(requested);
    const pending = [...requested];

    while (pending.length > 0) {
      const table = byId.get(pending.shift()!);
      if (!table) {
        continue;
      }
      for (const fk of table.foreignKeys) {
        const parent = qualified(fk.parent);
        if (parent === qualified(table.ref) || closure.has(parent) || !byId.has(parent)) {
          continue;
        }
        const required = fk.childColumns.some(
          (name) => table.columns.find((column) => column.name === name)?.nullable === false,
        );
        if (required) {
          closure.add(parent);
          pending.push(parent);
        }
      }
    }
    return closure;
  }

  /** Kahn's algorithm, with cycles decomposed and broken as they are reached. */
  order(): SeedOrder {
    const remaining = new Map<string, Set<string>>();
    for (const [id, parents] of this.parents) {
      remaining.set(id, new Set(parents));
    }

    const ordered: string[] = [];
    const depth = new Map<string, number>();
    const deferred: ForeignKey[] = [];
    const cycles: string[][] = [];
    const notes: string[] = [];
    const emitted = new Set<string>();

    // Sorted, so the same schema always produces the same order.
    const ready = new Set<string>(
      [...remaining.entries()].filter(([, parents]) => parents.size === 0).map(([id]) => id),
    );

    const sortedFirst = (set: Set<string>) => [...set].sort()[0];

    while (emitted.size < this.tables.size) {
      while (ready.size > 0) {
        const next = sortedFirst(ready)!;
        ready.delete(next);
        if (emitted.has(next)) {
          continue;
        }
        emitted.add(next);
        ordered.push(next);
        depth.set(next, this.depthOf(next, depth, deferred));

        for (const child of this.children.get(next) ?? []) {
          const childParents = remaining.get(child);
          if (childParents) {
            childParents.delete(next);
            if (childParents.size === 0 && !emitted.has(child)) {
              ready.add(child);
            }
          }
        }
      }

      if (emitted.size === this.tables.size) {
        break;
      }

      // Whatever is left is trapped in a cycle.
      const stuck = new Set([...this.tables.keys()].filter((id) => !emitted.has(id)));
      const components = this.stronglyConnected(stuck, remaining);
      const component =
        components
          .filter((c) => c.length > 1)
          .sort((a, b) => a.length - b.length || a[0].localeCompare(b[0]))[0] ??
        components[0] ??
        [];

      cycles.push(component);
      const breakable = this.findBreakableEdge(component);
      if (!breakable) {
        throw new Error(
          `Foreign-key cycle cannot be broken: ${component.join(' -> ')}. ` +
            'Every foreign key in the cycle is NOT NULL, so no row in any of these tables can be inserted first.',
        );
      }

      deferred.push(breakable);
      notes.push(
        `Broke a foreign-key cycle by deferring ${breakable.name} (${describe(breakable)}): ` +
          'those columns are seeded NULL and filled by a second pass after both tables exist.',
      );

      const childParents = remaining.get(qualified(breakable.child));
      if (childParents) {
        childParents.delete(qualified(breakable.parent));
      }
      for (const [id, parents] of remaining) {
        if (parents.size === 0 && !emitted.has(id)) {
          ready.add(id);
        }
      }
      if (ready.size === 0) {
        throw new Error(`Deferring ${breakable.name} did not unblock the cycle.`);
      }
    }

    for (const fk of this.dangling) {
      notes.push(
        `Foreign key ${fk.name} (${describe(fk)}) points outside the dataset; those columns are seeded NULL.`,
      );
    }
    for (const fk of this.selfEdges) {
      notes.push(
        `Self-reference ${fk.name} on ${qualified(fk.child)} is satisfied from earlier rows of the same table.`,
      );
    }

    return { order: ordered, deferred, dangling: this.dangling, depth, cycles, notes };
  }

  /** One more than the deepest parent, ignoring parents reached by a deferred edge. */
  private depthOf(id: string, depth: Map<string, number>, deferred: ForeignKey[]): number {
    let max = -1;
    for (const parent of this.parents.get(id) ?? []) {
      const viaDeferred = deferred.some(
        (fk) => qualified(fk.child) === id && qualified(fk.parent) === parent,
      );
      if (viaDeferred) {
        continue;
      }
      const parentDepth = depth.get(parent);
      if (parentDepth !== undefined) {
        max = Math.max(max, parentDepth);
      }
    }
    return max + 1;
  }

  /** The edge to sacrifice: all-nullable, fewest columns, lowest name. */
  private findBreakableEdge(component: string[]): ForeignKey | null {
    const inCycle = new Set(component);
    const candidates = this.edges
      .filter((fk) => inCycle.has(qualified(fk.child)) && inCycle.has(qualified(fk.parent)))
      .filter((fk) => this.isNullable(fk))
      .sort((a, b) => a.childColumns.length - b.childColumns.length || a.name.localeCompare(b.name));
    return candidates[0] ?? null;
  }

  private isNullable(fk: ForeignKey): boolean {
    const child = this.tables.get(qualified(fk.child));
    if (!child) {
      return false;
    }
    return fk.childColumns.every(
      (name) => child.columns.find((column) => column.name === name)?.nullable === true,
    );
  }

  /** Tarjan's strongly connected components over the still-blocked tables. */
  private stronglyConnected(
    nodes: Set<string>,
    adjacency: Map<string, Set<string>>,
  ): string[][] {
    const index = new Map<string, number>();
    const lowLink = new Map<string, number>();
    const stack: string[] = [];
    const onStack = new Set<string>();
    const components: string[][] = [];
    let counter = 0;

    const strongConnect = (node: string): void => {
      index.set(node, counter);
      lowLink.set(node, counter);
      counter++;
      stack.push(node);
      onStack.add(node);

      const neighbours = [...(adjacency.get(node) ?? [])].filter((n) => nodes.has(n)).sort();
      for (const neighbour of neighbours) {
        if (!index.has(neighbour)) {
          strongConnect(neighbour);
          lowLink.set(node, Math.min(lowLink.get(node)!, lowLink.get(neighbour)!));
        } else if (onStack.has(neighbour)) {
          lowLink.set(node, Math.min(lowLink.get(node)!, index.get(neighbour)!));
        }
      }

      if (lowLink.get(node) === index.get(node)) {
        const component: string[] = [];
        let member: string;
        do {
          member = stack.pop()!;
          onStack.delete(member);
          component.push(member);
        } while (member !== node);
        components.push(component.sort());
      }
    };

    for (const node of [...nodes].sort()) {
      if (!index.has(node)) {
        strongConnect(node);
      }
    }
    return components;
  }

  roots(): string[] {
    return [...this.parents.entries()]
      .filter(([, parents]) => parents.size === 0)
      .map(([id]) => id)
      .sort();
  }

  childCount(id: string): number {
    return this.children.get(id)?.size ?? 0;
  }
}

export function describe(fk: ForeignKey): string {
  return (
    `${qualified(fk.child)}(${fk.childColumns.join(', ')}) -> ` +
    `${qualified(fk.parent)}(${fk.parentColumns.join(', ')})`
  );
}
