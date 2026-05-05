const LOAD_STATUSES = {
  IDLE: 'idle',
  LOADING: 'loading',
  LOADED: 'loaded',
  ERROR: 'error'
};

const PLACEHOLDER_TYPES = {
  IMAGE: 'image',
  VIDEO: 'video',
  AUDIO: 'audio',
  OTHER: 'other'
};

const NETWORK_SPEED = {
  SLOW: 'slow',
  MEDIUM: 'medium',
  FAST: 'fast',
  VERY_FAST: 'very_fast'
};

const NETWORK_CONFIGS = {
  [NETWORK_SPEED.SLOW]: {
    concurrentLoads: 1,
    preloadBuffer: 0,
    preloadCount: 0,
    enablePreload: false,
    lazyLoadEnabled: true,
    description: 'Slow network - lazy load only'
  },
  [NETWORK_SPEED.MEDIUM]: {
    concurrentLoads: 2,
    preloadBuffer: 100,
    preloadCount: 3,
    enablePreload: true,
    lazyLoadEnabled: true,
    description: 'Medium network - limited preload'
  },
  [NETWORK_SPEED.FAST]: {
    concurrentLoads: 4,
    preloadBuffer: 300,
    preloadCount: 8,
    enablePreload: true,
    lazyLoadEnabled: true,
    description: 'Fast network - moderate preload'
  },
  [NETWORK_SPEED.VERY_FAST]: {
    concurrentLoads: 8,
    preloadBuffer: 500,
    preloadCount: 15,
    enablePreload: true,
    lazyLoadEnabled: false,
    description: 'Very fast network - aggressive preload'
  }
};

const BANDWIDTH_THRESHOLDS = {
  SLOW_MAX_KBPS: 500,
  MEDIUM_MAX_KBPS: 2000,
  FAST_MAX_KBPS: 10000
};

class NetworkBandwidthDetector {
  constructor(options = {}) {
    this.testUrls = options.testUrls || [];
    this.currentBandwidth = null;
    this.currentNetworkType = NETWORK_SPEED.FAST;
    this.lastTestTime = 0;
    this.testInterval = options.testInterval || 30000;
    this.sampleSize = options.sampleSize || 3;
    this.bandwidthSamples = [];
    this.listeners = [];
    this.connectionApiAvailable = false;
    this.connection = null;
    
    this.init();
  }

  init() {
    if (typeof navigator !== 'undefined' && navigator.connection) {
      this.connectionApiAvailable = true;
      this.connection = navigator.connection;
      
      this.connection.addEventListener('change', () => {
        this.handleConnectionChange();
      });
      
      console.log('[NetworkDetector] Network Information API available');
      this.updateFromConnectionApi();
    }
  }

  updateFromConnectionApi() {
    if (!this.connection) return;
    
    const effectiveType = this.connection.effectiveType;
    const downlink = this.connection.downlink;
    const saveData = this.connection.saveData;
    
    console.log(`[NetworkDetector] Connection API: effectiveType=${effectiveType}, downlink=${downlink}Mbps, saveData=${saveData}`);
    
    if (saveData) {
      this.currentNetworkType = NETWORK_SPEED.SLOW;
      this.currentBandwidth = 100;
      this.notifyListeners();
      return;
    }
    
    if (effectiveType === 'slow-2g' || effectiveType === '2g') {
      this.currentNetworkType = NETWORK_SPEED.SLOW;
      this.currentBandwidth = 250;
    } else if (effectiveType === '3g') {
      this.currentNetworkType = NETWORK_SPEED.MEDIUM;
      this.currentBandwidth = 750;
    } else if (effectiveType === '4g') {
      if (downlink >= 10) {
        this.currentNetworkType = NETWORK_SPEED.VERY_FAST;
        this.currentBandwidth = downlink * 1000;
      } else {
        this.currentNetworkType = NETWORK_SPEED.FAST;
        this.currentBandwidth = downlink * 1000;
      }
    }
    
    this.notifyListeners();
  }

  handleConnectionChange() {
    console.log('[NetworkDetector] Network connection changed');
    this.updateFromConnectionApi();
    this.runBandwidthTest(true);
  }

  addListener(callback) {
    this.listeners.push(callback);
  }

  removeListener(callback) {
    const index = this.listeners.indexOf(callback);
    if (index > -1) {
      this.listeners.splice(index, 1);
    }
  }

  notifyListeners() {
    const config = this.getCurrentConfig();
    this.listeners.forEach(callback => {
      try {
        callback(config, this.currentNetworkType, this.currentBandwidth);
      } catch (error) {
        console.error('[NetworkDetector] Listener error:', error);
      }
    });
  }

  async runBandwidthTest(force = false) {
    const now = Date.now();
    
    if (!force && now - this.lastTestTime < this.testInterval) {
      return this.currentNetworkType;
    }
    
    if (this.testUrls.length === 0) {
      console.log('[NetworkDetector] No test URLs available, using default configuration');
      if (!this.currentBandwidth) {
        this.currentNetworkType = NETWORK_SPEED.FAST;
        this.currentBandwidth = 5000;
      }
      return this.currentNetworkType;
    }
    
    console.log('[NetworkDetector] Running bandwidth test...');
    
    try {
      const bandwidth = await this.measureBandwidth();
      
      this.bandwidthSamples.push(bandwidth);
      if (this.bandwidthSamples.length > this.sampleSize) {
        this.bandwidthSamples.shift();
      }
      
      const averageBandwidth = this.bandwidthSamples.reduce((a, b) => a + b, 0) / this.bandwidthSamples.length;
      this.currentBandwidth = averageBandwidth;
      
      if (averageBandwidth <= BANDWIDTH_THRESHOLDS.SLOW_MAX_KBPS) {
        this.currentNetworkType = NETWORK_SPEED.SLOW;
      } else if (averageBandwidth <= BANDWIDTH_THRESHOLDS.MEDIUM_MAX_KBPS) {
        this.currentNetworkType = NETWORK_SPEED.MEDIUM;
      } else if (averageBandwidth <= BANDWIDTH_THRESHOLDS.FAST_MAX_KBPS) {
        this.currentNetworkType = NETWORK_SPEED.FAST;
      } else {
        this.currentNetworkType = NETWORK_SPEED.VERY_FAST;
      }
      
      this.lastTestTime = now;
      console.log(`[NetworkDetector] Bandwidth test completed: ${averageBandwidth.toFixed(0)} KB/s, type: ${this.currentNetworkType}`);
      
      this.notifyListeners();
      
    } catch (error) {
      console.error('[NetworkDetector] Bandwidth test failed:', error);
      if (!this.currentBandwidth) {
        this.currentNetworkType = NETWORK_SPEED.FAST;
        this.currentBandwidth = 5000;
      }
    }
    
    return this.currentNetworkType;
  }

  async measureBandwidth() {
    const testUrl = this.testUrls[Math.floor(Math.random() * this.testUrls.length)];
    const startTime = performance.now();
    
    try {
      const response = await fetch(testUrl, {
        method: 'GET',
        cache: 'no-store',
        headers: {
          'Cache-Control': 'no-cache, no-store, must-revalidate',
          'Pragma': 'no-cache'
        }
      });
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      const blob = await response.blob();
      const endTime = performance.now();
      
      const fileSizeKB = blob.size / 1024;
      const durationSeconds = (endTime - startTime) / 1000;
      const bandwidthKBPS = fileSizeKB / Math.max(durationSeconds, 0.001);
      
      console.log(`[NetworkDetector] Test download: ${fileSizeKB.toFixed(2)} KB in ${durationSeconds.toFixed(2)}s = ${bandwidthKBPS.toFixed(0)} KB/s`);
      
      return bandwidthKBPS;
      
    } catch (error) {
      console.warn('[NetworkDetector] Failed to download test file:', error);
      throw error;
    }
  }

  getCurrentConfig() {
    return {
      ...NETWORK_CONFIGS[this.currentNetworkType],
      bandwidth: this.currentBandwidth,
      networkType: this.currentNetworkType
    };
  }

  setNetworkType(type) {
    if (NETWORK_SPEED[type]) {
      this.currentNetworkType = NETWORK_SPEED[type];
      this.notifyListeners();
      console.log(`[NetworkDetector] Manual network type set: ${type}`);
    }
  }

  setTestUrls(urls) {
    this.testUrls = urls;
  }
}

class ThumbnailCache {
  constructor(maxSize = 100) {
    this.cache = new Map();
    this.maxSize = maxSize;
    this.accessOrder = [];
    this.ttl = 5 * 60 * 1000;
    this.timestamps = new Map();
  }

  get(key) {
    const now = Date.now();
    
    if (this.timestamps.has(key)) {
      if (now - this.timestamps.get(key) > this.ttl) {
        this.delete(key);
        return null;
      }
    }
    
    if (this.cache.has(key)) {
      const index = this.accessOrder.indexOf(key);
      if (index > -1) {
        this.accessOrder.splice(index, 1);
        this.accessOrder.push(key);
      }
      return this.cache.get(key);
    }
    return null;
  }

  set(key, value) {
    const now = Date.now();
    
    if (this.cache.size >= this.maxSize) {
      const oldestKey = this.accessOrder.shift();
      if (oldestKey) {
        this.cache.delete(oldestKey);
        this.timestamps.delete(oldestKey);
      }
    }
    
    this.cache.set(key, value);
    this.timestamps.set(key, now);
    
    const index = this.accessOrder.indexOf(key);
    if (index > -1) {
      this.accessOrder.splice(index, 1);
    }
    this.accessOrder.push(key);
  }

  has(key) {
    return this.cache.has(key) && this.timestamps.has(key) && 
           Date.now() - this.timestamps.get(key) <= this.ttl;
  }

  clear() {
    this.cache.clear();
    this.accessOrder = [];
    this.timestamps.clear();
  }

  delete(key) {
    this.cache.delete(key);
    this.timestamps.delete(key);
    const index = this.accessOrder.indexOf(key);
    if (index > -1) {
      this.accessOrder.splice(index, 1);
    }
  }
}

class AdaptiveThumbnailLoader {
  constructor(options = {}) {
    this.cache = new ThumbnailCache(options.maxCacheSize || 200);
    this.loadingPromises = new Map();
    this.baseUrl = options.baseUrl || '/api/v1/media/thumbnail';
    this.defaultPlaceholder = options.defaultPlaceholder || '';
    
    this.observer = null;
    this.observedElements = new Map();
    
    this.networkDetector = new NetworkBandwidthDetector({
      testUrls: options.bandwidthTestUrls || [],
      testInterval: options.bandwidthTestInterval || 30000
    });
    
    const initialConfig = this.networkDetector.getCurrentConfig();
    this.concurrentLoads = initialConfig.concurrentLoads;
    this.preloadBuffer = initialConfig.preloadBuffer;
    this.preloadCount = initialConfig.preloadCount;
    this.enablePreload = initialConfig.enablePreload;
    this.lazyLoadEnabled = initialConfig.lazyLoadEnabled;
    
    this.activeLoads = 0;
    this.loadQueue = [];
    
    this.networkDetector.addListener((config, networkType, bandwidth) => {
      this.handleNetworkChange(config, networkType, bandwidth);
    });
    
    console.log('[AdaptiveThumbnailLoader] Initialized with config:', this.getCurrentConfig());
  }

  getCurrentConfig() {
    return {
      concurrentLoads: this.concurrentLoads,
      preloadBuffer: this.preloadBuffer,
      preloadCount: this.preloadCount,
      enablePreload: this.enablePreload,
      lazyLoadEnabled: this.lazyLoadEnabled,
      networkType: this.networkDetector.currentNetworkType,
      bandwidth: this.networkDetector.currentBandwidth
    };
  }

  handleNetworkChange(config, networkType, bandwidth) {
    console.log(`[AdaptiveThumbnailLoader] Network changed: ${networkType} (${bandwidth} KB/s)`);
    console.log(`[AdaptiveThumbnailLoader] New config: concurrent=${config.concurrentLoads}, preload=${config.preloadCount}`);
    
    this.concurrentLoads = config.concurrentLoads;
    this.preloadBuffer = config.preloadBuffer;
    this.preloadCount = config.preloadCount;
    this.enablePreload = config.enablePreload;
    this.lazyLoadEnabled = config.lazyLoadEnabled;
    
    if (this.observer) {
      this.observer.disconnect();
      this.initLazyLoad();
      
      for (const [element, options] of this.observedElements) {
        this.registerLazyElement(element, options);
      }
    }
    
    if (this.enablePreload) {
      this.processQueue();
    }
  }

  getPlaceholderUrl(fileType) {
    const placeholders = {
      [PLACEHOLDER_TYPES.IMAGE]: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="320" height="240" viewBox="0 0 320 240"%3E%3Crect fill="%23f0f0f0" width="320" height="240"/%3E%3Cpath fill="%23d0d0d0" d="M160 160c-17.7 0-32-14.3-32-32s14.3-32 32-32 32 14.3 32 32-14.3 32-32 32zm0-48c-8.8 0-16 7.2-16 16s7.2 16 16 16 16-7.2 16-16-7.2-16-16-16z"/%3E%3C/svg%3E',
      [PLACEHOLDER_TYPES.VIDEO]: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="320" height="240" viewBox="0 0 320 240"%3E%3Crect fill="%23f0f0f0" width="320" height="240"/%3E%3Cpath fill="%23d0d0d0" d="M128 96v48l48-24z"/%3E%3C/svg%3E',
      [PLACEHOLDER_TYPES.AUDIO]: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="320" height="240" viewBox="0 0 320 240"%3E%3Crect fill="%23f0f0f0" width="320" height="240"/%3E%3Cpath fill="%23d0d0d0" d="M112 120c0-17.7 14.3-32 32-32v-48h-48v96c0 17.7 14.3 32 32 32s32-14.3 32-32h-16z"/%3E%3C/svg%3E',
      [PLACEHOLDER_TYPES.OTHER]: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="320" height="240" viewBox="0 0 320 240"%3E%3Crect fill="%23f0f0f0" width="320" height="240"/%3E%3C/svg%3E'
    };
    
    return placeholders[fileType] || placeholders[PLACEHOLDER_TYPES.OTHER];
  }

  getFileTypePlaceholder(fileType) {
    const typeMap = {
      'image': PLACEHOLDER_TYPES.IMAGE,
      'video': PLACEHOLDER_TYPES.VIDEO,
      'audio': PLACEHOLDER_TYPES.AUDIO
    };
    return this.getPlaceholderUrl(typeMap[fileType] || PLACEHOLDER_TYPES.OTHER);
  }

  getThumbnailUrl(mediaId, options = {}) {
    const { size = 'default', timestamp = null } = options;
    let url = `${this.baseUrl}/${mediaId}`;
    
    const params = [];
    if (size && size !== 'default') {
      params.push(`size=${encodeURIComponent(size)}`);
    }
    if (timestamp !== null) {
      params.push(`t=${timestamp}`);
    }
    
    if (params.length > 0) {
      url += `?${params.join('&')}`;
    }
    
    return url;
  }

  async load(url, options = {}) {
    const { cacheKey = url, forceReload = false, timeout = 30000, priority = 'normal' } = options;
    
    if (!forceReload && this.cache.has(cacheKey)) {
      const cached = this.cache.get(cacheKey);
      if (cached.status === LOAD_STATUSES.LOADED) {
        return { success: true, url: cached.url, fromCache: true };
      }
      if (cached.status === LOAD_STATUSES.ERROR) {
        return { success: false, error: cached.error, fromCache: true };
      }
    }
    
    if (this.loadingPromises.has(cacheKey)) {
      return this.loadingPromises.get(cacheKey);
    }
    
    const loadPromise = new Promise(async (resolve) => {
      try {
        this.cache.set(cacheKey, { status: LOAD_STATUSES.LOADING, url: null });
        
        const result = await this.loadImage(url, timeout);
        
        if (result.success) {
          this.cache.set(cacheKey, {
            status: LOAD_STATUSES.LOADED,
            url: result.url
          });
          resolve({ success: true, url: result.url, fromCache: false });
        } else {
          this.cache.set(cacheKey, {
            status: LOAD_STATUSES.ERROR,
            error: result.error
          });
          resolve({ success: false, error: result.error, fromCache: false });
        }
      } catch (error) {
        this.cache.set(cacheKey, {
          status: LOAD_STATUSES.ERROR,
          error: error.message
        });
        resolve({ success: false, error: error.message, fromCache: false });
      } finally {
        this.loadingPromises.delete(cacheKey);
      }
    });
    
    this.loadingPromises.set(cacheKey, loadPromise);
    return loadPromise;
  }

  loadImage(url, timeout) {
    return new Promise((resolve) => {
      const img = new Image();
      
      const timer = setTimeout(() => {
        img.src = '';
        resolve({ success: false, error: 'Load timeout' });
      }, timeout);
      
      img.onload = () => {
        clearTimeout(timer);
        resolve({ success: true, url: url });
      };
      
      img.onerror = (event) => {
        clearTimeout(timer);
        resolve({ success: false, error: 'Load failed' });
      };
      
      img.src = url;
    });
  }

  async loadMediaThumbnail(mediaId, options = {}) {
    const { size = 'default', forceReload = false, fileType = null, priority = 'normal' } = options;
    const cacheKey = `thumbnail:${mediaId}:${size}`;
    
    if (options.storagePath) {
      const url = this.getThumbnailUrl(mediaId, { size });
      return this.load(url, { cacheKey, forceReload, priority });
    }
    
    const url = this.getThumbnailUrl(mediaId, { size });
    return this.load(url, { cacheKey, forceReload, priority });
  }

  addToQueue(url, options = {}) {
    const { priority = 'normal' } = options;
    
    return new Promise((resolve) => {
      this.loadQueue.push({
        url,
        priority,
        resolve,
        timestamp: Date.now(),
        options: options
      });
      
      this.sortQueue();
      this.processQueue();
    });
  }

  sortQueue() {
    this.loadQueue.sort((a, b) => {
      const priorityValue = { low: 0, normal: 1, high: 2 };
      if (a.priority !== b.priority) {
        return priorityValue[b.priority] - priorityValue[a.priority];
      }
      return a.timestamp - b.timestamp;
    });
  }

  processQueue() {
    while (this.activeLoads < this.concurrentLoads && this.loadQueue.length > 0) {
      const item = this.loadQueue.shift();
      this.activeLoads++;
      
      this.load(item.url, item.options)
        .then(result => {
          item.resolve(result);
        })
        .finally(() => {
          this.activeLoads--;
          this.processQueue();
        });
    }
  }

  preload(urls, options = {}) {
    if (!this.enablePreload) {
      console.log('[AdaptiveThumbnailLoader] Preload disabled for current network');
      return Promise.resolve([]);
    }
    
    const { priority = 'low' } = options;
    const maxToPreload = Math.min(urls.length, this.preloadCount);
    const urlsToPreload = urls.slice(0, maxToPreload);
    
    console.log(`[AdaptiveThumbnailLoader] Preloading ${urlsToPreload.length} thumbnails (network: ${this.networkDetector.currentNetworkType})`);
    
    return Promise.all(
      urlsToPreload.map(url => this.addToQueue(url, { priority }))
    );
  }

  preloadMedia(mediaItems, options = {}) {
    if (!this.enablePreload) {
      return Promise.resolve([]);
    }
    
    const urls = mediaItems.map(item => 
      this.getThumbnailUrl(item.media_id, { size: options.size || 'default' })
    );
    return this.preload(urls, options);
  }

  initLazyLoad(container = null) {
    if (typeof window === 'undefined' || typeof IntersectionObserver === 'undefined') {
      console.warn('[AdaptiveThumbnailLoader] IntersectionObserver not supported');
      return false;
    }

    if (this.observer) {
      this.observer.disconnect();
    }

    this.observer = new IntersectionObserver(
      (entries) => {
        entries.forEach(entry => {
          if (entry.isIntersecting) {
            this.handleElementVisible(entry.target);
          }
        });
      },
      {
        root: container,
        rootMargin: `${this.preloadBuffer}px 0px`,
        threshold: 0.01
      }
    );

    return true;
  }

  registerLazyElement(element, options = {}) {
    if (!this.lazyLoadEnabled) {
      console.log('[AdaptiveThumbnailLoader] Lazy load disabled, loading immediately');
      this.loadImmediately(element, options);
      return;
    }

    if (!this.observer) {
      if (!this.initLazyLoad()) {
        this.load(options.url, options);
        return;
      }
    }

    element.dataset.thumbnailUrl = options.url || '';
    element.dataset.thumbnailCacheKey = options.cacheKey || options.url || '';
    element.dataset.thumbnailFiletype = options.fileType || 'other';
    element.dataset.thumbnailSize = options.size || 'default';
    element.dataset.thumbnailMediaId = options.mediaId || '';
    element.dataset.thumbnailLoading = 'false';
    
    if (options.placeholder !== false) {
      const placeholder = options.placeholder || this.getFileTypePlaceholder(options.fileType);
      if (element.tagName === 'IMG') {
        element.src = placeholder;
      } else if (element.style) {
        element.style.backgroundImage = `url(${placeholder})`;
      }
    }

    this.observedElements.set(element, options);
    this.observer.observe(element);
  }

  loadImmediately(element, options) {
    const url = options.url || (options.mediaId ? this.getThumbnailUrl(options.mediaId, { size: options.size }) : null);
    
    if (!url) return;
    
    if (element.tagName === 'IMG') {
      element.src = url;
    } else if (element.style) {
      element.style.backgroundImage = `url(${url})`;
    }
  }

  unregisterLazyElement(element) {
    if (this.observer) {
      this.observer.unobserve(element);
    }
    this.observedElements.delete(element);
  }

  async handleElementVisible(element) {
    if (element.dataset.thumbnailLoading === 'true') {
      return;
    }

    const url = element.dataset.thumbnailUrl;
    const cacheKey = element.dataset.thumbnailCacheKey;
    const mediaId = element.dataset.thumbnailMediaId;

    if (!url && !mediaId) {
      return;
    }

    element.dataset.thumbnailLoading = 'true';
    element.classList.add('thumbnail-loading');

    try {
      let result;
      if (mediaId) {
        result = await this.loadMediaThumbnail(mediaId, {
          size: element.dataset.thumbnailSize || 'default'
        });
      } else {
        result = await this.load(url, { cacheKey });
      }

      if (result.success) {
        if (element.tagName === 'IMG') {
          element.src = result.url;
        } else if (element.style) {
          element.style.backgroundImage = `url(${result.url})`;
        }
        
        element.classList.remove('thumbnail-loading');
        element.classList.add('thumbnail-loaded');
        element.dataset.thumbnailStatus = 'loaded';
      } else {
        element.classList.remove('thumbnail-loading');
        element.classList.add('thumbnail-error');
        element.dataset.thumbnailStatus = 'error';
      }
    } catch (error) {
      element.classList.remove('thumbnail-loading');
      element.classList.add('thumbnail-error');
      element.dataset.thumbnailStatus = 'error';
    } finally {
      element.dataset.thumbnailLoading = 'false';
    }
  }

  setBandwidthTestUrls(urls) {
    this.networkDetector.setTestUrls(urls);
  }

  async runBandwidthTest(force = false) {
    return this.networkDetector.runBandwidthTest(force);
  }

  getNetworkType() {
    return this.networkDetector.currentNetworkType;
  }

  getBandwidth() {
    return this.networkDetector.currentBandwidth;
  }

  clearCache() {
    this.cache.clear();
  }

  getCacheStats() {
    return {
      total: this.cache.cache.size,
      maxSize: this.cache.maxSize
    };
  }

  destroy() {
    if (this.observer) {
      this.observer.disconnect();
      this.observer = null;
    }
    this.observedElements.clear();
    this.loadQueue = [];
    this.loadingPromises.clear();
    this.cache.clear();
  }
}

const thumbnailLoader = new AdaptiveThumbnailLoader({
  maxCacheSize: 200
});

const createThumbnailComponent = (media, options = {}) => {
  const { 
    size = 'default', 
    lazyLoad = true,
    placeholder = true,
    onLoad = null,
    onError = null
  } = options;

  const img = document.createElement('img');
  img.className = 'media-thumbnail';
  img.alt = media.filename || 'Media thumbnail';
  
  const thumbnailUrl = thumbnailLoader.getThumbnailUrl(media.media_id, { size });
  
  if (lazyLoad && thumbnailLoader.lazyLoadEnabled) {
    thumbnailLoader.registerLazyElement(img, {
      url: thumbnailUrl,
      mediaId: media.media_id,
      fileType: media.file_type,
      size: size,
      placeholder: placeholder
    });
  } else {
    img.src = thumbnailUrl;
  }

  if (onLoad) {
    img.addEventListener('load', onLoad);
  }
  if (onError) {
    img.addEventListener('error', onError);
  }

  return img;
};

const getThumbnailUrlFromStorage = (storagePath) => {
  if (!storagePath) {
    return null;
  }
  return `/api/v1/storage/${encodeURIComponent(storagePath)}`;
};

module.exports = {
  thumbnailLoader,
  AdaptiveThumbnailLoader,
  NetworkBandwidthDetector,
  LOAD_STATUSES,
  PLACEHOLDER_TYPES,
  NETWORK_SPEED,
  NETWORK_CONFIGS,
  ThumbnailCache,
  createThumbnailComponent,
  getThumbnailUrlFromStorage
};
