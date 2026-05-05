require('dotenv').config();

module.exports = {
  chunkSize: parseInt(process.env.UPLOAD_CHUNK_SIZE) || 5 * 1024 * 1024,
  tempDir: process.env.UPLOAD_TEMP_DIR || './temp',
  maxFileSize: parseInt(process.env.UPLOAD_MAX_FILE_SIZE) || 5 * 1024 * 1024 * 1024,
  allowedTypes: {
    image: ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp'],
    video: ['video/mp4', 'video/webm', 'video/ogg', 'video/quicktime', 'video/x-msvideo'],
    audio: ['audio/mpeg', 'audio/ogg', 'audio/wav', 'audio/webm', 'audio/flac']
  }
};
