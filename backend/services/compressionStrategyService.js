const config = require('../config/config');
const logger = require('../utils/logger');
const compressionUtils = require('../utils/compressionUtils');
const diffUtils = require('../utils/diffUtils');

const DOCUMENT_TYPES = {
  CODE: 'code',
  RICH_TEXT: 'richText',
  PLAIN_TEXT: 'plainText'
};

const CODE_EXTENSIONS = [
  '.js', '.ts', '.py', '.java', '.cpp', '.c', '.h', '.cs', '.rb', '.go',
  '.php', '.swift', '.kt', '.rs', '.scala', '.sh', '.bash', '.sql',
  '.html', '.css', '.scss', '.less', '.vue', '.jsx', '.tsx',
  '.json', '.xml', '.yaml', '.yml', '.toml', '.ini', '.cfg'
];

const RICH_TEXT_EXTENSIONS = [
  '.doc', '.docx', '.odt', '.rtf', '.html', '.htm'
];

class CompressionStrategy {
  constructor(options = {}) {
    this.name = 'base';
    this.diffLevel = 'word';
    this.compressionAlgorithm = options.compressionAlgorithm || 'gzip';
    this.compressionLevel = options.compressionLevel || 6;
  }

  async compress(content, options = {}) {
    return compressionUtils.compress(content, {
      algorithm: this.compressionAlgorithm,
      level: this.compressionLevel,
      ...options
    });
  }

  async decompress(content, algorithm = 'gzip') {
    return compressionUtils.decompress(content, algorithm);
  }

  createDelta(baseContent, currentContent) {
    return diffUtils.createDelta(baseContent, currentContent);
  }

  applyDelta(baseContent, delta) {
    return diffUtils.applyDelta(baseContent, delta);
  }

  shouldUseDelta(baseContent, currentContent) {
    return diffUtils.shouldUseDelta(baseContent, currentContent);
  }

  estimateDeltaSize(delta) {
    return diffUtils.estimateDeltaSize(delta);
  }

  detectDocumentType(document, content = '') {
    const extension = this.getFileExtension(document);
    if (CODE_EXTENSIONS.includes(extension)) {
      return DOCUMENT_TYPES.CODE;
    }
    if (RICH_TEXT_EXTENSIONS.includes(extension)) {
      return DOCUMENT_TYPES.RICH_TEXT;
    }
    if (this.looksLikeCode(content)) {
      return DOCUMENT_TYPES.CODE;
    }
    if (this.looksLikeRichText(content)) {
      return DOCUMENT_TYPES.RICH_TEXT;
    }
    return DOCUMENT_TYPES.PLAIN_TEXT;
  }

  getFileExtension(document) {
    if (!document || !document.title) return '';
    const title = document.title.toLowerCase();
    const lastDotIndex = title.lastIndexOf('.');
    if (lastDotIndex === -1) return '';
    return title.substring(lastDotIndex);
  }

  looksLikeCode(content) {
    if (!content) return false;
    const codePatterns = [
      /function\s+\w+\s*\(/,
      /class\s+\w+/,
      /const\s+\w+\s*=/,
      /let\s+\w+\s*=/,
      /var\s+\w+\s*=/,
      /=>\s*\{/,
      /\{[\s\S]*\}/,
      /<\w+[^>]*>.*<\/\w+>/,
      /^[a-zA-Z_][a-zA-Z0-9_]*\s*:\s*.+$/m,
      /^\s*#\s*include\s*[<"]/,
      /^\s*import\s+(?:\w+|\{[^}]*\})\s+from\s+['"]/m,
      /^\s*from\s+['"][^'"]+['"]\s+import\s+/m,
      /^\s*require\s*\(/m
    ];

    for (const pattern of codePatterns) {
      if (pattern.test(content)) {
        return true;
      }
    }

    return false;
  }

  looksLikeRichText(content) {
    if (!content) return false;
    const richTextPatterns = [
      /<p[^>]*>.*<\/p>/i,
      /<div[^>]*>.*<\/div>/i,
      /<span[^>]*>.*<\/span>/i,
      /<b>.*<\/b>/i,
      /<strong>.*<\/strong>/i,
      /<i>.*<\/i>/i,
      /<em>.*<\/em>/i,
      /<u>.*<\/u>/i,
      /<a\s+href/i,
      /<img\s+src/i,
      /<table/i,
      /<ul/i,
      /<ol/i,
      /<h[1-6]/i,
      /<br\s*\/?>/i
    ];

    for (const pattern of richTextPatterns) {
      if (pattern.test(content)) {
        return true;
      }
    }

    return false;
  }
}

class LineBasedStrategy extends CompressionStrategy {
  constructor(options = {}) {
    super(options);
    this.name = 'line';
    this.diffLevel = 'line';
  }

  createDelta(baseContent, currentContent) {
    return diffUtils.computeLineDiff(baseContent, currentContent);
  }

  applyDelta(baseContent, delta) {
    return diffUtils.applyLineDelta(baseContent, delta);
  }

  shouldUseDelta(baseContent, currentContent) {
    const baseLines = baseContent.split('\n').length;
    const currentLines = currentContent.split('\n').length;
    
    const delta = this.createDelta(baseContent, currentContent);
    const estimatedSize = this.estimateDeltaSize(delta);
    const originalSize = Buffer.byteLength(currentContent, 'utf8');
    
    return estimatedSize < originalSize * 0.6;
  }
}

class ParagraphBasedStrategy extends CompressionStrategy {
  constructor(options = {}) {
    super(options);
    this.name = 'paragraph';
    this.diffLevel = 'paragraph';
  }

  createDelta(baseContent, currentContent) {
    return this.computeParagraphDiff(baseContent, currentContent);
  }

  applyDelta(baseContent, delta) {
    return this.applyParagraphDelta(baseContent, delta);
  }

  computeParagraphDiff(baseContent, currentContent) {
    const baseParagraphs = this.splitIntoParagraphs(baseContent);
    const currentParagraphs = this.splitIntoParagraphs(currentContent);
    
    return diffUtils.computeLineDiff(
      baseParagraphs.join('\n\n'),
      currentParagraphs.join('\n\n')
    );
  }

  applyParagraphDelta(baseContent, delta) {
    const baseParagraphs = this.splitIntoParagraphs(baseContent);
    const baseJoined = baseParagraphs.join('\n\n');
    const result = diffUtils.applyLineDelta(baseJoined, delta);
    return result.replace(/\n\n/g, '\n');
  }

  splitIntoParagraphs(content) {
    if (!content) return [''];
    return content.split(/\n\s*\n|\n{2,}/).filter(p => p.trim());
  }

  shouldUseDelta(baseContent, currentContent) {
    const baseParagraphs = this.splitIntoParagraphs(baseContent).length;
    const currentParagraphs = this.splitIntoParagraphs(currentContent).length;
    
    if (Math.abs(baseParagraphs - currentParagraphs) > baseParagraphs * 0.3) {
      return false;
    }
    
    const delta = this.createDelta(baseContent, currentContent);
    const estimatedSize = this.estimateDeltaSize(delta);
    const originalSize = Buffer.byteLength(currentContent, 'utf8');
    
    return estimatedSize < originalSize * 0.7;
  }
}

class WordBasedStrategy extends CompressionStrategy {
  constructor(options = {}) {
    super(options);
    this.name = 'word';
    this.diffLevel = 'word';
  }

  createDelta(baseContent, currentContent) {
    return diffUtils.computeCharDiff(baseContent, currentContent);
  }

  applyDelta(baseContent, delta) {
    return diffUtils.applyCharDelta(baseContent, delta);
  }

  shouldUseDelta(baseContent, currentContent) {
    const delta = this.createDelta(baseContent, currentContent);
    const estimatedSize = this.estimateDeltaSize(delta);
    const originalSize = Buffer.byteLength(currentContent, 'utf8');
    
    return estimatedSize < originalSize * 0.5;
  }
}

class CompressionStrategyService {
  constructor() {
    this.strategies = new Map();
    this.defaultStrategy = null;
    this.initializeStrategies();
  }

  initializeStrategies() {
    const strategiesConfig = config.compressionStrategies;

    if (strategiesConfig.code.enabled) {
      this.strategies.set(DOCUMENT_TYPES.CODE, new LineBasedStrategy({
        compressionAlgorithm: strategiesConfig.code.compressionAlgorithm,
        compressionLevel: strategiesConfig.code.compressionLevel
      }));
    }

    if (strategiesConfig.richText.enabled) {
      this.strategies.set(DOCUMENT_TYPES.RICH_TEXT, new ParagraphBasedStrategy({
        compressionAlgorithm: strategiesConfig.richText.compressionAlgorithm,
        compressionLevel: strategiesConfig.richText.compressionLevel
      }));
    }

    if (strategiesConfig.plainText.enabled) {
      this.strategies.set(DOCUMENT_TYPES.PLAIN_TEXT, new WordBasedStrategy({
        compressionAlgorithm: strategiesConfig.plainText.compressionAlgorithm,
        compressionLevel: strategiesConfig.plainText.compressionLevel
      }));
    }

    this.defaultStrategy = new CompressionStrategy();

    logger.info('压缩策略服务已初始化', {
      strategies: Array.from(this.strategies.keys())
    });
  }

  getStrategy(documentType) {
    return this.strategies.get(documentType) || this.defaultStrategy;
  }

  detectAndGetStrategy(document, content = '') {
    const baseStrategy = new CompressionStrategy();
    const docType = baseStrategy.detectDocumentType(document, content);
    return {
      type: docType,
      strategy: this.getStrategy(docType)
    };
  }

  getStrategyForDocument(document, content = '') {
    const { type, strategy } = this.detectAndGetStrategy(document, content);
    logger.debug(`为文档选择压缩策略: doc_id=${document.doc_id}, type=${type}, strategy=${strategy.name}`);
    return strategy;
  }

  registerStrategy(type, strategy) {
    this.strategies.set(type, strategy);
    logger.info(`注册新压缩策略: type=${type}`);
  }

  listStrategies() {
    return {
      available: Array.from(this.strategies.keys()),
      default: this.defaultStrategy.name
    };
  }

  async createVersionWithStrategy(document, baseContent, currentContent, user, changeDesc) {
    const strategy = this.getStrategyForDocument(document, currentContent);
    const docType = this.detectAndGetStrategy(document, currentContent).type;

    const originalSize = Buffer.byteLength(currentContent, 'utf8');
    const threshold = config.compression.threshold;

    let useDelta = false;
    let delta = null;

    if (config.version.useDelta && baseContent) {
      if (strategy.shouldUseDelta(baseContent, currentContent)) {
        delta = strategy.createDelta(baseContent, currentContent);
        const estimatedDeltaSize = strategy.estimateDeltaSize(delta);
        
        if (estimatedDeltaSize < originalSize * 0.6) {
          useDelta = true;
          logger.debug(`使用差异存储: doc_type=${docType}, estimated_size=${estimatedDeltaSize}`);
        }
      }
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

    if (config.compression.enabled && Buffer.byteLength(versionContent, 'utf8') >= threshold) {
      const compressionResult = await strategy.compress(versionContent);
      
      if (compressionResult.compressed) {
        versionContent = compressionResult.content;
        isCompressed = true;
        compressedSize = compressionResult.compressedSize;
        algorithm = compressionResult.algorithm;
        
        logger.debug(`版本内容已压缩: doc_type=${docType}, ` +
          `原始=${compressionResult.originalSize}, 压缩后=${compressedSize}, ` +
          `节省=${compressionResult.compressionRatio.toFixed(1)}%`);
      }
    }

    return {
      success: true,
      data: {
        content: versionContent,
        is_compressed: isCompressed,
        compression_algorithm: algorithm,
        original_size: originalSize,
        compressed_size: compressedSize || originalSize,
        content_type: useDelta ? 'delta' : 'full',
        delta_operations: useDelta ? delta : null,
        strategy_used: strategy.name,
        document_type: docType
      }
    };
  }

  async restoreVersionContent(versionDoc, strategy) {
    let content = versionDoc.content;

    if (versionDoc.is_compressed && content) {
      try {
        content = await strategy.decompress(content, versionDoc.compression_algorithm);
      } catch (error) {
        logger.error(`解压版本内容失败: ${error.message}`, { 
          version_id: versionDoc.version_id 
        });
        return null;
      }
    }

    return content;
  }
}

const compressionStrategyService = new CompressionStrategyService();

module.exports = {
  CompressionStrategy,
  LineBasedStrategy,
  ParagraphBasedStrategy,
  WordBasedStrategy,
  CompressionStrategyService,
  DOCUMENT_TYPES,
  compressionStrategyService
};
