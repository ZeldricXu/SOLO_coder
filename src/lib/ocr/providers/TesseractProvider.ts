import { OcrProvider, OcrResult, OcrImage, OcrServiceOptions } from '../types';

export class TesseractProvider implements OcrProvider {
  name: 'tesseract' = 'tesseract';
  private apiBaseUrl: string;
  private apiKey?: string;
  private timeoutMs: number;
  private maxRetries: number;
  private languages: string[];

  constructor(options: OcrServiceOptions) {
    this.apiBaseUrl = options.apiBaseUrl || process.env.TESSERACT_API_URL || 'http://localhost:9000';
    this.apiKey = options.apiKey || process.env.TESSERACT_API_KEY;
    this.timeoutMs = options.timeoutMs || 30000;
    this.maxRetries = options.maxRetries || 2;
    this.languages = options.languages || ['chi_sim', 'eng'];
  }

  async recognize(image: OcrImage): Promise<OcrResult> {
    const startTime = Date.now();
    let lastError: Error | null = null;

    for (let attempt = 1; attempt <= this.maxRetries + 1; attempt++) {
      try {
        const formData = new FormData();

        if (image.data) {
          const blob = new Blob([image.data], { type: image.mimeType || 'image/png' });
          formData.append('file', blob, 'image.png');
        } else {
          formData.append('url', image.url);
        }

        formData.append('languages', this.languages.join(','));

        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), this.timeoutMs);

        try {
          const response = await fetch(`${this.apiBaseUrl}/ocr`, {
            method: 'POST',
            headers: {
              ...(this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {}),
            },
            body: formData,
            signal: controller.signal,
          });

          clearTimeout(timeoutId);

          if (!response.ok) {
            throw new Error(`Tesseract API returned ${response.status}`);
          }

          const data = await response.json();

          return {
            text: data.text || '',
            confidence: data.confidence ?? 0.8,
            language: data.language || 'chi_sim+eng',
            provider: 'tesseract',
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
      provider: 'tesseract',
      processingTimeMs: Date.now() - startTime,
      success: false,
      error: lastError?.message || 'OCR recognition failed',
    };
  }

  async recognizeBatch(images: OcrImage[]): Promise<OcrResult[]> {
    const results = await Promise.allSettled(
      images.map((image) => this.recognize(image))
    );

    return results.map((result, index) => {
      if (result.status === 'fulfilled') {
        return result.value;
      }
      return {
        text: '',
        confidence: 0,
        language: '',
        provider: 'tesseract',
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
