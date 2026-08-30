/**
 * Deterministic randomness for generation.
 *
 * Every cell of the dataset gets its own stream, seeded from the dataset seed
 * plus the table, column and row index. Nothing depends on generation order or
 * on how many values were drawn before it, which is what lets row 7 be
 * identical whether you generate 8 rows or 800.
 *
 * The service does this with SplitMix64. JavaScript has no fast 64-bit integer
 * arithmetic, so this uses a 32-bit mixer of the same shape. The demo's numbers
 * therefore differ from the service's for the same seed; what carries over is
 * the property that matters, which is that a seed determines the dataset.
 */

/** FNV-1a over a string, as the seed for a cell's stream. */
export function hashString(value: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < value.length; i++) {
    hash ^= value.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}

/** Mixes two 32-bit values into one, avalanching well enough that adjacent rows are unrelated. */
export function mix(a: number, b: number): number {
  let hash = (a ^ Math.imul(b, 0x9e3779b1)) >>> 0;
  hash = Math.imul(hash ^ (hash >>> 16), 0x85ebca6b);
  hash = Math.imul(hash ^ (hash >>> 13), 0xc2b2ae35);
  return (hash ^ (hash >>> 16)) >>> 0;
}

/** The seed for one cell. */
export function cellSeed(datasetSeed: number, table: string, column: string, row: number): number {
  return mix(mix(mix(datasetSeed >>> 0, hashString(table)), hashString(column)), row + 1);
}

/** The seed for a table-level decision, such as its fanout. */
export function tableSeed(datasetSeed: number, table: string): number {
  return mix(mix(datasetSeed >>> 0, 0x94d049bb), hashString(table));
}

/**
 * A small, fast, deterministic generator.
 *
 * mulberry32: one multiply-xorshift round per draw, which is more than enough
 * for generating plausible values and cheap enough to run per cell.
 */
export class Rng {
  private state: number;

  constructor(seed: number) {
    this.state = seed >>> 0;
  }

  /** A double in the half-open unit interval. */
  next(): number {
    this.state = (this.state + 0x6d2b79f5) >>> 0;
    let t = this.state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }

  /** An integer in {@code [0, bound)}. */
  int(bound: number): number {
    return bound <= 0 ? 0 : Math.floor(this.next() * bound);
  }

  /** An integer in the inclusive range. */
  between(min: number, max: number): number {
    return min + this.int(max - min + 1);
  }

  bool(): boolean {
    return this.next() < 0.5;
  }

  /** True with the given percentage chance. */
  chance(percent: number): boolean {
    return this.int(100) < percent;
  }

  pick<T>(values: readonly T[]): T {
    return values[this.int(values.length)];
  }
}
