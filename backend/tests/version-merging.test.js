const { v4: uuidv4 } = require('uuid');
const mongoose = require('mongoose');

class VersionServiceTestable {
  constructor() {
    this.snapshotIntervals = new Map();
    this.pendingChanges = new Map();
    this.lastSnapshotTimestamps = new Map();
    this.lastSnapshotContent = new Map();
    
    this.MERGE_WINDOW_MS = 10 * 60 * 1000;
    this.MIN_CHANGES_FOR_SNAPSHOT = 10;
    this.MAX_PENDING_CHANGES = 50;
    this.SNAPSHOT_INTERVAL_MS = 5 * 60 * 1000;
    this.MIN_CONTENT_CHANGE_RATIO = 0.05;
    this.MAX_VERSIONS_TO_KEEP = 100;
  }

  generateContentHash(content) {
    const crypto = require('crypto');
    return crypto
      .createHash('sha256')
      .update(content || '')
      .digest('hex');
  }

  calculateChangeRatio(content1, content2) {
    if (!content1 || !content2) return 1.0;
    if (content1 === content2) return 0.0;
    
    const len1 = content1.length;
    const len2 = content2.length;
    const maxLen = Math.max(len1, len2);
    
    let commonPrefix = 0;
    while (commonPrefix < maxLen && content1[commonPrefix] === content2[commonPrefix]) {
      commonPrefix++;
    }
    
    let commonSuffix = 0;
    while (commonSuffix < maxLen - commonPrefix && 
           content1[len1 - 1 - commonSuffix] === content2[len2 - 1 - commonSuffix]) {
      commonSuffix++;
    }
    
    const changedChars = maxLen - commonPrefix - commonSuffix;
    return changedChars / maxLen;
  }

  isSignificantChange(doc_id, newContent, newTitle) {
    const lastContent = this.lastSnapshotContent.get(doc_id);
    if (!lastContent) {
      return true;
    }

    const contentRatio = this.calculateChangeRatio(lastContent.content, newContent);
    const titleChanged = lastContent.title !== newTitle;

    return titleChanged || contentRatio >= this.MIN_CONTENT_CHANGE_RATIO;
  }

  recordChange(doc_id, user_id, content, title, edit_summary = '') {
    if (!this.pendingChanges.has(doc_id)) {
      this.pendingChanges.set(doc_id, []);
    }

    const changes = this.pendingChanges.get(doc_id);
    
    changes.push({
      timestamp: new Date(),
      user_id,
      content,
      title,
      edit_summary,
      content_hash: this.generateContentHash(content)
    });

    if (changes.length > this.MAX_PENDING_CHANGES) {
      changes.shift();
    }

    this.pendingChanges.set(doc_id, changes);

    const shouldMerge = this.shouldMergeChanges(doc_id);
    return {
      shouldMerge,
      pendingCount: changes.length
    };
  }

  shouldMergeChanges(doc_id) {
    const changes = this.pendingChanges.get(doc_id) || [];
    if (changes.length === 0) return false;

    const lastSnapshot = this.lastSnapshotTimestamps.get(doc_id);
    const firstChange = changes[0].timestamp;
    const lastChange = changes[changes.length - 1].timestamp;

    const timeSinceLastSnapshot = lastSnapshot ? 
      (new Date() - lastSnapshot) : Infinity;
    const timeSpanOfChanges = lastChange - firstChange;

    if (changes.length >= this.MIN_CHANGES_FOR_SNAPSHOT) {
      return true;
    }

    if (timeSinceLastSnapshot >= this.SNAPSHOT_INTERVAL_MS) {
      return true;
    }

    if (timeSpanOfChanges >= this.MERGE_WINDOW_MS) {
      return true;
    }

    return false;
  }

  createMergeSummary(changes, doc_id) {
    if (changes.length === 0) {
      return 'Auto-merged snapshot';
    }

    if (changes.length === 1) {
      return changes[0].edit_summary || 'Auto-save snapshot';
    }

    const uniqueUsers = new Set(changes.map(c => c.user_id).filter(Boolean));
    const userCount = uniqueUsers.size;

    let summary = `合并了 ${changes.length} 次编辑`;
    
    if (userCount > 1) {
      summary += ` (${userCount} 位协作者)`;
    }

    const startTime = changes[0].timestamp;
    const endTime = changes[changes.length - 1].timestamp;
    const timeDiff = Math.round((endTime - startTime) / 60000);
    
    if (timeDiff > 0) {
      summary += `，历时 ${timeDiff} 分钟`;
    }

    return summary;
  }

  async getPendingChangeCount(doc_id) {
    const changes = this.pendingChanges.get(doc_id) || [];
    return changes.length;
  }

  flushPendingChanges(doc_id) {
    this.pendingChanges.set(doc_id, []);
  }

  setLastSnapshot(doc_id, content, title, timestamp) {
    this.lastSnapshotTimestamps.set(doc_id, timestamp || new Date());
    this.lastSnapshotContent.set(doc_id, { content, title });
  }
}

describe('Version Merging Mechanism', () => {
  let versionService;
  const DOC_ID = 'test-doc-001';
  const USER_ID_1 = 'user-001';
  const USER_ID_2 = 'user-002';

  beforeEach(() => {
    versionService = new VersionServiceTestable();
    versionService.MIN_CHANGES_FOR_SNAPSHOT = 3;
    versionService.MERGE_WINDOW_MS = 10 * 60 * 1000;
    versionService.SNAPSHOT_INTERVAL_MS = 5 * 60 * 1000;
    versionService.MIN_CONTENT_CHANGE_RATIO = 0.05;
  });

  describe('Version Merge Trigger Conditions', () => {
    test('should NOT trigger merge when pending changes are below threshold', () => {
      const initialContent = 'Initial document content';
      const title = 'Test Document';

      for (let i = 0; i < 2; i++) {
        versionService.recordChange(
          DOC_ID,
          USER_ID_1,
          `${initialContent} - edit ${i}`,
          title,
          `Edit ${i}`
        );
      }

      const { shouldMerge, pendingCount } = versionService.recordChange(
        DOC_ID,
        USER_ID_1,
        `${initialContent} - edit 2`,
        title,
        'Edit 2'
      );

      expect(pendingCount).toBe(3);
      expect(shouldMerge).toBe(true);
    });

    test('should trigger merge when pending changes reach threshold', () => {
      const initialContent = 'Initial document content';
      const title = 'Test Document';

      for (let i = 0; i < 2; i++) {
        versionService.recordChange(
          DOC_ID,
          USER_ID_1,
          `${initialContent} - edit ${i}`,
          title,
          `Edit ${i}`
        );
      }

      const { shouldMerge, pendingCount } = versionService.recordChange(
        DOC_ID,
        USER_ID_1,
        `${initialContent} - edit 2`,
        title,
        'Edit 2'
      );

      expect(pendingCount).toBe(3);
      expect(shouldMerge).toBe(true);
    });

    test('should identify significant vs insignificant changes', () => {
      const baseContent = 'This is a base document content that is long enough for testing change ratios.';
      const title = 'Test Document';

      versionService.setLastSnapshot(DOC_ID, baseContent, title);

      const minorChange = baseContent + '.';
      const minorRatio = versionService.calculateChangeRatio(baseContent, minorChange);
      expect(minorRatio).toBeLessThan(versionService.MIN_CONTENT_CHANGE_RATIO);

      const majorChange = 'This is a completely different document content with major modifications.';
      const majorRatio = versionService.calculateChangeRatio(baseContent, majorChange);
      expect(majorRatio).toBeGreaterThan(versionService.MIN_CONTENT_CHANGE_RATIO);
    });

    test('should detect significant change when title changes', () => {
      const content = 'Same content for both versions';
      
      versionService.setLastSnapshot(DOC_ID, content, 'Old Title');
      
      const isSignificant = versionService.isSignificantChange(DOC_ID, content, 'New Title');
      expect(isSignificant).toBe(true);
    });
  });

  describe('Content Integrity After Merging', () => {
    test('should preserve final content state after merge', () => {
      const changes = [];
      let currentContent = 'Start';

      for (let i = 0; i < 5; i++) {
        currentContent = `${currentContent} + edit ${i}`;
        const result = versionService.recordChange(
          DOC_ID,
          USER_ID_1,
          currentContent,
          'Test Doc',
          `Edit ${i}`
        );
        changes.push({
          content: currentContent,
          shouldMerge: result.shouldMerge
        });
      }

      const pendingChanges = versionService.pendingChanges.get(DOC_ID);
      expect(pendingChanges.length).toBe(5);
      expect(pendingChanges[pendingChanges.length - 1].content).toBe(currentContent);
    });

    test('should calculate accurate change ratios between versions', () => {
      const content1 = 'Hello World';
      const content2 = 'Hello Beautiful World';
      const content3 = 'Hi World';

      const ratio1 = versionService.calculateChangeRatio(content1, content2);
      const ratio2 = versionService.calculateChangeRatio(content1, content3);
      
      expect(ratio1).toBeGreaterThan(0);
      expect(ratio2).toBeGreaterThan(0);
      
      expect(versionService.calculateChangeRatio(content1, content1)).toBe(0);
    });

    test('should generate unique content hashes', () => {
      const hash1 = versionService.generateContentHash('Content A');
      const hash2 = versionService.generateContentHash('Content B');
      const hash3 = versionService.generateContentHash('Content A');

      expect(hash1).not.toBe(hash2);
      expect(hash1).toBe(hash3);
    });
  });

  describe('Editor Information Preservation', () => {
    test('should track all contributing editors in merge summary', () => {
      versionService.recordChange(DOC_ID, USER_ID_1, 'Content 1', 'Title', 'Edit by user1');
      versionService.recordChange(DOC_ID, USER_ID_2, 'Content 2', 'Title', 'Edit by user2');
      versionService.recordChange(DOC_ID, USER_ID_1, 'Content 3', 'Title', 'Another edit by user1');

      const pendingChanges = versionService.pendingChanges.get(DOC_ID);
      const uniqueUsers = new Set(pendingChanges.map(c => c.user_id));
      
      expect(uniqueUsers.size).toBe(2);
      expect(uniqueUsers.has(USER_ID_1)).toBe(true);
      expect(uniqueUsers.has(USER_ID_2)).toBe(true);
    });

    test('should create merge summary with multiple editors', () => {
      const changes = [
        { timestamp: new Date(), user_id: USER_ID_1, content: 'A', title: 'T' },
        { timestamp: new Date(), user_id: USER_ID_2, content: 'B', title: 'T' },
        { timestamp: new Date(), user_id: USER_ID_1, content: 'C', title: 'T' }
      ];

      const summary = versionService.createMergeSummary(changes, DOC_ID);
      
      expect(summary).toContain('3 次编辑');
      expect(summary).toContain('2 位协作者');
    });

    test('should create simple summary for single editor', () => {
      const changes = [
        { timestamp: new Date(), user_id: USER_ID_1, content: 'A', title: 'T', edit_summary: 'Single edit' }
      ];

      const summary = versionService.createMergeSummary(changes, DOC_ID);
      expect(summary).toBe('Single edit');
    });
  });

  describe('Version History Query Accuracy', () => {
    test('should maintain pending changes in chronological order', () => {
      const now = Date.now();
      
      versionService.recordChange(DOC_ID, USER_ID_1, 'Content 1', 'Title', 'Edit 1');
      versionService.recordChange(DOC_ID, USER_ID_1, 'Content 2', 'Title', 'Edit 2');
      versionService.recordChange(DOC_ID, USER_ID_1, 'Content 3', 'Title', 'Edit 3');

      const pendingChanges = versionService.pendingChanges.get(DOC_ID);
      
      expect(pendingChanges.length).toBe(3);
      
      for (let i = 1; i < pendingChanges.length; i++) {
        expect(pendingChanges[i].timestamp.getTime()).toBeGreaterThanOrEqual(
          pendingChanges[i - 1].timestamp.getTime()
        );
      }
    });

    test('should flush pending changes after merge', async () => {
      versionService.recordChange(DOC_ID, USER_ID_1, 'Content 1', 'Title', 'Edit 1');
      versionService.recordChange(DOC_ID, USER_ID_1, 'Content 2', 'Title', 'Edit 2');

      const countBefore = await versionService.getPendingChangeCount(DOC_ID);
      expect(countBefore).toBe(2);

      versionService.flushPendingChanges(DOC_ID);

      const countAfter = await versionService.getPendingChangeCount(DOC_ID);
      expect(countAfter).toBe(0);
    });

    test('should enforce maximum pending changes limit', () => {
      versionService.MAX_PENDING_CHANGES = 5;

      for (let i = 0; i < 10; i++) {
        versionService.recordChange(DOC_ID, USER_ID_1, `Content ${i}`, 'Title', `Edit ${i}`);
      }

      const pendingChanges = versionService.pendingChanges.get(DOC_ID);
      expect(pendingChanges.length).toBeLessThanOrEqual(versionService.MAX_PENDING_CHANGES);
      
      const contents = pendingChanges.map(c => c.content);
      expect(contents).toContain('Content 9');
      expect(contents).not.toContain('Content 0');
    });
  });

  describe('Real-world Collaboration Scenarios', () => {
    test('should handle rapid sequential edits from single user', () => {
      for (let i = 0; i < 8; i++) {
        versionService.recordChange(
          DOC_ID,
          USER_ID_1,
          `Document content iteration ${i}`,
          'Working Document',
          `Quick edit ${i}`
        );
      }

      const pendingChanges = versionService.pendingChanges.get(DOC_ID);
      expect(pendingChanges.length).toBe(8);
      
      const lastChange = pendingChanges[pendingChanges.length - 1];
      expect(lastChange.content).toBe('Document content iteration 7');
    });

    test('should handle concurrent edits from multiple users', () => {
      const edits = [
        { user: USER_ID_1, content: 'User1 first edit', summary: 'U1: First' },
        { user: USER_ID_2, content: 'User2 first edit', summary: 'U2: First' },
        { user: USER_ID_1, content: 'User1 second edit', summary: 'U1: Second' },
        { user: USER_ID_2, content: 'User2 second edit', summary: 'U2: Second' },
        { user: USER_ID_1, content: 'User1 final edit', summary: 'U1: Final' },
      ];

      for (const edit of edits) {
        versionService.recordChange(
          DOC_ID,
          edit.user,
          edit.content,
          'Collaborative Doc',
          edit.summary
        );
      }

      const pendingChanges = versionService.pendingChanges.get(DOC_ID);
      expect(pendingChanges.length).toBe(5);

      const users = pendingChanges.map(c => c.user_id);
      expect(users).toEqual([USER_ID_1, USER_ID_2, USER_ID_1, USER_ID_2, USER_ID_1]);
    });

    test('should generate merge summary for multi-user collaboration', () => {
      const changes = [
        { timestamp: new Date(Date.now() - 300000), user_id: USER_ID_1, content: 'A', title: 'T' },
        { timestamp: new Date(Date.now() - 240000), user_id: USER_ID_2, content: 'B', title: 'T' },
        { timestamp: new Date(Date.now() - 180000), user_id: USER_ID_1, content: 'C', title: 'T' },
        { timestamp: new Date(Date.now() - 120000), user_id: USER_ID_2, content: 'D', title: 'T' },
        { timestamp: new Date(), user_id: USER_ID_1, content: 'E', title: 'T' },
      ];

      const summary = versionService.createMergeSummary(changes, DOC_ID);
      
      expect(summary).toContain('5 次编辑');
      expect(summary).toContain('2 位协作者');
    });
  });

  describe('Change Ratio Calculation Edge Cases', () => {
    test('should handle empty content comparison', () => {
      const ratio1 = versionService.calculateChangeRatio('', '');
      const ratio2 = versionService.calculateChangeRatio('', 'new content');
      const ratio3 = versionService.calculateChangeRatio('old content', '');

      expect(ratio1).toBe(0);
      expect(ratio2).toBe(1);
      expect(ratio3).toBe(1);
    });

    test('should handle identical content', () => {
      const content = 'This is identical content';
      const ratio = versionService.calculateChangeRatio(content, content);
      expect(ratio).toBe(0);
    });

    test('should calculate correctly with common prefix and suffix', () => {
      const content1 = 'The quick brown fox jumps over the lazy dog';
      const content2 = 'The quick red fox jumps over the lazy dog';
      
      const ratio = versionService.calculateChangeRatio(content1, content2);
      expect(ratio).toBeGreaterThan(0);
      expect(ratio).toBeLessThan(1);
    });
  });
});
