import { ParameterSweepConfig, ParameterSweepResult } from '@physics-sim/shared';
import { SimulationScheduler } from './SimulationScheduler.js';
export declare class ParameterSweepService {
    private sweeps;
    private scheduler;
    constructor(scheduler: SimulationScheduler);
    startSweep(config: ParameterSweepConfig): Promise<string>;
    private pollResults;
    private applyParameter;
    private applySceneParameter;
    private extractResult;
    getSweepStatus(sweepId: string): ParameterSweepResult | null;
    cancelSweep(sweepId: string): boolean;
}
//# sourceMappingURL=ParameterSweepService.d.ts.map