import { Pipe, PipeTransform } from '@angular/core';

/** Compact counts: 1234 becomes 1.2k, which keeps table columns narrow. */
export function compactNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '-';
  }
  if (Math.abs(value) < 1000) {
    return String(value);
  }
  if (Math.abs(value) < 1_000_000) {
    return (value / 1000).toFixed(value % 1000 === 0 ? 0 : 1) + 'k';
  }
  return (value / 1_000_000).toFixed(1) + 'M';
}

/** A duration a person reads at a glance, not a precise one. */
export function humanDuration(millis: number | null | undefined): string {
  if (millis === null || millis === undefined) {
    return '-';
  }
  if (millis < 1000) {
    return `${Math.round(millis)}ms`;
  }
  const seconds = millis / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(seconds < 10 ? 1 : 0)}s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainder = Math.round(seconds % 60);
  if (minutes < 60) {
    return remainder === 0 ? `${minutes}m` : `${minutes}m ${remainder}s`;
  }
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

/** Time remaining, phrased as a countdown rather than a timestamp. */
export function remaining(seconds: number): string {
  if (seconds <= 0) {
    return 'expired';
  }
  if (seconds < 60) {
    return `${Math.round(seconds)}s left`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m left`;
  }
  const hours = Math.floor(minutes / 60);
  const remainderMinutes = minutes % 60;
  return remainderMinutes === 0 ? `${hours}h left` : `${hours}h ${remainderMinutes}m left`;
}

/** How long ago something happened. */
export function relativeTime(iso: string | null | undefined): string {
  if (!iso) {
    return '-';
  }
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return '-';
  }
  const deltaSeconds = (Date.now() - then) / 1000;
  if (deltaSeconds < 45) {
    return 'just now';
  }
  if (deltaSeconds < 3600) {
    return `${Math.round(deltaSeconds / 60)}m ago`;
  }
  if (deltaSeconds < 86_400) {
    return `${Math.round(deltaSeconds / 3600)}h ago`;
  }
  if (deltaSeconds < 604_800) {
    return `${Math.round(deltaSeconds / 86_400)}d ago`;
  }
  return new Date(iso).toLocaleDateString();
}

/** A pill class matching a job status. */
export function statusPill(status: string): string {
  switch (status) {
    case 'SUCCEEDED':
      return 'pill pill-ok';
    case 'RUNNING':
      return 'pill pill-accent';
    case 'PENDING':
      return 'pill pill-muted';
    case 'FAILED':
      return 'pill pill-danger';
    case 'CANCELLED':
      return 'pill pill-warn';
    default:
      return 'pill pill-muted';
  }
}

/** A pill class matching a lease state. */
export function leasePill(state: string): string {
  switch (state) {
    case 'ACTIVE':
      return 'pill pill-ok';
    case 'EXPIRED':
      return 'pill pill-muted';
    case 'RELEASED':
      return 'pill pill-muted';
    case 'REVOKED':
      return 'pill pill-warn';
    case 'FAILED':
      return 'pill pill-danger';
    default:
      return 'pill pill-muted';
  }
}

/**
 * A pill class matching a masking strategy. PRESERVE is deliberately the only
 * one that reads as a warning: it is the case a reviewer needs to notice.
 */
export function maskPill(strategy: string): string {
  return strategy === 'PRESERVE' ? 'pill pill-warn' : 'pill pill-info';
}

/** How many of a table plan's columns will be masked. */
@Pipe({ name: 'maskedCount' })
export class MaskedCountPipe implements PipeTransform {
  transform(columns: { mask: string }[] | null | undefined): number {
    return (columns ?? []).filter((column) => column.mask !== 'PRESERVE').length;
  }
}

@Pipe({ name: 'compact' })
export class CompactPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return compactNumber(value);
  }
}

@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(millis: number | null | undefined): string {
    return humanDuration(millis);
  }
}

@Pipe({ name: 'ago' })
export class AgoPipe implements PipeTransform {
  transform(iso: string | null | undefined): string {
    return relativeTime(iso);
  }
}

@Pipe({ name: 'remaining' })
export class RemainingPipe implements PipeTransform {
  transform(seconds: number): string {
    return remaining(seconds);
  }
}
