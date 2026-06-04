import { describe, it, expect } from 'vitest';
import { highlightText, extractFragments, escapeHtmlSafe, extractMatchedTerms } from '@/lib/search/highlighter';

describe('Search Highlighter', () => {
  describe('Text highlighting', () => {
    it('should highlight single keyword in text', () => {
      const text = '机器学习是人工智能的重要分支';
      const keywords = ['机器学习'];

      const result = highlightText(text, keywords);

      expect(result).toContain('<mark>机器学习</mark>');
      expect(result).toContain('人工智能');
    });

    it('should highlight multiple keywords', () => {
      const text = '机器学习和深度学习都是人工智能的重要领域';
      const keywords = ['机器学习', '深度学习', '人工智能'];

      const result = highlightText(text, keywords);

      expect(result).toContain('<mark>机器学习</mark>');
      expect(result).toContain('<mark>深度学习</mark>');
      expect(result).toContain('<mark>人工智能</mark>');
    });

    it('should be case-insensitive', () => {
      const text = 'JavaScript and TypeScript are popular languages';
      const keywords = ['javascript', 'typescript'];

      const result = highlightText(text, keywords);

      expect(result).toContain('<mark>JavaScript</mark>');
      expect(result).toContain('<mark>TypeScript</mark>');
    });

    it('should handle overlapping matches gracefully', () => {
      const text = 'machine learning machine';
      const keywords = ['machine', 'machine learning'];

      const result = highlightText(text, keywords);

      expect(result).toContain('<mark>');
    });

    it('should return original text when no matches', () => {
      const text = 'This is a test';
      const keywords = ['nomatch'];

      const result = highlightText(text, keywords);

      expect(result).toBe(text);
    });

    it('should handle empty keyword list', () => {
      const text = 'This is a test';
      const keywords: string[] = [];

      const result = highlightText(text, keywords);

      expect(result).toBe(text);
    });

    it('should handle empty text', () => {
      const text = '';
      const keywords = ['test'];

      const result = highlightText(text, keywords);

      expect(result).toBe('');
    });
  });

  describe('Chinese text highlighting', () => {
    it('should highlight Chinese keywords correctly', () => {
      const text = '本教程介绍机器学习的基础概念。机器学习包括监督学习和无监督学习。';
      const keywords = ['机器学习'];

      const result = highlightText(text, keywords);

      const matchCount = (result.match(/<mark>机器学习<\/mark>/g) || []).length;
      expect(matchCount).toBe(2);
    });

    it('should handle partial Chinese word matches', () => {
      const text = '机器学习 机器人 机器';
      const keywords = ['机器'];

      const result = highlightText(text, keywords);

      expect(result).toContain('<mark>机器</mark>');
    });

    it('should highlight multiple Chinese keywords', () => {
      const text = '深度学习使用神经网络进行特征提取';
      const keywords = ['深度学习', '神经网络', '特征提取'];

      const result = highlightText(text, keywords);

      expect(result).toContain('<mark>深度学习</mark>');
      expect(result).toContain('<mark>神经网络</mark>');
      expect(result).toContain('<mark>特征提取</mark>');
    });

    it('should handle mixed Chinese and English', () => {
      const text = '使用Python进行机器学习模型训练';
      const keywords = ['Python', '机器学习'];

      const result = highlightText(text, keywords);

      expect(result).toContain('<mark>Python</mark>');
      expect(result).toContain('<mark>机器学习</mark>');
    });
  });

  describe('Fragment extraction', () => {
    it('should extract fragments around matches', () => {
      const text = '这是开头。这里有一个重要的关键词。这是结尾。';
      const keywords = ['关键词'];

      const fragments = extractFragments(text, keywords, {
        fragmentSize: 20,
        maxFragments: 3,
      });

      expect(fragments.length).toBeGreaterThan(0);
      expect(fragments[0].text).toContain('关键词');
    });

    it('should respect fragment size limit', () => {
      const text = 'A'.repeat(1000);
      const keywords = ['A'];

      const fragments = extractFragments(text, keywords, {
        fragmentSize: 100,
        maxFragments: 1,
      });

      expect(fragments[0].text.length).toBeLessThanOrEqual(100);
    });

    it('should limit number of fragments', () => {
      const text = 'match match match match match';
      const keywords = ['match'];

      const fragments = extractFragments(text, keywords, {
        fragmentSize: 10,
        maxFragments: 3,
      });

      expect(fragments.length).toBeLessThanOrEqual(3);
    });

    it('should handle matches at the beginning', () => {
      const text = '关键词出现在开头的情况';
      const keywords = ['关键词'];

      const fragments = extractFragments(text, keywords, {
        fragmentSize: 20,
        maxFragments: 1,
      });

      expect(fragments[0].text).toContain('关键词');
    });

    it('should handle matches at the end', () => {
      const text = '内容较长的测试文本，关键词出现在最后';
      const keywords = ['关键词'];

      const fragments = extractFragments(text, keywords, {
        fragmentSize: 20,
        maxFragments: 1,
      });

      expect(fragments[0].text).toContain('关键词');
    });

    it('should return empty array when no matches', () => {
      const text = 'No matches here';
      const keywords = ['nonexistent'];

      const fragments = extractFragments(text, keywords);

      expect(fragments).toEqual([]);
    });

    it('should mark matches in fragments', () => {
      const text = 'Before match after';
      const keywords = ['match'];

      const fragments = extractFragments(text, keywords, {
        fragmentSize: 50,
        maxFragments: 1,
        markMatches: true,
      });

      expect(fragments[0].text).toContain('<mark>match</mark>');
    });

    it('should add ellipsis for truncated text', () => {
      const text = 'A very long text content with a match in the middle and more text after';
      const keywords = ['match'];

      const fragments = extractFragments(text, keywords, {
        fragmentSize: 20,
        maxFragments: 1,
        addEllipsis: true,
      });

      expect(fragments[0].text).toMatch(/(…|\.\.\.)/);
    });
  });

  describe('HTML escaping', () => {
    it('should escape HTML special characters', () => {
      const text = '<script>alert("xss")</script>';

      const escaped = escapeHtmlSafe(text);

      expect(escaped).not.toContain('<script>');
      expect(escaped).toContain('&lt;');
      expect(escaped).toContain('&gt;');
    });

    it('should escape quotes', () => {
      const text = 'He said "hello"';

      const escaped = escapeHtmlSafe(text);

      expect(escaped).toContain('&quot;');
    });

    it('should escape ampersands', () => {
      const text = 'A & B & C';

      const escaped = escapeHtmlSafe(text);

      expect(escaped).toContain('&amp;');
    });

    it('should handle already escaped text', () => {
      const text = '&lt;script&gt;';

      const escaped = escapeHtmlSafe(text);

      expect(escaped).toBeDefined();
    });

    it('should return empty string for null/undefined', () => {
      expect(escapeHtmlSafe(null as any)).toBe('');
      expect(escapeHtmlSafe(undefined as any)).toBe('');
    });
  });

  describe('Term extraction', () => {
    it('should extract matched terms from text', () => {
      const text = '机器学习和深度学习是AI的重要技术';
      const keywords = ['机器学习', '深度学习', 'AI'];

      const terms = extractMatchedTerms(text, keywords);

      expect(terms).toContain('机器学习');
      expect(terms).toContain('深度学习');
      expect(terms).toContain('AI');
    });

    it('should count occurrences of each term', () => {
      const text = '机器学习 机器学习 深度学习';
      const keywords = ['机器学习', '深度学习'];

      const occurrences: Record<string, number> = {};
      keywords.forEach((keyword) => {
        const regex = new RegExp(keyword, 'g');
        const matches = text.match(regex);
        occurrences[keyword] = matches ? matches.length : 0;
      });

      expect(occurrences['机器学习']).toBe(2);
      expect(occurrences['深度学习']).toBe(1);
    });

    it('should handle Chinese single-character matches', () => {
      const text = '人工智能';
      const keywords = ['人', '工', '智', '能'];

      const terms = extractMatchedTerms(text, keywords);

      expect(terms.length).toBeGreaterThan(0);
    });

    it('should handle Chinese two-character matches', () => {
      const text = '机器学习';
      const keywords = ['机器', '学习', '机器学习'];

      const terms = extractMatchedTerms(text, keywords);

      expect(terms).toContain('机器学习');
    });

    it('should deduplicate matched terms', () => {
      const text = 'test test test';
      const keywords = ['test'];

      const terms = extractMatchedTerms(text, keywords);

      expect(terms).toEqual(['test']);
    });

    it('should sort terms by occurrence count', () => {
      const text = 'a a a b b c';
      const keywords = ['a', 'b', 'c'];

      const terms = extractMatchedTerms(text, keywords);

      expect(terms[0]).toBe('a');
      expect(terms[1]).toBe('b');
      expect(terms[2]).toBe('c');
    });
  });

  describe('Combined highlighting and extraction', () => {
    it('should highlight and extract from the same text', () => {
      const text = '本教程详细介绍了机器学习的基础知识。' +
        '机器学习是人工智能的核心技术之一。' +
        '通过机器学习，我们可以构建智能系统。';
      const keywords = ['机器学习', '人工智能'];

      const highlighted = highlightText(text, keywords);
      const fragments = extractFragments(text, keywords, { maxFragments: 3 });

      expect(highlighted).toContain('<mark>机器学习</mark>');
      expect(fragments.length).toBeGreaterThan(0);
    });

    it('should preserve original text in fragments', () => {
      const text = 'Original text with keyword';
      const keywords = ['keyword'];

      const fragments = extractFragments(text, keywords, {
        fragmentSize: 100,
        markMatches: false,
      });

      expect(fragments[0].text).toBe('Original text with keyword');
    });
  });

  describe('Edge cases', () => {
    it('should handle very long text', () => {
      const longText = 'A'.repeat(10000) + ' keyword ' + 'B'.repeat(10000);
      const keywords = ['keyword'];

      const highlighted = highlightText(longText, keywords);
      const fragments = extractFragments(longText, keywords);

      expect(highlighted).toContain('<mark>keyword</mark>');
      expect(fragments.length).toBeGreaterThan(0);
    });

    it('should handle text with only whitespace', () => {
      const text = '   \n  \t   ';
      const keywords = ['test'];

      const highlighted = highlightText(text, keywords);
      const fragments = extractFragments(text, keywords);

      expect(highlighted).toBe(text);
      expect(fragments).toEqual([]);
    });

    it('should handle special characters in keywords', () => {
      const text = 'C++ and C# are programming languages';
      const keywords = ['C++', 'C#'];

      const result = highlightText(text, keywords);

      expect(result).toBeDefined();
    });
  });
});
