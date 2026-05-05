const { Document, DocumentVersion } = require('../models');
const logger = require('../utils/logger');
const config = require('../config/config');
const compressionUtils = require('../utils/compressionUtils');
const diffUtils = require('../utils/diffUtils');
const { compressionStrategyService } = require('./compressionStrategyService');
const eventBus = require('./eventBus');

const EVENTS = {
  VERSION_CREATED: 'version:created',
  VERSION_RESTORED: 'version:restored',
  DOCUMENT_UPDATED: 'document:updated',
  DOCUMENT_CREATED: 'document:created',
  DOCUMENT_DELETED: 'document:deleted'
};

const versionService = {
  async createVersion(docId, user, content, changeDesc = '') {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const newVersionNum = document.version_count + 1;
      const newVersion = `v${newVersionNum}`;

      const currentContent = document.content;
      const originalSize = Buffer.byteLength(currentContent, 'utf8');
      
      let useDelta = false;
      let baseVersion = null;
      let delta = null;
      let isFullVersion = false;
      let strategyUsed = 'default';
      let documentType = null;
      
      if (config.version.useDelta && document.version_count > 0) {
        const lastVersion = await DocumentVersion.findOne({ doc_id: docId })
          .sort({ created_at: -1 })
          .limit(1);
        
        if (lastVersion) {
          const fullVersionInterval = config.version.fullVersionInterval || 10;
          if (newVersionNum % fullVersionInterval === 0) {
            isFullVersion = true;
            logger.debug(`创建全量版本: doc_id=${docId}, version=${newVersion} (每${fullVersionInterval}个版本)`);
          } else {
            const lastFullVersion = await DocumentVersion.findOne({
              doc_id: docId,
              is_full_version: true
            }).sort({ created_at: -1 }).limit(1);
            
            if (lastFullVersion && lastFullVersion.version_number < newVersionNum) {
              const chainLength = newVersionNum - lastFullVersion.version_number;
              const maxDeltaChain = config.version.maxDeltaChain || 20;
              
              if (chainLength < maxDeltaChain) {
                let baseVersionContent;
                
                if (lastVersion.content_type === 'delta' && lastVersion.base_version) {
                  baseVersionContent = await this.restoreFullContent(docId, lastVersion.base_version);
                } else {
                  baseVersionContent = await this.getVersionContent(lastVersion);
                }
                
                if (baseVersionContent) {
                  const strategyResult = compressionStrategyService.getStrategyForDocument(document, currentContent);
                  strategyUsed = strategyResult.name;
                  documentType = compressionStrategyService.detectAndGetStrategy(document, currentContent).type;
                  
                  if (strategyResult.shouldUseDelta(baseVersionContent, currentContent)) {
                    delta = strategyResult.createDelta(baseVersionContent, currentContent);
                    
                    const estimatedDeltaSize = strategyResult.estimateDeltaSize(delta);
                    
                    if (estimatedDeltaSize < originalSize * 0.6 && estimatedDeltaSize > 0) {
                      useDelta = true;
                      baseVersion = lastFullVersion.version;
                      logger.debug(`使用策略差异存储: doc_id=${docId}, version=${newVersion}, ` +
                        `base=${baseVersion}, strategy=${strategyUsed}, doc_type=${documentType}, ` +
                        `节省约${((1 - estimatedDeltaSize / originalSize) * 100).toFixed(1)}%`);
                    } else {
                      isFullVersion = true;
                    }
                  } else {
                    isFullVersion = true;
                  }
                } else {
                  isFullVersion = true;
                }
              } else {
                isFullVersion = true;
                logger.debug(`达到最大差异链长度，创建全量版本: doc_id=${docId}, version=${newVersion}`);
              }
            } else {
              isFullVersion = true;
            }
          }
        } else {
          isFullVersion = true;
        }
      } else {
        isFullVersion = true;
      }

      if (!isFullVersion && newVersionNum === 1) {
        isFullVersion = true;
      }

      let versionContent;
      let isCompressed = false;
      let compressedSize = 0;
      let algorithm = null;

      if (useDelta && delta) {
        versionContent = JSON.stringify(delta);
      } else {
        versionContent = currentContent;
      }

      if (config.compression.enabled) {
        const strategy = compressionStrategyService.getStrategyForDocument(document, versionContent);
        const compressionResult = await strategy.compress(versionContent);
        
        if (compressionResult.compressed) {
          versionContent = compressionResult.content;
          isCompressed = true;
          compressedSize = compressionResult.compressedSize;
          algorithm = compressionResult.algorithm;
          
          logger.debug(`版本内容已压缩: doc_id=${docId}, version=${newVersion}, ` +
            `strategy=${strategy.name}, ` +
            `原始=${compressionUtils.calculateSize(compressionResult.originalSize)}, ` +
            `压缩后=${compressionUtils.calculateSize(compressionResult.compressedSize)}, ` +
            `节省=${compressionResult.compressionRatio.toFixed(1)}%`);
        }
      }

      const version = new DocumentVersion({
        doc_id: docId,
        version: newVersion,
        content: versionContent,
        change_desc: changeDesc || `版本更新 v${newVersion}`,
        author: user,
        is_compressed: isCompressed,
        compression_algorithm: algorithm,
        original_size: originalSize,
        compressed_size: compressedSize || originalSize,
        content_type: useDelta ? 'delta' : 'full',
        base_version: baseVersion,
        delta_from_version: useDelta ? (baseVersion || null) : null,
        delta_operations: useDelta ? delta : null,
        is_full_version: isFullVersion,
        version_number: newVersionNum,
        strategy_used: strategyUsed,
        document_type: documentType
      });

      await version.save();

      document.current_version = newVersion;
      document.version_count = newVersionNum;
      await document.save();

      logger.info(`版本创建成功: doc_id=${docId}, version=${newVersion}, user=${user}, ` +
        `type=${useDelta ? 'delta' : 'full'}, compressed=${isCompressed}, ` +
        `strategy=${strategyUsed}, doc_type=${documentType}`);
      
      eventBus.publish(EVENTS.VERSION_CREATED, {
        docId,
        version: newVersion,
        versionId: version.version_id,
        user,
        documentType,
        strategyUsed
      });

      eventBus.publish(EVENTS.DOCUMENT_UPDATED, {
        docId,
        user,
        updatedAt: Date.now()
      });
      
      return {
        success: true,
        data: {
          doc_id: docId,
          version: newVersion,
          version_id: version.version_id,
          is_compressed: isCompressed,
          content_type: useDelta ? 'delta' : 'full',
          is_full_version: isFullVersion,
          strategy_used: strategyUsed,
          document_type: documentType,
          compression_ratio: isCompressed && originalSize > 0 
            ? ((originalSize - compressedSize) / originalSize * 100).toFixed(2) + '%'
            : null
        }
      };
    } catch (error) {
      logger.error(`版本创建失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getVersionContent(versionDoc) {
    if (!versionDoc) {
      return null;
    }

    let content = versionDoc.content;

    if (versionDoc.is_compressed && content) {
      try {
        const algorithm = versionDoc.compression_algorithm || 'gzip';
        content = await compressionUtils.decompress(content, algorithm);
      } catch (error) {
        logger.error(`解压版本内容失败: ${error.message}`, { 
          version_id: versionDoc.version_id, 
          error 
        });
        return null;
      }
    }

    if (versionDoc.content_type === 'delta') {
      try {
        let baseContent = null;
        
        if (versionDoc.base_version) {
          baseContent = await this.restoreFullContent(versionDoc.doc_id, versionDoc.base_version);
        }
        
        if (baseContent && versionDoc.delta_operations) {
          const delta = versionDoc.delta_operations;
          const strategy = compressionStrategyService.getStrategy(versionDoc.document_type || 'plainText');
          content = strategy.applyDelta(baseContent, delta);
        }
      } catch (error) {
        logger.error(`恢复差异版本失败: ${error.message}`, { 
          version_id: versionDoc.version_id, 
          error 
        });
        return null;
      }
    }

    return content;
  },

  async restoreFullContent(docId, version) {
    const versionDoc = await DocumentVersion.findOne({ doc_id: docId, version });
    
    if (!versionDoc) {
      return null;
    }

    if (versionDoc.is_full_version && versionDoc.content_type === 'full') {
      return this.getVersionContent(versionDoc);
    }

    const fullVersions = await DocumentVersion.find({
      doc_id: docId,
      is_full_version: true,
      version_number: { $lte: versionDoc.version_number }
    }).sort({ version_number: -1 }).limit(1);

    if (fullVersions.length === 0) {
      return this.getVersionContent(versionDoc);
    }

    const baseFullVersion = fullVersions[0];
    let content = await this.getVersionContent(baseFullVersion);

    if (!content) {
      return null;
    }

    const deltaVersions = await DocumentVersion.find({
      doc_id: docId,
      version_number: { 
        $gt: baseFullVersion.version_number, 
        $lte: versionDoc.version_number 
      },
      content_type: 'delta'
    }).sort({ version_number: 1 });

    for (const deltaVersion of deltaVersions) {
      if (deltaVersion.delta_operations) {
        try {
          const deltaContent = await this.getVersionContent(deltaVersion);
          if (deltaContent) {
            const delta = deltaVersion.delta_operations;
            const strategy = compressionStrategyService.getStrategy(deltaVersion.document_type || 'plainText');
            content = strategy.applyDelta(content, delta);
          }
        } catch (error) {
          logger.error(`应用差异失败: version=${deltaVersion.version}, error=${error.message}`);
        }
      }
    }

    return content;
  },

  async getVersionHistory(docId, user, page = 1, pageSize = 20) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkReadPermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限访问此文档版本历史');
      }

      const skip = (page - 1) * pageSize;
      
      const versions = await DocumentVersion.find({ doc_id: docId })
        .sort({ created_at: -1 })
        .skip(skip)
        .limit(pageSize)
        .select('version_id version change_desc author created_at is_compressed compression_algorithm original_size compressed_size content_type is_full_version version_number strategy_used document_type');

      const total = await DocumentVersion.countDocuments({ doc_id: docId });

      const versionsWithStats = versions.map(v => ({
        ...v.toObject(),
        compression_ratio: v.is_compressed && v.original_size > 0
          ? ((v.original_size - v.compressed_size) / v.original_size * 100).toFixed(2) + '%'
          : null,
        size_formatted: compressionUtils.calculateSize(v.original_size),
        compressed_size_formatted: compressionUtils.calculateSize(v.compressed_size)
      }));

      return {
        success: true,
        data: {
          versions: versionsWithStats,
          current_version: document.current_version,
          pagination: {
            page,
            pageSize,
            total,
            totalPages: Math.ceil(total / pageSize)
          },
          stats: {
            total_versions: total,
            compressed_versions: versions.filter(v => v.is_compressed).length,
            delta_versions: versions.filter(v => v.content_type === 'delta').length,
            full_versions: versions.filter(v => v.is_full_version).length
          }
        }
      };
    } catch (error) {
      logger.error(`获取版本历史失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getVersion(docId, version, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkReadPermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限访问此文档版本');
      }

      const versionDoc = await DocumentVersion.findOne({ doc_id: docId, version });
      
      if (!versionDoc) {
        throw new Error('版本不存在');
      }

      const content = await this.getVersionContent(versionDoc);

      return {
        success: true,
        data: {
          ...versionDoc.toObject(),
          content: content,
          compression_ratio: versionDoc.is_compressed && versionDoc.original_size > 0
            ? ((versionDoc.original_size - versionDoc.compressed_size) / versionDoc.original_size * 100).toFixed(2) + '%'
            : null,
          size_formatted: compressionUtils.calculateSize(versionDoc.original_size),
          compressed_size_formatted: compressionUtils.calculateSize(versionDoc.compressed_size)
        }
      };
    } catch (error) {
      logger.error(`获取版本失败: ${error.message}`, { docId, version, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async compareVersions(docId, version1, version2, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkReadPermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限访问此文档版本');
      }

      const v1 = await DocumentVersion.findOne({ doc_id: docId, version: version1 });
      const v2 = await DocumentVersion.findOne({ doc_id: docId, version: version2 });

      if (!v1 || !v2) {
        throw new Error('指定的版本不存在');
      }

      const content1 = await this.getVersionContent(v1);
      const content2 = await this.getVersionContent(v2);

      if (content1 === null || content2 === null) {
        throw new Error('无法获取版本内容进行比对');
      }

      const diff = this.generateSimpleDiff(content1, content2);
      const stats = diffUtils.computeDiffStats(content1, content2);

      return {
        success: true,
        data: {
          doc_id: docId,
          version1: {
            version: v1.version,
            author: v1.author,
            change_desc: v1.change_desc,
            created_at: v1.created_at,
            document_type: v1.document_type,
            strategy_used: v1.strategy_used
          },
          version2: {
            version: v2.version,
            author: v2.author,
            change_desc: v2.change_desc,
            created_at: v2.created_at,
            document_type: v2.document_type,
            strategy_used: v2.strategy_used
          },
          diff: diff,
          stats: stats,
          from_content: content1,
          to_content: content2
        }
      };
    } catch (error) {
      logger.error(`版本比对失败: ${error.message}`, { docId, version1, version2, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async restoreVersion(docId, version, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkWritePermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限恢复此文档版本');
      }

      const versionDoc = await DocumentVersion.findOne({ doc_id: docId, version });
      
      if (!versionDoc) {
        throw new Error('版本不存在');
      }

      const restoreContent = await this.restoreFullContent(docId, version);
      
      if (!restoreContent) {
        throw new Error('无法恢复版本内容');
      }

      const createResult = await this.createVersion(
        docId,
        user,
        document.content,
        `恢复至版本 ${version} 前的备份`
      );

      if (!createResult.success) {
        throw new Error(createResult.error);
      }

      document.content = restoreContent;
      await document.save();

      logger.info(`版本恢复成功: doc_id=${docId}, version=${version}, user=${user}`);
      
      eventBus.publish(EVENTS.VERSION_RESTORED, {
        docId,
        version,
        user
      });

      eventBus.publish(EVENTS.DOCUMENT_UPDATED, {
        docId,
        user,
        updatedAt: Date.now()
      });
      
      return {
        success: true,
        data: {
          doc_id: docId,
          restored_version: version,
          current_version: document.current_version,
          backup_version: createResult.data.version,
          restored_content_preview: restoreContent.substring(0, 200) + (restoreContent.length > 200 ? '...' : '')
        }
      };
    } catch (error) {
      logger.error(`版本恢复失败: ${error.message}`, { docId, version, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getVersionStats(docId) {
    try {
      const versions = await DocumentVersion.find({ doc_id: docId });
      
      const stats = {
        total: versions.length,
        compressed: versions.filter(v => v.is_compressed).length,
        delta: versions.filter(v => v.content_type === 'delta').length,
        full: versions.filter(v => v.is_full_version).length,
        original_total_size: 0,
        compressed_total_size: 0,
        strategies: {},
        document_types: {}
      };

      for (const v of versions) {
        stats.original_total_size += v.original_size || 0;
        stats.compressed_total_size += v.compressed_size || (v.original_size || 0);
        
        if (v.strategy_used) {
          stats.strategies[v.strategy_used] = (stats.strategies[v.strategy_used] || 0) + 1;
        }
        if (v.document_type) {
          stats.document_types[v.document_type] = (stats.document_types[v.document_type] || 0) + 1;
        }
      }

      stats.savings = stats.original_total_size > 0 
        ? ((stats.original_total_size - stats.compressed_total_size) / stats.original_total_size * 100).toFixed(2) + '%'
        : '0%';
      
      stats.original_total_size_formatted = compressionUtils.calculateSize(stats.original_total_size);
      stats.compressed_total_size_formatted = compressionUtils.calculateSize(stats.compressed_total_size);

      return {
        success: true,
        data: stats
      };
    } catch (error) {
      logger.error(`获取版本统计失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  generateSimpleDiff(content1, content2) {
    return diffUtils.createSimpleDiff(content1, content2);
  },

  checkReadPermission(document, user) {
    if (document.author === user) return true;
    if (document.permissions.read.includes(user)) return true;
    if (document.permissions.write.includes(user)) return true;
    if (document.permissions.admin.includes(user)) return true;
    return false;
  },

  checkWritePermission(document, user) {
    if (document.author === user) return true;
    if (document.permissions.write.includes(user)) return true;
    if (document.permissions.admin.includes(user)) return true;
    return false;
  },

  getCompressionStrategies() {
    return compressionStrategyService.listStrategies();
  }
};

module.exports = versionService;
