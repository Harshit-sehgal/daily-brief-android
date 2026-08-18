/** The wire shapes of planning-contract, as TypeScript. The client renders; the server is
 *  authoritative for Apply. */
export interface Task {
  id: string;
  boardId: string;
  columnId?: string | null;
  parentId?: string | null;
  title: string;
  notes?: string | null;
  rank: number;
  startConstraint?: number | null;
  dueAt?: number | null;
  effortMinutes?: number | null;
  progress?: number;
  priority?: string;
  owner?: string | null;
  schedulingMode?: string;
  locked?: boolean;
  isMilestone?: boolean;
  completedAt?: number | null;
  archivedAt?: number | null;
}

export interface Interval {
  startAt: number;
  endAt: number;
}

export interface WorkWindow {
  id: string;
  dayOfWeek: number;
  startMinute: number;
  endMinute: number;
  rank: number;
}

export interface WorkSchedule {
  id: string;
  name: string;
  timeZoneId: string;
  isDefault: boolean;
  minimumChunkMinutes: number;
  maximumChunkMinutes: number;
  bufferMinutes: number;
  rank: number;
  windows: WorkWindow[];
}

export interface PlanningRequest {
  v: number;
  workspaceId: string;
  rangeStartMs: number;
  rangeEndMs: number;
  nowMs: number;
  items: Task[];
  blocks: unknown[];
  fixedCommitments: Interval[];
  dependencies: Dependency[];
  scheduleIdByTaskId: Record<string, string>;
  preferredOrder: string[];
  schedules: WorkSchedule[];
  deadlinePolicy: string;
}

export interface Proposal {
  itemId: string;
  startAt: number;
  endAt: number;
  reason: string;
}

export interface Unplaced {
  itemId: string;
  reason: string;
}

export interface Health {
  assessment: string;
  workingMinutes: number;
  capacityAfterCommitmentsMinutes: number;
  overloadMinutes: number;
  missingEstimateCount: number;
  unscheduledDemandMinutes: number;
  demands: Array<{ itemId: string; remainingEffortMinutes?: number | null; scheduledMinutes: number; unscheduledMinutes?: number | null }>;
  risks: Array<{ kind: string; itemId?: string | null; explanation: string }>;
  repairs: Array<{ action: string; explanation: string; itemId?: string | null }>;
  warnings: string[];
  explanation: string;
}

export interface PlanningResult {
  v: number;
  proposals: Proposal[];
  unplaced: Unplaced[];
  explanation: string;
  health: Health;
}

export interface PlanRun {
  runId: string;
  result: PlanningResult;
}

export interface ApplyResponse {
  blocks: number;
  entryId: string;
}

export interface ExternalEvent {
  id: string;
  title: string;
  startTime: number;
  endTime: number;
  source: string;
  description?: string | null;
  isDeadline?: boolean;
  isUrgent?: boolean;
  isAllDay?: boolean;
  location?: string | null;
  kanbanStatus?: string;
  kanbanBoard?: string;
  userEdited?: boolean;
}

export interface ScheduledBlock {
  id: string;
  planItemId: string;
  startAt: number;
  endAt: number;
  position?: number;
  locked?: boolean;
  linkedEventId?: string | null;
}

export interface TodayResponse {
  date: string;
  events: ExternalEvent[];
  blocks: ScheduledBlock[];
  conflicts: Array<{ first: ExternalEvent; second: ExternalEvent; overlapMs: number }>;
  busyMinutes: number;
  freeMinutes: number;
}

export interface SessionResponse {
  workspaceId: string;
  token: string;
}
export interface CapacityRequest {
  v: number;
  plan: PlanningRequest;
  newClientHoursPerWeek: number;
}

export interface CapacityMove {
  itemId: string;
  title: string;
  unscheduledMinutes: number;
}

export interface CapacityResponse {
  v: number;
  availableMinutes: number;
  plannedMinutes: number;
  spareMinutes: number;
  verdict: "CAN_TAKE" | "MOVE" | "CANNOT" | "INCOMPLETE";
  sentence: string;
  moves: CapacityMove[];
}

export interface Project {
  v: number;
  id: string;
  workspaceId: string;
  name: string;
  isDefault: boolean;
  rank: number;
  archivedAt?: number | null;
}

export interface Stage {
  id: string;
  name: string;
  rank: number;
  archivedAt?: number | null;
}

export interface Board {
  v: number;
  project: Project;
  stages: Stage[];
  tasks: Task[];
}

export interface Dependency {
  id: string;
  predecessorId: string;
  successorId: string;
  type: string;
  lagMinutes: number;
}

export interface ScenarioOrdering {
  key: string;
  name: string;
  rationale: string;
  preferredOrder: string[];
}

export interface ScenarioResult {
  key: string;
  name: string;
  rationale: string;
  preferredOrder: string[];
  result: PlanRun["result"];
}

export interface ScenarioResponse {
  v: number;
  scenarios: ScenarioResult[];
  spread: string;
}

export interface BaselineSnapshot {
  v: number;
  id: string;
  workspaceId: string;
  createdAt: number;
  taskCount: number;
  blockCount: number;
}

export interface TaskVariance {
  itemId: string;
  title: string;
  baselineStartMs?: number | null;
  currentStartMs?: number | null;
  baselineMinutes: number;
  currentMinutes: number;
  driftMinutes?: number | null;
  addedSinceBaseline: boolean;
  removedSinceBaseline: boolean;
}

export interface BaselineComparison {
  v: number;
  summary: string;
  rows: TaskVariance[];
}

export interface PortfolioBlock {
  itemId: string;
  title: string;
  startAt: number;
  endAt: number;
}

export interface PortfolioRow {
  projectId: string;
  projectName: string;
  openTasks: number;
  doneTasks: number;
  statedEffortMinutes: number;
  scheduledMinutes: number;
  overdueTasks: number;
  unestimatedTasks: number;
  weekBlocks: PortfolioBlock[];
}

export interface PortfolioResponse {
  v: number;
  rows: PortfolioRow[];
  note: string;
}

export interface TierLimits {
  maxProjects: number;
  baselines: boolean;
  capacity: boolean;
}

export interface TierUsage {
  projects: number;
  baselines: number;
}

export interface BillingResponse {
  v: number;
  tier: string;
  status: string;
  trialEndsAt?: number | null;
  limits: TierLimits;
  usage: TierUsage;
  checkoutUrl?: string | null;
}

export interface SummaryResponse {
  v: number;
  date: string;
  text: string;
  source: string;
  used: number;
  limit: number;
}
