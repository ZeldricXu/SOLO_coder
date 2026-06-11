import type { FastifyRequest, FastifyReply } from 'fastify';
import { modelRegistryService } from './registry';
import type {
  ModelCreateRequest,
  ModelListRequest,
} from '@mlops/shared';

export async function registerModelRoutes(fastify: any): Promise<void> {
  const service = modelRegistryService;

  fastify.post('/api/v1/models', async (request: FastifyRequest, reply: FastifyReply) => {
    const result = await service.createModel(request.body as ModelCreateRequest);
    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/models/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.getModel(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Model not found' });
    return result;
  });

  fastify.get('/api/v1/models', async (request: FastifyRequest, reply: FastifyReply) => {
    return service.listModels(request.query as ModelListRequest);
  });

  fastify.patch('/api/v1/models/:id/status', async (request: FastifyRequest<{ Params: { id: string }; Body: { status: string } }>, reply: FastifyReply) => {
    const result = await service.updateModelStatus(request.params.id, request.body.status);
    if (!result) return reply.status(404).send({ error: 'Model not found' });
    return result;
  });

  fastify.delete('/api/v1/models/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    await service.deleteModel(request.params.id);
    return reply.status(204).send();
  });

  fastify.post('/api/v1/models/:id/versions', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const parts = request.parts();
    let fileBuffer: Buffer | null = null;
    let fileName = '';
    const metadata: Record<string, unknown> = {};

    for await (const part of parts) {
      if (part.type === 'file') {
        const chunks: Uint8Array[] = [];
        for await (const chunk of part.file) {
          chunks.push(chunk);
        }
        fileBuffer = Buffer.concat(chunks);
        fileName = part.filename;
      } else {
        metadata[part.fieldname] = part.value;
      }
    }

    if (!fileBuffer) {
      return reply.status(400).send({ error: 'File is required' });
    }

    const result = await service.createModelVersion({
      ...(metadata as any),
      modelId: request.params.id,
      fileBuffer,
      fileName,
    });

    return reply.status(201).send(result);
  });

  fastify.get('/api/v1/models/:id/versions', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    return service.listModelVersions({
      ...(request.query as any),
      modelId: request.params.id,
    });
  });

  fastify.get('/api/v1/versions/:id', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.getModelVersion(request.params.id);
    if (!result) return reply.status(404).send({ error: 'Version not found' });
    return result;
  });

  fastify.get('/api/v1/versions/:id/download', async (request: FastifyRequest<{ Params: { id: string } }>, reply: FastifyReply) => {
    const result = await service.downloadModelVersion(request.params.id);
    reply.header('Content-Disposition', `attachment; filename="${result.fileName}"`);
    reply.type(result.contentType);
    return result.buffer;
  });
}
