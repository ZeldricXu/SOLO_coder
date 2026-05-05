const { Document, DocumentVersion } = require('../models');
const logger = require('../utils/logger');

const storageService = {
  async saveDocumentContent(docId, content) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      document.content = content;
      await document.save();

      logger.info(`文档内容保存成功: doc_id=${docId}`);
      
      return {
        success: true,
        data: {
          doc_id: docId,
          updated_at: document.updated_at
        }
      };
    } catch (error) {
      logger.error(`保存文档内容失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getDocumentContent(docId) {
    try {
      const document = await Document.findOne({ doc_id: docId }).select('doc_id content updated_at');
      
      if (!document) {
        throw new Error('文档不存在');
      }

      return {
        success: true,
        data: {
          doc_id: document.doc_id,
          content: document.content,
          updated_at: document.updated_at
        }
      };
    } catch (error) {
      logger.error(`获取文档内容失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async saveVersionContent(versionId, content) {
    try {
      const version = await DocumentVersion.findOne({ version_id: versionId });
      
      if (!version) {
        throw new Error('版本不存在');
      }

      version.content = content;
      await version.save();

      logger.info(`版本内容保存成功: version_id=${versionId}`);
      
      return {
        success: true,
        data: {
          version_id: versionId
        }
      };
    } catch (error) {
      logger.error(`保存版本内容失败: ${error.message}`, { versionId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getVersionContent(versionId) {
    try {
      const version = await DocumentVersion.findOne({ version_id: versionId }).select('version_id content created_at');
      
      if (!version) {
        throw new Error('版本不存在');
      }

      return {
        success: true,
        data: {
          version_id: version.version_id,
          content: version.content,
          created_at: version.created_at
        }
      };
    } catch (error) {
      logger.error(`获取版本内容失败: ${error.message}`, { versionId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async deleteDocumentContent(docId) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      await Document.deleteOne({ doc_id: docId });
      await DocumentVersion.deleteMany({ doc_id: docId });

      logger.info(`文档及其版本删除成功: doc_id=${docId}`);
      
      return {
        success: true,
        message: '文档已删除'
      };
    } catch (error) {
      logger.error(`删除文档内容失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getDocumentSize(docId) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const contentSize = Buffer.from(document.content).length;
      
      const versions = await DocumentVersion.find({ doc_id: docId });
      let versionsSize = 0;
      versions.forEach(v => {
        versionsSize += Buffer.from(v.content).length;
      });

      return {
        success: true,
        data: {
          doc_id: docId,
          current_size: contentSize,
          versions_size: versionsSize,
          total_size: contentSize + versionsSize,
          version_count: versions.length
        }
      };
    } catch (error) {
      logger.error(`获取文档大小失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async compressOldVersions(docId, keepRecent = 10) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const versions = await DocumentVersion.find({ doc_id: docId })
        .sort({ created_at: -1 });

      if (versions.length <= keepRecent) {
        return {
          success: true,
          message: '版本数量未超过保留数量，无需压缩',
          compressed: 0
        };
      }

      const versionsToCompress = versions.slice(keepRecent);
      
      let compressedCount = 0;
      for (const version of versionsToCompress) {
        await DocumentVersion.deleteOne({ version_id: version.version_id });
        compressedCount++;
      }

      logger.info(`版本压缩完成: doc_id=${docId}, compressed=${compressedCount}`);
      
      return {
        success: true,
        message: `已压缩 ${compressedCount} 个旧版本`,
        compressed: compressedCount,
        remaining: versions.length - compressedCount
      };
    } catch (error) {
      logger.error(`版本压缩失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  }
};

module.exports = storageService;
