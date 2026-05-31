import { EventEmitter } from 'events';
import { generateId, nowISO, calculatePercentiles } from '../shared/utils';
import { logger } from '../logging';
import { monitoring } from '../monitoring';

interface DocumentChunk {
  chunk_id: string;
  document_id: string;
  content: string;
  metadata: Record<string, unknown>;
  start_index: number;
  end_index: number;
  embedding?: number[];
  token_count?: number;
}

interface ParsedDocument {
  document_id: string;
  original_name: string;
  content_type: string;
  size_bytes: number;
  content: string;
  metadata: Record<string, unknown>;
  parsed_at: string;
  chunks?: DocumentChunk[];
}

interface ParseResult {
  success: boolean;
  document?: ParsedDocument;
  error?: string;
  duration_ms: number;
}

interface VectorizationResult {
  success: boolean;
  embeddings: number[][];
  error?: string;
  duration_ms: number;
}

interface PipelineStep {
  name: string;
  process: (input: unknown, context: PipelineContext) => Promise<unknown>;
}

interface PipelineContext {
  pipeline_id: string;
  document_id: string;
  trace_id: string;
  metadata: Record<string, unknown>;
  start_time: number;
}

interface ChunkingConfig {
  chunk_size: number;
  chunk_overlap: number;
  separator: string;
  max_chunks: number;
}

type DocumentFormat = 'text' | 'markdown' | 'html' | 'pdf' | 'docx' | 'json' | 'csv';

class DocumentParser extends EventEmitter {
  private parsers: Map<DocumentFormat, (content: Buffer, metadata?: Record<string, unknown>) => Promise<string>> = new Map();
  private defaultChunkingConfig: ChunkingConfig = {
    chunk_size: 1000,
    chunk_overlap: 200,
    separator: '\n\n',
    max_chunks: 100,
  };
  private documents: Map<string, ParsedDocument> = new Map();
  private vectorizationModel?: (texts: string[]) => Promise<number[][]>;

  constructor() {
    super();
    this.registerDefaultParsers();
  }

  private registerDefaultParsers(): void {
    this.parsers.set('text', async (content: Buffer) => content.toString('utf-8'));

    this.parsers.set('markdown', async (content: Buffer) => {
      const text = content.toString('utf-8');
      return text;
    });

    this.parsers.set('html', async (content: Buffer) => {
      const text = content.toString('utf-8');
      return this.stripHtml(text);
    });

    this.parsers.set('json', async (content: Buffer) => {
      try {
        const parsed = JSON.parse(content.toString('utf-8'));
        return JSON.stringify(parsed, null, 2);
      } catch {
        return content.toString('utf-8');
      }
    });

    this.parsers.set('csv', async (content: Buffer) => {
      return content.toString('utf-8');
    });

    this.parsers.set('pdf', async (content: Buffer) => {
      try {
        const pdfParse = await import('pdf-parse');
        const data = await pdfParse.default(content);
        return data.text;
      } catch (error) {
        logger.warn('PDF parsing failed, falling back to raw text', { error: (error as Error).message });
        return content.toString('utf-8', 0, Math.min(10000, content.length));
      }
    });

    this.parsers.set('docx', async (content: Buffer) => {
      try {
        const mammoth = await import('mammoth');
        const result = await mammoth.extractRawText({ buffer: content });
        return result.value;
      } catch (error) {
        logger.warn('DOCX parsing failed, falling back to raw text', { error: (error as Error).message });
        return content.toString('utf-8', 0, Math.min(10000, content.length));
      }
    });
  }

  private stripHtml(html: string): string {
    return html
      .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
      .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, '')
      .replace(/<[^>]+>/g, ' ')
      .replace(/&nbsp;/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  detectFormat(filename: string, mimeType?: string): DocumentFormat {
    if (mimeType) {
      const mimeToFormat: Record<string, DocumentFormat> = {
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
    const extToFormat: Record<string, DocumentFormat> = {
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

  async parseDocument(
    content: Buffer,
    filename: string,
    mimeType?: string,
    metadata?: Record<string, unknown>
  ): Promise<ParseResult> {
    const startTime = Date.now();
    const format = this.detectFormat(filename, mimeType);
    const documentId = generateId('doc');
    const traceId = generateId('trace');

    logger.info('Document parsing started', { document_id: documentId, filename, format }, traceId);
    this.emit('parse.started', documentId, filename, format);

    try {
      const parser = this.parsers.get(format);
      if (!parser) {
        throw new Error(`No parser available for format: ${format}`);
      }

      const parsedContent = await parser(content, metadata);
      const document: ParsedDocument = {
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
        parsed_at: nowISO(),
      };

      this.documents.set(documentId, document);

      const duration = Date.now() - startTime;
      monitoring.incrementCounter('documents_parsed', 1, { format });
      monitoring.recordLatency('parse_duration', duration, { format });

      logger.info('Document parsing completed', { document_id: documentId, duration_ms: duration, size_bytes: content.length }, traceId);
      this.emit('parse.completed', documentId, duration);

      return {
        success: true,
        document,
        duration_ms: duration,
      };
    } catch (error) {
      const duration = Date.now() - startTime;
      monitoring.incrementCounter('parse_errors', 1, { format });

      logger.error('Document parsing failed', {
        document_id: documentId,
        error: (error as Error).message,
        duration_ms: duration,
      }, traceId);
      this.emit('parse.failed', documentId, error);

      return {
        success: false,
        error: (error as Error).message,
        duration_ms: duration,
      };
    }
  }

  chunkDocument(
    documentId: string,
    config?: Partial<ChunkingConfig>
  ): DocumentChunk[] | null {
    const document = this.documents.get(documentId);
    if (!document) {
      logger.warn('Document not found for chunking', { document_id: documentId });
      return null;
    }

    const chunkConfig = { ...this.defaultChunkingConfig, ...config };
    const chunks: DocumentChunk[] = [];

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
      } else {
        if (currentChunk) {
          chunks.push({
            chunk_id: generateId('chk'),
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
            if (chunks.length >= chunkConfig.max_chunks) break;
            chunks.push({
              chunk_id: generateId('chk'),
              document_id: documentId,
              content: subChunk.text,
              metadata: { section_index: i, sub_chunk: subChunk.index },
              start_index: currentStart + subChunk.start,
              end_index: currentStart + subChunk.end,
              token_count: this.estimateTokenCount(subChunk.text),
            });
          }
          currentChunk = '';
        } else {
          const overlapStart = Math.max(0, currentChunk.length - chunkConfig.chunk_overlap);
          currentChunk = currentChunk.slice(overlapStart) + chunkConfig.separator + section;
          currentStart += overlapStart;
        }
      }
    }

    if (currentChunk && chunks.length < chunkConfig.max_chunks) {
      chunks.push({
        chunk_id: generateId('chk'),
        document_id: documentId,
        content: currentChunk,
        metadata: { section_index: sections.length },
        start_index: currentStart,
        end_index: currentStart + currentChunk.length,
        token_count: this.estimateTokenCount(currentChunk),
      });
    }

    document.chunks = chunks;
    logger.info('Document chunked', { document_id: documentId, chunk_count: chunks.length });
    this.emit('document.chunked', documentId, chunks.length);

    return chunks;
  }

  private chunkText(text: string, chunkSize: number, overlap: number): Array<{ text: string; start: number; end: number; index: number }> {
    const chunks: Array<{ text: string; start: number; end: number; index: number }> = [];
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

  private estimateTokenCount(text: string): number {
    return Math.ceil(text.length / 4);
  }

  async vectorizeChunks(
    documentId: string
  ): Promise<VectorizationResult> {
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
      monitoring.incrementCounter('chunks_vectorized', document.chunks.length);
      monitoring.recordLatency('vectorization_duration', duration);

      logger.info('Chunks vectorized', { document_id: documentId, chunk_count: document.chunks.length, duration_ms: duration });

      return {
        success: true,
        embeddings,
        duration_ms: duration,
      };
    } catch (error) {
      return {
        success: false,
        embeddings: [],
        error: (error as Error).message,
        duration_ms: Date.now() - startTime,
      };
    }
  }

  private generateMockEmbedding(text: string): number[] {
    const hash = this.simpleHash(text);
    const embedding: number[] = [];
    for (let i = 0; i < 384; i++) {
      const val = Math.sin(hash + i * 0.1) * 0.5 + 0.5;
      embedding.push(val);
    }
    const norm = Math.sqrt(embedding.reduce((a, b) => a + b * b, 0));
    return embedding.map((v) => v / norm);
  }

  private simpleHash(text: string): number {
    let hash = 0;
    for (let i = 0; i < text.length; i++) {
      const char = text.charCodeAt(i);
      hash = ((hash << 5) - hash) + char;
      hash = hash & hash;
    }
    return Math.abs(hash);
  }

  setVectorizationModel(model: (texts: string[]) => Promise<number[][]>): void {
    this.vectorizationModel = model;
    logger.info('Vectorization model set');
  }

  getDocument(documentId: string): ParsedDocument | null {
    return this.documents.get(documentId) || null;
  }

  deleteDocument(documentId: string): boolean {
    return this.documents.delete(documentId);
  }

  listDocuments(limit?: number, offset?: number): ParsedDocument[] {
    let docs = Array.from(this.documents.values());
    if (offset) {
      docs = docs.slice(offset);
    }
    if (limit) {
      docs = docs.slice(0, limit);
    }
    return docs;
  }

  async processPipeline(
    content: Buffer,
    filename: string,
    options?: {
      mimeType?: string;
      metadata?: Record<string, unknown>;
      chunk?: boolean;
      vectorize?: boolean;
      chunkConfig?: Partial<ChunkingConfig>;
    }
  ): Promise<ParseResult> {
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

  getStats(): {
    total_documents: number;
    total_chunks: number;
    total_size_bytes: number;
    format_distribution: Record<string, number>;
  } {
    const docs = Array.from(this.documents.values());
    const formatDist: Record<string, number> = {};
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

export const documentParser = new DocumentParser();
export { DocumentParser, DocumentChunk, ParsedDocument, ParseResult, ChunkingConfig, DocumentFormat, PipelineContext, PipelineStep };
