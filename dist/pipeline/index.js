"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.DocumentParser = exports.documentParser = void 0;
const events_1 = require("events");
const utils_1 = require("../shared/utils");
const logging_1 = require("../logging");
const monitoring_1 = require("../monitoring");
class DocumentParser extends events_1.EventEmitter {
    parsers = new Map();
    defaultChunkingConfig = {
        chunk_size: 1000,
        chunk_overlap: 200,
        separator: '\n\n',
        max_chunks: 100,
    };
    documents = new Map();
    vectorizationModel;
    constructor() {
        super();
        this.registerDefaultParsers();
    }
    registerDefaultParsers() {
        this.parsers.set('text', async (content) => content.toString('utf-8'));
        this.parsers.set('markdown', async (content) => {
            const text = content.toString('utf-8');
            return text;
        });
        this.parsers.set('html', async (content) => {
            const text = content.toString('utf-8');
            return this.stripHtml(text);
        });
        this.parsers.set('json', async (content) => {
            try {
                const parsed = JSON.parse(content.toString('utf-8'));
                return JSON.stringify(parsed, null, 2);
            }
            catch {
                return content.toString('utf-8');
            }
        });
        this.parsers.set('csv', async (content) => {
            return content.toString('utf-8');
        });
        this.parsers.set('pdf', async (content) => {
            try {
                const pdfParse = await Promise.resolve().then(() => __importStar(require('pdf-parse')));
                const data = await pdfParse.default(content);
                return data.text;
            }
            catch (error) {
                logging_1.logger.warn('PDF parsing failed, falling back to raw text', { error: error.message });
                return content.toString('utf-8', 0, Math.min(10000, content.length));
            }
        });
        this.parsers.set('docx', async (content) => {
            try {
                const mammoth = await Promise.resolve().then(() => __importStar(require('mammoth')));
                const result = await mammoth.extractRawText({ buffer: content });
                return result.value;
            }
            catch (error) {
                logging_1.logger.warn('DOCX parsing failed, falling back to raw text', { error: error.message });
                return content.toString('utf-8', 0, Math.min(10000, content.length));
            }
        });
    }
    stripHtml(html) {
        return html
            .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
            .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, '')
            .replace(/<[^>]+>/g, ' ')
            .replace(/&nbsp;/g, ' ')
            .replace(/\s+/g, ' ')
            .trim();
    }
    detectFormat(filename, mimeType) {
        if (mimeType) {
            const mimeToFormat = {
                'text/plain': 'text',
                'text/markdown': 'markdown',
                'text/html': 'html',
                'application/pdf': 'pdf',
                'application/json': 'json',
                'text/csv': 'csv',
                'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx',
            };
            if (mimeToFormat[mimeType]) {
                return mimeToFormat[mimeType];
            }
        }
        const ext = filename.split('.').pop()?.toLowerCase() || '';
        const extToFormat = {
            txt: 'text',
            md: 'markdown',
            html: 'html',
            htm: 'html',
            pdf: 'pdf',
            json: 'json',
            csv: 'csv',
            docx: 'docx',
        };
        return extToFormat[ext] || 'text';
    }
    async parseDocument(content, filename, mimeType, metadata) {
        const startTime = Date.now();
        const format = this.detectFormat(filename, mimeType);
        const documentId = (0, utils_1.generateId)('doc');
        const traceId = (0, utils_1.generateId)('trace');
        logging_1.logger.info('Document parsing started', { document_id: documentId, filename, format }, traceId);
        this.emit('parse.started', documentId, filename, format);
        try {
            const parser = this.parsers.get(format);
            if (!parser) {
                throw new Error(`No parser available for format: ${format}`);
            }
            const parsedContent = await parser(content, metadata);
            const document = {
                document_id: documentId,
                original_name: filename,
                content_type: format,
                size_bytes: content.length,
                content: parsedContent,
                metadata: {
                    ...metadata,
                    format,
                    character_count: parsedContent.length,
                    line_count: parsedContent.split('\n').length,
                },
                parsed_at: (0, utils_1.nowISO)(),
            };
            this.documents.set(documentId, document);
            const duration = Date.now() - startTime;
            monitoring_1.monitoring.incrementCounter('documents_parsed', 1, { format });
            monitoring_1.monitoring.recordLatency('parse_duration', duration, { format });
            logging_1.logger.info('Document parsing completed', { document_id: documentId, duration_ms: duration, size_bytes: content.length }, traceId);
            this.emit('parse.completed', documentId, duration);
            return {
                success: true,
                document,
                duration_ms: duration,
            };
        }
        catch (error) {
            const duration = Date.now() - startTime;
            monitoring_1.monitoring.incrementCounter('parse_errors', 1, { format });
            logging_1.logger.error('Document parsing failed', {
                document_id: documentId,
                error: error.message,
                duration_ms: duration,
            }, traceId);
            this.emit('parse.failed', documentId, error);
            return {
                success: false,
                error: error.message,
                duration_ms: duration,
            };
        }
    }
    chunkDocument(documentId, config) {
        const document = this.documents.get(documentId);
        if (!document) {
            logging_1.logger.warn('Document not found for chunking', { document_id: documentId });
            return null;
        }
        const chunkConfig = { ...this.defaultChunkingConfig, ...config };
        const chunks = [];
        if (document.content.length === 0) {
            return chunks;
        }
        const sections = document.content.split(chunkConfig.separator).filter((s) => s.trim().length > 0);
        let currentChunk = '';
        let currentStart = 0;
        for (let i = 0; i < sections.length && chunks.length < chunkConfig.max_chunks; i++) {
            const section = sections[i];
            if (currentChunk.length + section.length + chunkConfig.separator.length <= chunkConfig.chunk_size) {
                currentChunk += (currentChunk ? chunkConfig.separator : '') + section;
            }
            else {
                if (currentChunk) {
                    chunks.push({
                        chunk_id: (0, utils_1.generateId)('chk'),
                        document_id: documentId,
                        content: currentChunk,
                        metadata: { section_index: i },
                        start_index: currentStart,
                        end_index: currentStart + currentChunk.length,
                        token_count: this.estimateTokenCount(currentChunk),
                    });
                }
                if (section.length > chunkConfig.chunk_size) {
                    const subChunks = this.chunkText(section, chunkConfig.chunk_size, chunkConfig.chunk_overlap);
                    for (const subChunk of subChunks) {
                        if (chunks.length >= chunkConfig.max_chunks)
                            break;
                        chunks.push({
                            chunk_id: (0, utils_1.generateId)('chk'),
                            document_id: documentId,
                            content: subChunk.text,
                            metadata: { section_index: i, sub_chunk: subChunk.index },
                            start_index: currentStart + subChunk.start,
                            end_index: currentStart + subChunk.end,
                            token_count: this.estimateTokenCount(subChunk.text),
                        });
                    }
                    currentChunk = '';
                }
                else {
                    const overlapStart = Math.max(0, currentChunk.length - chunkConfig.chunk_overlap);
                    currentChunk = currentChunk.slice(overlapStart) + chunkConfig.separator + section;
                    currentStart += overlapStart;
                }
            }
        }
        if (currentChunk && chunks.length < chunkConfig.max_chunks) {
            chunks.push({
                chunk_id: (0, utils_1.generateId)('chk'),
                document_id: documentId,
                content: currentChunk,
                metadata: { section_index: sections.length },
                start_index: currentStart,
                end_index: currentStart + currentChunk.length,
                token_count: this.estimateTokenCount(currentChunk),
            });
        }
        document.chunks = chunks;
        logging_1.logger.info('Document chunked', { document_id: documentId, chunk_count: chunks.length });
        this.emit('document.chunked', documentId, chunks.length);
        return chunks;
    }
    chunkText(text, chunkSize, overlap) {
        const chunks = [];
        let index = 0;
        let start = 0;
        while (start < text.length) {
            const end = Math.min(start + chunkSize, text.length);
            chunks.push({
                text: text.slice(start, end),
                start,
                end,
                index,
            });
            start += chunkSize - overlap;
            index++;
        }
        return chunks;
    }
    estimateTokenCount(text) {
        return Math.ceil(text.length / 4);
    }
    async vectorizeChunks(documentId) {
        const startTime = Date.now();
        const document = this.documents.get(documentId);
        if (!document || !document.chunks) {
            return {
                success: false,
                embeddings: [],
                error: 'Document or chunks not found',
                duration_ms: Date.now() - startTime,
            };
        }
        if (!this.vectorizationModel) {
            const embeddings = document.chunks.map((chunk) => this.generateMockEmbedding(chunk.content));
            for (let i = 0; i < document.chunks.length; i++) {
                document.chunks[i].embedding = embeddings[i];
            }
            return {
                success: true,
                embeddings,
                duration_ms: Date.now() - startTime,
            };
        }
        try {
            const texts = document.chunks.map((c) => c.content);
            const embeddings = await this.vectorizationModel(texts);
            for (let i = 0; i < document.chunks.length; i++) {
                document.chunks[i].embedding = embeddings[i];
            }
            const duration = Date.now() - startTime;
            monitoring_1.monitoring.incrementCounter('chunks_vectorized', document.chunks.length);
            monitoring_1.monitoring.recordLatency('vectorization_duration', duration);
            logging_1.logger.info('Chunks vectorized', { document_id: documentId, chunk_count: document.chunks.length, duration_ms: duration });
            return {
                success: true,
                embeddings,
                duration_ms: duration,
            };
        }
        catch (error) {
            return {
                success: false,
                embeddings: [],
                error: error.message,
                duration_ms: Date.now() - startTime,
            };
        }
    }
    generateMockEmbedding(text) {
        const hash = this.simpleHash(text);
        const embedding = [];
        for (let i = 0; i < 384; i++) {
            const val = Math.sin(hash + i * 0.1) * 0.5 + 0.5;
            embedding.push(val);
        }
        const norm = Math.sqrt(embedding.reduce((a, b) => a + b * b, 0));
        return embedding.map((v) => v / norm);
    }
    simpleHash(text) {
        let hash = 0;
        for (let i = 0; i < text.length; i++) {
            const char = text.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash;
        }
        return Math.abs(hash);
    }
    setVectorizationModel(model) {
        this.vectorizationModel = model;
        logging_1.logger.info('Vectorization model set');
    }
    getDocument(documentId) {
        return this.documents.get(documentId) || null;
    }
    deleteDocument(documentId) {
        return this.documents.delete(documentId);
    }
    listDocuments(limit, offset) {
        let docs = Array.from(this.documents.values());
        if (offset) {
            docs = docs.slice(offset);
        }
        if (limit) {
            docs = docs.slice(0, limit);
        }
        return docs;
    }
    async processPipeline(content, filename, options) {
        const parseResult = await this.parseDocument(content, filename, options?.mimeType, options?.metadata);
        if (!parseResult.success || !parseResult.document) {
            return parseResult;
        }
        if (options?.chunk) {
            this.chunkDocument(parseResult.document.document_id, options?.chunkConfig);
        }
        if (options?.vectorize) {
            await this.vectorizeChunks(parseResult.document.document_id);
        }
        return parseResult;
    }
    getStats() {
        const docs = Array.from(this.documents.values());
        const formatDist = {};
        let totalChunks = 0;
        let totalSize = 0;
        for (const doc of docs) {
            formatDist[doc.content_type] = (formatDist[doc.content_type] || 0) + 1;
            totalChunks += doc.chunks?.length || 0;
            totalSize += doc.size_bytes;
        }
        return {
            total_documents: docs.length,
            total_chunks: totalChunks,
            total_size_bytes: totalSize,
            format_distribution: formatDist,
        };
    }
}
exports.DocumentParser = DocumentParser;
exports.documentParser = new DocumentParser();
//# sourceMappingURL=index.js.map