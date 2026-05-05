const Version = require('../models/Version');
const Document = require('../models/Document');
const { v4: uuidv4 } = require('uuid');
const crypto = require('crypto');
const _ = require('lodash');

class VersionService {
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

  async tryMergeAndSnapshot(doc_id, user_id) {
    const changes = this.pendingChanges.get(doc_id) || [];
    
    if (changes.length === 0) {
      return null;
    }

    const lastChange = changes[changes.length - 1];
    
    if (!this.isSignificantChange(doc_id, lastChange.content, lastChange.title)) {
      console.log(`Skipping snapshot for ${doc_id}: changes are insignificant`);
      return null;
    }

    const doc = await Document.findOne({ doc_id });
    if (!doc) {
      throw new Error(`Document not found: ${doc_id}`);
    }

    const lastVersion = await Version.findOne({ doc_id })
      .sort({ version_number: -1 })
      .exec();

    const currentHash = this.generateContentHash(lastChange.content);
    
    if (lastVersion) {
      const lastHash = this.generateContentHash(lastVersion.content_snapshot);
      if (currentHash === lastHash && lastVersion.title_snapshot === lastChange.title) {
        return null;
      }
    }

    const mergeSummary = this.createMergeSummary(changes, doc_id);

    const newVersionNumber = lastVersion ? lastVersion.version_number + 1 : 1;

    const version = new Version({
      version_id: uuidv4(),
      doc_id,
      version_number: newVersionNumber,
      content_snapshot: lastChange.content,
      title_snapshot: lastChange.title,
      edited_by: lastChange.user_id || user_id,
      edit_summary: mergeSummary,
      merged_changes_count: changes.length,
      merged_time_range: {
        start: changes[0].timestamp,
        end: changes[changes.length - 1].timestamp
      },
      created_at: new Date()
    });

    await version.save();

    doc.current_version = newVersionNumber;
    await doc.save();

    this.lastSnapshotTimestamps.set(doc_id, new Date());
    this.lastSnapshotContent.set(doc_id, {
      content: lastChange.content,
      title: lastChange.title
    });

    this.pendingChanges.set(doc_id, []);

    console.log(`Created merged version ${newVersionNumber} for document ${doc_id} (merged ${changes.length} changes)`);
    return version;
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

  async createSnapshot(doc_id, user_id, edit_summary = '', force = false) {
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      throw new Error(`Document not found: ${doc_id}`);
    }

    const currentHash = this.generateContentHash(doc.content);

    if (!force) {
      const { shouldMerge, pendingCount } = this.recordChange(
        doc_id, 
        user_id, 
        doc.content, 
        doc.title, 
        edit_summary
      );

      if (!shouldMerge) {
        console.log(`Deferring snapshot for ${doc_id}: ${pendingCount} changes accumulated`);
        return null;
      }
    }

    const lastVersion = await Version.findOne({ doc_id })
      .sort({ version_number: -1 })
      .exec();

    if (lastVersion && !force) {
      const lastHash = this.generateContentHash(lastVersion.content_snapshot);
      if (currentHash === lastHash && lastVersion.title_snapshot === doc.title) {
        return null;
      }
    }

    const newVersionNumber = lastVersion ? lastVersion.version_number + 1 : 1;

    const version = new Version({
      version_id: uuidv4(),
      doc_id,
      version_number: newVersionNumber,
      content_snapshot: doc.content,
      title_snapshot: doc.title,
      edited_by: user_id,
      edit_summary: edit_summary || 'Manual snapshot',
      created_at: new Date()
    });

    await version.save();

    doc.current_version = newVersionNumber;
    await doc.save();

    this.lastSnapshotTimestamps.set(doc_id, new Date());
    this.lastSnapshotContent.set(doc_id, {
      content: doc.content,
      title: doc.title
    });
    this.pendingChanges.set(doc_id, []);

    console.log(`Created version ${newVersionNumber} for document ${doc_id}`);
    return version;
  }

  async getVersions(doc_id, page = 1, limit = 20) {
    const skip = (page - 1) * limit;
    
    const versions = await Version.find({ doc_id })
      .sort({ version_number: -1 })
      .skip(skip)
      .limit(limit)
      .exec();
    
    const total = await Version.countDocuments({ doc_id });
    
    return {
      versions: versions.map(v => v.toObject()),
      total,
      page,
      limit,
      total_pages: Math.ceil(total / limit)
    };
  }

  async getVersion(doc_id, version_number) {
    const version = await Version.findOne({ doc_id, version_number });
    
    if (!version) {
      throw new Error(`Version ${version_number} not found for document ${doc_id}`);
    }
    
    return version.toObject();
  }

  async restoreVersion(doc_id, version_number, user_id) {
    const version = await this.getVersion(doc_id, version_number);
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      throw new Error(`Document not found: ${doc_id}`);
    }
    
    const originalContent = doc.content;
    const originalTitle = doc.title;
    
    doc.content = version.content_snapshot;
    doc.title = version.title_snapshot;
    doc.last_edited_by = user_id;
    doc.last_edited_at = new Date();
    
    await doc.save();
    
    await this.createSnapshot(
      doc_id,
      user_id,
      `Restored to version ${version_number}`,
      true
    );
    
    console.log(`Restored document ${doc_id} to version ${version_number}`);
    
    return {
      doc_id,
      version_number,
      restored_content: doc.content,
      restored_title: doc.title
    };
  }

  async compareVersions(doc_id, version_number_1, version_number_2) {
    const v1 = await this.getVersion(doc_id, version_number_1);
    const v2 = await this.getVersion(doc_id, version_number_2);
    
    const diff = this.computeDiff(
      v1.content_snapshot,
      v2.content_snapshot
    );
    
    return {
      doc_id,
      version_1: {
        number: version_number_1,
        title: v1.title_snapshot,
        edited_by: v1.edited_by,
        created_at: v1.created_at
      },
      version_2: {
        number: version_number_2,
        title: v2.title_snapshot,
        edited_by: v2.edited_by,
        created_at: v2.created_at
      },
      diff
    };
  }

  computeDiff(text1, text2) {
    const lines1 = (text1 || '').split('\n');
    const lines2 = (text2 || '').split('\n');
    
    const maxLen = Math.max(lines1.length, lines2.length);
    const changes = [];
    
    for (let i = 0; i < maxLen; i++) {
      const line1 = lines1[i] || '';
      const line2 = lines2[i] || '';
      
      if (i >= lines1.length) {
        changes.push({
          type: 'added',
          line_number: i + 1,
          content: line2
        });
      } else if (i >= lines2.length) {
        changes.push({
          type: 'removed',
          line_number: i + 1,
          content: line1
        });
      } else if (line1 !== line2) {
        changes.push({
          type: 'modified',
          line_number: i + 1,
          original: line1,
          modified: line2
        });
      }
    }
    
    return changes;
  }

  startPeriodicSnapshot(doc_id, user_id) {
    if (this.snapshotIntervals.has(doc_id)) {
      return;
    }
    
    const intervalId = setInterval(async () => {
      try {
        await this.tryMergeAndSnapshot(doc_id, user_id);
      } catch (error) {
        console.error(`Auto-snapshot failed for ${doc_id}:`, error);
      }
    }, this.SNAPSHOT_INTERVAL_MS);
    
    this.snapshotIntervals.set(doc_id, intervalId);
  }

  stopPeriodicSnapshot(doc_id) {
    const intervalId = this.snapshotIntervals.get(doc_id);
    if (intervalId) {
      clearInterval(intervalId);
      this.snapshotIntervals.delete(doc_id);
    }
  }

  async cleanupOldVersions(doc_id, keepRecent = 100, keepKeyVersions = true) {
    const versions = await Version.find({ doc_id })
      .sort({ version_number: -1 })
      .exec();
    
    if (versions.length <= keepRecent) {
      return 0;
    }

    let toDelete = [];
    
    if (keepKeyVersions) {
      const keyVersions = this.identifyKeyVersions(versions);
      
      toDelete = versions.filter((v, index) => {
        if (index < keepRecent) return false;
        if (keyVersions.has(v.version_number)) return false;
        return true;
      });
    } else {
      toDelete = versions.slice(keepRecent);
    }
    
    if (toDelete.length === 0) {
      return 0;
    }

    const deleteIds = toDelete.map(v => v.version_id);
    
    await Version.deleteMany({ version_id: { $in: deleteIds } });
    
    console.log(`Cleaned up ${deleteIds.length} old versions for ${doc_id}`);
    return deleteIds.length;
  }

  identifyKeyVersions(versions) {
    const keyVersions = new Set();
    
    if (versions.length === 0) return keyVersions;

    keyVersions.add(versions[0].version_number);
    keyVersions.add(versions[versions.length - 1].version_number);

    const contentGroups = this.groupSimilarVersions(versions);
    
    for (const group of contentGroups) {
      if (group.versions.length > 0) {
        keyVersions.add(group.versions[0].version_number);
      }
    }

    for (const version of versions) {
      if (version.merged_changes_count && version.merged_changes_count >= 10) {
        keyVersions.add(version.version_number);
      }
    }

    return keyVersions;
  }

  groupSimilarVersions(versions) {
    const groups = [];
    if (versions.length === 0) return groups;

    let currentGroup = {
      startVersion: versions[0],
      versions: [versions[0]],
      hash: this.generateContentHash(versions[0].content_snapshot)
    };

    for (let i = 1; i < versions.length; i++) {
      const version = versions[i];
      const hash = this.generateContentHash(version.content_snapshot);
      
      const changeRatio = this.calculateChangeRatio(
        currentGroup.startVersion.content_snapshot,
        version.content_snapshot
      );

      if (changeRatio >= 0.2) {
        groups.push(currentGroup);
        currentGroup = {
          startVersion: version,
          versions: [version],
          hash
        };
      } else {
        currentGroup.versions.push(version);
      }
    }

    if (currentGroup.versions.length > 0) {
      groups.push(currentGroup);
    }

    return groups;
  }

  async getPendingChangeCount(doc_id) {
    const changes = this.pendingChanges.get(doc_id) || [];
    return changes.length;
  }

  async flushAllPending(doc_id, user_id) {
    return await this.tryMergeAndSnapshot(doc_id, user_id);
  }
}

module.exports = new VersionService();
