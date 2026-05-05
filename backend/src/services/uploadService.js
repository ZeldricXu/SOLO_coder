const path = require('path');
const fs = require('fs').promises;
const { v4: uuidv4 } = require('uuid');
const crypto = require('crypto');
const UploadSession = require('../models/UploadSession');
const Media = require('../models/Media');
const Review = require('../models/Review');
const uploadConfig = require('../config/upload');
const storageService = require('./storageService');
const { websocketService, getChannels } = require('./websocketService');
const { queueService, JOB_TYPES, QUEUES } = require('./queueService');

class SessionLockManager {
  constructor() {
    this.locks = new Map();
  }

  acquire(sessionId) {
    if (this.locks.has(sessionId)) {
      return false;
    }
    this.locks.set(sessionId, {
      acquiredAt: Date.now(),
      owner: uuidv4()
    });
    return true;
  }

  release(sessionId) {
    this.locks.delete(sessionId);
  }

  isLocked(sessionId) {
    return this.locks.has(sessionId);
  }

  waitForLock(sessionId, timeout = 30000) {
    return new Promise((resolve, reject) => {
      const startTime = Date.now();
      
      const checkLock = () => {
        if (this.acquire(sessionId)) {
          resolve(true);
          return;
        }
        
        if (Date.now() - startTime > timeout) {
          reject(new Error(`Failed to acquire lock for session: ${sessionId} after ${timeout}ms`));
          return;
        }
        
        setTimeout(checkLock, 100);
      };
      
      checkLock();
    });
  }
}

const sessionLockManager = new SessionLockManager();

class UploadService {
  constructor() {
    this.chunkSize = uploadConfig.chunkSize;
    this.tempDir = uploadConfig.tempDir;
    this.maxFileSize = uploadConfig.maxFileSize;
    this.allowedTypes = uploadConfig.allowedTypes;
    this.sessionLocks = new Map();
  }

  async ensureTempDirExists() {
    try {
      await fs.mkdir(this.tempDir, { recursive: true });
      console.log(`[UploadService] Temp directory ensured: ${this.tempDir}`);
    } catch (error) {
      console.error('Error creating temp directory:', error);
      throw error;
    }
  }

  generateFileId(userId = '') {
    const timestamp = Date.now();
    const randomId = crypto.randomBytes(8).toString('hex');
    const shortUserId = userId ? crypto.createHash('sha256').update(userId).digest('hex').substring(0, 8) : 'anon';
    
    return `upload_${shortUserId}_${timestamp}_${randomId}`;
  }

  generateMediaId(userId = '') {
    const timestamp = Date.now();
    const randomId = crypto.randomBytes(12).toString('hex');
    const shortUserId = userId ? crypto.createHash('sha256').update(userId).digest('hex').substring(0, 6) : 'anon';
    
    return `media_${shortUserId}_${timestamp}_${randomId}`;
  }

  generateSessionDir(fileId, userId = '') {
    const dateStr = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    const userPrefix = userId ? `user_${userId}` : 'anonymous';
    const sessionDir = path.join(this.tempDir, userPrefix, dateStr, fileId);
    
    return {
      sessionDir: sessionDir,
      chunksDir: path.join(sessionDir, 'chunks'),
      mergedDir: path.join(sessionDir, 'merged'),
      thumbsDir: path.join(sessionDir, 'thumbs')
    };
  }

  calculateTotalChunks(fileSize) {
    return Math.ceil(fileSize / this.chunkSize);
  }

  detectFileType(mimeType) {
    if (mimeType.startsWith('image/')) {
      return 'image';
    } else if (mimeType.startsWith('video/')) {
      return 'video';
    } else if (mimeType.startsWith('audio/')) {
      return 'audio';
    }
    return 'other';
  }

  isFileTypeAllowed(mimeType, fileType) {
    const allowedMimeTypes = Object.values(this.allowedTypes).flat();
    return allowedMimeTypes.includes(mimeType);
  }

  async createUploadSession(filename, fileSize, mimeType, userId = '') {
    const fileType = this.detectFileType(mimeType);
    
    if (!this.isFileTypeAllowed(mimeType, fileType)) {
      throw new Error(`File type ${mimeType} is not allowed`);
    }

    if (fileSize > this.maxFileSize) {
      throw new Error(`File size ${fileSize} exceeds maximum allowed size ${this.maxFileSize}`);
    }

    const fileId = this.generateFileId(userId);
    const totalChunks = this.calculateTotalChunks(fileSize);
    const dirs = this.generateSessionDir(fileId, userId);
    
    await fs.mkdir(dirs.sessionDir, { recursive: true });
    await fs.mkdir(dirs.chunksDir, { recursive: true });
    await fs.mkdir(dirs.mergedDir, { recursive: true });
    await fs.mkdir(dirs.thumbsDir, { recursive: true });

    const session = await UploadSession.createSession(
      fileId,
      filename,
      fileSize,
      totalChunks,
      this.chunkSize,
      mimeType,
      fileType,
      dirs.sessionDir,
      userId
    );

    session.chunks_dir = dirs.chunksDir;
    session.merged_dir = dirs.mergedDir;
    session.thumbs_dir = dirs.thumbsDir;
    await session.save();

    console.log(`[UploadService] Session created: ${fileId}, directory: ${dirs.sessionDir}`);

    return {
      success: true,
      file_id: session.file_id,
      total_chunks: session.total_chunks,
      chunk_size: session.chunk_size,
      file_type: session.file_type,
      temp_dir: session.temp_dir
    };
  }

  async getUploadSession(fileId) {
    const session = await UploadSession.findByFileId(fileId);
    
    if (!session) {
      return {
        success: false,
        message: 'Upload session not found'
      };
    }

    return {
      success: true,
      file_id: session.file_id,
      filename: session.filename,
      total_chunks: session.total_chunks,
      uploaded_chunks: session.uploaded_chunks.length,
      progress: session.upload_progress,
      status: session.status,
      missing_chunks: session.getMissingChunks(),
      temp_dir: session.temp_dir
    };
  }

  async validateSessionForChunk(fileId, chunkIndex) {
    const session = await UploadSession.findByFileId(fileId);
    
    if (!session) {
      return {
        valid: false,
        error: 'Upload session not found'
      };
    }

    if (session.status === 'merged' || session.status === 'completed') {
      return {
        valid: false,
        error: 'Upload session already completed'
      };
    }

    if (chunkIndex < 0 || chunkIndex >= session.total_chunks) {
      return {
        valid: false,
        error: `Invalid chunk index: ${chunkIndex}`
      };
    }

    const existingChunk = session.uploaded_chunks.find(c => c.index === chunkIndex);
    if (existingChunk) {
      return {
        valid: true,
        session: session,
        alreadyUploaded: true
      };
    }

    const dirs = {
      chunksDir: session.chunks_dir || path.join(session.temp_dir, 'chunks')
    };

    try {
      await fs.access(dirs.chunksDir);
    } catch (error) {
      return {
        valid: false,
        error: 'Session directory not found'
      };
    }

    return {
      valid: true,
      session: session,
      alreadyUploaded: false,
      dirs: dirs
    };
  }

  generateChunkFilename(chunkIndex) {
    return `chunk_${chunkIndex.toString().padStart(10, '0')}.part`;
  }

  async uploadChunk(fileId, chunkIndex, chunkBuffer, chunkSize) {
    const validation = await this.validateSessionForChunk(fileId, chunkIndex);
    
    if (!validation.valid) {
      return {
        success: false,
        message: validation.error
      };
    }

    if (validation.alreadyUploaded) {
      const session = validation.session;
      return {
        success: true,
        file_id: fileId,
        chunk_index: chunkIndex,
        uploaded_chunks: session.uploaded_chunks.length,
        progress: session.upload_progress,
        message: 'Chunk already uploaded'
      };
    }

    const session = validation.session;
    const dirs = validation.dirs;

    try {
      await sessionLockManager.waitForLock(fileId, 10000);
    } catch (lockError) {
      console.error(`[UploadService] Failed to acquire lock for chunk upload: ${fileId}`, lockError);
      return {
        success: false,
        message: 'Session busy, please retry'
      };
    }

    try {
      const chunkFilename = this.generateChunkFilename(chunkIndex);
      const chunkPath = path.join(dirs.chunksDir, chunkFilename);

      await fs.writeFile(chunkPath, chunkBuffer);
      
      const updatedSession = await UploadSession.addChunk(
        fileId,
        chunkIndex,
        chunkSize,
        chunkPath
      );

      const progress = Math.round((updatedSession.uploaded_chunks.length / updatedSession.total_chunks) * 100);
      
      websocketService.notifyUploadProgress(
        fileId,
        progress,
        updatedSession.uploaded_chunks.length,
        updatedSession.total_chunks
      );

      console.log(`[UploadService] Chunk uploaded: ${fileId} #${chunkIndex}, progress: ${progress}%`);

      return {
        success: true,
        file_id: fileId,
        chunk_index: chunkIndex,
        uploaded_chunks: updatedSession.uploaded_chunks.length,
        progress: progress,
        is_complete: updatedSession.isComplete()
      };

    } catch (error) {
      console.error('Error writing chunk:', error);
      
      try {
        const chunkFilename = this.generateChunkFilename(chunkIndex);
        const chunkPath = path.join(dirs.chunksDir, chunkFilename);
        await fs.unlink(chunkPath).catch(() => {});
      } catch (cleanupError) {
        console.error('Error cleaning up chunk file:', cleanupError);
      }
      
      return {
        success: false,
        message: 'Failed to upload chunk',
        error: error.message
      };
    } finally {
      sessionLockManager.release(fileId);
    }
  }

  async validateSessionForMerge(fileId) {
    const session = await UploadSession.findByFileId(fileId);
    
    if (!session) {
      return {
        valid: false,
        error: 'Upload session not found'
      };
    }

    if (session.status === 'merged' || session.status === 'completed') {
      return {
        valid: false,
        error: 'Upload session already completed'
      };
    }

    const missingChunks = session.getMissingChunks();
    if (missingChunks.length > 0) {
      return {
        valid: false,
        error: 'Missing chunks',
        missing_chunks: missingChunks
      };
    }

    const chunksDir = session.chunks_dir || path.join(session.temp_dir, 'chunks');
    try {
      await fs.access(chunksDir);
    } catch (error) {
      return {
        valid: false,
        error: 'Chunks directory not found'
      };
    }

    return {
      valid: true,
      session: session,
      chunksDir: chunksDir,
      mergedDir: session.merged_dir || path.join(session.temp_dir, 'merged')
    };
  }

  async verifyAllChunks(session, chunksDir) {
    const sortedChunks = session.getSortedChunks();
    
    for (const chunk of sortedChunks) {
      const chunkFilename = this.generateChunkFilename(chunk.index);
      const chunkPath = path.join(chunksDir, chunkFilename);
      
      try {
        const stats = await fs.stat(chunkPath);
        
        if (stats.size !== chunk.size) {
          console.error(`[UploadService] Chunk size mismatch: ${chunkFilename}, expected: ${chunk.size}, actual: ${stats.size}`);
          return {
            valid: false,
            error: `Chunk ${chunk.index} size mismatch`,
            missing_chunks: [chunk.index]
          };
        }
      } catch (error) {
        console.error(`[UploadService] Chunk not found: ${chunkFilename}`, error);
        return {
          valid: false,
          error: `Chunk ${chunk.index} not found`,
          missing_chunks: [chunk.index]
        };
      }
    }
    
    return { valid: true };
  }

  async mergeChunks(fileId, expectedMd5 = null) {
    const validation = await this.validateSessionForMerge(fileId);
    
    if (!validation.valid) {
      return {
        success: false,
        message: validation.error,
        missing_chunks: validation.missing_chunks
      };
    }

    const session = validation.session;
    const chunksDir = validation.chunksDir;
    const mergedDir = validation.mergedDir;

    try {
      await sessionLockManager.waitForLock(fileId, 30000);
    } catch (lockError) {
      console.error(`[UploadService] Failed to acquire lock for merge: ${fileId}`, lockError);
      return {
        success: false,
        message: 'Session busy, please retry'
      };
    }

    try {
      const verification = await this.verifyAllChunks(session, chunksDir);
      if (!verification.valid) {
        return {
          success: false,
          message: verification.error,
          missing_chunks: verification.missing_chunks
        };
      }

      const sortedChunks = session.getSortedChunks();
      const mergedFilename = `merged_${session.file_id}_${Date.now()}.tmp`;
      const mergedPath = path.join(mergedDir, mergedFilename);

      const writeStream = require('fs').createWriteStream(mergedPath);
      const hash = crypto.createHash('md5');

      console.log(`[UploadService] Starting merge for session: ${fileId}, chunks: ${sortedChunks.length}`);

      for (const chunk of sortedChunks) {
        const chunkFilename = this.generateChunkFilename(chunk.index);
        const chunkPath = path.join(chunksDir, chunkFilename);
        
        const chunkBuffer = await fs.readFile(chunkPath);
        writeStream.write(chunkBuffer);
        hash.update(chunkBuffer);
      }

      writeStream.end();
      
      const actualMd5 = hash.digest('hex');

      if (expectedMd5 && actualMd5 !== expectedMd5) {
        await fs.unlink(mergedPath).catch(() => {});
        return {
          success: false,
          message: 'MD5 checksum verification failed',
          expected: expectedMd5,
          actual: actualMd5
        };
      }

      const stats = await fs.stat(mergedPath);
      
      if (stats.size !== session.total_size) {
        await fs.unlink(mergedPath).catch(() => {});
        return {
          success: false,
          message: 'File size mismatch after merge',
          expected: session.total_size,
          actual: stats.size
        };
      }

      await UploadSession.markMerged(fileId);

      const storagePathInfo = storageService.generateStoragePath(
        session.file_type,
        session.filename
      );

      const uploadResult = await storageService.uploadFromPath(
        mergedPath,
        storagePathInfo.path,
        session.mime_type
      );

      if (!uploadResult.success) {
        return {
          success: false,
          message: 'Failed to upload to storage'
        };
      }

      const mediaId = this.generateMediaId(session.user_id);
      
      const media = new Media({
        media_id: mediaId,
        filename: session.filename,
        file_type: session.file_type,
        file_size: session.total_size,
        mime_type: session.mime_type,
        metadata: {},
        tags: [],
        folder_id: '',
        status: 'processing',
        upload_progress: 100,
        storage_path: storagePathInfo.path,
        file_id: session.file_id
      });

      await media.save();

      const mediaProcessingJob = await queueService.addJob(
        QUEUES.MEDIA_PROCESSING,
        JOB_TYPES.MEDIA_PROCESS,
        {
          mediaId: mediaId,
          fileId: fileId,
          localFilePath: mergedPath,
          sessionTempDir: session.temp_dir,
          thumbsDir: session.thumbs_dir || path.join(session.temp_dir, 'thumbs'),
          fileType: session.file_type,
          mimeType: session.mime_type,
          filename: session.filename
        },
        {
          priority: session.file_type === 'video' ? 1 : 2,
          maxRetries: 2
        }
      );

      console.log(`[UploadService] Media processing job queued: ${mediaProcessingJob} for media: ${mediaId}`);

      websocketService.notifyUploadCompleted(fileId, mediaId);

      await UploadSession.markCompleted(fileId);

      console.log(`[UploadService] Merge completed: ${fileId} -> media: ${mediaId}`);

      return {
        success: true,
        media_id: mediaId,
        file_id: fileId,
        storage_path: storagePathInfo.path,
        md5: actualMd5,
        size: stats.size,
        status: 'processing',
        job_id: mediaProcessingJob
      };

    } catch (error) {
      console.error('Error merging chunks:', error);
      
      try {
        const mergedFilename = `merged_${session.file_id}_*.tmp`;
        const files = await fs.readdir(mergedDir).catch(() => []);
        for (const file of files) {
          if (file.startsWith(`merged_${session.file_id}`)) {
            await fs.unlink(path.join(mergedDir, file)).catch(() => {});
          }
        }
      } catch (cleanupError) {
        console.error('Error cleaning up merged file:', cleanupError);
      }

      return {
        success: false,
        message: 'Failed to merge chunks',
        error: error.message
      };
    } finally {
      sessionLockManager.release(fileId);
    }
  }

  async cleanupSession(session) {
    const sessionDir = session.temp_dir;
    
    console.log(`[UploadService] Cleaning up session: ${session.file_id}, directory: ${sessionDir}`);
    
    try {
      if (session.uploaded_chunks) {
        for (const chunk of session.uploaded_chunks) {
          await fs.unlink(chunk.temp_path).catch(() => {});
        }
      }
      
      try {
        await fs.rm(sessionDir, { recursive: true, force: true });
        console.log(`[UploadService] Session directory removed: ${sessionDir}`);
      } catch (rmError) {
        console.warn(`[UploadService] Failed to remove session directory: ${sessionDir}`, rmError);
      }
      
    } catch (error) {
      console.error('Error cleaning up session:', error);
    }
  }

  async cancelUpload(fileId) {
    const session = await UploadSession.findByFileId(fileId);
    
    if (!session) {
      return {
        success: false,
        message: 'Upload session not found'
      };
    }

    try {
      await sessionLockManager.waitForLock(fileId, 5000);
    } catch (lockError) {
      console.error(`[UploadService] Failed to acquire lock for cancel: ${fileId}`, lockError);
    }

    try {
      await this.cleanupSession(session);
      await UploadSession.markFailed(fileId);

      console.log(`[UploadService] Upload cancelled: ${fileId}`);

      return {
        success: true,
        message: 'Upload cancelled and cleaned up'
      };
    } finally {
      sessionLockManager.release(fileId);
    }
  }

  async getChunkStatus(fileId) {
    const session = await UploadSession.findByFileId(fileId);
    
    if (!session) {
      return {
        success: false,
        message: 'Upload session not found'
      };
    }

    return {
      success: true,
      file_id: session.file_id,
      total_chunks: session.total_chunks,
      uploaded_chunks: session.uploaded_chunks.length,
      missing_chunks: session.getMissingChunks(),
      progress: session.upload_progress,
      status: session.status,
      temp_dir: session.temp_dir
    };
  }
}

const uploadService = new UploadService();

module.exports = {
  uploadService,
  UploadService,
  sessionLockManager
};
