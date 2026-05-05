const { Category, Document } = require('../models');
const logger = require('../utils/logger');
const config = require('../config/config');
const eventBus = require('./eventBus');
const redisService = require('./redisService');

const EVENTS = {
  CATEGORY_CREATED: 'category:created',
  CATEGORY_UPDATED: 'category:updated',
  CATEGORY_DELETED: 'category:deleted'
};

const CACHE_KEYS = {
  CATEGORY_LIST: 'category:list',
  CATEGORY_BY_ID: 'category:by_id',
  CATEGORY_BY_NAME: 'category:by_name'
};

class CategoryConfigManager {
  constructor() {
    this.cachedCategories = null;
    this.cachedCategoriesById = new Map();
    this.cachedCategoriesByName = new Map();
    this.lastCacheTime = null;
    this.cacheTTL = 60000;
    this.isInitialized = false;
  }

  async initialize() {
    if (this.isInitialized) {
      return;
    }

    try {
      await this.refreshCache();
      this.isInitialized = true;
      logger.info('分类配置管理器已初始化');
    } catch (error) {
      logger.error('分类配置管理器初始化失败', { error: error.message });
    }
  }

  async refreshCache() {
    try {
      const categories = await Category.find({})
        .sort({ created_at: -1 })
        .select('category_id category_name parent_category description doc_count created_at');

      this.cachedCategories = categories;
      this.cachedCategoriesById.clear();
      this.cachedCategoriesByName.clear();

      for (const category of categories) {
        this.cachedCategoriesById.set(category.category_id, category);
        this.cachedCategoriesByName.set(category.category_name, category);
      }

      this.lastCacheTime = Date.now();

      logger.debug(`分类缓存已刷新: count=${categories.length}`);

      return categories;
    } catch (error) {
      logger.error('刷新分类缓存失败', { error: error.message });
      throw error;
    }
  }

  isCacheValid() {
    if (!this.lastCacheTime) {
      return false;
    }
    return Date.now() - this.lastCacheTime < this.cacheTTL;
  }

  async getAllCategories(includeParent = false, forceRefresh = false) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    if (forceRefresh || !this.isCacheValid()) {
      await this.refreshCache();
    }

    if (!this.cachedCategories) {
      return {
        success: true,
        data: []
      };
    }

    let categories = this.cachedCategories;

    if (includeParent) {
      const categoryMap = {};
      categories.forEach(cat => {
        categoryMap[cat.category_name] = cat.toObject ? cat.toObject() : cat;
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
  }

  async getCategoryById(categoryId, forceRefresh = false) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    if (forceRefresh || !this.isCacheValid()) {
      await this.refreshCache();
    }

    const category = this.cachedCategoriesById.get(categoryId);

    if (category) {
      return {
        success: true,
        data: category
      };
    }

    try {
      const categoryFromDB = await Category.findOne({ category_id: categoryId });
      
      if (categoryFromDB) {
        this.cachedCategoriesById.set(categoryId, categoryFromDB);
        
        return {
          success: true,
          data: categoryFromDB
        };
      }

      return {
        success: false,
        error: '分类不存在'
      };
    } catch (error) {
      logger.error('获取分类失败', { error: error.message, categoryId });
      return {
        success: false,
        error: error.message
      };
    }
  }

  async getCategoryByName(categoryName, forceRefresh = false) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    if (forceRefresh || !this.isCacheValid()) {
      await this.refreshCache();
    }

    const category = this.cachedCategoriesByName.get(categoryName);

    if (category) {
      return {
        success: true,
        data: category
      };
    }

    try {
      const categoryFromDB = await Category.findOne({ category_name: categoryName });
      
      if (categoryFromDB) {
        this.cachedCategoriesByName.set(categoryName, categoryFromDB);
        
        return {
          success: true,
          data: categoryFromDB
        };
      }

      return {
        success: false,
        error: '分类不存在'
      };
    } catch (error) {
      logger.error('获取分类失败', { error: error.message, categoryName });
      return {
        success: false,
        error: error.message
      };
    }
  }

  async createCategory(categoryName, description = '', parentCategory = null, user = null) {
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
        doc_count: 0,
        created_by: user
      });

      await category.save();

      await this.refreshCache();

      logger.info(`分类创建成功: category_name=${categoryName}, user=${user}`);

      eventBus.publish(EVENTS.CATEGORY_CREATED, {
        categoryId: category.category_id,
        categoryName: categoryName,
        user,
        createdAt: Date.now()
      });
      
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
  }

  async updateCategory(categoryId, categoryName = null, description = null, parentCategory = null, user = null) {
    try {
      const category = await Category.findOne({ category_id: categoryId });
      
      if (!category) {
        throw new Error('分类不存在');
      }

      const oldName = category.category_name;
      let nameChanged = false;

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
        
        category.category_name = categoryName.trim();
        nameChanged = true;

        if (nameChanged) {
          await Document.updateMany(
            { category: oldName },
            { category: categoryName.trim() }
          );
        }
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

      category.updated_at = Date.now();
      category.updated_by = user;

      await category.save();

      await this.refreshCache();

      logger.info(`分类更新成功: category_id=${categoryId}, user=${user}`);

      eventBus.publish(EVENTS.CATEGORY_UPDATED, {
        categoryId: category.category_id,
        categoryName: category.category_name,
        oldName,
        user,
        updatedAt: Date.now()
      });
      
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
  }

  async deleteCategory(categoryId, moveTo = '未分类', user = null) {
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

      const categoryName = category.category_name;
      await Category.deleteOne({ category_id: categoryId });

      await this.refreshCache();

      logger.info(`分类删除成功: category_id=${categoryId}, category_name=${categoryName}`);

      eventBus.publish(EVENTS.CATEGORY_DELETED, {
        categoryId,
        categoryName,
        movedDocs: docs.length,
        user,
        deletedAt: Date.now()
      });
      
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
  }

  async getCategoryTree() {
    if (!this.isInitialized) {
      await this.initialize();
    }

    if (!this.isCacheValid()) {
      await this.refreshCache();
    }

    const categories = this.cachedCategories || [];
    
    const categoryMap = {};
    const roots = [];

    for (const category of categories) {
      const catObj = category.toObject ? category.toObject() : category;
      categoryMap[catObj.category_id] = {
        ...catObj,
        children: []
      };
    }

    for (const category of categories) {
      const catObj = category.toObject ? category.toObject() : category;
      const node = categoryMap[catObj.category_id];
      
      if (catObj.parent_category) {
        const parentNode = Object.values(categoryMap).find(
          c => c.category_name === catObj.parent_category
        );
        if (parentNode) {
          parentNode.children.push(node);
        } else {
          roots.push(node);
        }
      } else {
        roots.push(node);
      }
    }

    return {
      success: true,
      data: {
        tree: roots,
        total: categories.length
      }
    };
  }

  async updateCategoryDocCount(categoryName, delta = 0) {
    try {
      const category = await Category.findOne({ category_name: categoryName });
      
      if (category) {
        category.doc_count = Math.max(0, (category.doc_count || 0) + delta);
        await category.save();
        
        await this.refreshCache();
      }
    } catch (error) {
      logger.error('更新分类文档计数失败', { error: error.message, categoryName });
    }
  }

  async getPopularCategories(user, limit = 10) {
    try {
      const categories = await Category.find({})
        .sort({ doc_count: -1 })
        .limit(limit)
        .select('category_id category_name description doc_count');

      return {
        success: true,
        data: categories
      };
    } catch (error) {
      logger.error('获取热门分类失败', { error: error.message });
      return {
        success: false,
        error: error.message
      };
    }
  }

  getCacheStatus() {
    return {
      initialized: this.isInitialized,
      cacheValid: this.isCacheValid(),
      lastCacheTime: this.lastCacheTime,
      cachedCount: this.cachedCategories ? this.cachedCategories.length : 0,
      cacheTTL: this.cacheTTL
    };
  }

  async clearCache() {
    this.cachedCategories = null;
    this.cachedCategoriesById.clear();
    this.cachedCategoriesByName.clear();
    this.lastCacheTime = null;
    logger.info('分类缓存已清空');
    return { success: true };
  }
}

const categoryConfigManager = new CategoryConfigManager();

module.exports = {
  CategoryConfigManager,
  categoryConfigManager,
  EVENTS
};
