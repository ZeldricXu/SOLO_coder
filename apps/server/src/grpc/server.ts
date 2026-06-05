import * as grpc from '@grpc/grpc-js';
import * as protoLoader from '@grpc/proto-loader';
import path from 'node:path';
import { logger } from '../config/logger';
import { env } from '../config/env';
import { inferenceGateway } from '../inference/gateway';
import { modelRegistryService } from '../model/registry';

const PROTO_PATH = path.join(__dirname, '../proto/inference.proto');

export class GrpcServer {
  private server: grpc.Server;

  constructor() {
    this.server = new grpc.Server();
  }

  async start(): Promise<void> {
    const packageDefinition = protoLoader.loadSync(PROTO_PATH, {
      keepCase: true,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true,
    });

    const protoDescriptor = grpc.loadPackageDefinition(packageDefinition) as any;
    const mlopsPackage = protoDescriptor.mlops;

    this.server.addService(mlopsPackage.InferenceService.service, {
      Predict: this.handlePredict.bind(this),
      PredictBatch: this.handlePredictBatch.bind(this),
      GetStatus: this.handleGetStatus.bind(this),
    });

    this.server.addService(mlopsPackage.ModelRegistryService.service, {
      GetModel: this.handleGetModel.bind(this),
      ListModels: this.handleListModels.bind(this),
      GetVersion: this.handleGetVersion.bind(this),
    });

    return new Promise((resolve, reject) => {
      this.server.bindAsync(
        `${env.SERVER_HOST}:${env.GRPC_PORT}`,
        grpc.ServerCredentials.createInsecure(),
        (err, port) => {
          if (err) {
            logger.error({ error: err }, 'Failed to bind gRPC server');
            reject(err);
            return;
          }
          this.server.start();
          logger.info({ port }, 'gRPC server started');
          resolve();
        }
      );
    });
  }

  async stop(): Promise<void> {
    return new Promise((resolve) => {
      this.server.tryShutdown(() => {
        logger.info('gRPC server stopped');
        resolve();
      });
    });
  }

  private async handlePredict(
    call: grpc.ServerUnaryCall<any, any>,
    callback: grpc.sendUnaryData<any>
  ): Promise<void> {
    try {
      const request = call.request;
      const inputs: Record<string, unknown> = {};

      for (const [key, tensor] of Object.entries(request.inputs || {})) {
        inputs[key] = this.decodeTensor(tensor as any);
      }

      const result = await inferenceGateway.infer({
        modelId: request.model_id,
        version: request.version,
        inputs,
        requestId: request.request_id,
        userId: request.user_id,
        sessionId: request.session_id,
        bypassCache: request.bypass_cache,
      });

      const outputs: Record<string, unknown> = {};
      for (const [key, value] of Object.entries(result.outputs as Record<string, unknown>)) {
        outputs[key] = this.encodeTensor(value);
      }

      callback(null, {
        model_id: result.modelId,
        version: result.version,
        outputs,
        request_id: result.requestId,
        inference_id: result.inferenceId,
        latency_ms: result.latencyMs,
        batch_size: result.batchSize,
        from_cache: result.fromCache,
        timestamp: result.timestamp,
      });
    } catch (error) {
      logger.error({ error }, 'gRPC Predict failed');
      callback({
        code: grpc.status.INTERNAL,
        message: error instanceof Error ? error.message : 'Prediction failed',
      });
    }
  }

  private async handlePredictBatch(
    call: grpc.ServerUnaryCall<any, any>,
    callback: grpc.sendUnaryData<any>
  ): Promise<void> {
    try {
      const request = call.request;
      const inputs: Record<string, unknown>[] = request.requests.map((r: any) => {
        const input: Record<string, unknown> = {};
        for (const [key, tensor] of Object.entries(r.inputs || {})) {
          input[key] = this.decodeTensor(tensor as any);
        }
        return input;
      });

      const result = await inferenceGateway.batchInfer({
        modelId: request.model_id,
        version: request.version,
        inputs,
        batchSize: request.batch_size,
      });

      callback(null, {
        model_id: result.modelId,
        version: result.version,
        responses: result.outputs.map((output, idx) => ({
          model_id: result.modelId,
          version: result.version,
          outputs: { output: this.encodeTensor(output) },
          inference_id: result.inferenceIds[idx] || '',
          request_id: request.requests[idx]?.request_id || '',
        })),
        total_latency_ms: result.totalLatencyMs,
        batch_count: result.batchCount,
        timestamp: result.timestamp,
      });
    } catch (error) {
      logger.error({ error }, 'gRPC PredictBatch failed');
      callback({
        code: grpc.status.INTERNAL,
        message: error instanceof Error ? error.message : 'Batch prediction failed',
      });
    }
  }

  private handleGetStatus(
    call: grpc.ServerUnaryCall<any, any>,
    callback: grpc.sendUnaryData<any>
  ): void {
    try {
      const status = inferenceGateway.getStatus();
      callback(null, {
        uptime_ms: status.uptimeMs,
        total_requests: status.totalRequests,
        success_rate: status.successRate,
        avg_latency_ms: status.avgLatencyMs,
        p50_latency_ms: status.p50LatencyMs,
        p95_latency_ms: status.p95LatencyMs,
        p99_latency_ms: status.p99LatencyMs,
        loaded_models: status.loadModels.map((m) => ({
          model_id: m.modelId,
          version: m.version,
          status: m.status,
          loaded_at: m.loadedAt || 0,
          memory_usage_bytes: m.memoryUsageBytes || 0,
        })),
      });
    } catch (error) {
      logger.error({ error }, 'gRPC GetStatus failed');
      callback({
        code: grpc.status.INTERNAL,
        message: error instanceof Error ? error.message : 'Get status failed',
      });
    }
  }

  private async handleGetModel(
    call: grpc.ServerUnaryCall<any, any>,
    callback: grpc.sendUnaryData<any>
  ): Promise<void> {
    try {
      const model = await modelRegistryService.getModel(call.request.id);
      if (!model) {
        callback({ code: grpc.status.NOT_FOUND, message: 'Model not found' });
        return;
      }

      callback(null, this.transformModelToProto(model));
    } catch (error) {
      callback({
        code: grpc.status.INTERNAL,
        message: error instanceof Error ? error.message : 'Get model failed',
      });
    }
  }

  private async handleListModels(
    call: grpc.ServerUnaryCall<any, any>,
    callback: grpc.sendUnaryData<any>
  ): Promise<void> {
    try {
      const result = await modelRegistryService.listModels({
        name: call.request.name,
        team: call.request.team,
        page: call.request.page || 1,
        pageSize: call.request.page_size || 20,
      });

      callback(null, {
        models: result.data.map((m) => this.transformModelToProto(m)),
        total: result.total,
        page: result.page,
        page_size: result.pageSize,
        total_pages: result.totalPages,
      });
    } catch (error) {
      callback({
        code: grpc.status.INTERNAL,
        message: error instanceof Error ? error.message : 'List models failed',
      });
    }
  }

  private async handleGetVersion(
    call: grpc.ServerUnaryCall<any, any>,
    callback: grpc.sendUnaryData<any>
  ): Promise<void> {
    try {
      const version = await modelRegistryService.getModelVersion(call.request.id);
      if (!version) {
        callback({ code: grpc.status.NOT_FOUND, message: 'Version not found' });
        return;
      }

      callback(null, this.transformVersionToProto(version));
    } catch (error) {
      callback({
        code: grpc.status.INTERNAL,
        message: error instanceof Error ? error.message : 'Get version failed',
      });
    }
  }

  private decodeTensor(tensor: { dtype: string; shape: number[]; data: Buffer }): unknown {
    const data = tensor.data;
    switch (tensor.dtype) {
      case 'float32': {
        const view = new Float32Array(
          data.buffer,
          data.byteOffset,
          data.byteLength / Float32Array.BYTES_PER_ELEMENT
        );
        return tensor.shape.length > 1 ? Array.from(view) : view[0];
      }
      case 'float64': {
        const view = new Float64Array(
          data.buffer,
          data.byteOffset,
          data.byteLength / Float64Array.BYTES_PER_ELEMENT
        );
        return tensor.shape.length > 1 ? Array.from(view) : view[0];
      }
      case 'int32': {
        const view = new Int32Array(
          data.buffer,
          data.byteOffset,
          data.byteLength / Int32Array.BYTES_PER_ELEMENT
        );
        return tensor.shape.length > 1 ? Array.from(view) : view[0];
      }
      case 'int64': {
        const view = new BigInt64Array(
          data.buffer,
          data.byteOffset,
          data.byteLength / BigInt64Array.BYTES_PER_ELEMENT
        );
        return tensor.shape.length > 1 ? Array.from(view).map(Number) : Number(view[0]);
      }
      default:
        return Array.from(data);
    }
  }

  private encodeTensor(value: unknown): any {
    if (Array.isArray(value)) {
      const flat = value.flat(Infinity) as number[];
      const shape = this.getShape(value);
      const buffer = Buffer.from(Float32Array.from(flat).buffer);
      return {
        shape,
        dtype: 'float32',
        data: buffer,
      };
    } else if (typeof value === 'number') {
      const buffer = Buffer.from(Float32Array.from([value]).buffer);
      return {
        shape: [],
        dtype: 'float32',
        data: buffer,
      };
    } else if (typeof value === 'string') {
      return {
        shape: [],
        dtype: 'string',
        data: Buffer.from(value),
      };
    } else {
      const buffer = Buffer.from(JSON.stringify(value));
      return {
        shape: [],
        dtype: 'bytes',
        data: buffer,
      };
    }
  }

  private getShape(arr: unknown): number[] {
    if (!Array.isArray(arr)) return [];
    return [arr.length, ...this.getShape(arr[0])];
  }

  private transformModelToProto(model: any): any {
    return {
      id: model.id,
      name: model.name,
      description: model.description || '',
      owner_id: model.ownerId,
      team: model.team,
      tags: model.tags || [],
      status: model.status,
      created_at: model.createdAt,
      updated_at: model.updatedAt,
      latest_version: model.latestVersion
        ? this.transformVersionToProto(model.latestVersion)
        : undefined,
    };
  }

  private transformVersionToProto(version: any): any {
    return {
      id: version.id,
      model_id: version.modelId,
      version: version.version,
      semantic_version: version.semanticVersion,
      format: version.format,
      size_bytes: version.sizeBytes,
      storage_backend: version.storageBackend,
      storage_path: version.storagePath,
      status: version.status,
      created_at: version.createdAt,
    };
  }
}

export const grpcServer = new GrpcServer();
