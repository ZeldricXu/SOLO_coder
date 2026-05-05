const Media = require('../models/Media');
const storageService = require('../services/storageService');

class MediaController {
  async getMediaList(req, res) {
    try {
      const { query, file_type, status, page = 1, limit = 20, media_ids } = req.query;
      
      const pageNum = parseInt(page) || 1;
      const limitNum = parseInt(limit) || 20;
      
      let mediaIdsArray = null;
      if (media_ids) {
        mediaIdsArray = media_ids.split(',').map(id => id.trim()).filter(Boolean);
      }

      const mediaList = await Media.search(
        query,
        file_type,
        status,
        pageNum,
        limitNum,
        mediaIdsArray
      );

      const total = await Media.countDocuments({});

      const mediaWithUrls = [];
      for (const media of mediaList) {
        const mediaObj = media.toObject();
        
        try {
          if (mediaObj.storage_path) {
            mediaObj.presigned_url = await storageService.getPresignedUrl(
              mediaObj.storage_path,
              3600
            );
          }
          
          if (mediaObj.metadata?.thumbnail) {
            mediaObj.thumbnail_url = await storageService.getPresignedUrl(
              mediaObj.metadata.thumbnail,
              3600
            );
          }
        } catch (urlError) {
          console.warn('Error generating presigned URL:', urlError);
        }
        
        mediaWithUrls.push(mediaObj);
      }

      return res.status(200).json({
        code: 200,
        data: {
          media: mediaWithUrls,
          pagination: {
            page: pageNum,
            limit: limitNum,
            total: total,
            total_pages: Math.ceil(total / limitNum)
          }
        }
      });
    } catch (error) {
      console.error('Error in getMediaList:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getMediaById(req, res) {
    try {
      const { media_id } = req.params;
      
      if (!media_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing media_id parameter'
        });
      }

      const media = await Media.findByMediaId(media_id);

      if (!media) {
        return res.status(404).json({
          code: 404,
          message: 'Media not found'
        });
      }

      const mediaObj = media.toObject();
      
      try {
        if (mediaObj.storage_path) {
          mediaObj.presigned_url = await storageService.getPresignedUrl(
            mediaObj.storage_path,
            3600
          );
        }
        
        if (mediaObj.metadata?.thumbnail) {
          mediaObj.thumbnail_url = await storageService.getPresignedUrl(
            mediaObj.metadata.thumbnail,
            3600
          );
        }
      } catch (urlError) {
        console.warn('Error generating presigned URL:', urlError);
      }

      return res.status(200).json({
        code: 200,
        data: mediaObj
      });
    } catch (error) {
      console.error('Error in getMediaById:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async updateMedia(req, res) {
    try {
      const { media_id } = req.params;
      const { tags, folder_id, filename } = req.body;
      
      if (!media_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing media_id parameter'
        });
      }

      const media = await Media.findByMediaId(media_id);

      if (!media) {
        return res.status(404).json({
          code: 404,
          message: 'Media not found'
        });
      }

      if (tags !== undefined && Array.isArray(tags)) {
        media.tags = tags;
      }
      
      if (folder_id !== undefined) {
        media.folder_id = folder_id;
      }
      
      if (filename !== undefined && typeof filename === 'string') {
        media.filename = filename;
      }

      await media.save();

      return res.status(200).json({
        code: 200,
        data: media.toObject()
      });
    } catch (error) {
      console.error('Error in updateMedia:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async deleteMedia(req, res) {
    try {
      const { media_id } = req.params;
      
      if (!media_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing media_id parameter'
        });
      }

      const media = await Media.findByMediaId(media_id);

      if (!media) {
        return res.status(404).json({
          code: 404,
          message: 'Media not found'
        });
      }

      if (media.storage_path) {
        try {
          await storageService.deleteObject(media.storage_path);
        } catch (storageError) {
          console.warn('Error deleting from storage:', storageError);
        }
      }

      if (media.metadata?.thumbnail) {
        try {
          await storageService.deleteObject(media.metadata.thumbnail);
        } catch (storageError) {
          console.warn('Error deleting thumbnail from storage:', storageError);
        }
      }

      await media.deleteOne();

      return res.status(200).json({
        code: 200,
        message: 'Media deleted successfully'
      });
    } catch (error) {
      console.error('Error in deleteMedia:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getMediaStats(req, res) {
    try {
      const total = await Media.countDocuments();
      const imageCount = await Media.countDocuments({ file_type: 'image' });
      const videoCount = await Media.countDocuments({ file_type: 'video' });
      const audioCount = await Media.countDocuments({ file_type: 'audio' });
      
      const approved = await Media.countDocuments({ status: 'approved' });
      const pendingReview = await Media.countDocuments({ status: 'pending_review' });
      const rejected = await Media.countDocuments({ status: 'rejected' });

      const sizeResult = await Media.aggregate([
        {
          $group: {
            _id: null,
            total_size: { $sum: '$file_size' }
          }
        }
      ]);

      const totalSize = sizeResult.length > 0 ? sizeResult[0].total_size : 0;

      return res.status(200).json({
        code: 200,
        data: {
          total,
          by_type: {
            image: imageCount,
            video: videoCount,
            audio: audioCount
          },
          by_status: {
            approved,
            pending_review: pendingReview,
            rejected
          },
          total_size: totalSize
        }
      });
    } catch (error) {
      console.error('Error in getMediaStats:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getPresignedUrl(req, res) {
    try {
      const { media_id } = req.params;
      const { expires_in = 3600 } = req.query;
      
      if (!media_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing media_id parameter'
        });
      }

      const media = await Media.findByMediaId(media_id);

      if (!media) {
        return res.status(404).json({
          code: 404,
          message: 'Media not found'
        });
      }

      if (!media.storage_path) {
        return res.status(400).json({
          code: 400,
          message: 'Media has no storage path'
        });
      }

      const url = await storageService.getPresignedUrl(
        media.storage_path,
        parseInt(expires_in) || 3600
      );

      return res.status(200).json({
        code: 200,
        data: {
          media_id: media.media_id,
          presigned_url: url,
          expires_in: expires_in
        }
      });
    } catch (error) {
      console.error('Error in getPresignedUrl:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async batchDelete(req, res) {
    try {
      const { media_ids } = req.body;
      
      if (!media_ids || !Array.isArray(media_ids) || media_ids.length === 0) {
        return res.status(400).json({
          code: 400,
          message: 'media_ids must be a non-empty array'
        });
      }

      const results = [];
      
      for (const mediaId of media_ids) {
        try {
          const media = await Media.findByMediaId(mediaId);
          
          if (!media) {
            results.push({
              media_id: mediaId,
              success: false,
              message: 'Media not found'
            });
            continue;
          }

          if (media.storage_path) {
            try {
              await storageService.deleteObject(media.storage_path);
            } catch (storageError) {
              console.warn('Error deleting from storage:', storageError);
            }
          }

          await media.deleteOne();
          
          results.push({
            media_id: mediaId,
            success: true
          });
        } catch (error) {
          results.push({
            media_id: mediaId,
            success: false,
            message: error.message
          });
        }
      }

      const successCount = results.filter(r => r.success).length;
      const failCount = results.filter(r => !r.success).length;

      return res.status(200).json({
        code: 200,
        data: {
          total: media_ids.length,
          success: successCount,
          failed: failCount,
          results: results
        }
      });
    } catch (error) {
      console.error('Error in batchDelete:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }
}

module.exports = new MediaController();
