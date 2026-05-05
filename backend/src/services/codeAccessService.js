const commitModel = require('../models/commitModel');
const logger = require('../config/logger');
const path = require('path');
const fs = require('fs-extra');
const { defaultQueue, EVENT_TYPES, EVENT_PRIORITY } = require('../queue');

const codeAccessService = {
  detectLanguage(filePath) {
    const ext = path.extname(filePath).toLowerCase();
    
    const languageMap = {
      '.js': 'javascript',
      '.jsx': 'javascript',
      '.ts': 'typescript',
      '.tsx': 'typescript',
      '.py': 'python',
      '.pyw': 'python',
      '.java': 'java',
      '.c': 'c',
      '.cpp': 'cpp',
      '.cc': 'cpp',
      '.h': 'c',
      '.hpp': 'cpp',
      '.cs': 'csharp',
      '.php': 'php',
      '.rb': 'ruby',
      '.go': 'go',
      '.rs': 'rust',
      '.swift': 'swift',
      '.kt': 'kotlin',
      '.dart': 'dart',
      '.scala': 'scala',
      '.sh': 'shell',
      '.bash': 'shell',
      '.html': 'html',
      '.css': 'css',
      '.scss': 'scss',
      '.sass': 'sass',
      '.less': 'less',
      '.vue': 'vue',
      '.json': 'json',
      '.xml': 'xml',
      '.yaml': 'yaml',
      '.yml': 'yaml',
      '.md': 'markdown'
    };
    
    return languageMap[ext] || 'unknown';
  },

  determineStatus(oldContent, newContent) {
    if (!oldContent && newContent) {
      return 'added';
    }
    if (oldContent && !newContent) {
      return 'deleted';
    }
    return 'modified';
  },

  async handleCommitEvent(eventData) {
    try {
      const { commit_id, repo_id, author, message, commit_time, files } = eventData;
      
      const commit = await commitModel.create({
        commit_id,
        repo_id,
        author,
        message,
        commit_time: commit_time ? new Date(commit_time) : new Date()
      });
      
      const changedFiles = [];
      
      if (files && Array.isArray(files)) {
        for (const file of files) {
          const language = this.detectLanguage(file.file_path);
          const status = this.determineStatus(file.old_content, file.file_content);
          
          const changedFile = await commitModel.addChangedFile(commit_id, {
            file_path: file.file_path,
            file_content: file.file_content,
            old_content: file.old_content,
            file_type: 'source',
            language,
            status
          });
          
          changedFiles.push(changedFile);
        }
      }
      
      logger.info('代码提交事件处理完成: commit_id=%s, files=%d', commit_id, changedFiles.length);
      
      return {
        commit,
        changedFiles
      };
    } catch (error) {
      logger.error('处理代码提交事件失败: %s', error.message);
      throw error;
    }
  },

  async submitCommitAsync(eventData) {
    try {
      const { commit_id, repo_id, priority } = eventData;
      
      if (!commit_id || !repo_id) {
        throw new Error('缺少必要参数: commit_id 或 repo_id');
      }
      
      const eventPriority = this.mapPriority(priority);
      
      const event = defaultQueue.createEvent(
        EVENT_TYPES.CODE_COMMIT,
        eventData,
        eventPriority
      );
      
      const eventId = await defaultQueue.push(event);
      
      logger.info('代码提交已异步排队: commit_id=%s, event_id=%s, priority=%d',
        commit_id, eventId, eventPriority);
      
      return {
        success: true,
        event_id: eventId,
        commit_id,
        status: 'queued',
        message: '代码提交已加入分析队列'
      };
    } catch (error) {
      logger.error('异步提交代码失败: %s', error.message);
      throw error;
    }
  },

  mapPriority(priority) {
    const priorityMap = {
      'high': EVENT_PRIORITY.HIGH,
      'critical': EVENT_PRIORITY.HIGH,
      'medium': EVENT_PRIORITY.MEDIUM,
      'normal': EVENT_PRIORITY.MEDIUM,
      'low': EVENT_PRIORITY.LOW
    };
    
    return priorityMap[priority] || EVENT_PRIORITY.MEDIUM;
  },

  async getEventStatus(eventId) {
    try {
      const queueStats = await defaultQueue.getStats();
      const pendingEvents = await defaultQueue.getPendingEvents(100);
      const processingEvents = await defaultQueue.getProcessingEvents();
      const failedEvents = await defaultQueue.getFailedEvents(100);
      
      const pending = pendingEvents.find(e => e.event_id === eventId);
      const processing = processingEvents.find(e => e.event_id === eventId);
      const failed = failedEvents.find(e => e.event_id === eventId);
      
      if (pending) {
        return {
          event_id: eventId,
          status: 'pending',
          position: pendingEvents.indexOf(pending),
          queue_size: queueStats.pending
        };
      }
      
      if (processing) {
        return {
          event_id: eventId,
          status: 'processing',
          started_at: processing.processed_at
        };
      }
      
      if (failed) {
        return {
          event_id: eventId,
          status: 'failed',
          error: failed.error,
          retries: failed.retries
        };
      }
      
      return {
        event_id: eventId,
        status: 'unknown',
        message: '事件状态未知，可能已完成或不存在'
      };
    } catch (error) {
      logger.error('获取事件状态失败: %s', error.message);
      throw error;
    }
  },

  async getQueueStats() {
    try {
      return await defaultQueue.getStats();
    } catch (error) {
      logger.error('获取队列统计失败: %s', error.message);
      throw error;
    }
  },

  async retryFailedEvent(eventId) {
    try {
      const success = await defaultQueue.retry(eventId);
      
      if (success) {
        logger.info('事件已重新加入队列: event_id=%s', eventId);
        return {
          success: true,
          event_id: eventId,
          status: 'requeued'
        };
      }
      
      return {
        success: false,
        event_id: eventId,
        status: 'not_found'
      };
    } catch (error) {
      logger.error('重试事件失败: %s', error.message);
      throw error;
    }
  },

  async getCommitWithFiles(commit_id) {
    try {
      const commit = await commitModel.findById(commit_id);
      if (!commit) {
        throw new Error(`提交不存在: ${commit_id}`);
      }
      
      const changedFiles = await commitModel.getChangedFiles(commit_id);
      
      return {
        ...commit,
        files: changedFiles
      };
    } catch (error) {
      logger.error('获取提交详情失败: %s', error.message);
      throw error;
    }
  },

  async getFileContent(commit_id, file_path) {
    try {
      const file = await commitModel.getFileByPath(commit_id, file_path);
      
      if (!file) {
        throw new Error(`文件不存在: ${commit_id}/${file_path}`);
      }
      
      return {
        file_path: file.file_path,
        file_content: file.file_content,
        old_content: file.old_content,
        language: file.language,
        status: file.status
      };
    } catch (error) {
      logger.error('获取文件内容失败: %s', error.message);
      throw error;
    }
  },

  async getFileDiff(commit_id, file_path) {
    try {
      const file = await this.getFileContent(commit_id, file_path);
      
      const diff = this.generateSimpleDiff(
        file.old_content || '',
        file.file_content || ''
      );
      
      return {
        file_path: file.file_path,
        language: file.language,
        status: file.status,
        old_content: file.old_content,
        new_content: file.file_content,
        diff
      };
    } catch (error) {
      logger.error('生成文件差异失败: %s', error.message);
      throw error;
    }
  },

  generateSimpleDiff(oldText, newText) {
    if (!oldText && newText) {
      const lines = newText.split('\n');
      return lines.map((line, index) => ({
        type: 'added',
        lineNumber: index + 1,
        content: line
      }));
    }
    
    if (oldText && !newText) {
      const lines = oldText.split('\n');
      return lines.map((line, index) => ({
        type: 'removed',
        lineNumber: index + 1,
        content: line
      }));
    }
    
    const oldLines = oldText.split('\n');
    const newLines = newText.split('\n');
    const diff = [];
    
    let oldIndex = 0;
    let newIndex = 0;
    
    while (oldIndex < oldLines.length || newIndex < newLines.length) {
      if (oldIndex >= oldLines.length) {
        diff.push({
          type: 'added',
          lineNumber: newIndex + 1,
          content: newLines[newIndex]
        });
        newIndex++;
      } else if (newIndex >= newLines.length) {
        diff.push({
          type: 'removed',
          lineNumber: oldIndex + 1,
          content: oldLines[oldIndex]
        });
        oldIndex++;
      } else if (oldLines[oldIndex] === newLines[newIndex]) {
        diff.push({
          type: 'unchanged',
          lineNumber: oldIndex + 1,
          newLineNumber: newIndex + 1,
          content: oldLines[oldIndex]
        });
        oldIndex++;
        newIndex++;
      } else {
        let foundMatch = false;
        
        for (let lookAhead = 1; lookAhead <= 5 && oldIndex + lookAhead < oldLines.length; lookAhead++) {
          if (oldLines[oldIndex + lookAhead] === newLines[newIndex]) {
            for (let i = 0; i < lookAhead; i++) {
              diff.push({
                type: 'removed',
                lineNumber: oldIndex + i + 1,
                content: oldLines[oldIndex + i]
              });
            }
            oldIndex += lookAhead;
            foundMatch = true;
            break;
          }
        }
        
        if (!foundMatch) {
          for (let lookAhead = 1; lookAhead <= 5 && newIndex + lookAhead < newLines.length; lookAhead++) {
            if (newLines[newIndex + lookAhead] === oldLines[oldIndex]) {
              for (let i = 0; i < lookAhead; i++) {
                diff.push({
                  type: 'added',
                  lineNumber: newIndex + i + 1,
                  content: newLines[newIndex + i]
                });
              }
              newIndex += lookAhead;
              foundMatch = true;
              break;
            }
          }
        }
        
        if (!foundMatch) {
          diff.push({
            type: 'removed',
            lineNumber: oldIndex + 1,
            content: oldLines[oldIndex]
          });
          diff.push({
            type: 'added',
            lineNumber: newIndex + 1,
            content: newLines[newIndex]
          });
          oldIndex++;
          newIndex++;
        }
      }
    }
    
    return diff;
  },

  async getCommitsByRepo(repo_id, limit = 100) {
    try {
      return await commitModel.findByRepoId(repo_id, limit);
    } catch (error) {
      logger.error('获取仓库提交记录失败: %s', error.message);
      throw error;
    }
  },

  validateLanguageSupport(language) {
    const supportedLanguages = [
      'javascript', 'typescript', 'python', 'java', 'c', 'cpp',
      'csharp', 'php', 'ruby', 'go', 'rust', 'swift', 'kotlin'
    ];
    
    return supportedLanguages.includes(language);
  }
};

module.exports = codeAccessService;
