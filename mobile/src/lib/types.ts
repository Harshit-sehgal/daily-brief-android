/** The wire shapes of planning-contract, as TypeScript. The client renders; the server is
 *  authoritative for Apply. Mirrors web/lib/types.ts. */
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
  dependencies: unknown[];
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
  demands: Array<{
    itemId: string;
    remainingEffortMinutes?: number | null;
    scheduledMinutes: number;
    unscheduledMinutes?: number | null;
  }>;
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