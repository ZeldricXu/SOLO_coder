import Piscina from 'piscina';
import path from 'path';
import os from 'os';
import { execFile } from 'child_process';
import { randomUUID } from 'crypto';
import { SimulationResult, SimpleScene, SimpleSimulationConfig, GPUInfo } from '@physics-sim/shared';
type Scene = SimpleScene;
type SimulationConfig = SimpleSimulationConfig;

declare const __dirname: string;
const schedulerDir: string = typeof __dirname !== 'undefined' ? __dirname : process.cwd();

type WorkerType = 'cpu' | 'gpu';

interface Job {
  id: string;
  status: 'queued' | 'running' | 'completed' | 'failed' | 'cancelled';
  scene: Scene;
  config: SimulationConfig;
  duration: number;
  priority: number;
  createdAt: number;
  startedAt?: number;
  completedAt?: number;
  result?: SimulationResult;
  error?: string;
  workerId?: number;
  workerType?: WorkerType;
  supportsGPU: boolean;
}

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
  gpu: WorkerStats & { gpuInfo: GPUInfo };
  pendingJobs: number;
}

export class SimulationScheduler {
  private cpuPool: Piscina;
  private gpuPool: Piscina | null = null;
  private jobs: Map<string, Job> = new Map();
  private jobQueue: string[] = [];
  private maxQueueSize = 100;
  private complexityThreshold = 1000;
  private gpuInfo: GPUInfo = {
    available: false,
    count: 0,
    devices: [],
  };
  private gpuDetectionPromise: Promise<GPUInfo> | null = null;

  constructor() {
    const workerPath = path.resolve(schedulerDir, '../workers/simulationWorker.js');
    
    this.cpuPool = new Piscina({
      filename: workerPath,
      minThreads: 2,
      maxThreads: Math.min(8, Math.max(2, os.cpus().length - 1)),
      idleTimeout: 30000,
    });

    this.cpuPool.on('error', (err) => {
      console.error('CPU Worker pool error:', err);
    });

    this.gpuDetectionPromise = this.detectCUDA();
    this.gpuDetectionPromise.then((info) => {
      this.gpuInfo = info;
      if (info.available && info.count > 0) {
        this.initializeGPUPool(workerPath);
      }
      console.log(`✅ SimulationScheduler initialized with ${this.cpuPool.threads.length} CPU workers, GPU: ${info.available ? info.count + ' devices' : 'none'}`);
    });

    console.log(`🔄 SimulationScheduler initializing... detecting CUDA...`);
  }

  private async detectCUDA(): Promise<GPUInfo> {
    return new Promise((resolve) => {
      execFile('nvidia-smi', [
        '--query-gpu=index,name,memory.total,memory.free',
        '--format=csv,noheader,nounits',
      ], { timeout: 5000 }, (error, stdout) => {
        if (error) {
          resolve({ available: false, count: 0, devices: [] });
          return;
        }

        const devices: GPUInfo['devices'] = [];
        const lines = stdout.trim().split('\n').filter(l => l.trim());
        
        for (const line of lines) {
          const parts = line.split(',').map(p => p.trim());
          if (parts.length >= 4) {
            devices.push({
              id: parseInt(parts[0], 10),
              name: parts[1],
              memoryTotal: parseInt(parts[2], 10),
              memoryFree: parseInt(parts[3], 10),
            });
          }
        }

        execFile('nvidia-smi', ['--query-gpu=driver_version', '--format=csv,noheader,nounits'], { timeout: 3000 }, (verror, vstdout) => {
          const cudaVersion = verror ? undefined : vstdout.trim();
          resolve({
            available: devices.length > 0,
            count: devices.length,
            devices,
            cudaVersion,
          });
        });
      });
    });
  }

  private initializeGPUPool(workerPath: string): void {
    try {
      this.gpuPool = new Piscina({
        filename: workerPath,
        minThreads: 1,
        maxThreads: Math.min(2, this.gpuInfo.count),
        idleTimeout: 60000,
        env: {
          ...process.env,
          CUDA_VISIBLE_DEVICES: this.gpuInfo.devices.map(d => d.id).join(','),
          USE_GPU: '1',
        },
      });

      this.gpuPool.on('error', (err) => {
        console.error('GPU Worker pool error:', err);
      });

      console.log(`✅ GPU worker pool initialized with ${this.gpuPool.threads.length} workers`);
    } catch (error) {
      console.warn('⚠️ Failed to initialize GPU worker pool, falling back to CPU only:', error);
      this.gpuPool = null;
      this.gpuInfo.available = false;
    }
  }

  async waitForInitialization(): Promise<void> {
    if (this.gpuDetectionPromise) {
      await this.gpuDetectionPromise;
    }
  }

  async getGPUInfo(): Promise<GPUInfo> {
    await this.waitForInitialization();
    return { ...this.gpuInfo };
  }

  estimateComplexity(scene: Scene, config: SimulationConfig): ComplexityEstimate {
    const objects = Array.isArray(scene.objects) ? scene.objects : Array.from(scene.objects.values());
    const objectCount = objects.length;
    const particleCount = objects.filter(
      (obj: any) => (obj.objectType || obj.type) === 'particle'
    ).length;
    
    const gridResolution = config.gridResolution || 0;
    const estimatedSteps = Math.ceil(config.duration / (config.timeStep || 0.01));
    
    const objectScore = objectCount * 10;
    const particleScore = particleCount * 0.5;
    const gridScore = gridResolution > 0 ? Math.pow(gridResolution, 3) * 0.001 : 0;
    const stepScore = estimatedSteps * 0.1;
    
    const score = objectScore + particleScore + gridScore + stepScore;
    const shouldOffload = score > this.complexityThreshold || particleCount > 10000 || gridResolution >= 128;
    
    const physicsTypes = config.physicsTypes || [];
    const supportsGPU = config.supportsGPU ?? (
      (gridResolution >= 64 && physicsTypes.some(t => 
        ['electromagnetics', 'thermodynamics', 'fluiddynamics'].includes(t)
      )) || particleCount > 5000
    );

    return {
      score,
      shouldOffload,
      supportsGPU,
      details: {
        objectCount,
        particleCount,
        gridResolution,
        estimatedSteps,
      },
    };
  }

  async scheduleSimulation(
    scene: Scene,
    config: SimulationConfig,
    duration: number
  ): Promise<string> {
    if (this.jobQueue.length >= this.maxQueueSize) {
      throw new Error('Job queue is full. Please try again later.');
    }

    if (this.gpuDetectionPromise) {
      await this.gpuDetectionPromise;
    }

    const jobId = randomUUID();
    const complexity = this.estimateComplexity(scene, config);
    
    const priority = complexity.score > 5000 ? 0 : complexity.score > 1000 ? 1 : 2;

    const job: Job = {
      id: jobId,
      status: 'queued',
      scene,
      config,
      duration,
      priority,
      createdAt: Date.now(),
      supportsGPU: complexity.supportsGPU && this.gpuInfo.available,
    };

    this.jobs.set(jobId, job);
    this.jobQueue.push(jobId);
    
    this.processQueue();

    return jobId;
  }

  private async processQueue() {
    const pendingJobs = this.jobQueue
      .map((id) => this.jobs.get(id)!)
      .filter((job) => job.status === 'queued')
      .sort((a, b) => {
        if (a.supportsGPU !== b.supportsGPU) {
          return a.supportsGPU ? -1 : 1;
        }
        return a.priority - b.priority || a.createdAt - b.createdAt;
      });

    for (const job of pendingJobs) {
      if (job.supportsGPU && this.gpuPool && this.hasIdleGPUWorker()) {
        await this.runJob(job.id, 'gpu');
      } else if (this.hasIdleCPUWorker()) {
        await this.runJob(job.id, 'cpu');
      }
    }
  }

  private hasIdleCPUWorker(): boolean {
    return this.cpuPool.threads.some((t: any) => t.idle);
  }

  private hasIdleGPUWorker(): boolean {
    if (!this.gpuPool) return false;
    return this.gpuPool.threads.some((t: any) => t.idle);
  }

  private async runJob(jobId: string, workerType: WorkerType) {
    const job = this.jobs.get(jobId);
    if (!job || job.status !== 'queued') return;

    const pool = workerType === 'gpu' && this.gpuPool ? this.gpuPool : this.cpuPool;
    
    job.status = 'running';
    job.startedAt = Date.now();
    job.workerType = workerType;
    
    const queueIndex = this.jobQueue.indexOf(jobId);
    if (queueIndex > -1) {
      this.jobQueue.splice(queueIndex, 1);
    }

    try {
      console.log(`🔄 Starting ${workerType.toUpperCase()} simulation job: ${jobId}`);
      
      const result = await pool.run(
        {
          scene: job.scene,
          config: {
            ...job.config,
            useGPU: workerType === 'gpu',
          },
          duration: job.duration,
          jobId,
        },
        { name: 'runSimulation' }
      );

      job.status = 'completed';
      job.completedAt = Date.now();
      job.result = result as SimulationResult;
      
      const duration = ((job.completedAt - job.startedAt!) / 1000).toFixed(2);
      console.log(`✅ ${workerType.toUpperCase()} simulation job ${jobId} completed in ${duration}s`);
    } catch (error) {
      if (workerType === 'gpu' && this.gpuPool) {
        console.warn(`⚠️ GPU job ${jobId} failed, retrying on CPU:`, (error as Error).message);
        job.status = 'queued';
        job.supportsGPU = false;
        job.error = `GPU failed: ${(error as Error).message} - retried on CPU`;
        this.jobQueue.push(jobId);
      } else {
        job.status = 'failed';
        job.completedAt = Date.now();
        job.error = (error as Error).message;
        console.error(`❌ ${workerType.toUpperCase()} simulation job ${jobId} failed:`, error);
      }
    }

    setImmediate(() => this.processQueue());
  }

  getJobStatus(jobId: string) {
    const job = this.jobs.get(jobId);
    if (!job) return null;

    return {
      id: job.id,
      status: job.status,
      createdAt: job.createdAt,
      startedAt: job.startedAt,
      completedAt: job.completedAt,
      error: job.error,
      progress: job.status === 'completed' ? 100 : job.status === 'running' ? 50 : 0,
      queuePosition: this.jobQueue.indexOf(jobId) + 1,
      workerType: job.workerType,
      supportsGPU: job.supportsGPU,
    };
  }

  async getJobResult(jobId: string): Promise<SimulationResult | null> {
    const job = this.jobs.get(jobId);
    if (!job || job.status !== 'completed') return null;
    return job.result || null;
  }

  cancelJob(jobId: string): boolean {
    const job = this.jobs.get(jobId);
    if (!job) return false;
    
    if (job.status === 'queued') {
      job.status = 'cancelled';
      const index = this.jobQueue.indexOf(jobId);
      if (index > -1) {
        this.jobQueue.splice(index, 1);
      }
      return true;
    }
    
    if (job.status === 'running') {
      return false;
    }
    
    return false;
  }

  getAllJobs() {
    return Array.from(this.jobs.values()).map((job) => ({
      id: job.id,
      status: job.status,
      createdAt: job.createdAt,
      startedAt: job.startedAt,
      completedAt: job.completedAt,
      priority: job.priority,
      workerType: job.workerType,
      supportsGPU: job.supportsGPU,
    }));
  }

  getWorkerStats(): WorkerStats {
    const threads = this.cpuPool.threads;
    const activeThreads = threads.filter((t: any) => !t.idle).length;
    
    return {
      totalThreads: threads.length,
      activeThreads,
      idleThreads: threads.length - activeThreads,
      queueSize: this.jobQueue.length,
      completedTasks: (this.cpuPool as any).completed,
      failedTasks: (this.cpuPool as any).failed,
    };
  }

  getStats(): SchedulerStats {
    const cpuStats = this.getWorkerStats();
    const gpuThreads = this.gpuPool?.threads || [];
    const gpuActive = gpuThreads.filter((t: any) => !t.idle).length;
    
    return {
      cpu: cpuStats,
      gpu: {
        totalThreads: gpuThreads.length,
        activeThreads: gpuActive,
        idleThreads: gpuThreads.length - gpuActive,
        queueSize: this.jobQueue.filter(id => {
          const job = this.jobs.get(id);
          return job?.supportsGPU;
        }).length,
        completedTasks: this.gpuPool ? (this.gpuPool as any).completed : 0,
        failedTasks: this.gpuPool ? (this.gpuPool as any).failed : 0,
        gpuInfo: { ...this.gpuInfo },
      },
      pendingJobs: this.jobQueue.length,
    };
  }

  async shutdown() {
    console.log('🔄 Shutting down simulation scheduler...');
    for (const [id, job] of this.jobs) {
      if (job.status === 'queued') {
        job.status = 'cancelled';
      }
    }
    this.jobQueue = [];
    
    await Promise.all([
      this.cpuPool.destroy(),
      this.gpuPool?.destroy(),
    ]);
    
    console.log('✅ Simulation scheduler shut down');
  }
}
