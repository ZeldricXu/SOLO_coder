const ffmpeg = require('fluent-ffmpeg');
const path = require('path');
const fs = require('fs').promises;
const { v4: uuidv4 } = require('uuid');
const storageService = require('./storageService');
const Media = require('../models/Media');
const Review = require('../models/Review');
const UploadSession = require('../models/UploadSession');
const { websocketService } = require('./websocketService');
const { uploadService } = require('./uploadService');

class MediaProcessingWorker {
  constructor() {
    ffmpeg.setFfmpegPath(process.env.FFMPEG_PATH || 'ffmpeg');
    ffmpeg.setFfprobePath(process.env.FFPROBE_PATH || 'ffprobe');
  }

  async execute(job, helpers) {
    const { mediaId, localFilePath, thumbsDir, fileType, mimeType, filename, sessionTempDir, fileId } = job.payload;
    
    console.log(`[MediaProcessingWorker] Starting processing for media: ${mediaId}, file: ${filename}`);
    
    try {
      const media = await Media.findByMediaId(mediaId);
      if (!media) {
        throw new Error(`Media not found: ${mediaId}`);
      }

      helpers.updateProgress(10);
      websocketService.notifyMediaProgress(mediaId, 10, 'extracting_metadata');

      const metadata = await this.extractMetadata(localFilePath, fileType);
      
      helpers.updateProgress(40);
      websocketService.notifyMediaProgress(mediaId, 40, 'generating_thumbnail');

      let thumbnailUrl = null;
      if (fileType === 'video' || fileType === 'image') {
        try {
          const thumbnailResult = await this.generateThumbnail(
            localFilePath,
            fileType,
            thumbsDir
          );
          
          if (thumbnailResult.success) {
            const thumbStoragePath = storageService.generateStoragePath(
              'thumbnails',
              thumbnailResult.filename
            );
            
            const uploadResult = await storageService.uploadFromPath(
              thumbnailResult.path,
              thumbStoragePath.path,
              'image/jpeg'
            );
            
            if (uploadResult.success) {
              metadata.thumbnail = thumbStoragePath.path;
              thumbnailUrl = thumbStoragePath.path;
            }
            
            await fs.unlink(thumbnailResult.path).catch(() => {});
          }
        } catch (thumbError) {
          console.warn(`[MediaProcessingWorker] Thumbnail generation failed for media: ${mediaId}`, thumbError);
        }
      }

      helpers.updateProgress(80);
      websocketService.notifyMediaProgress(mediaId, 80, 'saving_metadata');

      media.metadata = metadata;
      media.status = 'pending_review';
      await media.save();

      helpers.updateProgress(90);
      websocketService.notifyMediaProgress(mediaId, 90, 'creating_review_task');

      await Review.createReview(mediaId, 'system', 'medium');

      if (sessionTempDir) {
        try {
          const session = await UploadSession.findByFileId(fileId);
          if (session) {
            await uploadService.cleanupSession(session);
          }
        } catch (cleanupError) {
          console.warn(`[MediaProcessingWorker] Session cleanup error: ${cleanupError.message}`);
        }
      }

      helpers.updateProgress(100);
      websocketService.notifyMediaCompleted(mediaId, metadata, thumbnailUrl);

      console.log(`[MediaProcessingWorker] Processing completed for media: ${mediaId}`);

      return {
        success: true,
        mediaId: mediaId,
        metadata: metadata,
        thumbnailUrl: thumbnailUrl,
        status: 'pending_review'
      };

    } catch (error) {
      console.error(`[MediaProcessingWorker] Processing failed for media: ${mediaId}`, error);
      
      try {
        const media = await Media.findByMediaId(mediaId);
        if (media) {
          media.status = 'failed';
          await media.save();
        }
      } catch (saveError) {
        console.error(`[MediaProcessingWorker] Failed to update media status: ${saveError.message}`);
      }

      websocketService.notifyMediaFailed(mediaId, error.message);
      
      throw error;
    }
  }

  async probeMedia(filePath) {
    return new Promise((resolve, reject) => {
      ffmpeg.ffprobe(filePath, (err, metadata) => {
        if (err) {
          reject(err);
        } else {
          resolve(metadata);
        }
      });
    });
  }

  async extractMetadata(filePath, fileType) {
    try {
      const metadata = {
        duration: 0,
        width: 0,
        height: 0,
        bitrate: 0,
        thumbnail: ''
      };

      if (fileType === 'other') {
        return metadata;
      }

      const probeData = await this.probeMedia(filePath);
      
      if (!probeData || !probeData.streams) {
        return metadata;
      }

      if (probeData.format) {
        metadata.duration = parseFloat(probeData.format.duration) || 0;
        metadata.bitrate = parseInt(probeData.format.bit_rate) || 0;
      }

      for (const stream of probeData.streams) {
        if (stream.codec_type === 'video') {
          metadata.width = stream.width || 0;
          metadata.height = stream.height || 0;
          break;
        }
      }

      return metadata;
    } catch (error) {
      console.error('Error extracting metadata:', error);
      return {
        duration: 0,
        width: 0,
        height: 0,
        bitrate: 0,
        thumbnail: ''
      };
    }
  }

  async generateThumbnail(filePath, fileType, outputDir) {
    return new Promise(async (resolve, reject) => {
      const thumbnailFilename = `thumb_${uuidv4()}.jpg`;
      const thumbnailPath = path.join(outputDir, thumbnailFilename);

      try {
        await fs.mkdir(outputDir, { recursive: true });

        if (fileType === 'video') {
          const probeData = await this.probeMedia(filePath);
          const duration = parseFloat(probeData.format?.duration) || 0;
          const seekTime = Math.min(duration * 0.1, 5);

          ffmpeg(filePath)
            .screenshots({
              count: 1,
              folder: outputDir,
              filename: thumbnailFilename,
              size: '320x240',
              timemarks: [seekTime]
            })
            .on('end', () => {
              resolve({
                success: true,
                path: thumbnailPath,
                filename: thumbnailFilename
              });
            })
            .on('error', (err) => {
              console.error('Error generating video thumbnail:', err);
              reject(err);
            });
        } else if (fileType === 'image') {
          ffmpeg(filePath)
            .output(thumbnailPath)
            .size('320x240')
            .autoPad()
            .on('end', () => {
              resolve({
                success: true,
                path: thumbnailPath,
                filename: thumbnailFilename
              });
            })
            .on('error', (err) => {
              console.error('Error generating image thumbnail:', err);
              reject(err);
            })
            .run();
        } else {
          resolve({
            success: false,
            message: 'Unsupported file type for thumbnail generation'
          });
        }
      } catch (error) {
        console.error('Error in thumbnail generation:', error);
        reject(error);
      }
    });
  }

  async convertToPreview(videoPath, outputPath, options = {}) {
    return new Promise((resolve, reject) => {
      const {
        width = 720,
        height = -1,
        bitrate = '1M',
        format = 'mp4'
      } = options;

      ffmpeg(videoPath)
        .output(outputPath)
        .videoCodec('libx264')
        .audioCodec('aac')
        .size(`${width}x${height}`)
        .videoBitrate(bitrate)
        .format(format)
        .on('end', () => {
          resolve({
            success: true,
            path: outputPath
          });
        })
        .on('error', (err) => {
          console.error('Error converting video to preview:', err);
          reject(err);
        })
        .run();
    });
  }

  async extractAudio(videoPath, outputPath) {
    return new Promise((resolve, reject) => {
      ffmpeg(videoPath)
        .output(outputPath)
        .noVideo()
        .audioCodec('libmp3lame')
        .audioBitrate('128k')
        .format('mp3')
        .on('end', () => {
          resolve({
            success: true,
            path: outputPath
          });
        })
        .on('error', (err) => {
          console.error('Error extracting audio:', err);
          reject(err);
        })
        .run();
    });
  }

  async getVideoFrames(videoPath, outputDir, count = 10) {
    return new Promise((resolve, reject) => {
      ffmpeg(videoPath)
        .screenshots({
          count: count,
          folder: outputDir,
          filename: 'frame_%04d.jpg',
          size: '640x360'
        })
        .on('end', () => {
          resolve({
            success: true,
            outputDir: outputDir,
            count: count
          });
        })
        .on('error', (err) => {
          console.error('Error extracting video frames:', err);
          reject(err);
        });
    });
  }

  async addWatermark(inputPath, outputPath, watermarkPath, position = 'bottomright') {
    return new Promise((resolve, reject) => {
      const positions = {
        topleft: '10:10',
        topright: 'main_w-overlay_w-10:10',
        bottomleft: '10:main_h-overlay_h-10',
        bottomright: 'main_w-overlay_w-10:main_h-overlay_h-10',
        center: '(main_w-overlay_w)/2:(main_h-overlay_h)/2'
      };

      const positionString = positions[position] || positions.bottomright;

      ffmpeg(inputPath)
        .input(watermarkPath)
        .complexFilter([
          `overlay=${positionString}`
        ])
        .output(outputPath)
        .on('end', () => {
          resolve({
            success: true,
            path: outputPath
          });
        })
        .on('error', (err) => {
          console.error('Error adding watermark:', err);
          reject(err);
        })
        .run();
    });
  }
}

const mediaProcessingWorker = new MediaProcessingWorker();

class MediaService {
  constructor() {
    this.worker = mediaProcessingWorker;
  }

  detectFileType(mimeType) {
    return this.worker.detectFileType ? 
      this.worker.detectFileType(mimeType) : 
      mediaProcessingWorker.detectFileType(mimeType);
  }

  async processMedia(localFilePath, fileType, mimeType, tempDir) {
    return this.worker.execute({
      payload: {
        localFilePath,
        fileType,
        mimeType,
        thumbsDir: tempDir
      }
    }, {
      updateProgress: () => {}
    });
  }
}

const mediaService = new MediaService();

module.exports = {
  mediaService,
  MediaService,
  mediaProcessingWorker,
  MediaProcessingWorker
};
