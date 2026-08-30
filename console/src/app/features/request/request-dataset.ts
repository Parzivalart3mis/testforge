import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiError, TestForgeService } from '../../core/testforge.service';
import {
  DatasetRequestInput,
  GenerationPlan,
  MaskStrategy,
  MaskingRuleInput,
  SchemaSnapshot,
  Target,
} from '../../core/api.types';
import { CompactPipe, MaskedCountPipe, maskPill } from '../../core/format';

/** Strategies offered in the rule editor, in the order a person is likely to want them. */
const STRATEGIES: MaskStrategy[] = [
  'PRESERVE',
  'HASH',
  'REDACT',
  'PARTIAL',
  'EMAIL',
  'PHONE',
  'NAME',
  'SSN',
  'CREDIT_CARD',
  'IBAN',
  'DATE_SHIFT',
  'NUMERIC_JITTER',
  'TOKENIZE',
  'NULLIFY',
];

@Component({
  selector: 'tf-request-dataset',
  imports: [FormsModule, CompactPipe, MaskedCountPipe],
  templateUrl: './request-dataset.html',
  styleUrl: './request-dataset.css',
})
export class RequestDataset {
  private readonly api = inject(TestForgeService);
  private readonly router = inject(Router);

  protected readonly strategies = STRATEGIES;
  protected readonly maskPill = maskPill;

  protected readonly targets = signal<Target[]>([]);
  protected readonly schema = signal<SchemaSnapshot | null>(null);
  protected readonly plan = signal<GenerationPlan | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly previewing = signal(false);
  protected readonly submitting = signal(false);

  // --- form state -------------------------------------------------------

  protected name = 'checkout fixtures';
  protected description = '';
  protected requestedBy = 'console@example.com';
  protected targetId = '';
  protected scale = 100;
  protected ttlMinutes = 240;
  protected seed: number | null = null;
  protected maskSensitiveByDefault = true;
  protected exportSnapshot = false;

  protected readonly selectedTables = signal<Set<string>>(new Set());
  protected readonly rules = signal<MaskingRuleInput[]>([]);

  protected readonly tableNames = computed(
    () => this.schema()?.tables.map((table) => table.ref.name) ?? [],
  );

  /** Columns the classifier flagged as sensitive, so the form can show what will be masked. */
  protected readonly sensitiveColumns = computed(() => {
    const snapshot = this.schema();
    if (!snapshot) {
      return [];
    }
    return snapshot.tables.flatMap((table) =>
      table.columns
        .filter((column) => SENSITIVE_CLASSES.has(column.dataClass))
        .map((column) => ({
          table: table.ref.name,
          column: column.name,
          dataClass: column.dataClass,
        })),
    );
  });

  protected readonly selectionLabel = computed(() => {
    const count = this.selectedTables().size;
    if (count === 0) {
      return `all ${this.tableNames().length} tables`;
    }
    return `${count} of ${this.tableNames().length} tables`;
  });

  constructor() {
    this.api.listTargets().subscribe({
      next: (targets) => {
        this.targets.set(targets);
        if (targets.length > 0 && !this.targetId) {
          this.targetId = targets[0].id;
          this.loadSchema();
        }
      },
      error: (err: ApiError) => this.error.set(err.message),
    });
  }

  // --- schema -----------------------------------------------------------

  protected loadSchema(): void {
    if (!this.targetId) {
      return;
    }
    this.schema.set(null);
    this.selectedTables.set(new Set());
    this.plan.set(null);

    this.api.schema(this.targetId).subscribe({
      next: (snapshot) => this.schema.set(snapshot),
      error: (err: ApiError) => this.error.set(err.message),
    });
  }

  protected toggleTable(name: string): void {
    const next = new Set(this.selectedTables());
    if (next.has(name)) {
      next.delete(name);
    } else {
      next.add(name);
    }
    this.selectedTables.set(next);
  }

  protected isSelected(name: string): boolean {
    return this.selectedTables().has(name);
  }

  protected selectAll(): void {
    this.selectedTables.set(new Set());
  }

  // --- masking rules ----------------------------------------------------

  protected addRule(): void {
    this.rules.update((rules) => [
      ...rules,
      { table: '*', column: '', strategy: 'HASH' as MaskStrategy },
    ]);
  }

  protected removeRule(index: number): void {
    this.rules.update((rules) => rules.filter((_, i) => i !== index));
  }

  protected updateRule(index: number, patch: Partial<MaskingRuleInput>): void {
    this.rules.update((rules) =>
      rules.map((rule, i) => (i === index ? { ...rule, ...patch } : rule)),
    );
  }

  // --- actions ----------------------------------------------------------

  /**
   * Builds the request body. Empty optional fields are omitted rather than sent
   * as blanks, so the service's own defaults apply and the two cannot drift.
   */
  private body(): DatasetRequestInput {
    const selected = [...this.selectedTables()];
    return {
      name: this.name.trim(),
      description: this.description.trim() || undefined,
      requestedBy: this.requestedBy.trim(),
      targetId: this.targetId,
      includeTables: selected.length > 0 ? selected : undefined,
      scale: this.scale,
      ttlMinutes: this.ttlMinutes,
      seed: this.seed ?? undefined,
      maskSensitiveByDefault: this.maskSensitiveByDefault,
      maskingRules: this.rules().filter((rule) => rule.column.trim().length > 0),
      exportSnapshot: this.exportSnapshot,
    };
  }

  protected get canSubmit(): boolean {
    return (
      this.name.trim().length > 0 &&
      this.requestedBy.trim().length > 0 &&
      this.targetId.length > 0 &&
      !this.submitting()
    );
  }

  /** The dry run. Introspects and plans, provisions nothing. */
  protected preview(): void {
    this.previewing.set(true);
    this.error.set(null);
    this.plan.set(null);

    this.api.previewPlan(this.body()).subscribe({
      next: (plan) => {
        this.plan.set(plan);
        this.previewing.set(false);
      },
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.previewing.set(false);
      },
    });
  }

  protected submit(): void {
    this.submitting.set(true);
    this.error.set(null);

    this.api.requestDataset(this.body()).subscribe({
      next: (job) => this.router.navigate(['/jobs', job.id]),
      error: (err: ApiError) => {
        this.error.set(err.message);
        this.submitting.set(false);
      },
    });
  }
}

/** Mirrors DataClass.sensitive() on the service side. */
const SENSITIVE_CLASSES = new Set([
  'GIVEN_NAME',
  'FAMILY_NAME',
  'FULL_NAME',
  'USERNAME',
  'EMAIL',
  'PHONE',
  'SSN',
  'NATIONAL_ID',
  'CREDIT_CARD',
  'IBAN',
  'DATE_OF_BIRTH',
  'STREET_ADDRESS',
  'CITY',
  'REGION',
  'POSTAL_CODE',
  'LATITUDE',
  'LONGITUDE',
  'IP_ADDRESS',
  'MAC_ADDRESS',
  'PASSWORD_HASH',
  'API_TOKEN',
]);
