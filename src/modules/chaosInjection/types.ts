import { z } from 'zod';
import type { FaultType, InjectionStatus } from '../../types';

export const ChaosScenarioSchema = z.object({
  name: z.string().min(1).max(100),
  description: z.string().optional(),
  faultType: z.enum(['network_delay', 'packet_loss', 'cpu_stress', 'memory_stress', 'disk_io', 'service_kill', 'dns_poison']),
  targetScope: z.object({
    namespace: z.string(),
    selector: z.record(z.string()).optional(),
    targetIds: z.array(z.string()).optional(),
  }),
  parameters: z.record(z.unknown()),
  autoRollback: z.boolean().default(true),
  rollbackConfig: z.object({
    timeoutSeconds: z.number().int().positive().default(300),
    maxRetries: z.number().int().nonnegative().default(3),
  }).optional(),
  createdBy: z.string(),
});

export const ChaosInjectionSchema = z.object({
  scenarioId: z.string(),
  targetIds: z.array(z.string()).optional(),
});

export type CreateScenarioRequest = z.infer<typeof ChaosScenarioSchema>;
export type CreateInjectionRequest = z.infer<typeof ChaosInjectionSchema>;

export interface ChaosScenario {
  scenarioId: string;
  name: string;
  description?: string;
  faultType: FaultType;
  targetScope: Record<string, unknown>;
  parameters: Record<string, unknown>;
  autoRollback: boolean;
  rollbackConfig?: Record<string, unknown>;
  status: string;
  createdBy: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface ChaosInjection {
  injectionId: string;
  scenarioId: string;
  targetIds: string[];
  status: InjectionStatus;
  startedAt?: Date;
  endedAt?: Date;
  rollbackAt?: Date;
  errorDetail?: string;
  createdAt: Date;
  updatedAt: Date;
}
