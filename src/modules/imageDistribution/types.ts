import { z } from 'zod';

export const ImageSyncTaskSchema = z.object({
  sourceRegistry: z.string().min(1),
  targetRegistry: z.string().min(1),
  repository: z.string().min(1),
  tag: z.string().min(1),
  sourceAuth: z.object({
    username: z.string().optional(),
    password: z.string().optional(),
    insecure: z.boolean().default(false),
  }).optional(),
  targetAuth: z.object({
    username: z.string().optional(),
    password: z.string().optional(),
    insecure: z.boolean().default(false),
  }).optional(),
});

export const ImageLayerSchema = z.object({
  digest: z.string().min(1),
  size: z.number().int().positive(),
  contentUrl: z.string().optional(),
  registry: z.string().min(1),
  repository: z.string().min(1),
});

export const P2PConfigSchema = z.object({
  enabled: z.boolean().default(true),
  maxPeers: z.number().int().positive().default(50),
  chunkSize: z.number().int().positive().default(1048576),
  enableDHT: z.boolean().default(true),
  trackerUrls: z.array(z.string()).default([]),
});

export type CreateSyncTaskRequest = z.infer<typeof ImageSyncTaskSchema>;
export type CreateLayerRequest = z.infer<typeof ImageLayerSchema>;
export type P2PConfig = z.infer<typeof P2PConfigSchema>;

export interface ImageLayer {
  layerId: string;
  digest: string;
  size: number;
  contentUrl?: string;
  registry: string;
  repository: string;
  createdAt: Date;
}

export interface ImageSyncTask {
  taskId: string;
  sourceRegistry: string;
  targetRegistry: string;
  repository: string;
  tag: string;
  status: 'pending' | 'syncing' | 'completed' | 'failed';
  progress: number;
  startedAt?: Date;
  completedAt?: Date;
  errorDetail?: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface SyncProgress {
  taskId: string;
  currentLayer: number;
  totalLayers: number;
  bytesDownloaded: number;
  bytesUploaded: number;
  currentSpeed: number;
  estimatedRemaining: number;
}

export interface P2PPeer {
  peerId: string;
  address: string;
  availableLayers: string[];
  downloadSpeed: number;
  uploadSpeed: number;
  connectedAt: Date;
}
