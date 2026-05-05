import { DeduplicationService, DeduplicationResult } from '../src/services/deduplication';

describe('DeduplicationService', () => {
  describe('findLongestCommonPrefix', () => {
    it('should find common prefix between two strings', () => {
      const result = DeduplicationService.findLongestCommonPrefix(
        '大家好，今天我们讨论项目进度。',
        '大家好，今天我们讨论新的功能模块。'
      );
      expect(result).toBe('大家好，今天我们讨论');
    });

    it('should return empty string when no common prefix', () => {
      const result = DeduplicationService.findLongestCommonPrefix(
        '今天天气很好',
        '明天我们去开会'
      );
      expect(result).toBe('');
    });

    it('should return entire string when identical', () => {
      const text = '这是一段测试文本';
      const result = DeduplicationService.findLongestCommonPrefix(text, text);
      expect(result).toBe(text);
    });
  });

  describe('findLongestCommonSuffix', () => {
    it('should find common suffix between two strings', () => {
      const result = DeduplicationService.findLongestCommonSuffix(
        '我们需要优化性能和代码质量',
        '我们将继续优化性能和代码质量'
      );
      expect(result).toBe('优化性能和代码质量');
    });

    it('should return empty string when no common suffix', () => {
      const result = DeduplicationService.findLongestCommonSuffix(
        '今天天气很好',
        '明天我们去开会'
      );
      expect(result).toBe('');
    });
  });

  describe('calculateLevenshteinDistance', () => {
    it('should calculate correct distance', () => {
      const distance = DeduplicationService.calculateLevenshteinDistance(
        'kitten',
        'sitting'
      );
      expect(distance).toBe(3);
    });

    it('should return 0 for identical strings', () => {
      const distance = DeduplicationService.calculateLevenshteinDistance('test', 'test');
      expect(distance).toBe(0);
    });

    it('should return length for completely different strings', () => {
      const distance = DeduplicationService.calculateLevenshteinDistance('abc', 'xyz');
      expect(distance).toBe(3);
    });
  });

  describe('isHighlySimilar', () => {
    it('should return true for identical strings', () => {
      const result = DeduplicationService.isHighlySimilar('test', 'test');
      expect(result).toBe(true);
    });

    it('should return true for similar strings', () => {
      const result = DeduplicationService.isHighlySimilar(
        '大家好，今天我们讨论项目进度',
        '大家好，今天我们讨论项目进度。'
      );
      expect(result).toBe(true);
    });

    it('should return false for different strings', () => {
      const result = DeduplicationService.isHighlySimilar(
        '今天天气很好',
        '明天我们去开会'
      );
      expect(result).toBe(false);
    });
  });

  describe('deduplicate', () => {
    describe('Normal merge scenarios', () => {
      it('should merge texts with overlapping prefix correctly', () => {
        const previousText = '大家好，今天我们';
        const currentText = '今天我们讨论项目进度';
        
        const result = DeduplicationService.deduplicate(
          previousText,
          currentText,
          0.5
        );

        expect(result.isDuplicate).toBe(false);
        expect(result.matchedPrefix).toBe('今天我们');
        expect(result.text).toBe('讨论项目进度');
      });

      it('should return original text when first chunk (empty previous)', () => {
        const result = DeduplicationService.deduplicate(
          '',
          '这是第一个片段',
          0.5
        );

        expect(result.isDuplicate).toBe(false);
        expect(result.text).toBe('这是第一个片段');
      });

      it('should mark as duplicate when identical', () => {
        const text = '完全相同的文本';
        const result = DeduplicationService.deduplicate(text, text, 0.5);

        expect(result.isDuplicate).toBe(true);
        expect(result.text).toBe('');
      });
    });

    describe('Confidence-based scenarios', () => {
      it('should handle partial overlap correctly', () => {
        const previousText = '我们正在测试音频采集模块的功能';
        const currentText = '模块的功能是否正常运行';
        
        const result = DeduplicationService.deduplicate(
          previousText,
          currentText,
          0.5
        );

        expect(result.matchedPrefix).toBe('模块的功能');
        expect(result.isDuplicate).toBe(false);
      });

      it('should handle Chinese punctuation boundaries', () => {
        const previousText = '今天我们开会讨论项目。';
        const currentText = '项目的下一步计划是什么？';
        
        const result = DeduplicationService.deduplicate(
          previousText,
          currentText,
          0.5
        );

        expect(result.matchedPrefix).toBe('项目');
      });
    });

    describe('No overlap scenarios', () => {
      it('should return full text when no overlap', () => {
        const previousText = '今天天气很好';
        const currentText = '明天我们去开会';
        
        const result = DeduplicationService.deduplicate(
          previousText,
          currentText,
          0.5
        );

        expect(result.isDuplicate).toBe(false);
        expect(result.overlapRatio).toBe(0);
        expect(result.text).toBe('明天我们去开会');
      });

      it('should handle English texts without overlap', () => {
        const previousText = 'Hello world';
        const currentText = 'This is a test';
        
        const result = DeduplicationService.deduplicate(
          previousText,
          currentText,
          0.5
        );

        expect(result.isDuplicate).toBe(false);
        expect(result.text).toBe('This is a test');
      });
    });

    describe('High similarity scenarios', () => {
      it('should detect and mark highly similar texts as duplicate', () => {
        const previousText = '今天我们讨论项目的进展情况';
        const currentText = '今天我们讨论项目进展情况';
        
        const result = DeduplicationService.deduplicate(
          previousText,
          currentText,
          0.5
        );

        expect(result.isDuplicate).toBe(true);
      });
    });

    describe('Edge cases', () => {
      it('should handle empty strings', () => {
        const result = DeduplicationService.deduplicate('', '', 0.5);
        expect(result.isDuplicate).toBe(false);
      });

      it('should handle whitespace-only texts', () => {
        const result = DeduplicationService.deduplicate('   ', '   ', 0.5);
        expect(result.isDuplicate).toBe(false);
      });

      it('should handle very short texts', () => {
        const result = DeduplicationService.deduplicate('a', 'a', 0.5);
        expect(result.isDuplicate).toBe(true);
      });

      it('should handle very short non-overlapping texts', () => {
        const result = DeduplicationService.deduplicate('a', 'b', 0.5);
        expect(result.isDuplicate).toBe(false);
        expect(result.text).toBe('b');
      });
    });
  });

  describe('mergeTexts', () => {
    it('should merge texts correctly after deduplication', () => {
      const previousText = '大家好，今天我们';
      const currentText = '今天我们讨论项目进度';
      
      const deduplicationResult: DeduplicationResult = {
        text: '讨论项目进度',
        isDuplicate: false,
        overlapRatio: 0.5,
        matchedPrefix: '今天我们',
      };

      const result = DeduplicationService.mergeTexts(
        previousText,
        currentText,
        deduplicationResult
      );

      expect(result).toBe('大家好，今天我们讨论项目进度');
    });

    it('should return previous text when duplicate', () => {
      const previousText = '这是一段文本';
      const currentText = '这是一段文本';
      
      const deduplicationResult: DeduplicationResult = {
        text: '',
        isDuplicate: true,
        overlapRatio: 1.0,
        matchedPrefix: '这是一段文本',
      };

      const result = DeduplicationService.mergeTexts(
        previousText,
        currentText,
        deduplicationResult
      );

      expect(result).toBe(previousText);
    });

    it('should handle punctuation boundaries', () => {
      const previousText = '今天天气很好。';
      const currentText = '好。我们去散步吧';
      
      const deduplicationResult: DeduplicationResult = {
        text: '我们去散步吧',
        isDuplicate: false,
        overlapRatio: 0.2,
        matchedPrefix: '好。',
      };

      const result = DeduplicationService.mergeTexts(
        previousText,
        currentText,
        deduplicationResult
      );

      expect(result).toBe('今天天气很好。我们去散步吧');
    });

    it('should handle space between sentences in English', () => {
      const previousText = 'Hello world.';
      const currentText = 'world. This is a test';
      
      const deduplicationResult: DeduplicationResult = {
        text: 'This is a test',
        isDuplicate: false,
        overlapRatio: 0.3,
        matchedPrefix: 'world.',
      };

      const result = DeduplicationService.mergeTexts(
        previousText,
        currentText,
        deduplicationResult
      );

      expect(result).toBe('Hello world. This is a test');
    });
  });

  describe('normalizeText', () => {
    it('should normalize Chinese text spacing', () => {
      const text = '今天   天气   很好';
      const result = DeduplicationService.normalizeText(text);
      expect(result).toBe('今天 天气 很好');
    });

    it('should normalize Chinese punctuation spacing', () => {
      const text = '大家好 ， 今天我们开会 。';
      const result = DeduplicationService.normalizeText(text);
      expect(result).toBe('大家好，今天我们开会。');
    });

    it('should trim text', () => {
      const text = '  测试文本  ';
      const result = DeduplicationService.normalizeText(text);
      expect(result).toBe('测试文本');
    });
  });

  describe('Integration tests - full overlap scenario', () => {
    it('should handle typical overlapping audio chunks', () => {
      const chunk1Text = '大家好，今天我们来讨论项目的进展情况';
      const chunk2Text = '今天我们来讨论项目的进展情况，然后看一下下一个任务';
      
      const dedupResult = DeduplicationService.deduplicate(chunk1Text, chunk2Text, 0.5);
      
      expect(dedupResult.isDuplicate).toBe(false);
      
      const merged = DeduplicationService.mergeTexts(chunk1Text, chunk2Text, dedupResult);
      
      expect(merged).toBe('大家好，今天我们来讨论项目的进展情况，然后看一下下一个任务');
    });

    it('should handle English overlapping chunks', () => {
      const chunk1Text = 'Hello everyone, today we discuss the project progress';
      const chunk2Text = 'project progress and then look at the next tasks';
      
      const dedupResult = DeduplicationService.deduplicate(chunk1Text, chunk2Text, 0.5);
      
      expect(dedupResult.matchedPrefix).toBe('project progress');
      
      const merged = DeduplicationService.mergeTexts(chunk1Text, chunk2Text, dedupResult);
      
      expect(merged).toBe('Hello everyone, today we discuss the project progress and then look at the next tasks');
    });
  });
});
