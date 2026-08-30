/**
 * The wire types, mirroring the service's DTOs.
 *
 * Hand-written rather than generated from the OpenAPI document: the console
 * uses a small, stable subset of the API, and a generator would drag in a build
 * step plus a few hundred lines of types nothing here references.
 */

export type JobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export type JobPhase =
  | 'QUEUED'
  | 'INTROSPECTING'
  | 'PLANNING'
  | 'PROVISIONING'
  | 'APPLYING_DDL'
  | 'GENERATING'
  | 'SEEDING'
  | 'VERIFYING'
  | 'SNAPSHOTTING'
  | 'LEASING'
  | 'DONE';

export type LeaseState = 'ACTIVE' | 'EXPIRED' | 'RELEASED' | 'REVOKED' | 'FAILED';

export type ColumnRole =
  | 'DATABASE_GENERATED'
  | 'PRIMARY_KEY'
  | 'FOREIGN_KEY'
  | 'SELF_REFERENCE'
  | 'DEFERRED_FOREIGN_KEY'
  | 'DANGLING_FOREIGN_KEY'
  | 'VALUE';

export type MaskStrategy =
  | 'PRESERVE'
  | 'REDACT'
  | 'HASH'
  | 'PARTIAL'
  | 'EMAIL'
  | 'PHONE'
  | 'NAME'
  | 'SSN'
  | 'CREDIT_CARD'
  | 'IBAN'
  | 'DATE_SHIFT'
  | 'NUMERIC_JITTER'
  | 'TOKENIZE'
  | 'NULLIFY';

export interface Target {
  id: string;
  displayName: string;
  schema: string;
  jdbcUrl: string;
}

export interface ColumnMeta {
  name: string;
  position: number;
  formattedType: string;
  udtName: string;
  nullable: boolean;
  maxLength: number | null;
  defaultExpression: string | null;
  identity: boolean;
  generated: boolean;
  serial: boolean;
  enumLabels: string[];
  arrayElementType: string | null;
  comment: string | null;
  dataClass: string;
}

export interface ForeignKey {
  name: string;
  child: { schema: string; name: string };
  childColumns: string[];
  parent: { schema: string; name: string };
  parentColumns: string[];
  onDelete: string;
  onUpdate: string;
}

export interface TableMeta {
  ref: { schema: string; name: string };
  columns: ColumnMeta[];
  primaryKey: { name: string; columns: string[] } | null;
  foreignKeys: ForeignKey[];
  uniques: { name: string; columns: string[]; fromIndex: boolean }[];
  checks: { name: string; expression: string; columns: string[] }[];
  estimatedRows: number;
  comment: string | null;
}

export interface SchemaSnapshot {
  database: string;
  schema: string;
  capturedAt: string;
  tables: TableMeta[];
  fingerprint: string;
}

export interface GraphNode {
  id: string;
  name: string;
  depth: number;
  seedOrder: number;
  columns: number;
  foreignKeys: number;
  referencedBy: number;
  sensitiveColumns: number;
}

export interface GraphEdge {
  from: string;
  to: string;
  constraint: string;
  fromColumns: string[];
  toColumns: string[];
  deferred: boolean;
}

export interface SchemaGraph {
  schema: string;
  fingerprint: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
  maxDepth: number;
  levels: Record<string, string[]>;
  cycles: string[][];
  notes: string[];
}

export interface ColumnPlan {
  column: string;
  sqlType: string;
  dataClass: string;
  role: ColumnRole;
  generator: string;
  mask: MaskStrategy;
  maskSource: string;
  unique: boolean;
  nullable: boolean;
  parentTable: string | null;
  parentColumn: string | null;
}

export interface TablePlan {
  table: { schema: string; name: string };
  order: number;
  depth: number;
  rowCount: number;
  columns: ColumnPlan[];
  deferredEdges: ForeignKey[];
  selfEdges: ForeignKey[];
}

export interface GenerationPlan {
  seed: number;
  schema: string;
  snapshotFingerprint: string;
  tables: TablePlan[];
  totalRows: number;
  maskedColumns: number;
  warnings: string[];
}

export interface JobEvent {
  phase: JobPhase;
  label: string;
  message: string;
  at: string;
}

export interface Job {
  id: string;
  datasetId: string;
  requestedBy: string;
  status: JobStatus;
  phase: JobPhase;
  phaseLabel: string;
  percent: number;
  message: string | null;
  error: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  elapsedMs: number;
  metrics: Record<string, number>;
  events: JobEvent[];
}

export interface Lease {
  id: string;
  datasetId: string;
  databaseName: string;
  jdbcUrl: string;
  username: string;
  connectionString: string | null;
  holder: string;
  state: LeaseState;
  renewals: number;
  renewalsRemaining: number;
  issuedAt: string;
  expiresAt: string;
  remainingSeconds: number;
  closedAt: string | null;
}

export interface DatasetSummary {
  id: string;
  name: string;
  requestedBy: string;
  targetId: string;
  schema: string;
  status: JobStatus;
  totalRows: number;
  maskedColumns: number;
  durationMs: number | null;
  snapshotUri: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface Dataset extends DatasetSummary {
  description: string | null;
  snapshotId: string | null;
  seed: number;
  scale: number;
  plan: GenerationPlan | null;
  error: string | null;
}

export interface DatasetStats {
  total: number;
  succeeded: number;
  failed: number;
  inFlight: number;
  rowsGenerated: number;
  avgDurationMs: number;
}

export interface MaskingRecord {
  datasetId: string;
  table: string;
  column: string;
  dataClass: string;
  strategy: MaskStrategy;
  ruleSource: string;
}

export interface MaskingRuleInput {
  table: string;
  column: string;
  strategy: MaskStrategy;
  options?: Record<string, string>;
}

export interface DatasetRequestInput {
  name: string;
  description?: string;
  requestedBy: string;
  targetId: string;
  schema?: string;
  includeTables?: string[];
  excludeTables?: string[];
  scale?: number;
  rowOverrides?: Record<string, number>;
  seed?: number;
  ttlMinutes?: number;
  maskSensitiveByDefault?: boolean;
  maskingRules?: MaskingRuleInput[];
  exportSnapshot?: boolean;
}

/** RFC 9457 problem detail, which the service returns for every failure. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  timestamp?: string;
  fields?: Record<string, string>;
  cycle?: string[];
  column?: string;
}
