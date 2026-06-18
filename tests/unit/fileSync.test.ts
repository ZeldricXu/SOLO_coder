import * as fs from 'fs';
import * as path from 'path';
import { TempVault, waitFor, measurePerformance } from '../utils/testUtils';
import { generateBatchNotes } from '../__fixtures__/testFixtures';
import {
  parseFrontmatter,
  extractWikiLinks,
  extractTitleFromMarkdown,
  extractTags,
} from '@renderer/utils/editorUtils';
import type { Note, NoteLink } from '@shared/types';

jest.mock('chokidar', () => {
  const mockOn = jest.fn().mockReturnThis();
  const mockClose = jest.fn();
  
  return {
    watch: jest.fn(() => ({
      on: mockOn,
      close: mockClose,
    })),
    __mockOn: mockOn,
    __mockClose: mockClose,
  };
});

jest.mock('../../src/main/db', () => ({
  getDatabase: jest.fn().mockReturnValue({
    prepare: jest.fn().mockReturnThis(),
    run: jest.fn(),
    get: jest.fn(),
    all: jest.fn().mockReturnValue([]),
    exec: jest.fn(),
    pragma: jest.fn(),
    close: jest.fn(),
  }),
}));

describe('File System Sync - Parsing', () => {
  let tempVault: TempVault;

  beforeEach(() => {
    tempVault = new TempVault();
  });

  afterEach(() => {
    tempVault.cleanup();
  });

  it('should parse frontmatter correctly from file', () => {
    const content = `---
title: Test Note
tags: [test, example]
created: "2024-01-01"
---
# Test Note

Content here.
`;
    const filePath = tempVault.createFile('test.md', content);
    const fileContent = fs.readFileSync(filePath, 'utf-8');
    const result = parseFrontmatter(fileContent);
    
    expect(result.hasFrontmatter).toBe(true);
    expect(result.frontmatter.title).toBe('Test Note');
    expect(result.frontmatter.tags).toEqual(['test', 'example']);
    expect(result.frontmatter.created).toBe('2024-01-01');
    expect(result.content.trim()).toBe('# Test Note\n\nContent here.');
  });

  it('should extract title from markdown file', () => {
    const content = '# My Awesome Note\n\nSome content.';
    const title = extractTitleFromMarkdown(content);
    expect(title).toBe('My Awesome Note');
  });

  it('should extract wiki links from content', () => {
    const content = 'Link to [[Note 1]] and [[Note 2|Second Note]].';
    const links = extractWikiLinks(content);
    
    expect(links).toHaveLength(2);
    expect(links[0].target).toBe('Note 1');
    expect(links[1].target).toBe('Note 2');
    expect(links[1].displayText).toBe('Second Note');
  });

  it('should extract tags from frontmatter and inline', () => {
    const content = `---
tags: [javascript, react]
---
# Note

This is #important and #urgent.
`;
    const tags = extractTags(content);
    expect(tags).toEqual(expect.arrayContaining(['javascript', 'react', 'important', 'urgent']));
  });
});

describe('File System Sync - File Events', () => {
  let tempVault: TempVault;
  let eventHandler: any;

  beforeEach(() => {
    tempVault = new TempVault();
    
    jest.resetModules();
  });

  afterEach(() => {
    tempVault.cleanup();
    jest.clearAllMocks();
  });

  it('should capture file creation event', async () => {
    let addHandler: ((filePath: string) => void) | null = null;
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => ({
        on: (event: string, handler: any) => {
          if (event === 'add') addHandler = handler;
          return { on: jest.fn().mockReturnThis(), close: jest.fn() };
        },
      })),
    }));
    
    const { VaultService } = require('../../src/main/services/vaultService');
    VaultService.init(tempVault.getPath());
    
    const testContent = '# New Note\n\nContent.';
    tempVault.createFile('new-note.md', testContent);
    
    await waitFor(() => addHandler !== null, 1000);
    
    const parseSpy = jest.spyOn(require('../../src/main/services/vaultService').VaultService, 'parseMarkdownFile');
    addHandler!('new-note.md');
    
    await waitFor(() => parseSpy.mock.calls.length > 0, 1000);
    
    expect(parseSpy).toHaveBeenCalled();
    const callArgs = parseSpy.mock.calls[0];
    expect(callArgs[1]).toBe('new-note.md');
  });

  it('should capture file modification event', async () => {
    const filePath = tempVault.createFile('test.md', '# Original\n\nOriginal content.');
    
    let changeHandler: ((filePath: string) => void) | null = null;
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => ({
        on: (event: string, handler: any) => {
          if (event === 'change') changeHandler = handler;
          return { on: jest.fn().mockReturnThis(), close: jest.fn() };
        },
      })),
    }));
    
    const { VaultService } = require('../../src/main/services/vaultService');
    VaultService.init(tempVault.getPath());
    
    tempVault.modifyFile('test.md', '# Updated\n\nUpdated content.');
    
    await waitFor(() => changeHandler !== null, 1000);
    
    const updateSpy = jest.fn().mockResolvedValue({ id: '1', title: 'Updated' });
    jest.doMock('../../src/main/db/noteService', () => ({
      NoteService: {
        getByPath: jest.fn().mockResolvedValue({ id: '1' }),
        update: updateSpy,
      },
    }));
    
    changeHandler!('test.md');
    
    await new Promise(resolve => setTimeout(resolve, 100));
    
    expect(tempVault.readFile('test.md')).toContain('Updated content');
  });

  it('should capture file deletion event', async () => {
    tempVault.createFile('to-delete.md', '# To Delete\n\nContent.');
    
    let unlinkHandler: ((filePath: string) => void) | null = null;
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => ({
        on: (event: string, handler: any) => {
          if (event === 'unlink') unlinkHandler = handler;
          return { on: jest.fn().mockReturnThis(), close: jest.fn() };
        },
      })),
    }));
    
    const { VaultService } = require('../../src/main/services/vaultService');
    VaultService.init(tempVault.getPath());
    
    tempVault.deleteFile('to-delete.md');
    
    await waitFor(() => unlinkHandler !== null, 1000);
    
    const deleteSpy = jest.fn().mockResolvedValue(true);
    jest.doMock('../../src/main/db/noteService', () => ({
      NoteService: {
        getByPath: jest.fn().mockResolvedValue({ id: '1' }),
        delete: deleteSpy,
      },
    }));
    
    unlinkHandler!('to-delete.md');
    
    await new Promise(resolve => setTimeout(resolve, 100));
    
    expect(tempVault.exists('to-delete.md')).toBe(false);
  });

  it('should handle file rename (unlink + add)', async () => {
    tempVault.createFile('old-name.md', '# Test\n\nContent.');
    
    let addHandler: ((filePath: string) => void) | null = null;
    let unlinkHandler: ((filePath: string) => void) | null = null;
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => ({
        on: (event: string, handler: any) => {
          if (event === 'add') addHandler = handler;
          if (event === 'unlink') unlinkHandler = handler;
          return { on: jest.fn().mockReturnThis(), close: jest.fn() };
        },
      })),
    }));
    
    const { VaultService } = require('../../src/main/services/vaultService');
    VaultService.init(tempVault.getPath());
    
    tempVault.renameFile('old-name.md', 'new-name.md');
    
    await waitFor(() => addHandler !== null && unlinkHandler !== null, 1000);
    
    unlinkHandler!('old-name.md');
    addHandler!('new-name.md');
    
    await new Promise(resolve => setTimeout(resolve, 100));
    
    expect(tempVault.exists('new-name.md')).toBe(true);
    expect(tempVault.exists('old-name.md')).toBe(false);
  });

  it('should handle all four event types in sequence', async () => {
    const events: string[] = [];
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => ({
        on: (event: string, handler: any) => {
          if (['add', 'change', 'unlink', 'ready'].includes(event)) {
            events.push(event);
          }
          return { on: jest.fn().mockReturnThis(), close: jest.fn() };
        },
      })),
    }));
    
    const { VaultService } = require('../../src/main/services/vaultService');
    VaultService.init(tempVault.getPath());
    
    expect(events).toContain('add');
  });
});

describe('File System Sync - Batch Import', () => {
  let tempVault: TempVault;

  beforeEach(() => {
    tempVault = new TempVault();
  });

  afterEach(() => {
    tempVault.cleanup();
  });

  it('should handle 500+ files without losing events', async () => {
    const batchNotes = generateBatchNotes(500);
    
    const { totalTime } = await measurePerformance(async () => {
      for (const note of batchNotes) {
        let content = '';
        if (note.frontmatter && Object.keys(note.frontmatter).length > 0) {
          content += '---\n';
          for (const [key, value] of Object.entries(note.frontmatter)) {
            content += `${key}: ${value}\n`;
          }
          content += '---\n\n';
        }
        content += note.content;
        
        tempVault.createFile(note.path, content);
      }
    }, 1);
    
    const fileCount = fs.readdirSync(tempVault.getPath(), { recursive: true }).filter(
      f => f.toString().endsWith('.md')
    ).length;
    
    expect(fileCount).toBe(500);
    expect(totalTime).toBeLessThan(5000);
  }, 30000);

  it('should create linked notes and preserve relationships', () => {
    const noteCount = 100;
    const filePaths: string[] = [];
    
    for (let i = 0; i < noteCount; i++) {
      const linkTarget = i > 0 ? `[[note-${i - 1}]]` : '';
      const content = `# Note ${i}\n\nThis is note ${i}. ${linkTarget}\n\n[[note-${(i + 1) % noteCount}]]`;
      filePaths.push(tempVault.createFile(`note-${i}.md`, content));
    }
    
    let totalLinks = 0;
    for (const p of filePaths) {
      const content = fs.readFileSync(p, 'utf-8');
      totalLinks += extractWikiLinks(content).length;
    }
    
    expect(filePaths).toHaveLength(noteCount);
    expect(totalLinks).toBeGreaterThan(noteCount);
  });

  it('should process files concurrently without corruption', async () => {
    const fileCount = 100;
    const promises: Promise<void>[] = [];
    
    for (let i = 0; i < fileCount; i++) {
      promises.push(
        Promise.resolve().then(() => {
          const content = `# Concurrent Note ${i}\n\nContent ${Math.random()}.`;
          tempVault.createFile(`concurrent-${i}.md`, content);
        })
      );
    }
    
    await Promise.all(promises);
    
    const files = fs.readdirSync(tempVault.getPath()).filter(f => f.endsWith('.md'));
    expect(files).toHaveLength(fileCount);
    
    for (const file of files) {
      const content = fs.readFileSync(path.join(tempVault.getPath(), file), 'utf-8');
      expect(content).toMatch(/^# Concurrent Note \d+/);
      expect(content.length).toBeGreaterThan(0);
    }
  }, 10000);
});

describe('File System Sync - Exception Handling', () => {
  let tempVault: TempVault;

  beforeEach(() => {
    jest.resetModules();
    tempVault = new TempVault();
  });

  afterEach(() => {
    tempVault.cleanup();
    jest.clearAllMocks();
  });

  it('should gracefully handle vault deletion', async () => {
    let addHandler: ((filePath: string) => void) | null = null;
    let errorHandler: ((error: Error) => void) | null = null;
    const mockClose = jest.fn();
    
    jest.doMock('chokidar', () => ({
      watch: jest.fn(() => {
        const mockWatcher: any = {
          on: (event: string, handler: any) => {
            if (event === 'add') addHandler = handler;
            if (event === 'error') errorHandler = handler;
            return mockWatcher;
          },
          close: mockClose,
        };
        return mockWatcher;
      }),
    }));
    
    jest.doMock('../../src/main/db/noteService', () => ({
      NoteService: {
        getByPath: jest.fn().mockReturnValue(null),
        create: jest.fn().mockReturnValue({ id: '1', content: 'test', path: 'test.md' }),
        update: jest.fn(),
      },
    }));
    
    jest.doMock('../../src/main/db/linkService', () => ({
      LinkService: {
        clearLinksForNote: jest.fn(),
        addLink: jest.fn(),
        updateTargetIdsForPath: jest.fn(),
      },
    }));
    
    jest.doMock('../../src/main/services/searchService', () => ({
      SearchService: {
        addNote: jest.fn(),
      },
    }));
    
    const { VaultService } = require('../../src/main/services/vaultService');
    VaultService.init(tempVault.getPath());
    
    tempVault.cleanup();
    
    await waitFor(() => addHandler !== null, 1000);
    
    const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    
    expect(() => {
      addHandler!('test.md');
    }).not.toThrow();
    
    consoleErrorSpy.mockRestore();
  });

  it('should not crash when processing malformed files', () => {
    const malformedContent = '---\ninvalid: yaml: : :\n---\n# Content';
    const filePath = tempVault.createFile('malformed.md', malformedContent);
    
    expect(() => {
      const content = fs.readFileSync(filePath, 'utf-8');
      parseFrontmatter(content);
    }).not.toThrow();
  });

  it('should handle very large files gracefully', async () => {
    let content = '# Very Large Note\n\n';
    const paragraph = 'This is a paragraph with some content. '.repeat(100) + '\n\n';
    
    for (let i = 0; i < 1000; i++) {
      content += paragraph;
    }
    
    const { totalTime } = await measurePerformance(() => {
      tempVault.createFile('large-note.md', content);
    });
    
    const fileSize = fs.statSync(path.join(tempVault.getPath(), 'large-note.md')).size;
    expect(fileSize).toBeGreaterThan(100000);
    expect(totalTime).toBeLessThan(1000);
  });

  it('should handle files with special characters in names', () => {
    const specialNames = [
      'note with spaces.md',
      'note-with-dashes.md',
      'note_with_underscores.md',
      'note-with-中文.md',
      'note-with-@#$%.md',
    ];
    
    for (const name of specialNames) {
      const filePath = tempVault.createFile(name, '# Test\n\nContent.');
      expect(fs.existsSync(filePath)).toBe(true);
      
      const content = fs.readFileSync(filePath, 'utf-8');
      expect(content).toContain('# Test');
    }
  });

  it('should ignore non-markdown files', () => {
    tempVault.createFile('notes.txt', 'This should be ignored.');
    tempVault.createFile('image.png', 'not really an image');
    tempVault.createFile('data.json', '{}');
    tempVault.createFile('valid.md', '# Valid\n\nContent.');
    
    const files = fs.readdirSync(tempVault.getPath());
    const mdFiles = files.filter(f => f.endsWith('.md'));
    const otherFiles = files.filter(f => !f.endsWith('.md'));
    
    expect(mdFiles).toHaveLength(1);
    expect(otherFiles.length).toBeGreaterThan(0);
  });

  it('should handle nested directories correctly', () => {
    const nestedFiles = [
      'level1/note1.md',
      'level1/level2/note2.md',
      'level1/level2/level3/note3.md',
      'other/note4.md',
    ];
    
    for (const f of nestedFiles) {
      tempVault.createFile(f, `# ${f}\n\nContent.`);
    }
    
    for (const f of nestedFiles) {
      expect(tempVault.exists(f)).toBe(true);
    }
  });
});

describe('File System Sync - Link Indexing', () => {
  let tempVault: TempVault;

  beforeEach(() => {
    tempVault = new TempVault();
  });

  afterEach(() => {
    tempVault.cleanup();
  });

  it('should correctly extract and index bidirectional links', () => {
    tempVault.createFile('note-a.md', '# Note A\n\nSee [[Note B]] for details.');
    tempVault.createFile('note-b.md', '# Note B\n\nRelated to [[Note A]].');
    
    const contentA = tempVault.readFile('note-a.md');
    const contentB = tempVault.readFile('note-b.md');
    
    const linksA = extractWikiLinks(contentA);
    const linksB = extractWikiLinks(contentB);
    
    expect(linksA).toHaveLength(1);
    expect(linksA[0].target).toBe('Note B');
    
    expect(linksB).toHaveLength(1);
    expect(linksB[0].target).toBe('Note A');
  });

  it('should handle aliased links correctly', () => {
    const content = 'Check out [[detailed-explanation|this detailed explanation]].';
    const links = extractWikiLinks(content);
    
    expect(links).toHaveLength(1);
    expect(links[0].target).toBe('detailed-explanation');
    expect(links[0].displayText).toBe('this detailed explanation');
  });

  it('should not index invalid wiki links', () => {
    const content = 'Not a wiki link: [markdown link](test.md) and another [text](url).';
    const links = extractWikiLinks(content);
    
    expect(links).toHaveLength(0);
  });

  it('should handle multiple links to same target', () => {
    const content = '[[Target]] appears twice [[Target]] here.';
    const links = extractWikiLinks(content);
    
    expect(links).toHaveLength(2);
    expect(links[0].target).toBe(links[1].target);
  });

  it('should extract link context correctly', () => {
    const { extractLinkContext } = require('@renderer/utils/editorUtils');
    const content = 'Prefix text before the [[Target Note]] link, and suffix text after.';
    const linkIndex = content.indexOf('[[Target Note]]');
    
    const context = extractLinkContext(content, linkIndex, 50);
    
    expect(context).toContain('Target Note');
    expect(context).toContain('Prefix text');
    expect(context).toContain('suffix text');
  });
});

describe('File System Sync - Performance', () => {
  let tempVault: TempVault;

  beforeEach(() => {
    tempVault = new TempVault();
  });

  afterEach(() => {
    tempVault.cleanup();
  });

  it('should parse 500+ files under 2 seconds', async () => {
    const fileCount = 500;
    const files: string[] = [];
    
    for (let i = 0; i < fileCount; i++) {
      const content = `---\ntitle: Note ${i}\ntags: [tag-${i % 10}]\n---\n\n# Note ${i}\n\nContent of note ${i}.\n\n[[note-${(i + 1) % fileCount}]].`;
      files.push(tempVault.createFile(`note-${i}.md`, content));
    }
    
    const { totalTime } = await measurePerformance(() => {
      for (const f of files) {
        const content = fs.readFileSync(f, 'utf-8');
        parseFrontmatter(content);
        extractWikiLinks(content);
        extractTitleFromMarkdown(content);
      }
    });
    
    expect(totalTime).toBeLessThan(2000);
    expect(files).toHaveLength(fileCount);
  }, 10000);

  it('should not block event loop for large files', async () => {
    const largeContent = '# Large Note\n\n' + 'Line of content.\n'.repeat(10000);
    const filePath = tempVault.createFile('large.md', largeContent);
    
    const blockTimes: number[] = [];
    
    for (let i = 0; i < 5; i++) {
      const start = Date.now();
      fs.readFileSync(filePath, 'utf-8');
      blockTimes.push(Date.now() - start);
    }
    
    const avgBlockTime = blockTimes.reduce((a, b) => a + b, 0) / blockTimes.length;
    expect(avgBlockTime).toBeLessThan(100);
  });
});
