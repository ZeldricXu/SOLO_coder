export type EncodingName = 'utf-8' | 'gbk' | 'gb2312' | 'big5' | 'shift_jis' | 'euc-kr' | 'ascii';

export const COMMON_ENCODINGS: EncodingName[] = ['utf-8', 'gbk', 'gb2312', 'big5'];

export interface EncodingDetectionResult {
  encoding: EncodingName;
  confidence: number;
  tried: EncodingName[];
  errors: Record<EncodingName, string>;
}

const UTF8_BOM = [0xef, 0xbb, 0xbf];
const GBK_START = 0x81;
const GBK_END = 0xfe;

const hasUTF8BOM = (bytes: Uint8Array): boolean =>
  bytes.length >= 3 && bytes[0] === UTF8_BOM[0] && bytes[1] === UTF8_BOM[1] && bytes[2] === UTF8_BOM[2];

const looksLikeGBK = (bytes: Uint8Array): boolean => {
  let i = 0;
  while (i < bytes.length) {
    const b = bytes[i];
    if (b < 0x80) {
      i++;
      continue;
    }
    if (b >= GBK_START && b <= GBK_END && i + 1 < bytes.length) {
      const next = bytes[i + 1];
      if (next >= 0x40 && next <= 0xfe && next !== 0x7f) {
        i += 2;
        continue;
      }
    }
    return false;
  }
  return true;
};

const tryDecode = (bytes: Uint8Array, encoding: EncodingName): { success: boolean; text: string; error?: string } => {
  try {
    const decoder = new TextDecoder(encoding, { fatal: true });
    const text = decoder.decode(bytes);
    return { success: true, text };
  } catch (e) {
    return { success: false, text: '', error: (e as Error).message };
  }
};

export const detectAndDecode = async (
  buffer: ArrayBuffer,
  preferred?: EncodingName
): Promise<EncodingDetectionResult> => {
  const bytes = new Uint8Array(buffer);
  const errors: Record<EncodingName, string> = {} as Record<EncodingName, string>;
  const tried: EncodingName[] = [];

  const candidates: EncodingName[] = preferred
    ? [preferred, ...COMMON_ENCODINGS.filter((e) => e !== preferred)]
    : COMMON_ENCODINGS;

  if (hasUTF8BOM(bytes)) {
    const result = tryDecode(bytes.slice(3), 'utf-8');
    tried.push('utf-8');
    if (result.success) {
      return {
        encoding: 'utf-8',
        confidence: 1.0,
        tried,
        errors,
      };
    }
    errors['utf-8'] = result.error || 'BOM decode failed';
  }

  for (const encoding of candidates) {
    if (tried.includes(encoding)) continue;
    tried.push(encoding);

    const result = tryDecode(bytes, encoding);
    if (!result.success) {
      errors[encoding] = result.error || 'decode failed';
      continue;
    }

    let confidence = 0.5;
    if (encoding === 'utf-8') {
      confidence = 0.9;
    } else if (encoding === 'gbk' && looksLikeGBK(bytes)) {
      confidence = 0.85;
    } else if (encoding === 'ascii' && bytes.every((b) => b < 0x80)) {
      confidence = 0.95;
    }

    return {
      encoding,
      confidence,
      tried,
      errors,
    };
  }

  const lastResort = tryDecode(bytes, 'utf-8');
  return {
    encoding: 'utf-8',
    confidence: 0.1,
    tried,
    errors: { ...errors, 'utf-8': lastResort.error || 'fallback decode failed' },
  };
};

export const decodeWithEncoding = (buffer: ArrayBuffer, encoding: EncodingName): string => {
  const decoder = new TextDecoder(encoding);
  return decoder.decode(buffer);
};

export const encodingToLabel = (encoding: EncodingName): string => {
  const labels: Record<EncodingName, string> = {
    'utf-8': 'Unicode (UTF-8)',
    'gbk': '简体中文 (GBK)',
    'gb2312': '简体中文 (GB2312)',
    'big5': '繁体中文 (Big5)',
    'shift_jis': '日文 (Shift-JIS)',
    'euc-kr': '韩文 (EUC-KR)',
    'ascii': 'ASCII',
  };
  return labels[encoding] || encoding;
};

export class EncodingDetectionError extends Error {
  public tried: EncodingName[];
  public buffer: ArrayBuffer;

  constructor(message: string, tried: EncodingName[], buffer: ArrayBuffer) {
    super(message);
    this.name = 'EncodingDetectionError';
    this.tried = tried;
    this.buffer = buffer;
  }
}
