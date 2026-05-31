"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const pipeline_1 = require("../pipeline");
const logging_1 = require("../logging");
const router = (0, express_1.Router)();
router.post('/parse', async (req, res) => {
    try {
        const { content, filename, mime_type, metadata, chunk, vectorize } = req.body;
        if (!content || !filename) {
            res.status(400).json({ code: 400, error: 'Content and filename are required' });
            return;
        }
        const buffer = Buffer.from(content, 'base64');
        const result = await pipeline_1.documentParser.processPipeline(buffer, filename, {
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
    }
    catch (error) {
        logging_1.logger.error('Document parsing failed', { error: error.message });
        res.status(500).json({ code: 500, error: error.message });
    }
});
router.get('/', (req, res) => {
    const limit = req.query.limit ? parseInt(req.query.limit, 10) : undefined;
    const offset = req.query.offset ? parseInt(req.query.offset, 10) : undefined;
    const docs = pipeline_1.documentParser.listDocuments(limit, offset);
    res.json({ code: 200, data: docs });
});
router.get('/:id', (req, res) => {
    const doc = pipeline_1.documentParser.getDocument(req.params.id);
    if (!doc) {
        res.status(404).json({ code: 404, error: 'Document not found' });
        return;
    }
    res.json({ code: 200, data: doc });
});
router.post('/:id/chunk', (req, res) => {
    const { chunk_size, chunk_overlap, separator, max_chunks } = req.body;
    const chunks = pipeline_1.documentParser.chunkDocument(req.params.id, {
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
router.post('/:id/vectorize', async (req, res) => {
    const result = await pipeline_1.documentParser.vectorizeChunks(req.params.id);
    if (!result.success) {
        res.status(500).json({ code: 500, error: result.error });
        return;
    }
    res.json({ code: 200, data: { vectorized: result.embeddings.length } });
});
router.delete('/:id', (req, res) => {
    const deleted = pipeline_1.documentParser.deleteDocument(req.params.id);
    if (!deleted) {
        res.status(404).json({ code: 404, error: 'Document not found' });
        return;
    }
    res.json({ code: 200, message: 'Document deleted' });
});
router.get('/stats/summary', (req, res) => {
    const stats = pipeline_1.documentParser.getStats();
    res.json({ code: 200, data: stats });
});
exports.default = router;
//# sourceMappingURL=documents.js.map