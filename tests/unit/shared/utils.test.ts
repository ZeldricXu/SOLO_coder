import { describe, it, expect } from 'vitest';
import {
  parseTitle,
  parseTags,
  parseWikiLinks,
  parseOutline,
  countWords,
  generateDocId,
  generateHash,
  highlightSearch,
  getSearchSnippet,
} from '@/shared/utils/markdown';
import {
  formatDate,
  formatRelative,
  startOfDay,
  endOfDay,
  getDateRange,
} from '@/shared/utils/date';
import {
  normalizePath,
  joinPaths,
  getDefaultRepoPath,
  isMarkdownFile,
  isPathSafe,
} from '@/shared/utils/path';

describe('Markdown 工具函数', () => {
  describe('parseTitle', () => {
    it('应该从Markdown内容中提取标题', () => {
      const content = `
# 测试标题

这是正文内容。
      `;
      expect(parseTitle(content)).toBe('测试标题');
    });

    it('应该处理没有标题的内容', () => {
      const content = '这是没有标题的内容';
      expect(parseTitle(content)).toBe('');
    });

    it('应该处理YAML front matter后的标题', () => {
      const content = `
---
tags: [test]
---

# 真实标题

内容
      `;
      expect(parseTitle(content)).toBe('真实标题');
    });
  });

  describe('parseTags', () => {
    it('应该从内容中提取 #标签', () => {
      const content = '这是 #测试 内容，包含 #多个 标签';
      const tags = parseTags(content);
      expect(tags).toContain('测试');
      expect(tags).toContain('多个');
    });

    it('应该从YAML front matter提取tags', () => {
      const content = `
---
tags: [typescript, react]
---

内容
      `;
      const tags = parseTags(content);
      expect(tags).toContain('typescript');
      expect(tags).toContain('react');
    });

    it('应该返回空数组当没有标签时', () => {
      const content = '没有标签的内容';
      expect(parseTags(content)).toEqual([]);
    });
  });

  describe('parseWikiLinks', () => {
    it('应该解析简单的Wiki链接', () => {
      const content = '参考 [[文档名称]] 了解更多';
      const links = parseWikiLinks(content);
      expect(links).toHaveLength(1);
      expect(links[0].target).toBe('文档名称');
    });

    it('应该解析带有显示文本的Wiki链接', () => {
      const content = '查看 [[目标文档|显示文本]]';
      const links = parseWikiLinks(content);
      expect(links).toHaveLength(1);
      expect(links[0].target).toBe('目标文档');
      expect(links[0].displayText).toBe('显示文本');
    });

    it('应该解析多个Wiki链接', () => {
      const content = '[[链接1]] 和 [[链接2]] 以及 [[链接3]]';
      const links = parseWikiLinks(content);
      expect(links).toHaveLength(3);
    });

    it('应该处理没有Wiki链接的情况', () => {
      const content = '没有Wiki链接的普通文本';
      expect(parseWikiLinks(content)).toEqual([]);
    });
  });

  describe('parseOutline', () => {
    it('应该提取所有标题作为大纲', () => {
      const content = `
# 一级标题

## 二级标题1

内容

### 三级标题

## 二级标题2
      `;
      const outline = parseOutline(content);
      expect(outline).toHaveLength(4);
      expect(outline[0].level).toBe(1);
      expect(outline[0].text).toBe('一级标题');
    });

    it('应该为每个大纲项生成唯一ID', () => {
      const content = `
# 标题
## 子标题
      `;
      const outline = parseOutline(content);
      expect(outline[0].id).toBeDefined();
      expect(outline[1].id).toBeDefined();
      expect(outline[0].id).not.toBe(outline[1].id);
    });
  });

  describe('countWords', () => {
    it('应该正确计算英文单词数', () => {
      const content = 'Hello world this is a test';
      expect(countWords(content)).toBe(6);
    });

    it('应该正确计算中文字数', () => {
      const content = '这是一段中文文本';
      expect(countWords(content)).toBeGreaterThan(0);
    });

    it('应该处理空内容', () => {
      expect(countWords('')).toBe(0);
    });
  });

  describe('generateDocId', () => {
    it('应该生成唯一的文档ID', () => {
      const id1 = generateDocId();
      const id2 = generateDocId();
      expect(id1).not.toBe(id2);
      expect(id1.length).toBeGreaterThan(0);
    });
  });

  describe('generateHash', () => {
    it('相同内容应该生成相同哈希', () => {
      const content = 'test content';
      const hash1 = generateHash(content);
      const hash2 = generateHash(content);
      expect(hash1).toBe(hash2);
    });

    it('不同内容应该生成不同哈希', () => {
      const hash1 = generateHash('content1');
      const hash2 = generateHash('content2');
      expect(hash1).not.toBe(hash2);
    });
  });

  describe('highlightSearch', () => {
    it('应该用mark标签包裹匹配词', () => {
      const result = highlightSearch('Hello world', 'world');
      expect(result).toContain('<mark>world</mark>');
    });

    it('应该不区分大小写', () => {
      const result = highlightSearch('Hello WORLD', 'world');
      expect(result).toContain('<mark>WORLD</mark>');
    });

    it('应该处理空查询', () => {
      const result = highlightSearch('Hello world', '');
      expect(result).toBe('Hello world');
    });
  });

  describe('getSearchSnippet', () => {
    it('应该生成包含关键词的片段', () => {
      const text = '这是很长的内容，包含关键字测试';
      const snippet = getSearchSnippet(text, '关键字', 50);
      expect(snippet).toContain('关键字');
    });

    it('应该处理找不到关键词的情况', () => {
      const text = '这是一段普通文本';
      const snippet = getSearchSnippet(text, '不存在', 20);
      expect(snippet.length).toBeGreaterThan(0);
    });

    it('应该限制片段长度', () => {
      const text = 'a'.repeat(1000);
      const snippet = getSearchSnippet(text, 'a', 100);
      expect(snippet.length).toBeLessThanOrEqual(150);
    });
  });
});

describe('日期工具函数', () => {
  describe('formatDate', () => {
    it('应该格式化日期为字符串', () => {
      const date = new Date('2024-01-15');
      const formatted = formatDate(date, 'YYYY-MM-DD');
      expect(formatted).toContain('2024');
      expect(formatted).toContain('01');
      expect(formatted).toContain('15');
    });

    it('应该处理日期字符串输入', () => {
      const formatted = formatDate('2024-01-15', 'YYYY-MM-DD');
      expect(formatted).toContain('2024');
    });
  });

  describe('formatRelative', () => {
    it('应该返回相对时间描述', () => {
      const date = new Date();
      const relative = formatRelative(date);
      expect(relative).toBeDefined();
      expect(typeof relative).toBe('string');
    });
  });

  describe('startOfDay', () => {
    it('应该返回当天的开始时间', () => {
      const date = new Date('2024-01-15T14:30:00');
      const start = startOfDay(date);
      expect(start.getHours()).toBe(0);
      expect(start.getMinutes()).toBe(0);
      expect(start.getSeconds()).toBe(0);
    });
  });

  describe('endOfDay', () => {
    it('应该返回当天的结束时间', () => {
      const date = new Date('2024-01-15T14:30:00');
      const end = endOfDay(date);
      expect(end.getHours()).toBe(23);
      expect(end.getMinutes()).toBe(59);
      expect(end.getSeconds()).toBe(59);
    });
  });

  describe('getDateRange', () => {
    it('应该返回指定天数的日期范围', () => {
      const range = getDateRange(7);
      expect(range.start).toBeInstanceOf(Date);
      expect(range.end).toBeInstanceOf(Date);
    });
  });
});

describe('路径工具函数', () => {
  describe('normalizePath', () => {
    it('应该标准化路径分隔符', () => {
      const path = normalizePath('a\\b\\c');
      expect(path).toBe('a/b/c');
    });

    it('应该移除末尾的斜杠', () => {
      const path = normalizePath('a/b/c/');
      expect(path).toBe('a/b/c');
    });
  });

  describe('joinPaths', () => {
    it('应该正确连接多个路径', () => {
      const path = joinPaths('a', 'b', 'c');
      expect(path).toBe('a/b/c');
    });

    it('应该处理空参数', () => {
      const path = joinPaths('a', '', 'c');
      expect(path).toBe('a/c');
    });
  });

  describe('getDefaultRepoPath', () => {
    it('应该返回默认的仓库路径', () => {
      const path = getDefaultRepoPath();
      expect(path).toBeDefined();
      expect(typeof path).toBe('string');
    });
  });

  describe('isMarkdownFile', () => {
    it('应该识别.md文件', () => {
      expect(isMarkdownFile('document.md')).toBe(true);
    });

    it('应该识别.markdown文件', () => {
      expect(isMarkdownFile('document.markdown')).toBe(true);
    });

    it('应该返回false对于非Markdown文件', () => {
      expect(isMarkdownFile('document.txt')).toBe(false);
      expect(isMarkdownFile('image.png')).toBe(false);
    });
  });

  describe('isPathSafe', () => {
    it('应该检测路径遍历攻击', () => {
      expect(isPathSafe('/safe/path', '../etc/passwd')).toBe(false);
    });

    it('应该允许安全的子路径', () => {
      expect(isPathSafe('/safe/path', 'subdir/file.md')).toBe(true);
    });

    it('应该处理绝对路径', () => {
      expect(isPathSafe('/safe/path', '/safe/path/file.md')).toBe(true);
    });

    it('应该检测符号链接尝试', () => {
      expect(isPathSafe('/safe/path', 'symlink')).toBe(true);
    });
  });
});
