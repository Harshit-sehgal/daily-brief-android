package expo.modules.plannerengine

import com.example.core.EnginePreview
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * The narrow native bridge: one request JSON string in, one result JSON string out.
 * All engine and contract types stay inside planning-core's PlannerCore/engine artifacts;
 * the app only ever sees strings, and the server stays authoritative for Apply.
 *
 * The JSON shapes are the planning-contract wire types (v1), and [EnginePreview] runs the
 * exact mapping the server runs, so a phone preview matches what a server run would plan.
 */
class PlannerEngineModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("PlannerEngine")

    AsyncFunction("previewPlan") { requestJson: String ->
      EnginePreview.previewPlan(requestJson)
    }
  }
}