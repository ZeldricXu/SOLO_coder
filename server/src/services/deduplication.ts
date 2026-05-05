export interface DeduplicationResult {
  text: string;
  isDuplicate: boolean;
  overlapRatio: number;
  matchedPrefix: string;
}

export class DeduplicationService {
  private static MIN_OVERLAP_CHARS = 3;
  private static MAX_OVERLAP_RATIO = 0.7;

  static findLongestCommonPrefix(text1: string, text2: string): string {
    let prefix = '';
    const minLength = Math.min(text1.length, text2.length);

    for (let i = 0; i < minLength; i++) {
      if (text1[i] === text2[i]) {
        prefix += text1[i];
      } else {
        break;
      }
    }

    return prefix;
  }

  static findLongestCommonSuffix(text1: string, text2: string): string {
    let suffix = '';
    const minLength = Math.min(text1.length, text2.length);

    for (let i = 1; i <= minLength; i++) {
      if (text1[text1.length - i] === text2[text2.length - i]) {
        suffix = text1[text1.length - i] + suffix;
      } else {
        break;
      }
    }

    return suffix;
  }

  static findCommonSubstring(text1: string, text2: string): string {
    const prevText = text1.trim();
    const currentText = text2.trim();

    const prefix = this.findLongestCommonPrefix(prevText, currentText);
    const suffix = this.findLongestCommonSuffix(prevText, currentText);

    if (prefix.length > suffix.length) {
      return prefix;
    }
    return suffix;
  }

  static calculateLevenshteinDistance(s1: string, s2: string): number {
    const m = s1.length;
    const n = s2.length;
    const dp: number[][] = [];

    for (let i = 0; i <= m; i++) {
      dp[i] = [i];
    }
    for (let j = 0; j <= n; j++) {
      dp[0][j] = j;
    }

    for (let i = 1; i <= m; i++) {
      for (let j = 1; j <= n; j++) {
        const cost = s1[i - 1] === s2[j - 1] ? 0 : 1;
        dp[i][j] = Math.min(
          dp[i - 1][j] + 1,
          dp[i][j - 1] + 1,
          dp[i - 1][j - 1] + cost
        );
      }
    }

    return dp[m][n];
  }

  static isHighlySimilar(text1: string, text2: string, threshold: number = 0.8): boolean {
    const t1 = text1.trim();
    const t2 = text2.trim();

    if (t1 === t2) return true;

    const maxLength = Math.max(t1.length, t2.length);
    if (maxLength === 0) return true;

    const distance = this.calculateLevenshteinDistance(t1, t2);
    const similarity = 1 - distance / maxLength;

    return similarity >= threshold;
  }

  static deduplicate(
    previousText: string,
    currentText: string,
    overlapDuration: number = 0.5
  ): DeduplicationResult {
    const prevText = previousText.trim();
    const currText = currentText.trim();

    if (!prevText || prevText.length === 0) {
      return {
        text: currText,
        isDuplicate: false,
        overlapRatio: 0,
        matchedPrefix: '',
      };
    }

    if (prevText === currText) {
      return {
        text: '',
        isDuplicate: true,
        overlapRatio: 1.0,
        matchedPrefix: currText,
      };
    }

    const commonPrefix = this.findLongestCommonPrefix(prevText, currText);
    const commonSuffix = this.findLongestCommonSuffix(prevText, currText);

    let matchedText = '';
    if (commonPrefix.length > commonSuffix.length) {
      matchedText = commonPrefix;
    } else {
      matchedText = commonSuffix;
    }

    const overlapRatio = currText.length > 0 
      ? matchedText.length / currText.length 
      : 0;

    if (matchedText.length >= this.MIN_OVERLAP_CHARS && overlapRatio >= 0.3) {
      if (commonPrefix.length > 0) {
        const remainingText = currText.slice(commonPrefix.length);
        return {
          text: remainingText.trim(),
          isDuplicate: remainingText.trim().length === 0,
          overlapRatio,
          matchedPrefix: commonPrefix,
        };
      }
      if (commonSuffix.length > 0) {
        return {
          text: currText,
          isDuplicate: false,
          overlapRatio,
          matchedPrefix: commonSuffix,
        };
      }
    }

    if (this.isHighlySimilar(prevText, currText, 0.6)) {
      return {
        text: '',
        isDuplicate: true,
        overlapRatio: 0.6,
        matchedPrefix: '',
      };
    }

    return {
      text: currText,
      isDuplicate: false,
      overlapRatio: 0,
      matchedPrefix: '',
    };
  }

  static mergeTexts(
    previousText: string,
    currentText: string,
    deduplicationResult: DeduplicationResult
  ): string {
    if (deduplicationResult.isDuplicate) {
      return previousText;
    }

    if (!deduplicationResult.text.trim()) {
      return previousText;
    }

    const prevTrimmed = previousText.trim();
    const newText = deduplicationResult.text.trim();

    if (!prevTrimmed) {
      return newText;
    }

    const separator = prevTrimmed.endsWith('。') || 
                      prevTrimmed.endsWith('！') || 
                      prevTrimmed.endsWith('？') ||
                      prevTrimmed.endsWith('.') ||
                      prevTrimmed.endsWith('!') ||
                      prevTrimmed.endsWith('?')
      ? '' 
      : ' ';

    return `${prevTrimmed}${separator}${newText}`;
  }

  static normalizeText(text: string): string {
    return text
      .replace(/\s+/g, ' ')
      .replace(/，\s*/g, '，')
      .replace(/。\s*/g, '。')
      .replace(/！\s*/g, '！')
      .replace(/？\s*/g, '？')
      .trim();
  }
}

export const deduplicationService = DeduplicationService;
