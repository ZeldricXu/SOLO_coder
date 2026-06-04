import { SimulationResult, SimpleScene, SimpleSimulationConfig, GPUInfo } from '@physics-sim/shared';
type Scene = SimpleScene;
type SimulationConfig = SimpleSimulationConfig;
type WorkerType = 'cpu' | 'gpu';
interface ComplexityEstimate {
    score: number;
    shouldOffload: boolean;
    supportsGPU: boolean;
    details: {
        objectCount: number;
        particleCount: number;
        gridResolution?: number;
        estimatedSteps: number;
    };
}
interface WorkerStats {
    totalThreads: number;
    activeThreads: number;
    idleThreads: number;
    queueSize: number;
    completedTasks: number;
    failedTasks: number;
}
interface SchedulerStats {
    cpu: WorkerStats;
    gpu: WorkerStats & {
        gpuInfo: GPUInfo;
    };
    pendingJobs: number;
}
export declare class SimulationScheduler {
    private cpuPool;
    private gpuPool;
    private jobs;
    private jobQueue;
    private maxQueueSize;
    private complexityThreshold;
    private gpuInfo;
    private gpuDetectionPromise;
    constructor();
    private detectCUDA;
    private initializeGPUPool;
    waitForInitialization(): Promise<void>;
    getGPUInfo(): Promise<GPUInfo>;
    estimateComplexity(scene: Scene, config: SimulationConfig): ComplexityEstimate;
    scheduleSimulation(scene: Scene, config: SimulationConfig, duration: number): Promise<string>;
    private processQueue;
    private hasIdleCPUWorker;
    private hasIdleGPUWorker;
    private runJob;
    getJobStatus(jobId: string): {
        id: string;
        status: "queued" | "running" | "completed" | "failed" | "cancelled";
        createdAt: number;
        startedAt: number | undefined;
        completedAt: number | undefined;
        error: string | undefined;
        progress: number;
        queuePosition: number;
        workerType: WorkerType | undefined;
        supportsGPU: boolean;
    } | null;
    getJobResult(jobId: string): Promise<SimulationResult | null>;
    cancelJob(jobId: string): boolean;
    getAllJobs(): {
        id: string;
        status: "queued" | "running" | "completed" | "failed" | "cancelled";
        createdAt: number;
        startedAt: number | undefined;
        completedAt: number | undefined;
        priority: number;
        workerType: WorkerType | undefined;
        supportsGPU: boolean;
    }[];
    getWorkerStats(): WorkerStats;
    getStats(): SchedulerStats;
    shutdown(): Promise<void>;
}
export {};
//# sourceMappingURL=SimulationScheduler.d.ts.map