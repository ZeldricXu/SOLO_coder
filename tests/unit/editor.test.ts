import {
  parseFrontmatter,
  extractWikiLinks,
  extractTitleFromMarkdown,
  extractTags,
  getWikiLinkAutocomplete,
  extractLinkContext,
  serializeToMarkdown,
  WIKILINK_REGEX,
} from '@renderer/utils/editorUtils';
import { testFrontmatterCases, testMarkdownCases, createMockNote } from '../__fixtures__/testFixtures';

describe('Frontmatter Parser', () => {
  test.each(testFrontmatterCases)(
    'should parse $name correctly',
    ({ input, expectedFrontmatter, expectedContent }) => {
      const result = parseFrontmatter(input);
      expect(result.frontmatter).toEqual(expectedFrontmatter);
      expect(result.content.trim()).toBe(expectedContent.trim());
    }
  );

  it('should handle malformed frontmatter gracefully', () => {
    const input = '---\ninvalid: yaml: : :\n---\n# Content';
    const result = parseFrontmatter(input);
    expect(result.content).toBeTruthy();
  });

  it('should detect presence of frontmatter', () => {
    const withFrontmatter = '---\ntitle: Test\n---\n# Content';
    const withoutFrontmatter = '# Content';
    
    expect(parseFrontmatter(withFrontmatter).hasFrontmatter).toBe(true);
    expect(parseFrontmatter(withoutFrontmatter).hasFrontmatter).toBe(false);
  });

  it('should handle empty frontmatter correctly', () => {
    const result = parseFrontmatter('---\n---\n# Content');
    expect(result.hasFrontmatter).toBe(false);
    expect(result.frontmatter).toEqual({});
  });
});

describe('Wiki Link Extractor', () => {
  it('should extract basic wiki links', () => {
    const content = 'Check out [[知识图谱概述]] for more info.';
    const links = extractWikiLinks(content);
    
    expect(links).toHaveLength(1);
    expect(links[0].target).toBe('知识图谱概述');
    expect(links[0].displayText).toBe('知识图谱概述');
  });

  it('should extract aliased wiki links', () => {
    const content = 'Read [[知识图谱概述|this article]] for details.';
    const links = extractWikiLinks(content);
    
    expect(links).toHaveLength(1);
    expect(links[0].target).toBe('知识图谱概述');
    expect(links[0].displayText).toBe('this article');
  });

  it('should extract multiple wiki links', () => {
    const content = '[[笔记一]] and [[笔记二|Second Note]] and [[笔记三]]';
    const links = extractWikiLinks(content);
    
    expect(links).toHaveLength(3);
    expect(links.map(l => l.target)).toEqual(['笔记一', '笔记二', '笔记三']);
    expect(links.map(l => l.displayText)).toEqual(['笔记一', 'Second Note', '笔记三']);
  });

  it('should handle no wiki links', () => {
    const content = 'This is a normal note without any wiki links.';
    const links = extractWikiLinks(content);
    
    expect(links).toHaveLength(0);
  });

  it('should correctly report link positions', () => {
    const content = 'ABC [[Target]] DEF';
    const links = extractWikiLinks(content);
    
    expect(links[0].startIndex).toBe(4);
    expect(links[0].endIndex).toBe(14);
  });
});

describe('Wiki Link Regex', () => {
  it('should not match escaped brackets', () => {
    const content = 'This is \\[\\[not a link\\]\\]';
    const links = extractWikiLinks(content);
    expect(links).toHaveLength(0);
  });

  it('should not match nested brackets', () => {
    const content = '[[outer [inner] link]]';
    const links = extractWikiLinks(content);
    expect(links).toHaveLength(0);
  });

  it('should match links with spaces', () => {
    const content = '[[My Note Title With Spaces]]';
    const links = extractWikiLinks(content);
    expect(links[0].target).toBe('My Note Title With Spaces');
  });
});

describe('Title Extraction', () => {
  it('should extract H1 heading as title', () => {
    const content = '# My Awesome Note\n\nContent here.';
    expect(extractTitleFromMarkdown(content)).toBe('My Awesome Note');
  });

  it('should extract H1 after frontmatter', () => {
    const content = '---\ntags: [test]\n---\n\n# Real Title\n\nContent.';
    expect(extractTitleFromMarkdown(content)).toBe('Real Title');
  });

  it('should fall back to first line if no heading', () => {
    const content = 'This is the first line\nSecond line.';
    expect(extractTitleFromMarkdown(content)).toBe('This is the first line');
  });

  it('should return Untitled for empty content', () => {
    expect(extractTitleFromMarkdown('')).toBe('Untitled');
    expect(extractTitleFromMarkdown('   ')).toBe('Untitled');
  });

  it('should trim title to 100 characters', () => {
    const longTitle = 'A'.repeat(150);
    const content = `# ${longTitle}\nContent.`;
    const result = extractTitleFromMarkdown(content);
    expect(result.length).toBe(100);
  });
});

describe('Tag Extraction', () => {
  it('should extract tags from frontmatter', () => {
    const content = '---\ntags: [javascript, react]\n---\n# Content';
    const tags = extractTags(content);
    expect(tags).toEqual(expect.arrayContaining(['javascript', 'react']));
  });

  it('should extract single tag from frontmatter', () => {
    const content = '---\ntags: typescript\n---\n# Content';
    const tags = extractTags(content);
    expect(tags).toContain('typescript');
  });

  it('should extract inline tags', () => {
    const content = '# Note\n\nThis is #important and #urgent.';
    const tags = extractTags(content);
    expect(tags).toEqual(expect.arrayContaining(['important', 'urgent']));
  });

  it('should combine frontmatter and inline tags', () => {
    const content = '---\ntags: [work]\n---\n# Note\n\n#meeting notes';
    const tags = extractTags(content);
    expect(tags).toEqual(expect.arrayContaining(['work', 'meeting']));
  });

  it('should deduplicate tags', () => {
    const content = '---\ntags: [test]\n---\n# Note\n\n#test duplicate';
    const tags = extractTags(content);
    const testCount = tags.filter(t => t === 'test').length;
    expect(testCount).toBe(1);
  });

  it('should handle Chinese tags', () => {
    const content = '---\ntags: [重要, 紧急]\n---\n# Content';
    const tags = extractTags(content);
    expect(tags).toEqual(expect.arrayContaining(['重要', '紧急']));
  });

  it('should return empty array for no tags', () => {
    const content = '# Note\n\nNo tags here.';
    const tags = extractTags(content);
    expect(tags).toEqual([]);
  });
});

describe('Wiki Link Autocomplete', () => {
  const mockNotes = [
    { id: '1', title: 'JavaScript 基础', path: 'study/javascript.md', tags: ['javascript', 'programming'] },
    { id: '2', title: 'React 高级', path: 'study/react.md', tags: ['react', 'javascript'] },
    { id: '3', title: 'TypeScript 入门', path: 'study/typescript.md', tags: ['typescript'] },
    { id: '4', title: '项目管理方法', path: 'work/pm.md', tags: ['work', 'management'] },
  ];

  it('should return exact matches with highest score', () => {
    const results = getWikiLinkAutocomplete('React 高级', mockNotes);
    expect(results[0].id).toBe('2');
    expect(results[0].score).toBeGreaterThan(results[1]?.score || 0);
  });

  it('should return partial matches', () => {
    const results = getWikiLinkAutocomplete('Java', mockNotes);
    expect(results.map(r => r.title)).toEqual(
      expect.arrayContaining(['JavaScript 基础', 'React 高级'])
    );
  });

  it('should return all notes when query is empty', () => {
    const results = getWikiLinkAutocomplete('', mockNotes);
    expect(results).toHaveLength(mockNotes.length);
  });

  it('should return empty array for zero matches', () => {
    const results = getWikiLinkAutocomplete('Nonexistent Note', mockNotes);
    expect(results).toHaveLength(0);
  });

  it('should match in tags', () => {
    const results = getWikiLinkAutocomplete('management', mockNotes);
    expect(results[0].id).toBe('4');
  });

  it('should match in path', () => {
    const results = getWikiLinkAutocomplete('study', mockNotes);
    expect(results).toHaveLength(3);
  });

  it('should respect limit parameter', () => {
    const results = getWikiLinkAutocomplete('', mockNotes, 2);
    expect(results).toHaveLength(2);
  });

  it('should rank title matches higher than tag matches', () => {
    const results = getWikiLinkAutocomplete('javascript', mockNotes);
    expect(results[0].id).toBe('1');
  });

  it('should handle case-insensitive search', () => {
    const results1 = getWikiLinkAutocomplete('REACT', mockNotes);
    const results2 = getWikiLinkAutocomplete('react', mockNotes);
    expect(results1.map(r => r.id)).toEqual(results2.map(r => r.id));
  });

  it('should handle input during typing (mid-typing scenario)', () => {
    const queries = ['', 'R', 'Re', 'Rea', 'Reac', 'React'];
    const allResults = queries.map(q => getWikiLinkAutocomplete(q, mockNotes));
    
    expect(allResults[0].length).toBe(4);
    expect(allResults[allResults.length - 1].length).toBeGreaterThan(0);
    expect(allResults[allResults.length - 1][0].title).toBe('React 高级');
  });
});

describe('Link Context Extraction', () => {
  const content = 'This is some prefix text that comes before the [[Target Note]] link, and some suffix text that comes after.';

  it('should extract context around link', () => {
    const linkIndex = content.indexOf('[[Target Note]]');
    const context = extractLinkContext(content, linkIndex, 50);
    
    expect(context).toContain('Target Note');
    expect(context).toContain('...');
  });

  it('should handle link at start of content', () => {
    const text = '[[Target]] rest of content';
    const context = extractLinkContext(text, 0, 20);
    
    expect(context).not.toMatch(/^\.\.\./);
    expect(context).toContain('rest of');
  });

  it('should handle link at end of content', () => {
    const text = 'start of content [[Target]]';
    const context = extractLinkContext(text, text.indexOf('[[Target]]'), 20);
    
    expect(context).not.toMatch(/\.\.\.$/);
    expect(context).toContain('start of');
  });

  it('should replace newlines with spaces', () => {
    const text = 'Line 1\n[[Target]]\nLine 2';
    const context = extractLinkContext(text, text.indexOf('[[Target]]'), 30);
    
    expect(context).not.toContain('\n');
    expect(context).toContain('Line 1');
    expect(context).toContain('Line 2');
  });

  it('should collapse multiple whitespace', () => {
    const text = 'Lots    of   whitespace   [[Target]]   here';
    const context = extractLinkContext(text, text.indexOf('[[Target]]'), 40);
    
    expect(context).not.toMatch(/\s{2,}/);
  });
});

describe('Markdown Serialization', () => {
  it('should serialize wiki links correctly', () => {
    const nodes = [
      {
        type: 'paragraph',
        children: [
          { text: 'Check ' },
          {
            type: 'wiki-link',
            target: '目标笔记',
            displayText: '目标笔记',
            children: [{ text: '目标笔记' }],
          },
          { text: ' for more.' },
        ],
      },
    ] as any;
    
    const result = serializeToMarkdown(nodes);
    expect(result).toContain('[[目标笔记]]');
  });

  it('should serialize aliased wiki links correctly', () => {
    const nodes = [
      {
        type: 'paragraph',
        children: [
          {
            type: 'wiki-link',
            target: '目标笔记',
            displayText: '这里',
            children: [{ text: '这里' }],
          },
        ],
      },
    ] as any;
    
    const result = serializeToMarkdown(nodes);
    expect(result).toContain('[[目标笔记|这里]]');
  });

  it('should serialize code blocks with language', () => {
    const nodes = [
      {
        type: 'code-block',
        language: 'javascript',
        children: [{ text: 'const x = 1;' }],
      },
    ] as any;
    
    const result = serializeToMarkdown(nodes);
    expect(result).toContain('```javascript');
    expect(result).toContain('const x = 1;');
    expect(result).toContain('```');
  });

  it('should serialize inline formatting', () => {
    const nodes = [
      {
        type: 'paragraph',
        children: [
          { text: 'Normal ' },
          { text: 'bold', bold: true },
          { text: ' and ' },
          { text: 'italic', italic: true },
          { text: ' and ' },
          { text: 'code', code: true },
        ],
      },
    ] as any;
    
    const result = serializeToMarkdown(nodes);
    expect(result).toContain('**bold**');
    expect(result).toContain('*italic*');
    expect(result).toContain('`code`');
  });

  it('should serialize headings with correct levels', () => {
    for (let level = 1; level <= 3; level++) {
      const nodes = [
        {
          type: 'heading',
          level,
          children: [{ text: `Heading ${level}` }],
        },
      ] as any;
      
      const result = serializeToMarkdown(nodes);
      expect(result).toBe(`${'#'.repeat(level)} Heading ${level}`);
    }
  });

  it('should serialize blockquotes', () => {
    const nodes = [
      {
        type: 'blockquote',
        children: [{ text: 'This is a quote.\nSecond line.' }],
      },
    ] as any;
    
    const result = serializeToMarkdown(nodes);
    expect(result).toContain('> This is a quote.');
    expect(result).toContain('> Second line.');
  });

  it('should serialize bulleted lists', () => {
    const nodes = [
      {
        type: 'bulleted-list',
        children: [
          { type: 'list-item', children: [{ text: 'Item 1' }] },
          { type: 'list-item', children: [{ text: 'Item 2' }] },
          { type: 'list-item', children: [{ text: 'Item 3' }] },
        ],
      },
    ] as any;
    
    const result = serializeToMarkdown(nodes);
    expect(result).toBe('- Item 1\n- Item 2\n- Item 3');
  });

  it('should serialize numbered lists', () => {
    const nodes = [
      {
        type: 'numbered-list',
        children: [
          { type: 'list-item', children: [{ text: 'First' }] },
          { type: 'list-item', children: [{ text: 'Second' }] },
        ],
      },
    ] as any;
    
    const result = serializeToMarkdown(nodes);
    expect(result).toBe('1. First\n2. Second');
  });
});
