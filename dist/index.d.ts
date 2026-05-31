import { Express } from 'express';
import { EdgeInferenceScheduler } from './edge-inference';
import { MonitoringService } from './monitoring';
import { DataAccessLayer, CacheInvalidationManager } from './data-access';
import { PipelineProcessor, DataStandardizer } from './core-processing';
import { EdgeDataAggregator } from './edge-aggregation';
import { StorageManager } from './storage-management';
import { TaskScheduler } from './scheduling';
import { RuleEngine } from './rule-engine';
import { DeviceLifecycleManager } from './device-lifecycle';
import { ProtocolAdapterManager } from './protocol-adapter';
declare class AIEdgePlatform {
    private app;
    private port;
    inferenceScheduler: EdgeInferenceScheduler;
    monitoring: MonitoringService;
    dataAccess: DataAccessLayer;
    cacheInvalidation: CacheInvalidationManager;
    pipelineProcessor: PipelineProcessor;
    dataStandardizer: DataStandardizer;
    dataAggregator: EdgeDataAggregator;
    storageManager: StorageManager;
    taskScheduler: TaskScheduler;
    ruleEngine: RuleEngine;
    deviceManager: DeviceLifecycleManager;
    protocolAdapter: ProtocolAdapterManager;
    constructor(port?: number);
    private setupMiddleware;
    private setupModuleInteractions;
    private setupRoutes;
    start(): Promise<void>;
    stop(): Promise<void>;
    getApp(): Express;
}
export default AIEdgePlatform;
//# sourceMappingURL=index.d.ts.map