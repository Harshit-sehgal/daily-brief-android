import ExpoModulesCore
import PlannerCore

public class PlannerEngineModule: Module {
  public func definition() -> ModuleDefinition {
    Name("PlannerEngine")

    // Narrow bridge, same contract as the Android side: one request JSON string in, one
    // result JSON string out. PlannerCore is the XCFramework built by
    // scripts/build-ios-framework.sh on macOS (planning-core iosArm64 + iosSimulatorArm64).
    AsyncFunction("previewPlan") { (requestJson: String) -> String in
      try EnginePreview.shared.previewPlan(requestJson: requestJson)
    }
  }
}