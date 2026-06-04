import { OcrProvider, OcrResult, OcrImage, OcrServiceOptions } from '../types';

export class PaddleOcrProvider implements OcrProvider {
  name: 'paddleocr' = 'paddleocr';
  private apiBaseUrl: string;
  private apiKey?: string;
  private timeoutMs: number;
  private maxRetries: number;
  private languages: string[];

  constructor(options: OcrServiceOptions) {
    this.apiBaseUrl = options.apiBaseUrl || process.env.PADDLEOCR_API_URL || 'http://localhost:9001';
    this.apiKey = options.apiKey || process.env.PADDLEOCR_API_KEY;
    this.timeoutMs = options.timeoutMs || 30000;
    this.maxRetries = options.maxRetries || 2;
    this.languages = options.languages || ['ch', 'en'];
  }

  async recognize(image: OcrImage): Promise<OcrResult> {
    const startTime = Date.now();
    let lastError: Error | null = null;

    for (let attempt = 1; attempt <= this.maxRetries + 1; attempt++) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), this.timeoutMs);

        try {
          let response: Response;

          if (image.data) {
            const base64 = image.data.toString('base64');
            response = await fetch(`${this.apiBaseUrl}/ocr/predict`, {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                ...(this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {}),
              },
              body: JSON.stringify({
                image: base64,
                lang: this.languages[0] || 'ch',
                det: true,
                rec: true,
                cls: true,
              }),
              signal: controller.signal,
            });
          } else {
            response = await fetch(`${this.apiBaseUrl}/ocr/predict-url`, {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                ...(this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {}),
              },
              body: JSON.stringify({
                url: image.url,
                lang: this.languages[0] || 'ch',
                det: true,
                rec: true,
                cls: true,
              }),
              signal: controller.signal,
            });
          }

          clearTimeout(timeoutId);

          if (!response.ok) {
            throw new Error(`PaddleOCR API returned ${response.status}`);
          }

          const data = await response.json();

          const text = this.extractTextFromPaddleResult(data);
          const confidence = this.calculateAverageConfidence(data);

          return {
            text,
            confidence,
            language: data.lang || this.languages.join('+'),
            provider: 'paddleocr',
            processingTimeMs: Date.now() - startTime,
            success: true,
          };
        } catch (error) {
          clearTimeout(timeoutId);
          throw error;
        }
      } catch (error) {
        lastError = error as Error;
        if (attempt <= this.maxRetries) {
          await this.delay(1000 * Math.pow(2, attempt - 1));
        }
      }
    }

    return {
      text: '',
      confidence: 0,
      language: '',
      provider: 'paddleocr',
      processingTimeMs: Date.now() - startTime,
      success: false,
      error: lastError?.message || 'OCR recognition failed',
    };
  }

  private extractTextFromPaddleResult(data: any): string {
    if (!data?.results || !Array.isArray(data.results)) {
      return data?.text || '';
    }

    return data.results
      .map((item: any) => item?.text || '')
      .filter(Boolean)
      .join('\n');
  }

  private calculateAverageConfidence(data: any): number {
    if (!data?.results || !Array.isArray(data.results)) {
      return data?.confidence ?? 0.85;
    }

    const confidences = data.results
      .map((item: any) => item?.confidence)
      .filter((c: number) => typeof c === 'number');

    if (confidences.length === 0) return 0.85;

    return confidences.reduce((a: number, b: number) => a + b, 0) / confidences.length;
  }

  async recognizeBatch(images: OcrImage[]): Promise<OcrResult[]> {
    const results = await Promise.allSettled(
      images.map((image) => this.recognize(image))
    );

    return results.map((result) => {
      if (result.status === 'fulfilled') {
        return result.value;
      }
      return {
        text: '',
        confidence: 0,
        language: '',
        provider: 'paddleocr',
        processingTimeMs: 0,
        success: false,
        error: result.reason?.message || 'Batch OCR failed',
      };
    });
  }

  async healthCheck(): Promise<boolean> {
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 5000);

      const response = await fetch(`${this.apiBaseUrl}/health`, {
        signal: controller.signal,
      });

      clearTimeout(timeoutId);
      return response.ok;
    } catch {
      return false;
    }
  }

  private delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}
