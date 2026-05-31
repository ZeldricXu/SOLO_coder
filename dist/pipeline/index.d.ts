import { EventEmitter } from 'events';
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
declare class DocumentParser extends EventEmitter {
    private parsers;
    private defaultChunkingConfig;
    private documents;
    private vectorizationModel?;
    constructor();
    private registerDefaultParsers;
    private stripHtml;
    detectFormat(filename: string, mimeType?: string): DocumentFormat;
    parseDocument(content: Buffer, filename: string, mimeType?: string, metadata?: Record<string, unknown>): Promise<ParseResult>;
    chunkDocument(documentId: string, config?: Partial<ChunkingConfig>): DocumentChunk[] | null;
    private chunkText;
    private estimateTokenCount;
    vectorizeChunks(documentId: string): Promise<VectorizationResult>;
    private generateMockEmbedding;
    private simpleHash;
    setVectorizationModel(model: (texts: string[]) => Promise<number[][]>): void;
    getDocument(documentId: string): ParsedDocument | null;
    deleteDocument(documentId: string): boolean;
    listDocuments(limit?: number, offset?: number): ParsedDocument[];
    processPipeline(content: Buffer, filename: string, options?: {
        mimeType?: string;
        metadata?: Record<string, unknown>;
        chunk?: boolean;
        vectorize?: boolean;
        chunkConfig?: Partial<ChunkingConfig>;
    }): Promise<ParseResult>;
    getStats(): {
        total_documents: number;
        total_chunks: number;
        total_size_bytes: number;
        format_distribution: Record<string, number>;
    };
}
export declare const documentParser: DocumentParser;
export { DocumentParser, DocumentChunk, ParsedDocument, ParseResult, ChunkingConfig, DocumentFormat, PipelineContext, PipelineStep };
//# sourceMappingURL=index.d.ts.map