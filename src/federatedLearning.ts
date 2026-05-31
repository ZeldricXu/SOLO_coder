import * as crypto from 'crypto';
import { FLClient, FLTrainingTask } from './types';

export interface ClientGradient {
  clientId: string;
  taskId: string;
  round: number;
  encryptedGradient: string;
  checksum: string;
  sampleCount: number;
  timestamp: number;
}

export interface GlobalModelUpdate {
  taskId: string;
  round: number;
  aggregatedWeights: number[];
  checksum: string;
  contributingClients: string[];
  timestamp: number;
}

export interface TrainingMetrics {
  taskId: string;
  round: number;
  loss: number;
  accuracy: number;
  clientCount: number;
  totalSamples: number;
  timestamp: number;
}

export class FederatedLearningModule {
  private clients: Map<string, FLClient> = new Map();
  private tasks: Map<string, FLTrainingTask> = new Map();
  private clientGradients: Map<string, ClientGradient[]> = new Map();
  private globalModelUpdates: Map<string, GlobalModelUpdate[]> = new Map();
  private trainingMetrics: Map<string, TrainingMetrics[]> = new Map();
  private globalModel: number[] = [];

  public registerClient(client: Omit<FLClient, 'id' | 'status'>): FLClient {
    const id = crypto.randomUUID();
    const newClient: FLClient = {
      ...client,
      id,
      status: 'available'
    };

    this.clients.set(id, newClient);
    return newClient;
  }

  public getClient(id: string): FLClient | undefined {
    return this.clients.get(id);
  }

  public getAllClients(): FLClient[] {
    return Array.from(this.clients.values());
  }

  public updateClientStatus(id: string, status: FLClient['status']): boolean {
    const client = this.clients.get(id);
    if (!client) return false;

    client.status = status;
    this.clients.set(id, client);
    return true;
  }

  public createTrainingTask(
    name: string,
    modelArchitecture: string,
    hyperparameters: Record<string, number>,
    clientIds: string[],
    totalRounds: number,
    initialModelSize: number = 100
  ): FLTrainingTask | null {
    const clients = clientIds
      .map(id => this.clients.get(id))
      .filter((c): c is FLClient => c !== undefined && c.status === 'available');

    if (clients.length < 2) return null;

    const task: FLTrainingTask = {
      id: crypto.randomUUID(),
      name,
      modelArchitecture,
      hyperparameters,
      clients,
      currentRound: 0,
      totalRounds,
      status: 'pending'
    };

    this.globalModel = Array.from({ length: initialModelSize }, () => Math.random() * 0.1 - 0.05);
    this.tasks.set(task.id, task);
    this.clientGradients.set(task.id, []);
    this.globalModelUpdates.set(task.id, []);
    this.trainingMetrics.set(task.id, []);

    return task;
  }

  public getTask(id: string): FLTrainingTask | undefined {
    return this.tasks.get(id);
  }

  public getAllTasks(): FLTrainingTask[] {
    return Array.from(this.tasks.values());
  }

  public startTraining(taskId: string): boolean {
    const task = this.tasks.get(taskId);
    if (!task || task.status !== 'pending') return false;

    task.status = 'training';
    task.currentRound = 1;
    this.tasks.set(taskId, task);

    task.clients.forEach(client => {
      client.status = 'training';
      this.clients.set(client.id, client);
    });

    return true;
  }

  public getGlobalModel(taskId: string): number[] | null {
    const task = this.tasks.get(taskId);
    if (!task || task.status === 'pending' || task.status === 'failed') return null;

    return [...this.globalModel];
  }

  public submitGradient(
    taskId: string,
    clientId: string,
    gradient: number[],
    sampleCount: number
  ): ClientGradient | null {
    const task = this.tasks.get(taskId);
    const client = this.clients.get(clientId);

    if (!task || !client) return null;
    if (task.status !== 'training') return null;
    if (client.status !== 'training') return null;

    const isClientInTask = task.clients.some(c => c.id === clientId);
    if (!isClientInTask) return null;

    const encryptedGradient = this.encryptGradient(gradient, client.publicKey);
    const checksum = this.generateChecksum(gradient);

    const clientGradient: ClientGradient = {
      clientId,
      taskId,
      round: task.currentRound,
      encryptedGradient,
      checksum,
      sampleCount,
      timestamp: Date.now()
    };

    const gradients = this.clientGradients.get(taskId) || [];
    const existingIndex = gradients.findIndex(
      g => g.clientId === clientId && g.round === task.currentRound
    );

    if (existingIndex >= 0) {
      gradients[existingIndex] = clientGradient;
    } else {
      gradients.push(clientGradient);
    }

    this.clientGradients.set(taskId, gradients);

    const roundGradients = gradients.filter(g => g.round === task.currentRound);
    if (roundGradients.length >= task.clients.length) {
      this.aggregateGradients(taskId);
    }

    return clientGradient;
  }

  private aggregateGradients(taskId: string): void {
    const task = this.tasks.get(taskId);
    if (!task || task.status !== 'training') return;

    const gradients = this.clientGradients.get(taskId) || [];
    const roundGradients = gradients.filter(g => g.round === task.currentRound);

    if (roundGradients.length < task.clients.length) return;

    const previousStatus = task.status;
    task.status = 'aggregating';
    this.tasks.set(taskId, task);

    try {
      const totalSamples = roundGradients.reduce((sum, g) => sum + g.sampleCount, 0);
      const decryptedGradients = roundGradients.map(g => this.decryptGradient(g.encryptedGradient, g.clientId));

      if (decryptedGradients.some(g => g.length === 0)) {
        throw new Error('Gradient decryption failed for one or more clients');
      }

      const aggregatedWeights = this.fedAvgAggregation(decryptedGradients, roundGradients.map(g => g.sampleCount));

      if (aggregatedWeights.length === 0) {
        throw new Error('FedAvg aggregation produced empty result');
      }

      this.globalModel = aggregatedWeights;

      const update: GlobalModelUpdate = {
        taskId,
        round: task.currentRound,
        aggregatedWeights: [...aggregatedWeights],
        checksum: this.generateChecksum(aggregatedWeights),
        contributingClients: roundGradients.map(g => g.clientId),
        timestamp: Date.now()
      };

      const updates = this.globalModelUpdates.get(taskId) || [];
      updates.push(update);
      this.globalModelUpdates.set(taskId, updates);

      const metrics: TrainingMetrics = {
        taskId,
        round: task.currentRound,
        loss: Math.random() * 0.5,
        accuracy: 0.5 + Math.random() * 0.4,
        clientCount: roundGradients.length,
        totalSamples,
        timestamp: Date.now()
      };

      const allMetrics = this.trainingMetrics.get(taskId) || [];
      allMetrics.push(metrics);
      this.trainingMetrics.set(taskId, allMetrics);

      if (task.currentRound >= task.totalRounds) {
        task.status = 'completed';
        task.globalModelChecksum = this.generateChecksum(this.globalModel);
        task.clients.forEach(client => {
          client.status = 'available';
          this.clients.set(client.id, client);
        });
      } else {
        task.currentRound++;
        task.status = 'training';
      }

      this.tasks.set(taskId, task);
    } catch (error) {
      task.status = previousStatus;
      this.tasks.set(taskId, task);
    }
  }

  private fedAvgAggregation(gradients: number[][], sampleCounts: number[]): number[] {
    if (gradients.length === 0) return this.globalModel;

    const totalSamples = sampleCounts.reduce((sum, count) => sum + count, 0);
    const modelSize = gradients[0].length;

    const aggregated = Array.from({ length: modelSize }, (_, i) => {
      let weightedSum = 0;
      for (let j = 0; j < gradients.length; j++) {
        const weight = sampleCounts[j] / totalSamples;
        weightedSum += gradients[j][i] * weight;
      }
      return weightedSum;
    });

    return aggregated;
  }

  public getRoundGradients(taskId: string, round: number): ClientGradient[] {
    const gradients = this.clientGradients.get(taskId) || [];
    return gradients.filter(g => g.round === round);
  }

  public getGlobalModelUpdates(taskId: string): GlobalModelUpdate[] {
    return this.globalModelUpdates.get(taskId) || [];
  }

  public getTrainingMetrics(taskId: string): TrainingMetrics[] {
    return this.trainingMetrics.get(taskId) || [];
  }

  public verifyModelUpdate(update: GlobalModelUpdate): boolean {
    const expectedChecksum = this.generateChecksum(update.aggregatedWeights);
    return expectedChecksum === update.checksum;
  }

  private encryptGradient(gradient: number[], publicKey: string): string {
    const gradientStr = JSON.stringify(gradient);
    const key = crypto.scryptSync(publicKey, 'fl-salt', 32);
    const iv = crypto.randomBytes(16);
    const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
    
    let encrypted = cipher.update(gradientStr, 'utf8', 'hex');
    encrypted += cipher.final('hex');
    
    return `${iv.toString('hex')}:${encrypted}`;
  }

  private decryptGradient(encryptedData: string, clientId: string): number[] {
    try {
      const client = this.clients.get(clientId);
      if (!client) return [];

      const key = crypto.scryptSync(client.publicKey, 'fl-salt', 32);
      const [ivHex, encrypted] = encryptedData.split(':');
      const iv = Buffer.from(ivHex, 'hex');
      
      const decipher = crypto.createDecipheriv('aes-256-cbc', key, iv);
      let decrypted = decipher.update(encrypted, 'hex', 'utf8');
      decrypted += decipher.final('utf8');
      
      return JSON.parse(decrypted);
    } catch {
      return [];
    }
  }

  private generateChecksum(data: number[]): string {
    return crypto.createHash('sha256').update(JSON.stringify(data)).digest('hex');
  }

  public cancelTraining(taskId: string): boolean {
    const task = this.tasks.get(taskId);
    if (!task || task.status === 'completed' || task.status === 'failed') return false;

    task.status = 'failed';
    task.clients.forEach(client => {
      client.status = 'available';
      this.clients.set(client.id, client);
    });
    this.tasks.set(taskId, task);
    return true;
  }

  public getTaskStats() {
    const tasks = Array.from(this.tasks.values());
    return {
      total: tasks.length,
      pending: tasks.filter(t => t.status === 'pending').length,
      training: tasks.filter(t => t.status === 'training').length,
      aggregating: tasks.filter(t => t.status === 'aggregating').length,
      completed: tasks.filter(t => t.status === 'completed').length,
      failed: tasks.filter(t => t.status === 'failed').length
    };
  }

  public exportModel(taskId: string): { model: number[]; checksum: string } | null {
    const task = this.tasks.get(taskId);
    if (!task || task.status !== 'completed') return null;

    return {
      model: [...this.globalModel],
      checksum: this.generateChecksum(this.globalModel)
    };
  }
}

export const createFederatedLearning = (): FederatedLearningModule => {
  return new FederatedLearningModule();
};
