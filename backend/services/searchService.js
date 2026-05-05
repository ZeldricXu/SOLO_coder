const { Document } = require('../models');
const logger = require('../utils/logger');
const config = require('../config/config');
const cacheService = require('./cacheService');
const eventBus = require('./eventBus');

const EVENTS = {
  DOCUMENT_UPDATED: 'document:updated',
  DOCUMENT_CREATED: 'document:created',
  DOCUMENT_DELETED: 'document:deleted',
  CATEGORY_UPDATED: 'category:updated',
  CATEGORY_DELETED: 'category:deleted'
};

class SearchService {
  constructor() {
    this.subscriptionIds = [];
    this.initializeEventListeners();
  }

  initializeEventListeners() {
    const documentUpdatedSub = eventBus.subscribe(
      EVENTS.DOCUMENT_UPDATED,
      (event) => this.handleDocumentUpdated(event)
    );
    this.subscriptionIds.push(documentUpdatedSub);

    const documentCreatedSub = eventBus.subscribe(
      EVENTS.DOCUMENT_CREATED,
      (event) => this.handleDocumentCreated(event)
    );
    this.subscriptionIds.push(documentCreatedSub);

    const documentDeletedSub = eventBus.subscribe(
      EVENTS.DOCUMENT_DELETED,
      (event) => this.handleDocumentDeleted(event)
    );
    this.subscriptionIds.push(documentDeletedSub);

    const categoryUpdatedSub = eventBus.subscribe(
      EVENTS.CATEGORY_UPDATED,
      (event) => this.handleCategoryUpdated(event)
    );
    this.subscriptionIds.push(categoryUpdatedSub);

    const categoryDeletedSub = eventBus.subscribe(
      EVENTS.CATEGORY_DELETED,
      (event) => this.handleCategoryDeleted(event)
    );
    this.subscriptionIds.push(categoryDeletedSub);

    logger.info('搜索服务事件监听器已初始化');
  }

  handleDocumentUpdated(event) {
    const { docId, user } = event.data;
    logger.debug(`处理文档更新事件: docId=${docId}, user=${user}`);
    
    if (user) {
      this.invalidateUserCache(user);
    }
    if (docId) {
      this.invalidateSearchCache(docId);
    }
  }

  handleDocumentCreated(event) {
    const { docId, user } = event.data;
    logger.debug(`处理文档创建事件: docId=${docId}, user=${user}`);
    
    if (user) {
      this.invalidateUserCache(user);
    }
  }

  handleDocumentDeleted(event) {
    const { docId, user } = event.data;
    logger.debug(`处理文档删除事件: docId=${docId}, user=${user}`);
    
    if (user) {
      this.invalidateUserCache(user);
    }
    if (docId) {
      this.invalidateSearchCache(docId);
    }
  }

  handleCategoryUpdated(event) {
    const { categoryId, categoryName } = event.data;
    logger.debug(`处理分类更新事件: categoryId=${categoryId}, categoryName=${categoryName}`);
    
    if (categoryName) {
      const patterns = [
        `search:.*category.*${categoryName}.*`,
        `search:category.*`
      ];
      let invalidatedCount = 0;
      for (const pattern of patterns) {
        invalidatedCount += cacheService.deleteByPattern(pattern);
      }
      logger.info(`分类更新导致缓存失效: categoryName=${categoryName}, invalidated=${invalidatedCount}`);
    }
  }

  handleCategoryDeleted(event) {
    const { categoryId, categoryName } = event.data;
    logger.debug(`处理分类删除事件: categoryId=${categoryId}, categoryName=${categoryName}`);
    
    const invalidatedCount = cacheService.deleteByPrefix('search:');
    logger.info(`分类删除导致缓存全部失效: invalidated=${invalidatedCount}`);
  }

  generateSearchCacheKey(user, keyword, category, tags, status, page, pageSize) {
    const params = {
      user,
      keyword: keyword || '',
      category: category || null,
      tags: tags || [],
      status: status || null,
      page: page,
      pageSize: pageSize
    };
    return cacheService.generateKey('search:documents', params);
  }

  async searchDocuments(user, keyword = '', category = null, tags = [], status = null, page = 1, pageSize = 20) {
    try {
      const cacheKey = this.generateSearchCacheKey(user, keyword, category, tags, status, page, pageSize);
      
      if (config.cache.enabled) {
        const cachedResult = cacheService.get(cacheKey);
        if (cachedResult !== null) {
          logger.debug(`检索结果缓存命中: key=${cacheKey}`);
          return {
            success: true,
            data: {
              ...cachedResult,
              from_cache: true
            }
          };
        }
      }

      const skip = (page - 1) * pageSize;
      
      let query = {
        $or: [
          { author: user },
          { 'permissions.read': user }
        ]
      };

      if (keyword && keyword.trim()) {
        const keywordRegex = new RegExp(keyword.trim(), 'i');
        query.$and = [
          {
            $or: [
              { title: keywordRegex },
              { content: keywordRegex },
              { tags: keywordRegex }
            ]
          }
        ];
      }

      if (category && category !== 'all') {
        if (!query.$and) query.$and = [];
        query.$and.push({ category: category });
      }

      if (tags && tags.length > 0) {
        if (!query.$and) query.$and = [];
        query.$and.push({ tags: { $all: tags } });
      }

      if (status && status !== 'all') {
        if (!query.$and) query.$and = [];
        query.$and.push({ status: status });
      }

      const docs = await Document.find(query)
        .sort({ updated_at: -1 })
        .skip(skip)
        .limit(pageSize)
        .select('doc_id title author category tags status current_version created_at updated_at');

      const total = await Document.countDocuments(query);

      const stats = await this.getSearchStats(query, keyword, category, tags);

      const result = {
        docs,
        stats,
        pagination: {
          page,
          pageSize,
          total,
          totalPages: Math.ceil(total / pageSize)
        }
      };

      if (config.cache.enabled) {
        const cacheTTL = config.cache.searchTTL || config.cache.defaultTTL;
        cacheService.set(cacheKey, result, cacheTTL);
        logger.debug(`检索结果已缓存: key=${cacheKey}, ttl=${cacheTTL}ms`);
      }

      return {
        success: true,
        data: {
          ...result,
          from_cache: false
        }
      };
    } catch (error) {
      logger.error(`文档检索失败: ${error.message}`, { keyword, error });
      return {
        success: false,
        error: error.message
      };
    }
  }

  async getSearchStats(query, keyword, category, tags) {
    try {
      const stats = {};

      const categoryStats = await Document.aggregate([
        { $match: query },
        { $group: { _id: '$category', count: { $sum: 1 } } },
        { $sort: { count: -1 } }
      ]);
      
      stats.categories = categoryStats.map(c => ({
        category: c._id,
        count: c.count
      }));

      const tagsStats = await Document.aggregate([
        { $match: query },
        { $unwind: '$tags' },
        { $group: { _id: '$tags', count: { $sum: 1 } } },
        { $sort: { count: -1 } },
        { $limit: 20 }
      ]);
      
      stats.popular_tags = tagsStats.map(t => ({
        tag: t._id,
        count: t.count
      }));

      const statusStats = await Document.aggregate([
        { $match: query },
        { $group: { _id: '$status', count: { $sum: 1 } } }
      ]);
      
      stats.status = statusStats.map(s => ({
        status: s._id,
        count: s.count
      }));

      return stats;
    } catch (error) {
      logger.error(`获取检索统计失败: ${error.message}`, { error });
      return {};
    }
  }

  async quickSearch(user, keyword, limit = 10) {
    try {
      const cacheKey = cacheService.generateKey('search:quick', { user, keyword, limit });
      
      if (config.cache.enabled) {
        const cachedResult = cacheService.get(cacheKey);
        if (cachedResult !== null) {
          logger.debug(`快速检索缓存命中: key=${cacheKey}`);
          return {
            success: true,
            data: {
              ...cachedResult,
              from_cache: true
            }
          };
        }
      }

      if (!keyword || !keyword.trim()) {
        return {
          success: true,
          data: {
            docs: [],
            suggestions: []
          }
        };
      }

      const keywordRegex = new RegExp(keyword.trim(), 'i');
      
      const query = {
        $and: [
          {
            $or: [
              { author: user },
              { 'permissions.read': user }
            ]
          },
          {
            $or: [
              { title: keywordRegex },
              { tags: keywordRegex }
            ]
          }
        ]
      };

      const docs = await Document.find(query)
        .sort({ updated_at: -1 })
        .limit(limit)
        .select('doc_id title author category tags status created_at');

      const suggestions = await this.getSearchSuggestions(user, keyword);

      const result = {
        docs,
        suggestions
      };

      if (config.cache.enabled) {
        cacheService.set(cacheKey, result, config.cache.searchTTL || 30000);
        logger.debug(`快速检索结果已缓存: key=${cacheKey}`);
      }

      return {
        success: true,
        data: {
          ...result,
          from_cache: false
        }
      };
    } catch (error) {
      logger.error(`快速检索失败: ${error.message}`, { keyword, error });
      return {
        success: false,
        error: error.message
      };
    }
  }

  async getSearchSuggestions(user, keyword) {
    try {
      if (!keyword || !keyword.trim()) {
        return [];
      }

      const keywordRegex = new RegExp(keyword.trim(), 'i');
      
      const query = {
        $and: [
          {
            $or: [
              { author: user },
              { 'permissions.read': user }
            ]
          }
        ]
      };

      const titleSuggestions = await Document.distinct('title', {
        ...query,
        title: keywordRegex
      }).limit(5);

      const tagSuggestions = await Document.distinct('tags', {
        ...query,
        tags: keywordRegex
      }).limit(10);

      const categorySuggestions = await Document.distinct('category', {
        ...query,
        category: keywordRegex
      }).limit(5);

      const suggestions = [
        ...titleSuggestions.map(t => ({ type: 'title', text: t })),
        ...tagSuggestions.map(t => ({ type: 'tag', text: t })),
        ...categorySuggestions.map(t => ({ type: 'category', text: t }))
      ];

      return suggestions;
    } catch (error) {
      logger.error(`获取检索建议失败: ${error.message}`, { keyword, error });
      return [];
    }
  }

  async getRecentDocuments(user, limit = 10) {
    try {
      const cacheKey = cacheService.generateKey('search:recent', { user, limit });
      
      if (config.cache.enabled) {
        const cachedResult = cacheService.get(cacheKey);
        if (cachedResult !== null) {
          logger.debug(`最近文档缓存命中: key=${cacheKey}`);
          return {
            success: true,
            data: {
              ...cachedResult,
              from_cache: true
            }
          };
        }
      }

      const docs = await Document.find({
        $or: [
          { author: user },
          { 'permissions.read': user }
        ]
      })
      .sort({ updated_at: -1 })
      .limit(limit)
      .select('doc_id title author category tags status current_version updated_at');

      const result = {
        docs
      };

      if (config.cache.enabled) {
        cacheService.set(cacheKey, result, config.cache.searchTTL || 30000);
        logger.debug(`最近文档已缓存: key=${cacheKey}`);
      }

      return {
        success: true,
        data: {
          ...result,
          from_cache: false
        }
      };
    } catch (error) {
      logger.error(`获取最近文档失败: ${error.message}`, { user, error });
      return {
        success: false,
        error: error.message
      };
    }
  }

  async getDocumentsByCategory(user, category, page = 1, pageSize = 20) {
    try {
      const cacheKey = cacheService.generateKey('search:category', { user, category, page, pageSize });
      
      if (config.cache.enabled) {
        const cachedResult = cacheService.get(cacheKey);
        if (cachedResult !== null) {
          logger.debug(`分类文档缓存命中: key=${cacheKey}`);
          return {
            success: true,
            data: {
              ...cachedResult,
              from_cache: true
            }
          };
        }
      }

      const skip = (page - 1) * pageSize;
      
      const query = {
        $and: [
          {
            $or: [
              { author: user },
              { 'permissions.read': user }
            ]
          },
          { category: category }
        ]
      };

      const docs = await Document.find(query)
        .sort({ updated_at: -1 })
        .skip(skip)
        .limit(pageSize)
        .select('doc_id title author category tags status current_version created_at updated_at');

      const total = await Document.countDocuments(query);

      const result = {
        category,
        docs,
        pagination: {
          page,
          pageSize,
          total,
          totalPages: Math.ceil(total / pageSize)
        }
      };

      if (config.cache.enabled) {
        cacheService.set(cacheKey, result, config.cache.searchTTL || 30000);
        logger.debug(`分类文档已缓存: key=${cacheKey}`);
      }

      return {
        success: true,
        data: {
          ...result,
          from_cache: false
        }
      };
    } catch (error) {
      logger.error(`按分类获取文档失败: ${error.message}`, { category, error });
      return {
        success: false,
        error: error.message
      };
    }
  }

  async getDocumentsByTags(user, tags, page = 1, pageSize = 20) {
    try {
      const cacheKey = cacheService.generateKey('search:tags', { user, tags, page, pageSize });
      
      if (config.cache.enabled) {
        const cachedResult = cacheService.get(cacheKey);
        if (cachedResult !== null) {
          logger.debug(`标签文档缓存命中: key=${cacheKey}`);
          return {
            success: true,
            data: {
              ...cachedResult,
              from_cache: true
            }
          };
        }
      }

      if (!tags || tags.length === 0) {
        return {
          success: false,
          error: '标签不能为空'
        };
      }

      const skip = (page - 1) * pageSize;
      
      const query = {
        $and: [
          {
            $or: [
              { author: user },
              { 'permissions.read': user }
            ]
          },
          { tags: { $all: tags } }
        ]
      };

      const docs = await Document.find(query)
        .sort({ updated_at: -1 })
        .skip(skip)
        .limit(pageSize)
        .select('doc_id title author category tags status current_version created_at updated_at');

      const total = await Document.countDocuments(query);

      const result = {
        tags,
        docs,
        pagination: {
          page,
          pageSize,
          total,
          totalPages: Math.ceil(total / pageSize)
        }
      };

      if (config.cache.enabled) {
        cacheService.set(cacheKey, result, config.cache.searchTTL || 30000);
        logger.debug(`标签文档已缓存: key=${cacheKey}`);
      }

      return {
        success: true,
        data: {
          ...result,
          from_cache: false
        }
      };
    } catch (error) {
      logger.error(`按标签获取文档失败: ${error.message}`, { tags, error });
      return {
        success: false,
        error: error.message
      };
    }
  }

  invalidateSearchCache(docId = null, user = null) {
    if (!config.cache.enabled) {
      return 0;
    }

    let invalidatedCount = 0;

    if (docId) {
      invalidatedCount += cacheService.deleteByPattern(`search:.*doc_id.*${docId}.*`);
    }

    if (user) {
      invalidatedCount += cacheService.deleteByPattern(`search:.*user.*${user}.*`);
    } else {
      invalidatedCount += cacheService.deleteByPrefix('search:');
    }

    logger.info(`检索缓存已失效: invalidated=${invalidatedCount} entries`);
    return invalidatedCount;
  }

  invalidateUserCache(user) {
    if (!config.cache.enabled) {
      return 0;
    }
    
    const patterns = [
      `search:documents.*user.*${user}.*`,
      `search:quick.*user.*${user}.*`,
      `search:recent.*user.*${user}.*`,
      `search:category.*user.*${user}.*`,
      `search:tags.*user.*${user}.*`
    ];

    let invalidatedCount = 0;
    for (const pattern of patterns) {
      invalidatedCount += cacheService.deleteByPattern(pattern);
    }

    logger.debug(`用户检索缓存已失效: user=${user}, invalidated=${invalidatedCount}`);
    return invalidatedCount;
  }

  invalidateCacheByPattern(pattern) {
    if (!config.cache.enabled) {
      return 0;
    }
    const count = cacheService.deleteByPattern(pattern);
    logger.debug(`按模式失效缓存: pattern=${pattern}, count=${count}`);
    return count;
  }

  getCacheStats() {
    return cacheService.getStats();
  }

  clearAllCache() {
    cacheService.clear();
    logger.info('所有检索缓存已清空');
  }

  shutdown() {
    for (const subId of this.subscriptionIds) {
      eventBus.unsubscribe(subId);
    }
    this.subscriptionIds = [];
    logger.info('搜索服务已关闭，事件监听器已移除');
  }
}

const searchService = new SearchService();

module.exports = searchService;
