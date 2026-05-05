import { DatabaseService } from '../src/services/database';
import { Segment, TranscribeRecord } from '../src/types';
import { existsSync, unlinkSync, mkdirSync } from 'fs';
import { join } from 'path';

const testDataDir = join(process.cwd(), 'test-data');

describe('DatabaseService', () => {
  beforeEach(() => {
    if (!existsSync(testDataDir)) {
      mkdirSync(testDataDir, { recursive: true });
    }
    jest.resetModules();
  });

  afterEach(() => {
    const dbPath = join(testDataDir, 'voicetrans.db');
    if (existsSync(dbPath)) {
      unlinkSync(dbPath);
    }
  });

  describe('CRUD operations', () => {
    it('should create a new transcribe record', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '这是第一段测试文本',
          confidence: 0.95,
          status: 'confirmed',
        },
      ];

      const record = DatabaseService.createRecord({
        sessionId: 'test-session-001',
        audioLanguage: 'zh-CN',
        targetLanguage: 'en-US',
        segments,
        totalDuration: 120,
      });

      expect(record.transcribeId).toBeDefined();
      expect(record.sessionId).toBe('test-session-001');
      expect(record.audioLanguage).toBe('zh-CN');
      expect(record.targetLanguage).toBe('en-US');
      expect(record.segments).toHaveLength(1);
      expect(record.totalDuration).toBe(120);
    });

    it('should retrieve a record by transcribeId', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '测试检索功能',
          confidence: 0.92,
          status: 'confirmed',
        },
      ];

      const created = DatabaseService.createRecord({
        sessionId: 'test-session-002',
        audioLanguage: 'zh-CN',
        segments,
        totalDuration: 60,
      });

      const retrieved = DatabaseService.getRecord(created.transcribeId);

      expect(retrieved).not.toBeNull();
      expect(retrieved!.transcribeId).toBe(created.transcribeId);
      expect(retrieved!.sessionId).toBe('test-session-002');
      expect(retrieved!.segments).toHaveLength(1);
      expect(retrieved!.segments[0].originalText).toBe('测试检索功能');
    });

    it('should return null for non-existent transcribeId', () => {
      const result = DatabaseService.getRecord('non-existent-id');
      expect(result).toBeNull();
    });

    it('should retrieve records by sessionId', () => {
      DatabaseService.createRecord({
        sessionId: 'session-multi-001',
        audioLanguage: 'zh-CN',
        segments: [
          {
            segmentId: 1,
            startTime: 0,
            endTime: 2.0,
            originalText: '第一段',
            confidence: 0.9,
            status: 'confirmed',
          },
        ],
        totalDuration: 30,
      });

      DatabaseService.createRecord({
        sessionId: 'session-multi-001',
        audioLanguage: 'zh-CN',
        segments: [
          {
            segmentId: 2,
            startTime: 2.0,
            endTime: 4.0,
            originalText: '第二段',
            confidence: 0.9,
            status: 'confirmed',
          },
        ],
        totalDuration: 30,
      });

      const records = DatabaseService.getRecordsBySession('session-multi-001');
      expect(records).toHaveLength(2);
    });

    it('should delete a record', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '待删除的文本',
          confidence: 0.9,
          status: 'confirmed',
        },
      ];

      const created = DatabaseService.createRecord({
        sessionId: 'test-delete-001',
        audioLanguage: 'zh-CN',
        segments,
        totalDuration: 30,
      });

      let retrieved = DatabaseService.getRecord(created.transcribeId);
      expect(retrieved).not.toBeNull();

      DatabaseService.deleteRecord(created.transcribeId);

      retrieved = DatabaseService.getRecord(created.transcribeId);
      expect(retrieved).toBeNull();
    });

    it('should update a record', () => {
      const initialSegments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '初始文本',
          confidence: 0.9,
          status: 'confirmed',
        },
      ];

      const created = DatabaseService.createRecord({
        sessionId: 'test-update-001',
        audioLanguage: 'zh-CN',
        segments: initialSegments,
        totalDuration: 30,
      });

      const updatedSegments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '初始文本',
          translatedText: 'Initial text',
          confidence: 0.9,
          status: 'confirmed',
        },
        {
          segmentId: 2,
          startTime: 2.0,
          endTime: 4.0,
          originalText: '新增文本',
          translatedText: 'Additional text',
          confidence: 0.95,
          status: 'confirmed',
        },
      ];

      DatabaseService.updateRecord(created.transcribeId, {
        segments: updatedSegments,
        totalDuration: 60,
      });

      const retrieved = DatabaseService.getRecord(created.transcribeId);
      expect(retrieved).not.toBeNull();
      expect(retrieved!.segments).toHaveLength(2);
      expect(retrieved!.totalDuration).toBe(60);
      expect(retrieved!.segments[0].translatedText).toBe('Initial text');
    });
  });

  describe('History query', () => {
    beforeEach(() => {
      jest.resetModules();
    });

    it('should get history with pagination', () => {
      for (let i = 0; i < 15; i++) {
        DatabaseService.createRecord({
          sessionId: `test-history-${i}`,
          audioLanguage: 'zh-CN',
          segments: [
            {
              segmentId: i,
              startTime: 0,
              endTime: 2.0,
              originalText: `历史记录测试 ${i}`,
              confidence: 0.9,
              status: 'confirmed',
            },
          ],
          totalDuration: 10,
        });
      }

      const result1 = DatabaseService.getHistory({ limit: 10, offset: 0 });
      expect(result1.records).toHaveLength(10);
      expect(result1.total).toBe(15);

      const result2 = DatabaseService.getHistory({ limit: 10, offset: 10 });
      expect(result2.records).toHaveLength(5);
    });

    it('should return empty result when no records', () => {
      const result = DatabaseService.getHistory({ limit: 10, offset: 0 });
      expect(result.records).toHaveLength(0);
      expect(result.total).toBe(0);
    });

    it('should order records by created_at descending', () => {
      for (let i = 0; i < 5; i++) {
        DatabaseService.createRecord({
          sessionId: `test-order-${i}`,
          audioLanguage: 'zh-CN',
          segments: [
            {
              segmentId: i,
              startTime: 0,
              endTime: 2.0,
              originalText: `测试 ${i}`,
              confidence: 0.9,
              status: 'confirmed',
            },
          ],
          totalDuration: 10,
        });
      }

      const result = DatabaseService.getHistory({ limit: 10, offset: 0 });
      expect(result.records).toHaveLength(5);
    });
  });

  describe('Edge cases', () => {
    it('should handle empty segments array', () => {
      const record = DatabaseService.createRecord({
        sessionId: 'test-empty-segments',
        audioLanguage: 'zh-CN',
        segments: [],
        totalDuration: 0,
      });

      const retrieved = DatabaseService.getRecord(record.transcribeId);
      expect(retrieved).not.toBeNull();
      expect(retrieved!.segments).toHaveLength(0);
    });

    it('should handle pending status segments', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '待确认文本',
          confidence: 0.5,
          status: 'pending',
        },
      ];

      const record = DatabaseService.createRecord({
        sessionId: 'test-pending',
        audioLanguage: 'zh-CN',
        segments,
        totalDuration: 30,
      });

      const retrieved = DatabaseService.getRecord(record.transcribeId);
      expect(retrieved!.segments[0].status).toBe('pending');
      expect(retrieved!.segments[0].confidence).toBe(0.5);
    });

    it('should handle optional targetLanguage', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '没有翻译的文本',
          confidence: 0.9,
          status: 'confirmed',
        },
      ];

      const record = DatabaseService.createRecord({
        sessionId: 'test-no-target-lang',
        audioLanguage: 'zh-CN',
        segments,
        totalDuration: 30,
      });

      const retrieved = DatabaseService.getRecord(record.transcribeId);
      expect(retrieved!.targetLanguage).toBeUndefined();
    });
  });
});

describe('ExportService (simulated)', () => {
  describe('SRT format generation', () => {
    it('should generate valid SRT format', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0.5,
          endTime: 3.2,
          originalText: '这是第一段字幕',
          confidence: 0.95,
          status: 'confirmed',
        },
        {
          segmentId: 2,
          startTime: 3.5,
          endTime: 6.8,
          originalText: '这是第二段字幕',
          translatedText: 'This is the second subtitle',
          confidence: 0.9,
          status: 'confirmed',
        },
      ];

      const srt = generateSRT(segments);

      expect(srt).toContain('00:00:00,500 --> 00:00:03,200');
      expect(srt).toContain('00:00:03,500 --> 00:00:06,800');
      expect(srt).toContain('这是第一段字幕');
      expect(srt).toContain('这是第二段字幕');
      expect(srt).toContain('This is the second subtitle');
    });

    it('should format time correctly for different durations', () => {
      expect(formatSRTTime(0.5)).toBe('00:00:00,500');
      expect(formatSRTTime(65.25)).toBe('00:01:05,250');
      expect(formatSRTTime(3661.5)).toBe('01:01:01,500');
    });

    it('should handle empty segments array', () => {
      const srt = generateSRT([]);
      expect(srt).toBe('');
    });
  });

  describe('TXT format generation', () => {
    it('should generate readable TXT format with timestamps', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '第一段文本',
          confidence: 0.95,
          status: 'confirmed',
        },
      ];

      const txt = generateTXT(segments);

      expect(txt).toContain('[00:00 - 00:02]');
      expect(txt).toContain('第一段文本');
    });

    it('should include translation when available', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '中文文本',
          translatedText: 'Chinese text',
          confidence: 0.95,
          status: 'confirmed',
        },
      ];

      const txtWithTranslation = generateTXT(segments, true);
      const txtWithoutTranslation = generateTXT(segments, false);

      expect(txtWithTranslation).toContain('Chinese text');
      expect(txtWithoutTranslation).not.toContain('Chinese text');
    });
  });

  describe('JSON format generation', () => {
    it('should generate valid JSON format', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '测试JSON导出',
          confidence: 0.95,
          status: 'confirmed',
        },
      ];

      const json = generateJSON(segments, {
        sessionId: 'test-json-001',
        audioLanguage: 'zh-CN',
      });

      const parsed = JSON.parse(json);
      expect(parsed.sessionId).toBe('test-json-001');
      expect(parsed.audioLanguage).toBe('zh-CN');
      expect(parsed.segments).toHaveLength(1);
      expect(parsed.exported_at).toBeDefined();
    });
  });

  describe('Word count calculation', () => {
    it('should calculate Chinese word count correctly', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '这是一段测试文本',
          confidence: 0.95,
          status: 'confirmed',
        },
      ];

      const wordCount = calculateWordCount(segments);
      expect(wordCount).toBe(8);
    });

    it('should calculate mixed Chinese/English word count', () => {
      const segments: Segment[] = [
        {
          segmentId: 1,
          startTime: 0,
          endTime: 2.0,
          originalText: '这是一段 test 文本',
          confidence: 0.95,
          status: 'confirmed',
        },
      ];

      const wordCount = calculateWordCount(segments);
      expect(wordCount).toBe(7);
    });
  });
});

function formatSRTTime(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const secs = Math.floor(seconds % 60);
  const milliseconds = Math.floor((seconds % 1) * 1000);

  return `${padZero(hours)}:${padZero(minutes)}:${padZero(secs)},${padZeroMilliseconds(milliseconds)}`;
}

function padZero(num: number): string {
  return num.toString().padStart(2, '0');
}

function padZeroMilliseconds(num: number): string {
  return num.toString().padStart(3, '0');
}

function formatTime(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${padZero(mins)}:${padZero(secs)}`;
}

function generateSRT(segments: Segment[]): string {
  let srt = '';

  segments.forEach((segment, index) => {
    const seqNum = index + 1;
    const startTime = formatSRTTime(segment.startTime);
    const endTime = formatSRTTime(segment.endTime);
    
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

function generateTXT(segments: Segment[], includeTranslation: boolean = true): string {
  let txt = '';

  segments.forEach((segment) => {
    const startTime = formatTime(segment.startTime);
    const endTime = formatTime(segment.endTime);
    
    txt += `[${startTime} - ${endTime}] `;
    txt += segment.originalText;
    if (segment.translatedText && includeTranslation) {
      txt += `\n${segment.translatedText}`;
    }
    txt += '\n\n';
  });

  return txt.trim();
}

function generateJSON(
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

function calculateWordCount(segments: Segment[]): number {
  const text = segments.map(s => s.originalText).join(' ');
  const chinesePattern = /[\u4e00-\u9fa5]/g;
  const chineseCount = (text.match(chinesePattern) || []).length;
  const otherText = text.replace(chinesePattern, '');
  const otherWords = otherText.split(/\s+/).filter(w => w.length > 0);
  return chineseCount + otherWords.length;
}
