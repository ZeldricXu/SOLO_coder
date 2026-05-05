const { Document } = require('../models');
const logger = require('../utils/logger');

const favoriteService = {
  async toggleFavorite(docId, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkReadPermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限访问此文档');
      }

      const isFavorited = document.favorites.includes(user);
      
      if (isFavorited) {
        document.favorites = document.favorites.filter(u => u !== user);
        await document.save();
        
        logger.info(`取消收藏: doc_id=${docId}, user=${user}`);
        
        return {
          success: true,
          data: {
            doc_id: docId,
            is_favorited: false,
            message: '已取消收藏'
          }
        };
      } else {
        document.favorites.push(user);
        await document.save();
        
        logger.info(`添加收藏: doc_id=${docId}, user=${user}`);
        
        return {
          success: true,
          data: {
            doc_id: docId,
            is_favorited: true,
            message: '已添加收藏'
          }
        };
      }
    } catch (error) {
      logger.error(`收藏操作失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getUserFavorites(user, page = 1, pageSize = 20) {
    try {
      const skip = (page - 1) * pageSize;
      
      const docs = await Document.find({ favorites: user })
        .sort({ updated_at: -1 })
        .skip(skip)
        .limit(pageSize)
        .select('doc_id title author category tags status current_version created_at updated_at');

      const total = await Document.countDocuments({ favorites: user });

      return {
        success: true,
        data: {
          docs,
          pagination: {
            page,
            pageSize,
            total,
            totalPages: Math.ceil(total / pageSize)
          }
        }
      };
    } catch (error) {
      logger.error(`获取收藏列表失败: ${error.message}`, { user, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async checkFavorite(docId, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const isFavorited = document.favorites.includes(user);

      return {
        success: true,
        data: {
          doc_id: docId,
          is_favorited: isFavorited,
          favorite_count: document.favorites.length
        }
      };
    } catch (error) {
      logger.error(`检查收藏状态失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  checkReadPermission(document, user) {
    if (document.author === user) return true;
    if (document.permissions.read.includes(user)) return true;
    if (document.permissions.write.includes(user)) return true;
    if (document.permissions.admin.includes(user)) return true;
    return false;
  }
};

module.exports = favoriteService;
