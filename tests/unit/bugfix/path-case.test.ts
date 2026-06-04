import { describe, it, expect } from 'vitest';
import { resolveWikilinkTarget, normalizeWikilinks } from '@/shared/utils/markdown';

describe('Windows路径大小写修复回归测试', () => {
  const mockDocuments = [
    { id: 'doc-1', title: 'Document A', filePath: '/vault/Document A.md' },
    { id: 'doc-2', title: 'My Project Overview', filePath: '/vault/My Project Overview.md' },
    { id: 'doc-3', title: '技术架构', filePath: '/vault/技术架构.md' },
    { id: 'doc-4', title: 'TypeScript Guide', filePath: '/vault/TypeScript Guide.md' },
  ];

  describe('resolveWikilinkTarget', () => {
    it('精确匹配优先于大小写不敏感匹配', () => {
      const result = resolveWikilinkTarget('Document A', mockDocuments);
      expect(result).not.toBeNull();
      expect(result!.id).toBe('doc-1');
      expect(result!.actualTitle).toBe('Document A');
    });

    it('全小写链接能匹配首字母大写的文档标题', () => {
      const result = resolveWikilinkTarget('document a', mockDocuments);
      expect(result).not.toBeNull();
      expect(result!.id).toBe('doc-1');
      expect(result!.actualTitle).toBe('Document A');
    });

    it('全大写链接能匹配混合大小写的文档标题', () => {
      const result = resolveWikilinkTarget('DOCUMENT A', mockDocuments);
      expect(result).not.toBeNull();
      expect(result!.id).toBe('doc-1');
      expect(result!.actualTitle).toBe('Document A');
    });

    it('大小写不敏感匹配返回实际标题', () => {
      const result = resolveWikilinkTarget('my project overview', mockDocuments);
      expect(result).not.toBeNull();
      expect(result!.actualTitle).toBe('My Project Overview');
    });

    it('中文标题匹配不受大小写影响', () => {
      const result = resolveWikilinkTarget('技术架构', mockDocuments);
      expect(result).not.toBeNull();
      expect(result!.id).toBe('doc-3');
    });

    it('不存在的链接返回null', () => {
      const result = resolveWikilinkTarget('Not Exist', mockDocuments);
      expect(result).toBeNull();
    });

    it('驼峰式大小写不敏感匹配', () => {
      const result = resolveWikilinkTarget('typescript guide', mockDocuments);
      expect(result).not.toBeNull();
      expect(result!.actualTitle).toBe('TypeScript Guide');
    });

    it('混合大小写链接能匹配文档', () => {
      const result = resolveWikilinkTarget('typeScript GUIDE', mockDocuments);
      expect(result).not.toBeNull();
      expect(result!.actualTitle).toBe('TypeScript Guide');
    });
  });

  describe('normalizeWikilinks', () => {
    it('将链接中的小写目标修正为实际标题大小写', () => {
      const content = '参考 [[document a]] 了解更多。';
      const result = normalizeWikilinks(content, mockDocuments);
      expect(result).toBe('参考 [[Document A]] 了解更多。');
    });

    it('保留已有别名不变', () => {
      const content = '查看 [[document a|文档A]] 了解更多。';
      const result = normalizeWikilinks(content, mockDocuments);
      expect(result).toBe('查看 [[Document A|文档A]] 了解更多。');
    });

    it('大小写已正确的链接不做修改', () => {
      const content = '参考 [[Document A]] 了解更多。';
      const result = normalizeWikilinks(content, mockDocuments);
      expect(result).toBe('参考 [[Document A]] 了解更多。');
    });

    it('不存在的链接目标不做修改', () => {
      const content = '参考 [[unknown link]] 了解更多。';
      const result = normalizeWikilinks(content, mockDocuments);
      expect(result).toBe('参考 [[unknown link]] 了解更多。');
    });

    it('同时修正多个大小写不匹配的链接', () => {
      const content = '参考 [[document a]] 和 [[typescript guide]]。';
      const result = normalizeWikilinks(content, mockDocuments);
      expect(result).toBe('参考 [[Document A]] 和 [[TypeScript Guide]]。');
    });

    it('别名与目标大小写不同时保留别名', () => {
      const content = '参考 [[document a|文档A]]。';
      const result = normalizeWikilinks(content, mockDocuments);
      expect(result).toBe('参考 [[Document A|文档A]]。');
    });

    it('别名与实际标题相同时仍保留别名格式', () => {
      const content = '参考 [[document a|Document A]]。';
      const result = normalizeWikilinks(content, mockDocuments);
      expect(result).toBe('参考 [[Document A|Document A]]。');
    });
  });
});
