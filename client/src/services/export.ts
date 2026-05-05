import { Segment, TranscribeRecord, HistoryRecord } from '../types';

export class ExportService {
  static generateSRT(segments: Segment[]): string {
    let srt = '';

    segments.forEach((segment, index) => {
      const seqNum = index + 1;
      const startTime = this.formatSRTTime(segment.startTime);
      const endTime = this.formatSRTTime(segment.endTime);
      
      srt += `${seqNum}\n`;
      srt += `${startTime} --> ${endTime}\n`;
      srt += segment.originalText;
      if (segment.translatedText) {
        srt += `\n${segment.translatedText}`;
      }
      srt += '\n\n';
    });

    return srt.trim();
  }

  static generateTXT(
    segments: Segment[],
    includeTranslation: boolean = true
  ): string {
    let txt = '';

    segments.forEach((segment) => {
      const startTime = this.formatTime(segment.startTime);
      const endTime = this.formatTime(segment.endTime);
      
      txt += `[${startTime} - ${endTime}] `;
      txt += segment.originalText;
      if (segment.translatedText && includeTranslation) {
        txt += `\n${segment.translatedText}`;
      }
      txt += '\n\n';
    });

    return txt.trim();
  }

  static generateJSON(
    segments: Segment[],
    metadata?: {
      sessionId?: string;
      audioLanguage?: string;
      targetLanguage?: string;
      totalDuration?: number;
    }
  ): string {
    const data = {
      ...metadata,
      segments: segments.map(segment => ({
      segment_id: segment.segmentId,
      start_time: segment.startTime,
      end_time: segment.endTime,
      original_text: segment.originalText,
      translated_text: segment.translatedText,
      confidence: segment.confidence,
      status: segment.status,
    })),
      exported_at: new Date().toISOString(),
    };

    return JSON.stringify(data, null, 2);
  }

  static download(content: string, filename: string, mimeType: string = 'text/plain'): void {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }

  static downloadSRT(segments: Segment[], filename?: string): void {
    const content = this.generateSRT(segments);
    const finalFilename = filename || `voicetrans_${Date.now()}.srt`;
    this.download(content, finalFilename, 'text/srt');
  }

  static downloadTXT(
    segments: Segment[],
    includeTranslation: boolean = true,
    filename?: string
  ): void {
    const content = this.generateTXT(segments, includeTranslation);
    const finalFilename = filename || `voicetrans_${Date.now()}.txt`;
    this.download(content, finalFilename, 'text/plain');
  }

  static downloadJSON(
    segments: Segment[],
    metadata?: {
      sessionId?: string;
      audioLanguage?: string;
      targetLanguage?: string;
      totalDuration?: number;
    },
    filename?: string
  ): void {
    const content = this.generateJSON(segments, metadata);
    const finalFilename = filename || `voicetrans_${Date.now()}.json`;
    this.download(content, finalFilename, 'application/json');
  }

  private static formatSRTTime(seconds: number): string {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);
    const milliseconds = Math.floor((seconds % 1) * 1000);

    return `${this.padZero(hours)}:${this.padZero(minutes)}:${this.padZero(secs)},${this.padZeroMilliseconds(milliseconds)}`;
  }

  private static formatTime(seconds: number): string {
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = Math.floor(seconds % 60);

    if (hours > 0) {
      return `${this.padZero(hours)}:${this.padZero(minutes)}:${this.padZero(secs)}`;
    }
    return `${this.padZero(minutes)}:${this.padZero(secs)}`;
  }

  private static padZero(num: number): string {
    return num.toString().padStart(2, '0');
  }

  private static padZeroMilliseconds(num: number): string {
    return num.toString().padStart(3, '0');
  }

  static generatePlainText(segments: Segment[]): string {
    return segments.map(s => s.originalText).join(' ');
  }

  static calculateWordCount(segments: Segment[]): number {
    const text = this.generatePlainText(segments);
    const chinesePattern = /[\u4e00-\u9fa5]/g;
    const chineseCount = (text.match(chinesePattern) || []).length;
    const otherText = text.replace(chinesePattern, '');
    const otherWords = otherText.split(/\s+/).filter(w => w.length > 0);
    return chineseCount + otherWords.length;
  }

  static calculateDuration(segments: Segment[]): number {
    if (segments.length === 0) return 0;
    return segments[segments.length - 1].endTime;
  }
}

export const exportService = ExportService;
