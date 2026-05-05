const zlib = require('zlib');
const util = require('util');
const config = require('../config/config');
const logger = require('./logger');

const gzipCompress = util.promisify(zlib.gzip);
const gzipDecompress = util.promisify(zlib.gunzip);
const deflateCompress = util.promisify(zlib.deflate);
const deflateDecompress = util.promisify(zlib.inflate);
const brotliCompress = util.promisify(zlib.brotliCompress);
const brotliDecompress = util.promisify(zlib.brotliDecompress);

const compressionUtils = {
  
  async compress(content, options = {}) {
    if (!config.compression.enabled) {
      return {
        compressed: false,
        content: content,
        originalSize: Buffer.byteLength(content, 'utf8'),
        compressedSize: Buffer.byteLength(content, 'utf8')
      };
    }

    const originalSize = Buffer.byteLength(content, 'utf8');
    const threshold = options.threshold || config.compression.threshold;
    
    if (originalSize < threshold) {
      return {
        compressed: false,
        content: content,
        originalSize: originalSize,
        compressedSize: originalSize
      };
    }

    try {
      const algorithm = options.algorithm || config.compression.algorithm;
      const level = options.level || config.compression.level;
      
      let compressedBuffer;
      let usedAlgorithm = algorithm;

      switch (algorithm) {
        case 'gzip':
          compressedBuffer = await gzipCompress(content, { level });
          break;
        case 'deflate':
          compressedBuffer = await deflateCompress(content, { level });
          break;
        case 'brotli':
          compressedBuffer = await brotliCompress(content, {
            params: {
              [zlib.constants.BROTLI_PARAM_QUALITY]: level
            }
          });
          break;
        default:
          compressedBuffer = await gzipCompress(content, { level });
          usedAlgorithm = 'gzip';
      }

      const compressedSize = compressedBuffer.length;
      const compressionRatio = originalSize > 0 ? (1 - compressedSize / originalSize) * 100 : 0;

      if (compressedSize >= originalSize) {
        logger.debug(`压缩效果不佳，跳过压缩: ${originalSize} -> ${compressedSize} bytes`);
        return {
          compressed: false,
          content: content,
          originalSize: originalSize,
          compressedSize: originalSize
        };
      }

      const compressedBase64 = compressedBuffer.toString('base64');

      logger.debug(`压缩成功: ${algorithm}, ${originalSize} -> ${compressedSize} bytes (${compressionRatio.toFixed(1)}%)`);

      return {
        compressed: true,
        content: compressedBase64,
        originalSize: originalSize,
        compressedSize: compressedSize,
        algorithm: usedAlgorithm,
        compressionRatio: compressionRatio
      };
    } catch (error) {
      logger.error(`压缩失败: ${error.message}`, { error });
      return {
        compressed: false,
        content: content,
        originalSize: originalSize,
        compressedSize: originalSize,
        error: error.message
      };
    }
  },

  async decompress(compressedContent, algorithm = 'gzip') {
    if (!compressedContent) {
      return null;
    }

    try {
      let decompressedBuffer;

      switch (algorithm) {
        case 'gzip':
          decompressedBuffer = await gzipDecompress(Buffer.from(compressedContent, 'base64'));
          break;
        case 'deflate':
          decompressedBuffer = await deflateDecompress(Buffer.from(compressedContent, 'base64'));
          break;
        case 'brotli':
          decompressedBuffer = await brotliDecompress(Buffer.from(compressedContent, 'base64'));
          break;
        default:
          decompressedBuffer = await gzipDecompress(Buffer.from(compressedContent, 'base64'));
      }

      return decompressedBuffer.toString('utf8');
    } catch (error) {
      logger.error(`解压失败: ${error.message}`, { error });
      throw error;
    }
  },

  async tryDecompress(compressedContent, algorithm = 'gzip') {
    try {
      return await this.decompress(compressedContent, algorithm);
    } catch (error) {
      logger.warn(`尝试解压失败，返回原始内容: ${error.message}`);
      return compressedContent;
    }
  },

  isBase64(str) {
    if (!str || typeof str !== 'string') {
      return false;
    }
    const base64Regex = /^[A-Za-z0-9+/]*={0,2}$/;
    return base64Regex.test(str) && str.length % 4 === 0;
  },

  estimateCompressionGain(originalSize, compressedSize) {
    if (originalSize <= 0) {
      return 0;
    }
    return ((originalSize - compressedSize) / originalSize) * 100;
  },

  calculateSize(size) {
    if (size < 1024) {
      return `${size} B`;
    } else if (size < 1024 * 1024) {
      return `${(size / 1024).toFixed(2)} KB`;
    } else {
      return `${(size / (1024 * 1024)).toFixed(2)} MB`;
    }
  },

  compressSync(content, options = {}) {
    if (!config.compression.enabled) {
      return content;
    }

    const threshold = options.threshold || config.compression.threshold;
    const originalSize = Buffer.byteLength(content, 'utf8');
    
    if (originalSize < threshold) {
      return content;
    }

    const algorithm = options.algorithm || config.compression.algorithm;
    const level = options.level || config.compression.level;

    try {
      let compressed;
      switch (algorithm) {
        case 'gzip':
          compressed = zlib.gzipSync(content, { level });
          break;
        case 'deflate':
          compressed = zlib.deflateSync(content, { level });
          break;
        default:
          compressed = zlib.gzipSync(content, { level });
      }
      return compressed.toString('base64');
    } catch (error) {
      logger.error(`同步压缩失败: ${error.message}`);
      return content;
    }
  },

  decompressSync(compressedContent, algorithm = 'gzip') {
    if (!compressedContent) {
      return null;
    }

    try {
      const buffer = Buffer.from(compressedContent, 'base64');
      let decompressed;
      
      switch (algorithm) {
        case 'gzip':
          decompressed = zlib.gunzipSync(buffer);
          break;
        case 'deflate':
          decompressed = zlib.inflateSync(buffer);
          break;
        default:
          decompressed = zlib.gunzipSync(buffer);
      }
      
      return decompressed.toString('utf8');
    } catch (error) {
      logger.error(`同步解压失败: ${error.message}`);
      return compressedContent;
    }
  }
};

module.exports = compressionUtils;
