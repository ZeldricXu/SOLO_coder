export type OcrProviderType = 'tesseract' | 'paddleocr';

export interface OcrImage {
  url: string;
  data?: Buffer;
  mimeType?: string;
}

export interface OcrResult {
  text: string;
  confidence: number;
  language: string;
  provider: OcrProviderType;
  processingTimeMs: number;
  success: boolean;
  error?: string;
}

export interface OcrProvider {
  name: OcrProviderType;
  recognize(image: OcrImage): Promise<OcrResult>;
  recognizeBatch(images: OcrImage[]): Promise<OcrResult[]>;
  healthCheck(): Promise<boolean>;
}

export interface OcrServiceOptions {
  provider: OcrProviderType;
  apiBaseUrl?: string;
  apiKey?: string;
  timeoutMs?: number;
  maxRetries?: number;
  languages?: string[];
}

export interface ImageExtractedImage {
  url: string;
  position: number;
  alt?: string;
}

export interface DocumentOcrResult {
  documentId: string;
  totalImages: number;
  processedImages: number;
  failedImages: number;
  ocrText: string;
  processingTimeMs: number;
  imageResults: Array<{
    imageUrl: string;
    success: boolean;
    text?: string;
    error?: string;
  }>;
}
