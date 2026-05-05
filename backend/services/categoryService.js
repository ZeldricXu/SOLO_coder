const { Category, Document } = require('../models');
const logger = require('../utils/logger');

const categoryService = {
  async createCategory(categoryName, description = '', parentCategory = null) {
    try {
      if (!categoryName || categoryName.trim() === '') {
        throw new Error('分类名称不能为空');
      }

      const existing = await Category.findOne({ category_name: categoryName.trim() });
      if (existing) {
        throw new Error('分类名称已存在');
      }

      if (parentCategory) {
        const parent = await Category.findOne({ category_name: parentCategory });
        if (!parent) {
          throw new Error('父分类不存在');
        }
      }

      const category = new Category({
        category_name: categoryName.trim(),
        description: description || '',
        parent_category: parentCategory || null,
        doc_count: 0
      });

      await category.save();

      logger.info(`分类创建成功: category_name=${categoryName}`);
      
      return {
        success: true,
        data: {
          category_id: category.category_id,
          category_name: category.category_name
        }
      };
    } catch (error) {
      logger.error(`分类创建失败: ${error.message}`, { categoryName, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getAllCategories(includeParent = false) {
    try {
      const categories = await Category.find({})
        .sort({ created_at: -1 })
        .select('category_id category_name parent_category description doc_count created_at');

      if (includeParent) {
        const categoryMap = {};
        categories.forEach(cat => {
          categoryMap[cat.category_name] = cat;
        });

        const result = categories.map(cat => {
          const catObj = cat.toObject ? cat.toObject() : cat;
          if (catObj.parent_category && categoryMap[catObj.parent_category]) {
            catObj.parent = categoryMap[catObj.parent_category];
          }
          return catObj;
        });

        return {
          success: true,
          data: result
        };
      }

      return {
        success: true,
        data: categories
      };
    } catch (error) {
      logger.error(`获取分类列表失败: ${error.message}`, { error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getCategoryById(categoryId) {
    try {
      const category = await Category.findOne({ category_id: categoryId });
      
      if (!category) {
        throw new Error('分类不存在');
      }

      return {
        success: true,
        data: category
      };
    } catch (error) {
      logger.error(`获取分类失败: ${error.message}`, { categoryId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async updateCategory(categoryId, categoryName = null, description = null, parentCategory = null) {
    try {
      const category = await Category.findOne({ category_id: categoryId });
      
      if (!category) {
        throw new Error('分类不存在');
      }

      if (categoryName !== null && categoryName !== undefined) {
        if (categoryName.trim() === '') {
          throw new Error('分类名称不能为空');
        }
        
        const existing = await Category.findOne({ 
          category_name: categoryName.trim(),
          category_id: { $ne: categoryId }
        });
        if (existing) {
          throw new Error('分类名称已存在');
        }
        
        const oldName = category.category_name;
        category.category_name = categoryName.trim();
        
        await Document.updateMany(
          { category: oldName },
          { category: categoryName.trim() }
        );
      }

      if (description !== null && description !== undefined) {
        category.description = description;
      }

      if (parentCategory !== undefined) {
        if (parentCategory) {
          const parent = await Category.findOne({ category_name: parentCategory });
          if (!parent) {
            throw new Error('父分类不存在');
          }
          if (parent.category_id === categoryId) {
            throw new Error('不能将自身设为父分类');
          }
        }
        category.parent_category = parentCategory || null;
      }

      await category.save();

      logger.info(`分类更新成功: category_id=${categoryId}`);
      
      return {
        success: true,
        data: category
      };
    } catch (error) {
      logger.error(`分类更新失败: ${error.message}`, { categoryId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async deleteCategory(categoryId, moveTo = '未分类') {
    try {
      const category = await Category.findOne({ category_id: categoryId });
      
      if (!category) {
        throw new Error('分类不存在');
      }

      const childCategories = await Category.find({ parent_category: category.category_name });
      if (childCategories.length > 0) {
        throw new Error('存在子分类，无法删除');
      }

      const docs = await Document.find({ category: category.category_name });
      
      if (docs.length > 0) {
        if (moveTo === '未分类') {
          await Document.updateMany(
            { category: category.category_name },
            { category: '未分类' }
          );
        } else {
          const targetCat = await Category.findOne({ category_name: moveTo });
          if (!targetCat) {
            throw new Error('目标分类不存在');
          }
          
          await Document.updateMany(
            { category: category.category_name },
            { category: moveTo }
          );
          
          targetCat.doc_count += docs.length;
          await targetCat.save();
        }
      }

      await Category.deleteOne({ category_id: categoryId });

      logger.info(`分类删除成功: category_id=${categoryId}`);
      
      return {
        success: true,
        message: '分类已删除',
        moved_docs: docs.length
      };
    } catch (error) {
      logger.error(`分类删除失败: ${error.message}`, { categoryId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async getPopularTags(user, limit = 20) {
    try {
      const pipeline = [
        { $match: { author: user } },
        { $unwind: '$tags' },
        { $group: { _id: '$tags', count: { $sum: 1 } } },
        { $sort: { count: -1 } },
        { $limit: limit }
      ];

      const tags = await Document.aggregate(pipeline);

      return {
        success: true,
        data: tags.map(t => ({
          tag: t._id,
          count: t.count
        }))
      };
    } catch (error) {
      logger.error(`获取热门标签失败: ${error.message}`, { user, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async addTagsToDocument(docId, tags, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      if (document.author !== user && !document.permissions.write.includes(user)) {
        throw new Error('无权限编辑此文档');
      }

      const newTags = tags.map(t => t.trim()).filter(t => t);
      const existingTags = document.tags;
      
      const tagsToAdd = newTags.filter(t => !existingTags.includes(t));
      
      if (tagsToAdd.length > 0) {
        document.tags = [...existingTags, ...tagsToAdd];
        await document.save();
      }

      return {
        success: true,
        data: {
          doc_id: docId,
          tags: document.tags
        }
      };
    } catch (error) {
      logger.error(`添加标签失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  },

  async removeTagFromDocument(docId, tag, user) {
    try {
      const document = await Document.findOne({ doc_id: docId });
      
      if (!document) {
        throw new Error('文档不存在');
      }

      if (document.author !== user && !document.permissions.write.includes(user)) {
        throw new Error('无权限编辑此文档');
      }

      const tagToRemove = tag.trim();
      document.tags = document.tags.filter(t => t !== tagToRemove);
      
      await document.save();

      return {
        success: true,
        data: {
          doc_id: docId,
          tags: document.tags
        }
      };
    } catch (error) {
      logger.error(`移除标签失败: ${error.message}`, { docId, error });
      return {
        success: false,
        error: error.message
      };
    }
  }
};

module.exports = categoryService;
