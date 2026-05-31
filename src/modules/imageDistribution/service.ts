import { PrismaClient, ImageLayer as DbLayer, ImageSyncTask as DbTask } from '@prisma/client';
import { generateLayerId, generateSyncTaskId } from '../../utils/idGenerator';
import { NotFoundError } from '../../utils/errors';
import type { CreateSyncTaskRequest, CreateLayerRequest, ImageLayer, ImageSyncTask, P2PConfig, SyncProgress } from './types';
import type { PaginationParams, PaginatedResult } from '../../types';
import logger from '../../utils/logger';
import axios from 'axios';
import { Mutex } from 'async-mutex';

const prisma = new PrismaClient();
const syncMutex = new Mutex();

let p2pConfig: P2PConfig = {
  enabled: true,
  maxPeers: 50,
  chunkSize: 1048576,
  enableDHT: true,
  trackerUrls: [],
};

const toLayer = (db: DbLayer): ImageLayer => ({
  layerId: db.layerId,
  digest: db.digest,
  size: db.size,
  contentUrl: db.contentUrl ?? undefined,
  registry: db.registry,
  repository: db.repository,
  createdAt: db.createdAt,
});

const toTask = (db: DbTask): ImageSyncTask => ({
  taskId: db.taskId,
  sourceRegistry: db.sourceRegistry,
  targetRegistry: db.targetRegistry,
  repository: db.repository,
  tag: db.tag,
  status: db.status as ImageSyncTask['status'],
  progress: db.progress,
  startedAt: db.startedAt ?? undefined,
  completedAt: db.completedAt ?? undefined,
  errorDetail: db.errorDetail ?? undefined,
  createdAt: db.createdAt,
  updatedAt: db.updatedAt,
});

export const createSyncTask = async (data: CreateSyncTaskRequest): Promise<ImageSyncTask> => {
  const release = await syncMutex.acquire();
  try {
    const existing = await prisma.imageSyncTask.findFirst({
      where: {
        sourceRegistry: data.sourceRegistry,
        targetRegistry: data.targetRegistry,
        repository: data.repository,
        tag: data.tag,
        status: { in: ['pending', 'syncing'] },
      },
    });

    if (existing) {
      logger.info({ taskId: existing.taskId }, 'Existing sync task found, returning');
      return toTask(existing);
    }

    const task = await prisma.imageSyncTask.create({
      data: {
        taskId: generateSyncTaskId(),
        sourceRegistry: data.sourceRegistry,
        targetRegistry: data.targetRegistry,
        repository: data.repository,
        tag: data.tag,
        status: 'pending',
        progress: 0,
      },
    });

    logger.info({ taskId: task.taskId, repository: data.repository, tag: data.tag }, 'Sync task created');
    return toTask(task);
  } finally {
    release();
  }
};

export const getSyncTask = async (taskId: string): Promise<ImageSyncTask> => {
  const task = await prisma.imageSyncTask.findUnique({ where: { taskId } });
  if (!task) throw new NotFoundError(`Sync task ${taskId} not found`);
  return toTask(task);
};

export const listSyncTasks = async (params: PaginationParams, status?: ImageSyncTask['status']): Promise<PaginatedResult<ImageSyncTask>> => {
  const where = status ? { status } : {};
  const [items, total] = await Promise.all([
    prisma.imageSyncTask.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
    }),
    prisma.imageSyncTask.count({ where }),
  ]);
  return {
    items: items.map(toTask),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const getTaskProgress = async (taskId: string): Promise<SyncProgress> => {
  const task = await getSyncTask(taskId);
  const layers = await prisma.imageLayer.findMany({
    where: {
      registry: task.targetRegistry,
      repository: task.repository,
    },
  });

  const totalSize = layers.reduce((sum, l) => sum + l.size, 0);
  const downloadedSize = totalSize * task.progress;

  return {
    taskId,
    currentLayer: Math.floor(layers.length * task.progress),
    totalLayers: layers.length,
    bytesDownloaded: downloadedSize,
    bytesUploaded: downloadedSize * 0.9,
    currentSpeed: 1024 * 1024 * 5,
    estimatedRemaining: totalSize > 0 ? Math.ceil((totalSize - downloadedSize) / (1024 * 1024 * 5)) : 0,
  };
};

export const startSyncTask = async (taskId: string): Promise<ImageSyncTask> => {
  const task = await prisma.imageSyncTask.update({
    where: { taskId },
    data: {
      status: 'syncing',
      startedAt: new Date(),
    },
  });
  logger.info({ taskId }, 'Sync task started');
  return toTask(task);
};

export const updateSyncProgress = async (taskId: string, progress: number): Promise<ImageSyncTask> => {
  const task = await prisma.imageSyncTask.update({
    where: { taskId },
    data: { progress: Math.min(1, Math.max(0, progress)) },
  });
  return toTask(task);
};

export const completeSyncTask = async (taskId: string, success: boolean, errorDetail?: string): Promise<ImageSyncTask> => {
  const task = await prisma.imageSyncTask.update({
    where: { taskId },
    data: {
      status: success ? 'completed' : 'failed',
      progress: success ? 1 : undefined,
      completedAt: new Date(),
      errorDetail,
    },
  });
  logger.info({ taskId, success }, 'Sync task completed');
  return toTask(task);
};

export const cancelSyncTask = async (taskId: string): Promise<ImageSyncTask> => {
  const task = await prisma.imageSyncTask.update({
    where: { taskId },
    data: {
      status: 'failed',
      completedAt: new Date(),
      errorDetail: 'Cancelled by user',
    },
  });
  logger.info({ taskId }, 'Sync task cancelled');
  return toTask(task);
};

export const addLayer = async (data: CreateLayerRequest): Promise<ImageLayer> => {
  const existing = await prisma.imageLayer.findUnique({ where: { digest: data.digest } });
  if (existing) {
    logger.debug({ digest: data.digest }, 'Layer already exists');
    return toLayer(existing);
  }

  const layer = await prisma.imageLayer.create({
    data: {
      layerId: generateLayerId(),
      digest: data.digest,
      size: data.size,
      contentUrl: data.contentUrl,
      registry: data.registry,
      repository: data.repository,
    },
  });
  logger.info({ layerId: layer.layerId, digest: data.digest }, 'Layer added');
  return toLayer(layer);
};

export const getLayer = async (layerId: string): Promise<ImageLayer> => {
  const layer = await prisma.imageLayer.findUnique({ where: { layerId } });
  if (!layer) throw new NotFoundError(`Layer ${layerId} not found`);
  return toLayer(layer);
};

export const getLayerByDigest = async (digest: string): Promise<ImageLayer | null> => {
  const layer = await prisma.imageLayer.findUnique({ where: { digest } });
  return layer ? toLayer(layer) : null;
};

export const listLayers = async (params: PaginationParams, repository?: string, registry?: string): Promise<PaginatedResult<ImageLayer>> => {
  const where: Record<string, unknown> = {};
  if (repository) where.repository = repository;
  if (registry) where.registry = registry;

  const [items, total] = await Promise.all([
    prisma.imageLayer.findMany({
      where,
      skip: (params.page - 1) * params.pageSize,
      take: params.pageSize,
      orderBy: { createdAt: 'desc' },
    }),
    prisma.imageLayer.count({ where }),
  ]);
  return {
    items: items.map(toLayer),
    total,
    page: params.page,
    pageSize: params.pageSize,
    totalPages: Math.ceil(total / params.pageSize),
  };
};

export const deleteLayer = async (layerId: string): Promise<void> => {
  await prisma.imageLayer.delete({ where: { layerId } });
  logger.info({ layerId }, 'Layer deleted');
};

const fetchManifest = async (registry: string, repository: string, tag: string, auth?: { username?: string; password?: string; insecure?: boolean }): Promise<{ layers: Array<{ digest: string; size: number }> }> => {
  const protocol = auth?.insecure ? 'http' : 'https';
  const url = `${protocol}://${registry}/v2/${repository}/manifests/${tag}`;

  const headers: Record<string, string> = {
    'Accept': 'application/vnd.docker.distribution.manifest.v2+json',
  };

  if (auth?.username && auth?.password) {
    const basicAuth = Buffer.from(`${auth.username}:${auth.password}`).toString('base64');
    headers['Authorization'] = `Basic ${basicAuth}`;
  }

  const response = await axios.get(url, { headers, timeout: 30000 });
  return response.data;
};

export const syncImage = async (taskId: string, sourceAuth?: { username?: string; password?: string; insecure?: boolean }, targetAuth?: { username?: string; password?: string; insecure?: boolean }): Promise<void> => {
  const task = await getSyncTask(taskId);
  if (task.status !== 'pending' && task.status !== 'syncing') {
    throw new Error(`Cannot sync task with status ${task.status}`);
  }

  await startSyncTask(taskId);

  try {
    const manifest = await fetchManifest(task.sourceRegistry, task.repository, task.tag, sourceAuth);

    const totalLayers = manifest.layers.length;
    for (let i = 0; i < totalLayers; i++) {
      const layer = manifest.layers[i];

      await addLayer({
        digest: layer.digest,
        size: layer.size,
        registry: task.targetRegistry,
        repository: task.repository,
      });

      await updateSyncProgress(taskId, (i + 1) / totalLayers);
    }

    await completeSyncTask(taskId, true);
  } catch (error) {
    logger.error({ taskId, error }, 'Sync task failed');
    await completeSyncTask(taskId, false, error instanceof Error ? error.message : 'Unknown error');
    throw error;
  }
};

export const getP2PConfig = (): P2PConfig => ({ ...p2pConfig });

export const updateP2PConfig = (config: Partial<P2PConfig>): P2PConfig => {
  p2pConfig = { ...p2pConfig, ...config };
  logger.info({ config }, 'P2P config updated');
  return p2pConfig;
};

export const getRegistryStats = async (registry: string) => {
  const layers = await prisma.imageLayer.findMany({ where: { registry } });
  const totalSize = layers.reduce((sum, l) => sum + l.size, 0);
  const uniqueRepos = new Set(layers.map(l => l.repository));

  return {
    registry,
    totalLayers: layers.length,
    totalSize,
    uniqueRepositories: uniqueRepos.size,
    repositories: Array.from(uniqueRepos),
  };
};

export default {
  createSyncTask,
  getSyncTask,
  listSyncTasks,
  getTaskProgress,
  startSyncTask,
  updateSyncProgress,
  completeSyncTask,
  cancelSyncTask,
  addLayer,
  getLayer,
  getLayerByDigest,
  listLayers,
  deleteLayer,
  syncImage,
  getP2PConfig,
  updateP2PConfig,
  getRegistryStats,
};
