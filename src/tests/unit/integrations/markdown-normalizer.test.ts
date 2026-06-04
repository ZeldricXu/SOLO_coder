import { describe, it, expect } from 'vitest';
import { MarkdownNormalizer } from '@/lib/integrations/MarkdownNormalizer';
import type { ExternalSource } from '@prisma/client';

describe('MarkdownNormalizer', () => {
  describe('Feishu Markdown normalization', () => {
    it('should convert Feishu callout blocks to CommonMark', () => {
      const feishuMarkdown = `
# Title

[!NOTE]
This is a note callout

[!WARNING]
This is a warning callout

Regular content here.
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(feishuMarkdown, 'FEISHU' as ExternalSource);

      expect(result.normalizedContent).toContain('> **Note**');
      expect(result.normalizedContent).toContain('> This is a note callout');
      expect(result.normalizedContent).toContain('> **Warning**');
      expect(result.normalizedContent).toContain('> This is a warning callout');
    });

    it('should handle Feishu math formulas', () => {
      const feishuMarkdown = `
Inline formula: $E=mc^2$

Block formula:
$$
\\sum_{i=1}^{n} x_i
$$
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(feishuMarkdown, 'FEISHU' as ExternalSource);

      expect(result.normalizedContent).toContain('$E=mc^2$');
      expect(result.normalizedContent).toContain('$$');
      expect(result.normalizedContent).toContain('\\sum_{i=1}^{n} x_i');
    });

    it('should extract headings from Feishu markdown', () => {
      const feishuMarkdown = `
# H1 Title

## H2 Section

### H3 Subsection

Regular content.
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(feishuMarkdown, 'FEISHU' as ExternalSource);

      expect(result.headings).toHaveLength(3);
      expect(result.headings[0].text).toBe('H1 Title');
      expect(result.headings[0].level).toBe(1);
      expect(result.headings[1].text).toBe('H2 Section');
      expect(result.headings[1].level).toBe(2);
      expect(result.headings[2].text).toBe('H3 Subsection');
      expect(result.headings[2].level).toBe(3);
    });
  });

  describe('Notion Markdown normalization', () => {
    it('should normalize Notion task lists', () => {
      const notionMarkdown = `
- [ ] Incomplete task
- [x] Completed task
- [ ] Another task
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(notionMarkdown, 'NOTION' as ExternalSource);

      expect(result.normalizedContent).toContain('- [ ] Incomplete task');
      expect(result.normalizedContent).toContain('- [x] Completed task');
    });

    it('should extract internal links from Notion markdown', () => {
      const notionMarkdown = `
Check [Document 1](/doc-123) for details.

Also see [this page](https://notion.so/page) and [[Wiki Link]].
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(notionMarkdown, 'NOTION' as ExternalSource);

      expect(result.internalLinks).toBeDefined();
      expect(result.internalLinks.length).toBeGreaterThan(0);
    });

    it('should handle Notion code blocks', () => {
      const notionMarkdown = `
\`\`\`typescript
const hello = "world";
console.log(hello);
\`\`\`
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(notionMarkdown, 'NOTION' as ExternalSource);

      expect(result.codeBlocks).toHaveLength(1);
      expect(result.codeBlocks[0].language).toBe('typescript');
      expect(result.codeBlocks[0].content).toContain('const hello');
    });
  });

  describe('Confluence Markdown normalization', () => {
    it('should convert Confluence macros to CommonMark', () => {
      const confluenceMarkdown = `
{code:language=java}
public class Hello {
}
{code}

{note}
This is a note
{note}

{warning}
This is a warning
{warning}
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(confluenceMarkdown, 'CONFLUENCE' as ExternalSource);

      expect(result.normalizedContent).toContain('\`\`\`java');
      expect(result.normalizedContent).toContain('public class Hello');
      expect(result.normalizedContent).toContain('> **Note**');
      expect(result.normalizedContent).toContain('> **Warning**');
    });

    it('should handle Confluence info and tip macros', () => {
      const confluenceMarkdown = `
{info}
Information message
{info}

{tip}
Helpful tip here
{tip}
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(confluenceMarkdown, 'CONFLUENCE' as ExternalSource);

      expect(result.normalizedContent).toContain('> **Info**');
      expect(result.normalizedContent).toContain('> **Tip**');
    });
  });

  describe('GitHub Wiki normalization', () => {
    it('should convert Wiki links to Markdown links', () => {
      const githubWiki = `
Check [[Home Page]] for more info.

See [[Advanced Topics|Advanced Guide]] for details.
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(githubWiki, 'GITHUB_WIKI' as ExternalSource);

      expect(result.normalizedContent).toContain('[Home Page]');
      expect(result.normalizedContent).toContain('[Advanced Guide]');
    });

    it('should handle GitHub Flavored Markdown features', () => {
      const githubWiki = `
| Header 1 | Header 2 |
|----------|----------|
| Cell 1   | Cell 2   |

- [x] Task 1
- [ ] Task 2
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(githubWiki, 'GITHUB_WIKI' as ExternalSource);

      expect(result.normalizedContent).toContain('| Header 1 |');
      expect(result.normalizedContent).toContain('- [x] Task 1');
    });
  });

  describe('Tag extraction', () => {
    it('should extract tags from hashtag format', () => {
      const markdown = `
# Document

This is about #javascript and #typescript.

Also mentions #react and #nextjs.
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(markdown, 'INTERNAL' as ExternalSource);

      expect(result.tags).toContain('javascript');
      expect(result.tags).toContain('typescript');
      expect(result.tags).toContain('react');
      expect(result.tags).toContain('nextjs');
    });
  });

  describe('Universal normalization', () => {
    it('should produce valid CommonMark for all sources', () => {
      const inputs = [
        { source: 'FEISHU', content: '# Hello\n\nWorld' },
        { source: 'NOTION', content: '# Hello\n\nWorld' },
        { source: 'CONFLUENCE', content: '# Hello\n\nWorld' },
        { source: 'GITHUB_WIKI', content: '# Hello\n\nWorld' },
      ];

      const normalizer = new MarkdownNormalizer();

      inputs.forEach(({ source, content }) => {
        const result = normalizer.normalize(content, source as ExternalSource);
        expect(result.normalizedContent).toContain('# Hello');
        expect(result.normalizedContent).toContain('World');
      });
    });

    it('should handle empty content gracefully', () => {
      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize('', 'INTERNAL' as ExternalSource);
      
      expect(result.normalizedContent).toBe('');
      expect(result.headings).toEqual([]);
      expect(result.tags).toEqual([]);
      expect(result.internalLinks).toEqual([]);
      expect(result.codeBlocks).toEqual([]);
    });

    it('should handle undefined content gracefully', () => {
      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(null as any, 'INTERNAL' as ExternalSource);
      
      expect(result.normalizedContent).toBe('');
    });
  });

  describe('Link extraction', () => {
    it('should separate internal and external links', () => {
      const markdown = `
Check [internal doc](/docs/internal) and [external](https://example.com).

Also [another doc](../guide).
      `.trim();

      const normalizer = new MarkdownNormalizer();
      const result = normalizer.normalize(markdown, 'INTERNAL' as ExternalSource);

      const internalLinks = result.internalLinks.filter(
        (link) => !link.url.startsWith('http')
      );
      const externalLinks = result.internalLinks.filter(
        (link) => link.url.startsWith('http')
      );

      expect(internalLinks.length).toBeGreaterThan(0);
      expect(externalLinks.length).toBeGreaterThan(0);
    });
  });
});
