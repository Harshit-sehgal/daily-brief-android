import type { PlanRun, PlanningRequest } from '../../../src/lib/types';

/** previewPlan mirrors the server's /v1/plan (same mapping, same engine), but runs
 *  locally through the native bridge. */
export type PreviewPlan = (request: PlanningRequest) => Promise<PlanRun>;