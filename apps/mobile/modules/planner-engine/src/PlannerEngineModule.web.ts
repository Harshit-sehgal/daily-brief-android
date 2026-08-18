import { registerWebModule, NativeModule } from 'expo';

class PlannerEngineModule extends NativeModule<{}> {}

export default registerWebModule(PlannerEngineModule, 'PlannerEngineModule');
