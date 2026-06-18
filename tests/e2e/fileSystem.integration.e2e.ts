import { test, expect } from '@playwright/test';
import { TestVault, createSampleNote, waitForCondition } from './testUtils';
import * as fs from 'fs';
import * as path from 'path';

test.describe('File System Integration', () => {
  let vault: TestVault;

  test.beforeEach(() => {
    vault = new TestVault();
  });

  test.afterEach(() => {
    vault.cleanup();
  });

  test('should have consistent count between file system and expected notes', () => {
    const noteCount = 10;
    
    for (let i = 0; i < noteCount; i++) {
      vault.createFile(
        `note-${i}.md`,
        createSampleNote(`笔记 ${i}`, {
          tags: ['test', `tag-${i % 3}`],
          links: i > 0 ? [`笔记 ${i - 1}`] : [],
        })
      );
    }
    
    const mdFiles = vault.listMdFiles();
    expect(mdFiles.length).toBe(noteCount);
    
    for (let i = 0; i < noteCount; i++) {
      expect(mdFiles).toContain(`note-${i}.md`);
    }
  });

  test('should maintain consistency after file creation', () => {
    const initialCount = vault.listMdFiles().length;
    expect(initialCount).toBe(0);
    
    vault.createFile('note1.md', createSampleNote('笔记一'));
    
    const afterCreateCount = vault.listMdFiles().length;
    expect(afterCreateCount).toBe(1);
    
    vault.createFile('note2.md', createSampleNote('笔记二'));
    vault.createFile('subdir/note3.md', createSampleNote('笔记三'));
    
    const finalCount = vault.listMdFiles().length;
    expect(finalCount).toBe(3);
  });

  test('should maintain consistency after file deletion', () => {
    vault.createFile('note1.md', createSampleNote('笔记一'));
    vault.createFile('note2.md', createSampleNote('笔记二'));
    vault.createFile('note3.md', createSampleNote('笔记三'));
    
    expect(vault.listMdFiles().length).toBe(3);
    
    vault.deleteFile('note2.md');
    
    const mdFiles = vault.listMdFiles();
    expect(mdFiles.length).toBe(2);
    expect(mdFiles).toContain('note1.md');
    expect(mdFiles).toContain('note3.md');
    expect(mdFiles).not.toContain('note2.md');
  });

  test('should maintain consistency after file rename', () => {
    vault.createFile('old-name.md', createSampleNote('旧笔记'));
    
    expect(vault.exists('old-name.md')).toBe(true);
    expect(vault.exists('new-name.md')).toBe(false);
    
    vault.renameFile('old-name.md', 'new-name.md');
    
    expect(vault.exists('old-name.md')).toBe(false);
    expect(vault.exists('new-name.md')).toBe(true);
    
    const content = vault.readFile('new-name.md');
    expect(content).toContain('# 旧笔记');
  });

  test('should maintain consistency after file modification', () => {
    vault.createFile('note.md', createSampleNote('原始标题', { content: '原始内容。' }));
    
    const originalContent = vault.readFile('note.md');
    expect(originalContent).toContain('原始标题');
    expect(originalContent).toContain('原始内容');
    
    const newContent = createSampleNote('更新后的标题', { content: '更新后的内容。' });
    vault.modifyFile('note.md', newContent);
    
    const updatedContent = vault.readFile('note.md');
    expect(updatedContent).toContain('更新后的标题');
    expect(updatedContent).toContain('更新后的内容');
  });

  test('should handle nested directories correctly', () => {
    const files = [
      'root.md',
      'level1/note1.md',
      'level1/level2/note2.md',
      'level1/level2/level3/note3.md',
      'other/note4.md',
    ];
    
    for (const file of files) {
      vault.createFile(file, createSampleNote(path.basename(file, '.md')));
    }
    
    const mdFiles = vault.listMdFiles();
    expect(mdFiles.length).toBe(files.length);
    
    for (const file of files) {
      const normalizedFile = file.replace(/\//g, path.sep);
      expect(mdFiles).toContain(normalizedFile);
    }
  });

  test('should parse frontmatter correctly from all files', () => {
    const notes = [
      { file: 'note1.md', title: '笔记一', tags: ['tag1', 'tag2'] },
      { file: 'note2.md', title: '笔记二', tags: [] },
      { file: 'note3.md', title: '笔记三', tags: ['single-tag'] },
    ];
    
    for (const note of notes) {
      vault.createFile(
        note.file,
        createSampleNote(note.title, { tags: note.tags })
      );
    }
    
    const mdFiles = vault.listMdFiles();
    expect(mdFiles.length).toBe(notes.length);
    
    for (const file of mdFiles) {
      const content = vault.readFile(file);
      expect(content.startsWith('---') || content.startsWith('#')).toBe(true);
    }
  });

  test('should handle wiki links consistently', () => {
    vault.createFile(
      'note-a.md',
      createSampleNote('笔记A', { links: ['笔记B', '笔记C'] })
    );
    vault.createFile(
      'note-b.md',
      createSampleNote('笔记B', { links: ['笔记A'] })
    );
    vault.createFile(
      'note-c.md',
      createSampleNote('笔记C', { links: ['笔记A', '笔记B'] })
    );
    
    const contentA = vault.readFile('note-a.md');
    const contentB = vault.readFile('note-b.md');
    const contentC = vault.readFile('note-c.md');
    
    expect(contentA).toContain('[[笔记B]]');
    expect(contentA).toContain('[[笔记C]]');
    expect(contentB).toContain('[[笔记A]]');
    expect(contentC).toContain('[[笔记A]]');
    expect(contentC).toContain('[[笔记B]]');
  });

  test('should handle batch operations consistently', () => {
    const batchSize = 50;
    
    for (let i = 0; i < batchSize; i++) {
      vault.createFile(
        `batch/note-${i}.md`,
        createSampleNote(`批量笔记 ${i}`, {
          tags: ['batch'],
          content: `这是第 ${i} 篇批量笔记。`,
        })
      );
    }
    
    const mdFiles = vault.listMdFiles();
    expect(mdFiles.length).toBe(batchSize);
    
    const batchFiles = mdFiles.filter(f => f.startsWith('batch' + path.sep));
    expect(batchFiles.length).toBe(batchSize);
  });

  test('should handle special characters in filenames', () => {
    const specialFiles = [
      'note with spaces.md',
      'note-with-dashes.md',
      'note_with_underscores.md',
      '中文笔记.md',
      'note-with-@#$%.md',
    ];
    
    for (const file of specialFiles) {
      vault.createFile(file, createSampleNote(path.basename(file, '.md')));
    }
    
    const mdFiles = vault.listMdFiles();
    expect(mdFiles.length).toBe(specialFiles.length);
    
    for (const file of specialFiles) {
      expect(vault.exists(file)).toBe(true);
      
      const content = vault.readFile(file);
      expect(content.length).toBeGreaterThan(0);
    }
  });

  test('should ignore non-markdown files', () => {
    vault.createFile('note.md', '# Note\n\nContent.');
    vault.createFile('image.png', 'not an image');
    vault.createFile('data.json', '{}');
    vault.createFile('readme.txt', 'text file');
    vault.createFile('script.js', 'console.log("test")');
    
    const mdFiles = vault.listMdFiles();
    expect(mdFiles.length).toBe(1);
    expect(mdFiles[0]).toBe('note.md');
    
    const allFiles = vault.listAllFiles();
    expect(allFiles.length).toBe(5);
  });

  test('should maintain content integrity after multiple operations', () => {
    const originalContent = createSampleNote('测试笔记', {
      tags: ['test', 'integrity'],
      content: '这是原始内容，用于验证完整性。',
      links: ['其他笔记'],
    });
    
    vault.createFile('integrity-test.md', originalContent);
    
    const read1 = vault.readFile('integrity-test.md');
    expect(read1).toBe(originalContent);
    
    vault.modifyFile('integrity-test.md', originalContent);
    
    const read2 = vault.readFile('integrity-test.md');
    expect(read2).toBe(originalContent);
    
    vault.renameFile('integrity-test.md', 'integrity-test-renamed.md');
    
    const read3 = vault.readFile('integrity-test-renamed.md');
    expect(read3).toBe(originalContent);
  });
});

test.describe('Frontmatter Edge Cases Integration', () => {
  let vault: TestVault;

  test.beforeEach(() => {
    vault = new TestVault();
  });

  test.afterEach(() => {
    vault.cleanup();
  });

  test('should handle empty frontmatter', () => {
    const content = `---
---
# Note with Empty Frontmatter

Content here.
`;
    
    vault.createFile('empty-frontmatter.md', content);
    const readContent = vault.readFile('empty-frontmatter.md');
    
    expect(readContent).toContain('---');
    expect(readContent).toContain('# Note with Empty Frontmatter');
  });

  test('should handle multi-line values in frontmatter', () => {
    const content = `---
title: Test Note
description: |
  This is a multi-line
  description that spans
  several lines.
tags: [test, multi-line]
---
# Test Note

Content.
`;
    
    vault.createFile('multiline-frontmatter.md', content);
    const readContent = vault.readFile('multiline-frontmatter.md');
    
    expect(readContent).toContain('title: Test Note');
    expect(readContent).toContain('This is a multi-line');
    expect(readContent).toContain('# Test Note');
  });

  test('should handle nested arrays in frontmatter', () => {
    const content = `---
title: Test Note
categories:
  - category1:
    - subitem1
    - subitem2
  - category2
tags:
  - tag1
  - tag2
  - tag3
---
# Test Note

Content.
`;
    
    vault.createFile('nested-arrays.md', content);
    const readContent = vault.readFile('nested-arrays.md');
    
    expect(readContent).toContain('categories:');
    expect(readContent).toContain('subitem1');
    expect(readContent).toContain('- tag3');
  });

  test('should handle various data types in frontmatter', () => {
    const content = `---
title: String Value
count: 42
isPublished: true
price: 19.99
tags: [a, b, c]
metadata:
  author: John
  date: "2024-01-01"
---
# Test Note

Content.
`;
    
    vault.createFile('various-types.md', content);
    const readContent = vault.readFile('various-types.md');
    
    expect(readContent).toContain('count: 42');
    expect(readContent).toContain('isPublished: true');
    expect(readContent).toContain('price: 19.99');
    expect(readContent).toContain('author: John');
  });

  test('should handle notes without frontmatter', () => {
    const content = `# Note Without Frontmatter

Just regular markdown content.

## Section

More content.
`;
    
    vault.createFile('no-frontmatter.md', content);
    const readContent = vault.readFile('no-frontmatter.md');
    
    expect(readContent).not.toContain('---');
    expect(readContent).toContain('# Note Without Frontmatter');
    expect(readContent).toContain('## Section');
  });
});
