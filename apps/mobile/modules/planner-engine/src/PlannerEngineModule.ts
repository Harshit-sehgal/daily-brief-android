import { NativeModule, requireNativeModule } from 'expo';

/**
 * The narrow native bridge: previewPlan(requestJson) -> resultJson. The JSON shapes are
 * the planning-contract v1 wire types; the engine runs locally so a plan can be explored
 * without a server round-trip. The server stays authoritative for Apply.
 */
declare class PlannerEngineModule extends NativeModule<{}> {
  previewPlan(requestJson: string): Promise<string>;
}

export default requireNativeModule<PlannerEngineModule>('PlannerEngine');