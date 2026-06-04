export interface DocumentReference {
  documentId: string;
  title: string;
  spaceId: string;
  spaceName?: string;
  snippet: string;
  relevanceScore: number;
  url?: string;
}

export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  references?: DocumentReference[];
  createdAt: Date;
}

export interface AiQaSession {
  id: string;
  spaceId: string;
  title: string;
  messages: Message[];
  createdAt: Date;
  updatedAt: Date;
}

export interface LlmProviderConfig {
  provider: 'openai' | 'anthropic';
  apiKey: string;
  model?: string;
  baseUrl?: string;
}

export interface LlmCompletionRequest {
  messages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }>;
  temperature?: number;
  maxTokens?: number;
}

export interface LlmCompletionResponse {
  content: string;
  usage?: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
  };
}

export interface RetrievalResult {
  documentId: string;
  title: string;
  spaceId: string;
  content: string;
  snippet: string;
  relevanceScore: number;
}

export interface RAGOptions {
  topK: number;
  maxContextLength: number;
  includeOcrText: boolean;
}

export interface AskQuestionOptions {
  spaceId: string;
  question: string;
  sessionId?: string;
  topK?: number;
}

export interface AskQuestionResult {
  answer: string;
  references: DocumentReference[];
  sessionId: string;
  messageId: string;
}

export interface LlmProvider {
  complete(request: LlmCompletionRequest): Promise<LlmCompletionResponse>;
}
