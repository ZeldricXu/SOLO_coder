import * as crypto from 'crypto';
import { MPCParticipant, MPCTask } from './types';

export interface EncryptedInput {
  participantId: string;
  taskId: string;
  encryptedData: string;
  publicKey: string;
  timestamp: number;
}

export interface MPCResult {
  taskId: string;
  result: unknown;
  participantContributions: string[];
  timestamp: number;
  verificationHash: string;
}

export interface GarbledGate {
  type: 'AND' | 'OR' | 'XOR' | 'NOT';
  inputs: [string, string];
  output: string;
  garbledTable: string[];
}

export class MPCModule {
  private participants: Map<string, MPCParticipant> = new Map();
  private tasks: Map<string, MPCTask> = new Map();
  private encryptedInputs: Map<string, EncryptedInput[]> = new Map();
  private results: Map<string, MPCResult> = new Map();

  public registerParticipant(participant: Omit<MPCParticipant, 'id' | 'status'>): MPCParticipant {
    const id = crypto.randomUUID();
    const newParticipant: MPCParticipant = {
      ...participant,
      id,
      status: 'active'
    };

    this.participants.set(id, newParticipant);
    return newParticipant;
  }

  public getParticipant(id: string): MPCParticipant | undefined {
    return this.participants.get(id);
  }

  public getAllParticipants(): MPCParticipant[] {
    return Array.from(this.participants.values());
  }

  public updateParticipantStatus(id: string, status: MPCParticipant['status']): boolean {
    const participant = this.participants.get(id);
    if (!participant) return false;

    participant.status = status;
    this.participants.set(id, participant);
    return true;
  }

  public createTask(
    name: string,
    protocol: MPCTask['protocol'],
    participantIds: string[],
    inputFormat: Record<string, string>,
    resultFormat: Record<string, string>
  ): MPCTask | null {
    const participants = participantIds
      .map(id => this.participants.get(id))
      .filter((p): p is MPCParticipant => p !== undefined && p.status === 'active');

    if (participants.length < 2) return null;

    const task: MPCTask = {
      id: crypto.randomUUID(),
      name,
      protocol,
      participants,
      status: 'pending',
      inputFormat,
      resultFormat
    };

    this.tasks.set(task.id, task);
    this.encryptedInputs.set(task.id, []);

    return task;
  }

  public getTask(id: string): MPCTask | undefined {
    return this.tasks.get(id);
  }

  public getAllTasks(): MPCTask[] {
    return Array.from(this.tasks.values());
  }

  public submitEncryptedInput(
    taskId: string,
    participantId: string,
    inputData: Record<string, unknown>
  ): EncryptedInput | null {
    const task = this.tasks.get(taskId);
    const participant = this.participants.get(participantId);

    if (!task || !participant || task.status !== 'pending') return null;

    const isParticipantInTask = task.participants.some(p => p.id === participantId);
    if (!isParticipantInTask) return null;

    const encryptedData = this.encryptWithParticipantKey(
      JSON.stringify(inputData),
      participant.publicKey
    );

    const encryptedInput: EncryptedInput = {
      participantId,
      taskId,
      encryptedData,
      publicKey: participant.publicKey,
      timestamp: Date.now()
    };

    const inputs = this.encryptedInputs.get(taskId) || [];
    inputs.push(encryptedInput);
    this.encryptedInputs.set(taskId, inputs);

    if (inputs.length >= task.participants.length) {
      task.status = 'running';
      this.tasks.set(taskId, task);
    }

    return encryptedInput;
  }

  public executeTask(taskId: string): MPCResult | null {
    const task = this.tasks.get(taskId);
    if (!task || task.status !== 'running') return null;

    const inputs = this.encryptedInputs.get(taskId) || [];
    if (inputs.length < task.participants.length) return null;

    try {
      let result: unknown;

      switch (task.protocol) {
        case 'secret-sharing':
          result = this.executeSecretSharing(task, inputs);
          break;
        case 'garbled-circuit':
          result = this.executeGarbledCircuit(task, inputs);
          break;
        case 'homomorphic-encryption':
          result = this.executeHomomorphicEncryption(task, inputs);
          break;
        default:
          task.status = 'failed';
          this.tasks.set(taskId, task);
          return null;
      }

      const mpcResult: MPCResult = {
        taskId,
        result,
        participantContributions: inputs.map(i => i.participantId),
        timestamp: Date.now(),
        verificationHash: this.generateVerificationHash(task, inputs, result)
      };

      task.status = 'completed';
      this.tasks.set(taskId, task);
      this.results.set(taskId, mpcResult);

      return mpcResult;
    } catch (error) {
      task.status = 'failed';
      this.tasks.set(taskId, task);
      return null;
    }
  }

  public getResult(taskId: string): MPCResult | undefined {
    return this.results.get(taskId);
  }

  public decryptResult(result: MPCResult, privateKey: string): unknown {
    const encryptedResult = JSON.stringify(result.result);
    return this.decryptWithPrivateKey(encryptedResult, privateKey);
  }

  public verifyResult(result: MPCResult): boolean {
    const task = this.tasks.get(result.taskId);
    const inputs = this.encryptedInputs.get(result.taskId);

    if (!task || !inputs) return false;

    const expectedHash = this.generateVerificationHash(task, inputs, result.result);
    return expectedHash === result.verificationHash;
  }

  private executeSecretSharing(task: MPCTask, inputs: EncryptedInput[]): unknown {
    const decryptedInputs = inputs.map(input => 
      this.simulateDecrypt(input.encryptedData)
    );

    const numericInputs = decryptedInputs.map(data => {
      const parsed = typeof data === 'string' ? JSON.parse(data) : data;
      return Number(parsed.value) || 0;
    });

    const sum = numericInputs.reduce((acc, val) => acc + val, 0);
    const average = sum / numericInputs.length;
    const max = Math.max(...numericInputs);
    const min = Math.min(...numericInputs);

    return {
      sum,
      average,
      max,
      min,
      count: numericInputs.length
    };
  }

  private executeGarbledCircuit(task: MPCTask, inputs: EncryptedInput[]): unknown {
    const decryptedInputs = inputs.map(input => 
      this.simulateDecrypt(input.encryptedData)
    );

    const booleanInputs = decryptedInputs.map(data => {
      const parsed = typeof data === 'string' ? JSON.parse(data) : data;
      return Boolean(parsed.value);
    });

    const circuit = this.generateGarbledCircuit(booleanInputs.length);
    const circuitResult = this.evaluateGarbledCircuit(circuit, booleanInputs);

    return {
      circuitResult,
      gatesEvaluated: circuit.length,
      inputCount: booleanInputs.length
    };
  }

  private executeHomomorphicEncryption(task: MPCTask, inputs: EncryptedInput[]): unknown {
    const decryptedInputs = inputs.map(input => 
      this.simulateDecrypt(input.encryptedData)
    );

    const numericInputs = decryptedInputs.map(data => {
      const parsed = typeof data === 'string' ? JSON.parse(data) : data;
      return Number(parsed.value) || 0;
    });

    const encryptedSum = numericInputs.reduce((acc, val) => acc + val, 0);
    const encryptedProduct = numericInputs.reduce((acc, val) => acc * val, 1);

    return {
      encryptedSum,
      encryptedProduct,
      operationsPerformed: ['addition', 'multiplication'],
      inputCount: numericInputs.length
    };
  }

  private generateGarbledCircuit(inputCount: number): GarbledGate[] {
    const gates: GarbledGate[] = [];
    const inputWires = Array.from({ length: inputCount }, (_, i) => `in_${i}`);
    let currentWire = inputCount;

    for (let i = 0; i < inputCount - 1; i++) {
      gates.push({
        type: 'AND',
        inputs: [inputWires[i], inputWires[i + 1]],
        output: `out_${currentWire}`,
        garbledTable: this.generateGarbledTable()
      });
      currentWire++;
    }

    return gates;
  }

  private generateGarbledTable(): string[] {
    return Array.from({ length: 4 }, () => crypto.randomBytes(32).toString('hex'));
  }

  private evaluateGarbledCircuit(circuit: GarbledGate[], inputs: boolean[]): boolean {
    const wireValues: Map<string, boolean> = new Map();
    inputs.forEach((val, i) => wireValues.set(`in_${i}`, val));

    let lastResult = false;
    for (const gate of circuit) {
      const inputA = wireValues.get(gate.inputs[0]) ?? false;
      const inputB = wireValues.get(gate.inputs[1]) ?? false;

      let result: boolean;
      switch (gate.type) {
        case 'AND':
          result = inputA && inputB;
          break;
        case 'OR':
          result = inputA || inputB;
          break;
        case 'XOR':
          result = inputA !== inputB;
          break;
        case 'NOT':
          result = !inputA;
          break;
        default:
          result = false;
      }

      wireValues.set(gate.output, result);
      lastResult = result;
    }

    return lastResult;
  }

  private encryptWithParticipantKey(data: string, publicKey: string): string {
    const key = crypto.scryptSync(publicKey, 'mpc-salt', 32);
    const iv = crypto.randomBytes(16);
    const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
    
    let encrypted = cipher.update(data, 'utf8', 'hex');
    encrypted += cipher.final('hex');
    
    return `${iv.toString('hex')}:${encrypted}`;
  }

  private decryptWithPrivateKey(encryptedData: string, privateKey: string): unknown {
    try {
      const key = crypto.scryptSync(privateKey, 'mpc-salt', 32);
      const [ivHex, encrypted] = encryptedData.split(':');
      const iv = Buffer.from(ivHex, 'hex');
      
      const decipher = crypto.createDecipheriv('aes-256-cbc', key, iv);
      let decrypted = decipher.update(encrypted, 'hex', 'utf8');
      decrypted += decipher.final('utf8');
      
      return JSON.parse(decrypted);
    } catch {
      return null;
    }
  }

  private simulateDecrypt(encryptedData: string): unknown {
    try {
      const [, encrypted] = encryptedData.split(':');
      const seed = crypto.createHash('sha256').update(encrypted).digest('hex');
      const pseudoRandom = parseInt(seed.slice(0, 8), 16);
      return { value: (pseudoRandom % 100) + 1 };
    } catch {
      return { value: 0 };
    }
  }

  private generateVerificationHash(task: MPCTask, inputs: EncryptedInput[], result: unknown): string {
    const data = JSON.stringify({
      taskId: task.id,
      protocol: task.protocol,
      inputs: inputs.map(i => i.encryptedData),
      result
    });

    return crypto.createHash('sha256').update(data).digest('hex');
  }

  public cancelTask(taskId: string): boolean {
    const task = this.tasks.get(taskId);
    if (!task || task.status === 'completed' || task.status === 'failed') return false;

    task.status = 'failed';
    this.tasks.set(taskId, task);
    return true;
  }

  public getTaskStats() {
    const tasks = Array.from(this.tasks.values());
    return {
      total: tasks.length,
      pending: tasks.filter(t => t.status === 'pending').length,
      running: tasks.filter(t => t.status === 'running').length,
      completed: tasks.filter(t => t.status === 'completed').length,
      failed: tasks.filter(t => t.status === 'failed').length
    };
  }
}

export const createMPC = (): MPCModule => {
  return new MPCModule();
};
