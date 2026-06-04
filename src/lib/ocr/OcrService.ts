import { PrismaClient } from '@prisma/client';
import pLimit from 'p-limit';
import { OcrProvider, OcrServiceOptions, OcrResult, DocumentOcrResult, OcrProviderType } from './types';
import { TesseractProvider } from './providers/TesseractProvider';
import { PaddleOcrProvider } from './providers/PaddleOcrProvider';

export class OcrService {
  private prisma: PrismaClient;
  private provider: OcrProvider;
  private concurrency: number;
  private enabled: boolean;

  constructor(
    prisma: PrismaClient,
    options?: Partial<OcrServiceOptions> & { concurrency?: number; enabled?: boolean }
  ) {
    this.prisma = prisma;
    this.concurrency = options?.concurrency || 3;
    this.enabled = options?.enabled ?? this.isEnabledByDefault();

    const providerType = options?.provider || this.getDefaultProviderType();
    this.provider = this.createProvider(providerType, options);
  }

  private isEnabledByDefault(): boolean {
    return process.env.OCR_ENABLED === 'true';
  }

  private getDefaultProviderType(): OcrProviderType {
    return (process.env.OCR_PROVIDER as OcrProviderType) || 'tesseract';
  }

  private createProvider(
    type: OcrProviderType,
    options?: Partial<OcrServiceOptions>
  ): OcrProvider {
    const baseOptions: OcrServiceOptions = {
      provider: type,
      apiBaseUrl: options?.apiBaseUrl,
      apiKey: options?.apiKey,
      timeoutMs: options?.timeoutMs,
      maxRetries: options?.maxRetries,
      languages: options?.languages,
    };

    switch (type) {
      case 'tesseract':
        return new TesseractProvider(baseOptions);
      case 'paddleocr':
        return new PaddleOcrProvider(baseOptions);
      default:
        return new TesseractProvider(baseOptions);
    }
  }

  isEnabled(): boolean {
    return this.enabled;
  }

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
  }

  async healthCheck(): Promise<{ enabled: boolean; provider: string; healthy: boolean }> {
    if (!this.enabled) {
      return { enabled: false, provider: this.provider.name, healthy: false };
    }

    const healthy = await this.provider.healthCheck();
    return { enabled: true, provider: this.provider.name, healthy };
  }

  async processDocument(
    documentId: string,
    images: Array<{ url: string; alt?: string }>
  ): Promise<DocumentOcrResult | null> {
    if (!this.enabled || images.length === 0) {
      return null;
    }

    const startTime = Date.now();
    const limit = pLimit(this.concurrency);
    const imageResults: Array<{
      imageUrl: string;
      success: boolean;
      text?: string;
      error?: string;
      confidence?: number;
    }> = [];
    const allOcrTexts: string[] = [];

    const promises = images.map((image) =>
      limit(async () => {
        const existing = await this.prisma.imageOcrRecord.findFirst({
          where: { documentId, imageUrl: image.url, isProcessed: true },
          select: { ocrText: true, confidence: true },
        });

        if (existing) {
          allOcrTexts.push(existing.ocrText);
          imageResults.push({
            imageUrl: image.url,
            success: true,
            text: existing.ocrText,
            confidence: existing.confidence ?? undefined,
          });
          return;
        }

        try {
          const result = await this.provider.recognize({ url: image.url });

          await this.prisma.imageOcrRecord.create({
            data: {
              documentId,
              imageUrl: image.url,
              ocrText: result.text,
              confidence: result.confidence,
              language: result.language,
              isProcessed: result.success,
              errorMessage: result.error,
              processedAt: new Date(),
            },
          });

          if (result.success && result.text) {
            allOcrTexts.push(result.text);
            imageResults.push({
              imageUrl: image.url,
              success: true,
              text: result.text,
              confidence: result.confidence,
            });
          } else {
            imageResults.push({
              imageUrl: image.url,
              success: false,
              error: result.error || 'OCR failed',
            });
          }
        } catch (error) {
          const errorMsg = error instanceof Error ? error.message : 'Unknown error';

          await this.prisma.imageOcrRecord.create({
            data: {
              documentId,
              imageUrl: image.url,
              ocrText: '',
              isProcessed: false,
              errorMessage: errorMsg,
              processedAt: new Date(),
            },
          });

          imageResults.push({
            imageUrl: image.url,
            success: false,
            error: errorMsg,
          });
        }
      })
    );

    await Promise.all(promises);

    const combinedOcrText = allOcrTexts.join('\n\n');

    await this.prisma.document.update({
      where: { id: documentId },
      data: {
        ocrText: combinedOcrText || null,
        ocrProcessedAt: new Date(),
        updatedAt: new Date(),
      },
    });

    return {
      documentId,
      totalImages: images.length,
      processedImages: imageResults.filter((r) => r.success).length,
      failedImages: imageResults.filter((r) => !r.success).length,
      ocrText: combinedOcrText,
      processingTimeMs: Date.now() - startTime,
      imageResults,
    };
  }

  async reprocessDocument(documentId: string): Promise<DocumentOcrResult | null> {
    await this.prisma.imageOcrRecord.deleteMany({
      where: { documentId },
    });

    await this.prisma.document.update({
      where: { id: documentId },
      data: {
        ocrText: null,
        ocrProcessedAt: null,
      },
    });

    const images = await this.extractImagesFromDocument(documentId);
    return this.processDocument(documentId, images);
  }

  private async extractImagesFromDocument(documentId: string): Promise<Array<{ url: string; alt?: string }>> {
    const document = await this.prisma.document.findUnique({
      where: { id: documentId },
      select: { content: true, contentHtml: true },
    });

    if (!document) return [];

    const images: Array<{ url: string; alt?: string }> = [];

    if (document.content) {
      const mdImages = this.extractMarkdownImages(document.content);
      images.push(...mdImages);
    }

    if (document.contentHtml) {
      const htmlImages = this.extractHtmlImages(document.contentHtml);
      for (const htmlImg of htmlImages) {
        if (!images.some((img) => img.url === htmlImg.url)) {
          images.push(htmlImg);
        }
      }
    }

    return images;
  }

  private extractMarkdownImages(content: string): Array<{ url: string; alt?: string }> {
    const imgRegex = /!\[([^\]]*)\]\(([^)]+)\)/g;
    const images: Array<{ url: string; alt?: string }> = [];
    let match;

    while ((match = imgRegex.exec(content)) !== null) {
      const url = match[2].trim();
      if (url && (url.startsWith('http') || url.startsWith('/'))) {
        images.push({
          url,
          alt: match[1] || undefined,
        });
      }
    }

    return images;
  }

  private extractHtmlImages(html: string): Array<{ url: string; alt?: string }> {
    const imgRegex = /<img[^>]+src=["']([^"']+)["'][^>]*alt=["']([^"']*)["']/gi;
    const imgRegexNoAlt = /<img[^>]+src=["']([^"']+)["']/gi;
    const images: Array<{ url: string; alt?: string }> = [];
    const foundUrls = new Set<string>();

    let match;
    while ((match = imgRegex.exec(html)) !== null) {
      const url = match[1].trim();
      if (url && !foundUrls.has(url) && (url.startsWith('http') || url.startsWith('/'))) {
        foundUrls.add(url);
        images.push({
          url,
          alt: match[2] || undefined,
        });
      }
    }

    while ((match = imgRegexNoAlt.exec(html)) !== null) {
      const url = match[1].trim();
      if (url && !foundUrls.has(url) && (url.startsWith('http') || url.startsWith('/'))) {
        foundUrls.add(url);
        images.push({
          url,
        });
      }
    }

    return images;
  }

  async processDocumentById(documentId: string): Promise<DocumentOcrResult | null> {
    const images = await this.extractImagesFromDocument(documentId);
    return this.processDocument(documentId, images);
  }

  async processBatch(documentIds: string[]): Promise<DocumentOcrResult[]> {
    const results: DocumentOcrResult[] = [];
    const limit = pLimit(this.concurrency);

    const promises = documentIds.map((id) =>
      limit(async () => {
        const result = await this.processDocumentById(id);
        if (result) {
          results.push(result);
        }
        return result;
      })
    );

    await Promise.all(promises);
    return results;
  }

  async recognizeImage(imageUrl: string): Promise<OcrResult> {
    if (!this.enabled) {
      return {
        text: '',
        confidence: 0,
        language: '',
        provider: this.provider.name,
        processingTimeMs: 0,
        success: false,
        error: 'OCR service is disabled',
      };
    }

    return this.provider.recognize({ url: imageUrl });
  }
}

export function extractImagesFromContent(
  content: string,
  contentHtml?: string | null
): Array<{ url: string; alt?: string }> {
  const images: Array<{ url: string; alt?: string }> = [];
  const foundUrls = new Set<string>();

  const mdRegex = /!\[([^\]]*)\]\(([^)]+)\)/g;
  let match;

  while ((match = mdRegex.exec(content)) !== null) {
    const url = match[2].trim();
    if (url && !foundUrls.has(url) && (url.startsWith('http') || url.startsWith('/'))) {
      foundUrls.add(url);
      images.push({
        url,
        alt: match[1] || undefined,
      });
    }
  }

  if (contentHtml) {
    const htmlRegex = /<img[^>]+src=["']([^"']+)["'][^>]*alt=["']([^"']*)["']/gi;
    const htmlRegexNoAlt = /<img[^>]+src=["']([^"']+)["']/gi;

    while ((match = htmlRegex.exec(contentHtml)) !== null) {
      const url = match[1].trim();
      if (url && !foundUrls.has(url) && (url.startsWith('http') || url.startsWith('/'))) {
        foundUrls.add(url);
        images.push({
          url,
          alt: match[2] || undefined,
        });
      }
    }

    while ((match = htmlRegexNoAlt.exec(contentHtml)) !== null) {
      const url = match[1].trim();
      if (url && !foundUrls.has(url) && (url.startsWith('http') || url.startsWith('/'))) {
        foundUrls.add(url);
        images.push({ url });
      }
    }
  }

  return images;
}
