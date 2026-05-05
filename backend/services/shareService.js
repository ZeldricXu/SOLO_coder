const { Document, Share } = require('../models');
const logger = require('../utils/logger');

const shareService = {
  async shareDocument(docId, user, shareType, targetId, permission = 'read', expiresAt = null) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkSharePermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限分享此文档');
      }

      const validShareTypes = ['user', 'team', 'public'];
      if (!validShareTypes.includes(shareType)) {
        throw new Error('无效的分享类型');
      }

      const validPermissions = ['read', 'write', 'admin'];
      if (!validPermissions.includes(permission)) {
        throw new Error('无效的权限类型');
      }

      const existingShare = await Share.findOne({
        doc_id: docId,
        share_type: shareType,
        target_id: targetId,
        is_active: true
      });

      if (existingShare) {
        if (existingShare.permission === permission) {
          return {
            success: true,
            data: {
              share_id: existingShare.share_id,
              message: '文档已分享'
            }
          };
        } else {
          existingShare.permission = permission;
          await existingShare.save();
          
          await this.updateDocumentPermissions(docId, shareType, targetId, permission, document);
          
          logger.info(`分享更新: doc_id=${docId}, share_type=${shareType}, permission=${permission}`);
          
          return {
            success: true,
            data: {
              share_id: existingShare.share_id,
              message: '分享权限已更新'
            }
          };
        }
      }

      const share = new Share({
        doc_id: docId,
        share_type: shareType,
        target_id: targetId,
        permission: permission,
        created_by: user,
        expires_at: expiresAt,
        is_active: true
      });

      await share.save();

      await this.updateDocumentPermissions(docId, shareType, targetId, permission, document);

      logger.info(`分享创建成功: doc_id=${docId}, share_type=${shareType}, permission=${permission}, user=${user}`);
      
      return {
        success: true,
        data: {
          share_id: share.share_id,
          doc_id: docId,
          share_type: shareType,
          target_id: targetId,
          permission: permission
        }
      };
    } catch (error) {
      logger.error(`分享创建失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getDocumentShares(docId, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkSharePermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限查看此文档的分享信息');
      }

      const shares = await Share.find({ doc_id: docId, is_active: true })
        .sort({ created_at: -1 })
        .select('share_id share_type target_id permission created_by created_at expires_at');

      return {
        success: true,
        data: {
          doc_id: docId,
          shares: shares
        }
      };
    } catch (error) {
      logger.error(`获取分享列表失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async revokeShare(shareId, user) {
    try {
      const share = await Share.findOne({ share_id: shareId });
      
      if (!share) {
        throw new Error('分享不存在');
      }

      const document = await Document.findOne({ doc_id: share.doc_id });
      if (!document) {
        throw new Error('文档不存在');
      }

      if (share.created_by !== user && !document.permissions.admin.includes(user)) {
        throw new Error('无权限撤销此分享');
      }

      share.is_active = false;
      await share.save();

      await this.removeDocumentPermission(
        document, 
        share.share_type, 
        share.target_id, 
        share.permission
      );

      logger.info(`分享已撤销: share_id=${shareId}, user=${user}`);
      
      return {
        success: true,
        message: '分享已撤销'
      };
    } catch (error) {
      logger.error(`撤销分享失败: ${error.message}`, { shareId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async checkShareAccess(docId, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        return { accessible: false, reason: '文档不存在' };
      }

      if (document.author === user) {
        return { 
          accessible: true, 
          permission: 'admin',
          reason: '文档所有者'
        };
      }

      if (document.permissions.admin.includes(user)) {
        return { 
          accessible: true, 
          permission: 'admin',
          reason: '管理员权限'
        };
      }

      if (document.permissions.write.includes(user)) {
        return { 
          accessible: true, 
          permission: 'write',
          reason: '编辑权限'
        };
      }

      if (document.permissions.read.includes(user)) {
        return { 
          accessible: true, 
          permission: 'read',
          reason: '读取权限'
        };
      }

      const publicShare = await Share.findOne({
        doc_id: docId,
        share_type: 'public',
        is_active: true
      });

      if (publicShare) {
        const now = new Date();
        if (!publicShare.expires_at || publicShare.expires_at > now) {
          return {
            accessible: true,
            permission: publicShare.permission,
            reason: '公开分享'
          };
        }
      }

      return { accessible: false, reason: '无访问权限' };
    } catch (error) {
      logger.error(`检查访问权限失败: ${error.message}`, { docId, error });
      return { accessible: false, reason: error.message };
    }
  },

  async updateDocumentPermissions(docId, shareType, targetId, permission, document = null) {
    if (!document) {
      document = await Document.findOne({ doc_id: docId });
      if (!document) return;
    }

    if (shareType === 'user') {
      const permissions = document.permissions;
      
      if (permission === 'read') {
        if (!permissions.read.includes(targetId)) {
          permissions.read.push(targetId);
        }
      } else if (permission === 'write') {
        if (!permissions.write.includes(targetId)) {
          permissions.write.push(targetId);
        }
        if (!permissions.read.includes(targetId)) {
          permissions.read.push(targetId);
        }
      } else if (permission === 'admin') {
        if (!permissions.admin.includes(targetId)) {
          permissions.admin.push(targetId);
        }
        if (!permissions.write.includes(targetId)) {
          permissions.write.push(targetId);
        }
        if (!permissions.read.includes(targetId)) {
          permissions.read.push(targetId);
        }
      }

      await document.save();
    }
  },

  async removeDocumentPermission(document, shareType, targetId, permission) {
    if (shareType === 'user') {
      const permissions = document.permissions;
      
      permissions.read = permissions.read.filter(u => u !== targetId);
      permissions.write = permissions.write.filter(u => u !== targetId);
      permissions.admin = permissions.admin.filter(u => u !== targetId);

      const activeShares = await Share.find({
        doc_id: document.doc_id,
        target_id: targetId,
        share_type: 'user',
        is_active: true
      });

      activeShares.forEach(share => {
        if (share.permission === 'read' && !permissions.read.includes(targetId)) {
          permissions.read.push(targetId);
        }
        if (share.permission === 'write') {
          if (!permissions.write.includes(targetId)) permissions.write.push(targetId);
          if (!permissions.read.includes(targetId)) permissions.read.push(targetId);
        }
        if (share.permission === 'admin') {
          if (!permissions.admin.includes(targetId)) permissions.admin.push(targetId);
          if (!permissions.write.includes(targetId)) permissions.write.push(targetId);
          if (!permissions.read.includes(targetId)) permissions.read.push(targetId);
        }
      });

      await document.save();
    }
  },

  checkSharePermission(document, user) {
    if (document.author === user) return true;
    if (document.permissions.admin.includes(user)) return true;
    return false;
  }
};

module.exports = shareService;
