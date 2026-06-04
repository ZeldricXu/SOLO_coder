import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NotionSource } from '@/lib/integrations/NotionSource';

describe('NotionSource', () => {
  let notionSource: NotionSource;
  let mockFetch: any;

  beforeEach(() => {
    mockFetch = vi.fn();
    global.fetch = mockFetch;

    notionSource = new NotionSource({
      apiKey: 'test-api-key',
      databaseIds: ['db-1', 'db-2'],
    });
  });

  describe('Block to Markdown conversion', () => {
    it('should convert paragraph blocks correctly', async () => {
      const block = {
        object: 'block',
        id: 'block-1',
        parent: { type: 'page_id', page_id: 'page-1' },
        created_time: '2024-01-01T00:00:00Z',
        last_edited_time: '2024-01-01T00:00:00Z',
        created_by: { object: 'user', id: 'user-1' },
        last_edited_by: { object: 'user', id: 'user-1' },
        has_children: false,
        archived: false,
        type: 'paragraph',
        paragraph: {
          rich_text: [
            {
              type: 'text',
              text: { content: 'Hello world', link: null },
              annotations: {
                bold: false,
                italic: false,
                strikethrough: false,
                underline: false,
                code: false,
                color: 'default',
              },
              plain_text: 'Hello world',
              href: null,
            },
          ],
          color: 'default',
        },
      };

      const result = await (notionSource as any).convertBlockToMarkdown(block);
      expect(result).toBe('Hello world\n\n');
    });

    it('should convert heading blocks correctly', async () => {
      const heading1 = {
        type: 'heading_1',
        heading_1: {
          rich_text: [{ text: { content: 'Heading 1' }, plain_text: 'Heading 1' }],
        },
      };

      const heading2 = {
        type: 'heading_2',
        heading_2: {
          rich_text: [{ text: { content: 'Heading 2' }, plain_text: 'Heading 2' }],
        },
      };

      const heading3 = {
        type: 'heading_3',
        heading_3: {
          rich_text: [{ text: { content: 'Heading 3' }, plain_text: 'Heading 3' }],
        },
      };

      const result1 = await (notionSource as any).convertBlockToMarkdown(heading1 as any);
      const result2 = await (notionSource as any).convertBlockToMarkdown(heading2 as any);
      const result3 = await (notionSource as any).convertBlockToMarkdown(heading3 as any);

      expect(result1).toBe('# Heading 1\n\n');
      expect(result2).toBe('## Heading 2\n\n');
      expect(result3).toBe('### Heading 3\n\n');
    });

    it('should convert bulleted list blocks correctly', async () => {
      const block = {
        type: 'bulleted_list_item',
        bulleted_list_item: {
          rich_text: [{ text: { content: 'Item 1' }, plain_text: 'Item 1' }],
        },
      };

      const result = await (notionSource as any).convertBlockToMarkdown(block as any);
      expect(result).toBe('- Item 1\n');
    });

    it('should convert numbered list blocks correctly', async () => {
      const block = {
        type: 'numbered_list_item',
        numbered_list_item: {
          rich_text: [{ text: { content: 'First item' }, plain_text: 'First item' }],
        },
      };

      const result = await (notionSource as any).convertBlockToMarkdown(block as any);
      expect(result).toBe('1. First item\n');
    });

    it('should convert to-do blocks correctly', async () => {
      const checked = {
        type: 'to_do',
        to_do: {
          rich_text: [{ text: { content: 'Done task' }, plain_text: 'Done task' }],
          checked: true,
        },
      };

      const unchecked = {
        type: 'to_do',
        to_do: {
          rich_text: [{ text: { content: 'Pending task' }, plain_text: 'Pending task' }],
          checked: false,
        },
      };

      const result1 = await (notionSource as any).convertBlockToMarkdown(checked as any);
      const result2 = await (notionSource as any).convertBlockToMarkdown(unchecked as any);

      expect(result1).toBe('- [x] Done task\n');
      expect(result2).toBe('- [ ] Pending task\n');
    });

    it('should convert code blocks correctly', async () => {
      const block = {
        type: 'code',
        code: {
          language: 'typescript',
          rich_text: [{ text: { content: 'const x = 1;' }, plain_text: 'const x = 1;' }],
        },
      };

      const result = await (notionSource as any).convertBlockToMarkdown(block as any);
      expect(result).toContain('```typescript');
      expect(result).toContain('const x = 1;');
      expect(result).toContain('```');
    });

    it('should convert quote blocks correctly', async () => {
      const block = {
        type: 'quote',
        quote: {
          rich_text: [{ text: { content: 'Famous quote' }, plain_text: 'Famous quote' }],
        },
      };

      const result = await (notionSource as any).convertBlockToMarkdown(block as any);
      expect(result).toBe('> Famous quote\n\n');
    });

    it('should convert callout blocks correctly', async () => {
      const block = {
        type: 'callout',
        callout: {
          rich_text: [{ text: { content: 'Important info' }, plain_text: 'Important info' }],
          icon: { emoji: '💡' },
        },
      };

      const result = await (notionSource as any).convertBlockToMarkdown(block as any);
      expect(result).toContain('> 💡 Important info');
    });

    it('should convert divider blocks correctly', async () => {
      const block = { type: 'divider', divider: {} };
      const result = await (notionSource as any).convertBlockToMarkdown(block as any);
      expect(result).toBe('---\n\n');
    });

    it('should convert image blocks correctly', async () => {
      const block = {
        type: 'image',
        image: {
          type: 'external',
          external: { url: 'https://example.com/image.jpg' },
          caption: [{ text: { content: 'Alt text' }, plain_text: 'Alt text' }],
        },
      };

      const result = await (notionSource as any).convertBlockToMarkdown(block as any);
      expect(result).toContain('![Alt text](https://example.com/image.jpg)');
    });

    it('should handle rich text annotations', async () => {
      const block = {
        type: 'paragraph',
        paragraph: {
          rich_text: [
            { text: { content: 'Bold' }, annotations: { bold: true }, plain_text: 'Bold' },
            { text: { content: ' ' }, plain_text: ' ' },
            { text: { content: 'Italic' }, annotations: { italic: true }, plain_text: 'Italic' },
            { text: { content: ' ' }, plain_text: ' ' },
            { text: { content: 'Code' }, annotations: { code: true }, plain_text: 'Code' },
          ],
        },
      };

      const result = await (notionSource as any).convertBlockToMarkdown(block as any);
      expect(result).toContain('**Bold**');
      expect(result).toContain('_Italic_');
      expect(result).toContain('`Code`');
    });

    it('should handle links in rich text', async () => {
      const block = {
        type: 'paragraph',
        paragraph: {
          rich_text: [
            {
              text: { content: 'Click here', link: { url: 'https://example.com' } },
              plain_text: 'Click here',
            },
          ],
        },
      };

      const result = await (notionSource as any).convertBlockToMarkdown(block as any);
      expect(result).toContain('[Click here](https://example.com)');
    });
  });

  describe('Nested block handling', () => {
    it('should recursively convert nested blocks', async () => {
      const parentBlock = {
        id: 'parent',
        type: 'toggle',
        toggle: {
          rich_text: [{ text: { content: 'Toggle' }, plain_text: 'Toggle' }],
        },
        has_children: true,
      };

      const childBlocks = {
        object: 'list',
        results: [
          {
            id: 'child',
            type: 'paragraph',
            paragraph: {
              rich_text: [{ text: { content: 'Child content' }, plain_text: 'Child content' }],
            },
            has_children: false,
          },
        ],
        has_more: false,
      };

      mockFetch
        .mockResolvedValueOnce({
          ok: true,
          json: () => Promise.resolve(childBlocks),
        });

      const result = await (notionSource as any).convertBlockToMarkdown(parentBlock as any);
      expect(result).toContain('Toggle');
    });
  });

  describe('Page properties extraction', () => {
    it('should extract title from page properties', () => {
      const properties = {
        Name: {
          title: [{ plain_text: 'My Document' }],
        },
      };

      const title = (notionSource as any).extractTitle(properties);
      expect(title).toBe('My Document');
    });

    it('should extract tags from multi-select properties', () => {
      const properties = {
        Tags: {
          multi_select: [{ name: 'javascript' }, { name: 'typescript' }],
        },
      };

      const tags = (notionSource as any).extractTags(properties);
      expect(tags).toEqual(['javascript', 'typescript']);
    });

    it('should handle missing properties gracefully', () => {
      const properties = {};

      const title = (notionSource as any).extractTitle(properties);
      const tags = (notionSource as any).extractTags(properties);

      expect(title).toBe('Untitled');
      expect(tags).toEqual([]);
    });
  });

  describe('API error handling', () => {
    it('should throw meaningful error on API failure', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 401,
        json: () => Promise.resolve({ message: 'Unauthorized' }),
      });

      await expect(
        notionSource.fetchDocuments({})
      ).rejects.toThrow('Notion API error');
    });

    it('should retry on rate limit errors', async () => {
      mockFetch
        .mockResolvedValueOnce({
          ok: false,
          status: 429,
          json: () => Promise.resolve({ message: 'Rate limit exceeded' }),
        })
        .mockResolvedValueOnce({
          ok: true,
          json: () => Promise.resolve({ results: [], has_more: false }),
        });

      const result = await notionSource.fetchDocuments({});
      expect(result.documents).toEqual([]);
    });
  });

  describe('API configuration', () => {
    it('should set correct Authorization header', async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ results: [], has_more: false }),
      });

      await notionSource.fetchDocuments({});

      const call = mockFetch.mock.calls[0];
      expect(call[1].headers['Authorization']).toBe('Bearer test-api-key');
      expect(call[1].headers['Notion-Version']).toBeDefined();
    });
  });
});
