const documentService = require('./documentService');
const versionService = require('./versionService');
const categoryService = require('./categoryService');
const searchService = require('./searchService');
const shareService = require('./shareService');
const commentService = require('./commentService');
const favoriteService = require('./favoriteService');
const storageService = require('./storageService');
const cacheService = require('./cacheService');
const taskQueue = require('./taskQueue');
const asyncSaveService = require('./asyncSaveService');
const eventBus = require('./eventBus');
const redisService = require('./redisService');
const { redisTaskQueue } = require('./redisTaskQueue');
const { compressionStrategyService } = require('./compressionStrategyService');
const { categoryConfigManager } = require('./categoryConfigManager');

module.exports = {
  documentService,
  versionService,
  categoryService,
  searchService,
  shareService,
  commentService,
  favoriteService,
  storageService,
  cacheService,
  taskQueue,
  asyncSaveService,
  eventBus,
  redisService,
  redisTaskQueue,
  compressionStrategyService,
  categoryConfigManager
};
