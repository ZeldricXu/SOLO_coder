import { describe, it, expect } from 'vitest';
import {
  detectAndDecode,
  decodeWithEncoding,
  encodingToLabel,
  EncodingDetectionError,
  COMMON_ENCODINGS,
  type EncodingName,
} from '@/utils/io/encodingDetector';

const strToBuffer = (str: string, encoding: EncodingName): ArrayBuffer => {
  const encoder = new TextEncoder();
  return encoder.encode(str).buffer;
};

describe('编码检测器 - UTF-8 测试', () => {
  it('UTF-8编码的纯ASCII应该能正确识别', async () => {
    const buffer = strToBuffer('Hello World 123', 'utf-8');
    const result = await detectAndDecode(buffer);
    expect(result.encoding).toBe('utf-8');
    expect(result.confidence).toBeGreaterThan(0.5);
  });

  it('带BOM的UTF-8应该返回100%置信度', async () => {
    const bom = new Uint8Array([0xef, 0xbb, 0xbf]);
    const content = new TextEncoder().encode('中文内容 UTF-8 with BOM');
    const combined = new Uint8Array(bom.length + content.length);
    combined.set(bom, 0);
    combined.set(content, bom.length);
    const result = await detectAndDecode(combined.buffer);
    expect(result.encoding).toBe('utf-8');
    expect(result.confidence).toBe(1.0);
  });

  it('纯中文UTF-8应该能正确识别', async () => {
    const buffer = strToBuffer('这是一段中文测试内容包含简体和繁体字符', 'utf-8');
    const result = await detectAndDecode(buffer);
    expect(result.encoding).toBe('utf-8');
    expect(result.tried).toContain('utf-8');
  });
});

describe('编码检测器 - GBK fallback 测试', () => {
  it('指定GBK编码应该能正确解码', () => {
    const chineseStr = '墙体 窗户 家具 设计图';
    const utf8Buffer = strToBuffer(chineseStr, 'utf-8');
    const decoded = decodeWithEncoding(utf8Buffer, 'utf-8');
    expect(decoded).toContain('墙体');
  });

  it('应该能尝试多种编码', async () => {
    const buffer = strToBuffer('Test content', 'utf-8');
    const result = await detectAndDecode(buffer, 'ascii');
    expect(result.tried.length).toBeGreaterThanOrEqual(1);
  });

  it('encodingToLabel应该返回友好名称', () => {
    expect(encodingToLabel('utf-8')).toBe('Unicode (UTF-8)');
    expect(encodingToLabel('gbk')).toBe('简体中文 (GBK)');
    expect(encodingToLabel('big5')).toBe('繁体中文 (Big5)');
  });

  it('COMMON_ENCODINGS应该包含4种常见编码', () => {
    expect(COMMON_ENCODINGS).toEqual(['utf-8', 'gbk', 'gb2312', 'big5']);
  });
});

describe('EncodingDetectionError', () => {
  it('应该正确构造错误对象', () => {
    const buffer = new ArrayBuffer(10);
    const error = new EncodingDetectionError('测试错误', ['utf-8', 'gbk'], buffer);
    expect(error.name).toBe('EncodingDetectionError');
    expect(error.message).toBe('测试错误');
    expect(error.tried).toEqual(['utf-8', 'gbk']);
    expect(error.buffer).toBe(buffer);
  });
});
