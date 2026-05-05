const uploadService = require('../services/uploadService');

class UploadController {
  async createSession(req, res) {
    try {
      const { filename, file_size, mime_type } = req.body;
      
      if (!filename || !file_size || !mime_type) {
        return res.status(400).json({
          code: 400,
          message: 'Missing required fields: filename, file_size, mime_type'
        });
      }

      const result = await uploadService.createUploadSession(
        filename,
        file_size,
        mime_type
      );

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in createSession:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getSessionStatus(req, res) {
    try {
      const { file_id } = req.params;
      
      if (!file_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing file_id parameter'
        });
      }

      const result = await uploadService.getUploadSession(file_id);

      if (!result.success) {
        return res.status(404).json({
          code: 404,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in getSessionStatus:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async uploadChunk(req, res) {
    try {
      const { file_id, chunk_index, total_chunks } = req.body;
      
      if (!file_id || chunk_index === undefined || !total_chunks) {
        return res.status(400).json({
          code: 400,
          message: 'Missing required fields: file_id, chunk_index, total_chunks'
        });
      }

      if (!req.file) {
        return res.status(400).json({
          code: 400,
          message: 'No file uploaded'
        });
      }

      const chunkIndex = parseInt(chunk_index);
      
      if (isNaN(chunkIndex) || chunkIndex < 0) {
        return res.status(400).json({
          code: 400,
          message: 'Invalid chunk_index'
        });
      }

      const result = await uploadService.uploadChunk(
        file_id,
        chunkIndex,
        req.file.buffer,
        req.file.size
      );

      if (!result.success) {
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in uploadChunk:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async completeUpload(req, res) {
    try {
      const { file_id, filename, total_size, expected_md5 } = req.body;
      
      if (!file_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing file_id parameter'
        });
      }

      const result = await uploadService.mergeChunks(file_id, expected_md5);

      if (!result.success) {
        if (result.missing_chunks) {
          return res.status(400).json({
            code: 400,
            message: result.message,
            data: { missing_chunks: result.missing_chunks }
          });
        }
        return res.status(400).json({
          code: 400,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in completeUpload:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async cancelUpload(req, res) {
    try {
      const { file_id } = req.params;
      
      if (!file_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing file_id parameter'
        });
      }

      const result = await uploadService.cancelUpload(file_id);

      if (!result.success) {
        return res.status(404).json({
          code: 404,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in cancelUpload:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }

  async getChunkStatus(req, res) {
    try {
      const { file_id } = req.params;
      
      if (!file_id) {
        return res.status(400).json({
          code: 400,
          message: 'Missing file_id parameter'
        });
      }

      const result = await uploadService.getChunkStatus(file_id);

      if (!result.success) {
        return res.status(404).json({
          code: 404,
          message: result.message
        });
      }

      return res.status(200).json({
        code: 200,
        data: result
      });
    } catch (error) {
      console.error('Error in getChunkStatus:', error);
      return res.status(500).json({
        code: 500,
        message: 'Internal server error',
        error: error.message
      });
    }
  }
}

module.exports = new UploadController();
