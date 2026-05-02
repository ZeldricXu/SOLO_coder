export interface Document {
  id: string;
  file_name: string;
  file_type: string;
  file_size: number;
  upload_time: string;
  chunk_count: number;
  collection_name: string;
}

export interface SourceNode {
  document_id: string;
  file_name: string;
  content: string;
  score: number;
  chunk_index: number | null;
}

export interface QueryRequest {
  query: string;
  knowledge_base_id: string;
  top_k: number;
  stream: boolean;
}

export interface QueryResponse {
  answer: string;
  sources: SourceNode[];
}

export interface UploadResponse {
  document_id: string;
  file_name: string;
  file_type: string;
  chunk_count: number;
  status: string;
}

export interface CollectionInfo {
  name: string;
  document_count: number;
  chunk_count: number;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  sources?: SourceNode[];
  timestamp: Date;
}
