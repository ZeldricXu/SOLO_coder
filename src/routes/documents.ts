import { Router, Request, Response } from 'express';
import { documentParser } from '../pipeline';
import { logger } from '../logging';

const router = Router();

router.post('/parse', async (req: Request, res: Response) => {
  try {
    const { content, filename, mime_type, metadata, chunk, vectorize } = req.body;

    if (!content || !filename) {
      res.status(400).json({ code: 400, error: 'Content and filename are required' });
      return;
    }

    const buffer = Buffer.from(content, 'base64');

    const result = await documentParser.processPipeline(buffer, filename, {
      mimeType: mime_type,
      metadata,
      chunk: chunk === true,
      vectorize: vectorize === true,
    });

    if (!result.success) {
      res.status(500).json({ code: 500, error: result.error });
      return;
    }

    res.status(201).json({ code: 201, data: result });
  } catch (error) {
    logger.error('Document parsing failed', { error: (error as Error).message });
    res.status(500).json({ code: 500, error: (error as Error).message });
  }
});

router.get('/', (req: Request, res: Response) => {
  const limit = req.query.limit ? parseInt(req.query.limit as string, 10) : undefined;
  const offset = req.query.offset ? parseInt(req.query.offset as string, 10) : undefined;
  const docs = documentParser.listDocuments(limit, offset);
  res.json({ code: 200, data: docs });
});

router.get('/:id', (req: Request, res: Response) => {
  const doc = documentParser.getDocument(req.params.id);
  if (!doc) {
    res.status(404).json({ code: 404, error: 'Document not found' });
    return;
  }
  res.json({ code: 200, data: doc });
});

router.post('/:id/chunk', (req: Request, res: Response) => {
  const { chunk_size, chunk_overlap, separator, max_chunks } = req.body;
  const chunks = documentParser.chunkDocument(req.params.id, {
    chunk_size,
    chunk_overlap,
    separator,
    max_chunks,
  });

  if (!chunks) {
    res.status(404).json({ code: 404, error: 'Document not found' });
    return;
  }

  res.json({ code: 200, data: { chunk_count: chunks.length, chunks } });
});

router.post('/:id/vectorize', async (req: Request, res: Response) => {
  const result = await documentParser.vectorizeChunks(req.params.id);
  if (!result.success) {
    res.status(500).json({ code: 500, error: result.error });
    return;
  }
  res.json({ code: 200, data: { vectorized: result.embeddings.length } });
});

router.delete('/:id', (req: Request, res: Response) => {
  const deleted = documentParser.deleteDocument(req.params.id);
  if (!deleted) {
    res.status(404).json({ code: 404, error: 'Document not found' });
    return;
  }
  res.json({ code: 200, message: 'Document deleted' });
});

router.get('/stats/summary', (req: Request, res: Response) => {
  const stats = documentParser.getStats();
  res.json({ code: 200, data: stats });
});

export default router;
