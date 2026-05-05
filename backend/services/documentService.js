const { Document, Category } = require('../models');
const logger = require('../utils/logger');

const documentService = {
  async createDocument(user, title, content, category = '未分类', tags = []) {
    try {
      if (!title || title.trim() === '') {
        throw new Error('文档标题不能为空');
      }
      if (!content || content.trim() === '') {
        throw new Error('文档内容不能为空');
      }

      const document = new Document({
        title: title.trim(),
        content: content.trim(),
        author: user,
        category: category.trim(),
        tags: tags.map(t => t.trim()).filter(t => t),
        status: 'draft',
        current_version: 'v1',
        version_count: 1,
        permissions: {
          read: [user],
          write: [user],
          admin: [user]
        }
      });

      await document.save();

      if (category && category !== '未分类') {
        const cat = await Category.findOne({ category_name: category });
        if (cat) {
          cat.doc_count += 1;
          await cat.save();
        }
      }

      logger.info(`文档创建成功: doc_id=${document.doc_id}, author=${user}`);
      
      return {
        success: true,
        data: {
          doc_id: document.doc_id,
          version: document.current_version
        }
      };
    } catch (error) {
      logger.error(`文档创建失败: ${error.message}`, { error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getDocument(docId, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkReadPermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限访问此文档');
      }

      return {
        success: true,
        data: document
      };
    } catch (error) {
      logger.error(`获取文档失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async updateDocument(docId, user, content, changeDesc = '', title = null, category = null, tags = null) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkWritePermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限编辑此文档');
      }

      if (content !== undefined && content !== null) {
        if (content.trim() === '') {
          throw new Error('文档内容不能为空');
        }
        document.content = content.trim();
      }

      if (title !== null && title !== undefined) {
        if (title.trim() === '') {
          throw new Error('文档标题不能为空');
        }
        document.title = title.trim();
      }

      if (category !== null && category !== undefined) {
        const oldCategory = document.category;
        const newCategory = category.trim();
        
        if (oldCategory !== newCategory) {
          if (oldCategory && oldCategory !== '未分类') {
            const oldCat = await Category.findOne({ category_name: oldCategory });
            if (oldCat && oldCat.doc_count > 0) {
              oldCat.doc_count -= 1;
              await oldCat.save();
            }
          }
          
          if (newCategory && newCategory !== '未分类') {
            const newCat = await Category.findOne({ category_name: newCategory });
            if (newCat) {
              newCat.doc_count += 1;
              await newCat.save();
            }
          }
        }
        document.category = newCategory;
      }

      if (tags !== null && tags !== undefined) {
        document.tags = tags.map(t => t.trim()).filter(t => t);
      }

      await document.save();

      logger.info(`文档更新成功: doc_id=${docId}, user=${user}`);
      
      return {
        success: true,
        data: {
          doc_id: document.doc_id,
          current_version: document.current_version
        }
      };
    } catch (error) {
      logger.error(`文档更新失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async deleteDocument(docId, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      const hasPermission = this.checkAdminPermission(document, user);
      if (!hasPermission) {
        throw new Error('无权限删除此文档');
      }

      if (document.category && document.category !== '未分类') {
        const cat = await Category.findOne({ category_name: document.category });
        if (cat && cat.doc_count > 0) {
          cat.doc_count -= 1;
          await cat.save();
        }
      }

      await Document.deleteOne({ doc_id: docId });

      logger.info(`文档删除成功: doc_id=${docId}, user=${user}`);
      
      return {
        success: true,
        message: '文档已删除'
      };
    } catch (error) {
      logger.error(`文档删除失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getUserDocuments(user, page = 1, pageSize = 20) {
    try {
      const skip = (page - 1) * pageSize;
      
      const docs = await Document.find({
        $or: [
          { author: user },
          { 'permissions.read': user }
        ]
      })
      .sort({ updated_at: -1 })
      .skip(skip)
      .limit(pageSize)
      .select('doc_id title author category tags status current_version created_at updated_at');

      const total = await Document.countDocuments({
        $or: [
          { author: user },
          { 'permissions.read': user }
        ]
      });

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
      logger.error(`获取用户文档列表失败: ${error.message}`, { user, error });
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
  },

  checkWritePermission(document, user) {
    if (document.author === user) return true;
    if (document.permissions.write.includes(user)) return true;
    if (document.permissions.admin.includes(user)) return true;
    return false;
  },

  checkAdminPermission(document, user) {
    if (document.author === user) return true;
    if (document.permissions.admin.includes(user)) return true;
    return false;
  }
};

module.exports = documentService;
