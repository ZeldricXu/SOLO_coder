import { v4 as uuidv4 } from 'uuid';
import crypto from 'node:crypto';
import { prisma } from '../config/database';
import { logger } from '../config/logger';
import { modelStorage } from '../storage';
import { modelLoaderRegistry } from './loader';
import type {
  Model,
  ModelVersion,
  ModelCreateRequest,
  ModelListRequest,
  ModelVersionListRequest,
  PaginatedResponse,
  ModelFormat,
} from '@mlops/shared';
import {
  modelCreateRequestSchema,
  modelVersionCreateRequestSchema,
  modelListRequestSchema,
  modelVersionListRequestSchema,
} from '@mlops/shared';

export class ModelRegistryService {
  async createModel(request: ModelCreateRequest): Promise<Model> {
    const validated = modelCreateRequestSchema.parse(request);

    const model = await prisma.model.create({
      data: {
        id: uuidv4(),
        name: validated.name,
        description: validated.description,
        ownerId: validated.ownerId,
        team: validated.team,
        tags: validated.tags || [],
        metadata: validated.metadata || {},
      },
      include: {
        versions: {
          orderBy: { createdAt: 'desc' },
          take: 1,
        },
      },
    });

    logger.info({ modelId: model.id, name: model.name }, 'Model created');
    return this.transformModel(model);
  }

  async getModel(id: string): Promise<Model | null> {
    const model = await prisma.model.findUnique({
      where: { id },
      include: {
        versions: {
          orderBy: { createdAt: 'desc' },
          take: 10,
        },
      },
    });

    if (!model) return null;
    return this.transformModel(model);
  }

  async listModels(request: ModelListRequest): Promise<PaginatedResponse<Model>> {
    const validated = modelListRequestSchema.parse(request);
    const page = validated.page || 1;
    const pageSize = validated.pageSize || 20;

    const where: Record<string, unknown> = {};
    if (validated.name) where.name = { contains: validated.name };
    if (validated.ownerId) where.ownerId = validated.ownerId;
    if (validated.team) where.team = validated.team;
    if (validated.status) where.status = validated.status;
    if (validated.tags && validated.tags.length > 0) {
      where.tags = { hasEvery: validated.tags };
    }

    const [total, models] = await Promise.all([
      prisma.model.count({ where }),
      prisma.model.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
        include: {
          versions: {
            orderBy: { createdAt: 'desc' },
            take: 1,
          },
        },
      }),
    ]);

    return {
      data: models.map((m) => this.transformModel(m)),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async createModelVersion(
    request: Omit<import('@mlops/shared').ModelVersionCreateRequest, 'file'> & {
      fileBuffer: Buffer;
      fileName: string;
    }
  ): Promise<ModelVersion> {
    const validated = modelVersionCreateRequestSchema.parse(request);

    const model = await prisma.model.findUnique({
      where: { id: validated.modelId },
    });

    if (!model) {
      throw new Error(`Model not found: ${validated.modelId}`);
    }

    const format = request.format || modelLoaderRegistry.autoDetect(request.fileName);
    const checksum = crypto.createHash('sha256').update(request.fileBuffer).digest('hex');
    const storagePath = `${validated.modelId}/${validated.version}/${request.fileName}`;

    const etag = await modelStorage.putObject(storagePath, request.fileBuffer);

    const version = await prisma.modelVersion.create({
      data: {
        id: uuidv4(),
        modelId: validated.modelId,
        version: validated.version,
        semanticVersion: validated.semanticVersion,
        format,
        sizeBytes: BigInt(request.fileBuffer.length),
        storageBackend: process.env.STORAGE_BACKEND === 's3' ? 's3' : 'local',
        storagePath,
        checksum: etag || checksum,
        status: 'ready',
        dataSchema: validated.dataSchema,
        loaderConfig: validated.loaderConfig || {},
        experimentId: validated.experimentId,
        tags: validated.tags || [],
        metrics: {
          create: validated.metrics?.map((m) => ({
            id: uuidv4(),
            name: m.name,
            value: m.value,
            timestamp: new Date(m.timestamp),
            step: m.step,
            context: m.context,
          })) || [],
        },
        hyperParameters: {
          create: Object.entries(validated.hyperParameters || {}).map(([name, value]) => ({
            id: uuidv4(),
            name,
            value: String(value),
            type: typeof value === 'number' ? 'number' : typeof value === 'boolean' ? 'boolean' : 'string',
          })),
        },
      },
      include: {
        metrics: true,
        hyperParameters: true,
      },
    });

    await prisma.model.update({
      where: { id: validated.modelId },
      data: {
        latestVersionId: version.id,
        updatedAt: new Date(),
      },
    });

    logger.info(
      { modelId: validated.modelId, versionId: version.id, version: validated.version, format },
      'Model version created'
    );

    return this.transformModelVersion(version);
  }

  async getModelVersion(id: string): Promise<ModelVersion | null> {
    const version = await prisma.modelVersion.findUnique({
      where: { id },
      include: {
        metrics: true,
        hyperParameters: true,
      },
    });

    if (!version) return null;
    return this.transformModelVersion(version);
  }

  async getLatestModelVersion(modelId: string): Promise<ModelVersion | null> {
    const version = await prisma.modelVersion.findFirst({
      where: { modelId, status: 'ready' },
      orderBy: { createdAt: 'desc' },
      include: {
        metrics: true,
        hyperParameters: true,
      },
    });

    if (!version) return null;
    return this.transformModelVersion(version);
  }

  async listModelVersions(request: ModelVersionListRequest): Promise<PaginatedResponse<ModelVersion>> {
    const validated = modelVersionListRequestSchema.parse(request);
    const page = validated.page || 1;
    const pageSize = validated.pageSize || 20;

    const where: Record<string, unknown> = { modelId: validated.modelId };
    if (validated.status) where.status = validated.status;

    const [total, versions] = await Promise.all([
      prisma.modelVersion.count({ where }),
      prisma.modelVersion.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
        include: {
          metrics: true,
          hyperParameters: true,
        },
      }),
    ]);

    return {
      data: versions.map((v) => this.transformModelVersion(v)),
      total,
      page,
      pageSize,
      totalPages: Math.ceil(total / pageSize),
    };
  }

  async downloadModelVersion(versionId: string): Promise<{ buffer: Buffer; fileName: string; contentType: string }> {
    const version = await prisma.modelVersion.findUnique({
      where: { id: versionId },
    });

    if (!version) {
      throw new Error(`Model version not found: ${versionId}`);
    }

    const buffer = await modelStorage.getObject(version.storagePath);
    const fileName = version.storagePath.split('/').pop() || 'model.bin';

    const contentTypes: Record<ModelFormat, string> = {
      pkl: 'application/octet-stream',
      onnx: 'application/octet-stream',
      pt: 'application/octet-stream',
      joblib: 'application/octet-stream',
      h5: 'application/hdf5',
      pb: 'application/octet-stream',
      custom: 'application/octet-stream',
    };

    return {
      buffer,
      fileName,
      contentType: contentTypes[version.format as ModelFormat] || 'application/octet-stream',
    };
  }

  async updateModelStatus(id: string, status: string): Promise<Model | null> {
    const model = await prisma.model.update({
      where: { id },
      data: { status, updatedAt: new Date() },
      include: {
        versions: {
          orderBy: { createdAt: 'desc' },
          take: 1,
        },
      },
    });

    if (!model) return null;
    logger.info({ modelId: id, status }, 'Model status updated');
    return this.transformModel(model);
  }

  async deleteModel(id: string): Promise<void> {
    const model = await prisma.model.findUnique({
      where: { id },
      include: { versions: true },
    });

    if (!model) return;

    for (const version of model.versions) {
      await modelStorage.deleteObject(version.storagePath).catch(() => {});
    }

    await prisma.model.delete({ where: { id } });
    logger.info({ modelId: id }, 'Model deleted');
  }

  private transformModel(prismaModel: any): Model {
    const latestVersion = prismaModel.versions?.[0];
    return {
      id: prismaModel.id,
      name: prismaModel.name,
      description: prismaModel.description ?? undefined,
      ownerId: prismaModel.ownerId,
      team: prismaModel.team,
      tags: prismaModel.tags || [],
      status: prismaModel.status as Model['status'],
      createdAt: prismaModel.createdAt.getTime(),
      updatedAt: prismaModel.updatedAt.getTime(),
      latestVersion: latestVersion ? this.transformModelVersion(latestVersion) : undefined,
      versions: (prismaModel.versions || []).map((v: any) => this.transformModelVersion(v)),
      metadata: (prismaModel.metadata as Record<string, unknown>) || {},
    };
  }

  private transformModelVersion(prismaVersion: any): ModelVersion {
    return {
      id: prismaVersion.id,
      modelId: prismaVersion.modelId,
      version: prismaVersion.version,
      semanticVersion: prismaVersion.semanticVersion,
      format: prismaVersion.format as ModelVersion['format'],
      sizeBytes: Number(prismaVersion.sizeBytes),
      storageBackend: prismaVersion.storageBackend as ModelVersion['storageBackend'],
      storagePath: prismaVersion.storagePath,
      checksum: prismaVersion.checksum,
      status: prismaVersion.status as ModelVersion['status'],
      createdAt: prismaVersion.createdAt.getTime(),
      metrics: (prismaVersion.metrics || []).map((m: any) => ({
        name: m.name,
        value: m.value,
        timestamp: m.timestamp.getTime(),
        step: m.step ?? undefined,
        context: (m.context as Record<string, unknown>) ?? undefined,
      })),
      hyperParameters: Object.fromEntries(
        (prismaVersion.hyperParameters || []).map((hp: any) => [hp.name, this.parseValue(hp.value, hp.type)])
      ),
      dataSchema: prismaVersion.dataSchema as ModelVersion['dataSchema'],
      loaderConfig: (prismaVersion.loaderConfig as Record<string, unknown>) || {},
      experimentId: prismaVersion.experimentId ?? undefined,
      tags: prismaVersion.tags || [],
    };
  }

  private parseValue(value: string, type: string): string | number | boolean | null {
    if (type === 'number') return parseFloat(value);
    if (type === 'boolean') return value === 'true';
    if (value === 'null') return null;
    return value;
  }
}

export const modelRegistryService = new ModelRegistryService();
