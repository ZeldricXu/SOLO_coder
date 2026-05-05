const logger = require('../config/logger');
const BaseParser = require('./baseParser');
const PythonParser = require('./pythonParser');
const { JavaScriptParser, TypeScriptParser } = require('./javascriptParser');
const GoParser = require('./goParser');

class ParserFactory {
  constructor() {
    this.parsers = new Map();
    this.extensionToLanguage = new Map();
    this.languageToParser = new Map();
    
    this._initializeParsers();
  }

  _initializeParsers() {
    this.registerParser('python', new PythonParser());
    this.registerParser('javascript', new JavaScriptParser('javascript'));
    this.registerParser('typescript', new TypeScriptParser());
    this.registerParser('go', new GoParser());
    
    this._registerExtensions();
  }

  _registerExtensions() {
    const extensions = {
      '.py': 'python',
      '.pyw': 'python',
      '.pyi': 'python',
      '.js': 'javascript',
      '.jsx': 'javascript',
      '.mjs': 'javascript',
      '.cjs': 'javascript',
      '.ts': 'typescript',
      '.tsx': 'typescript',
      '.mts': 'typescript',
      '.cts': 'typescript',
      '.go': 'go'
    };
    
    for (const [ext, lang] of Object.entries(extensions)) {
      this.extensionToLanguage.set(ext, lang);
    }
  }

  registerParser(language, parser) {
    if (!(parser instanceof BaseParser)) {
      throw new Error(`Parser for language '${language}' must extend BaseParser`);
    }
    
    this.parsers.set(language.toLowerCase(), parser);
    this.languageToParser.set(language.toLowerCase(), parser);
    
    logger.info('Parser registered: language=%s', language);
  }

  unregisterParser(language) {
    const lang = language.toLowerCase();
    this.parsers.delete(lang);
    this.languageToParser.delete(lang);
    logger.info('Parser unregistered: language=%s', language);
  }

  getParser(language) {
    const lang = language.toLowerCase();
    const parser = this.languageToParser.get(lang);
    
    if (!parser) {
      logger.warn('No parser found for language: %s', language);
      return null;
    }
    
    return parser;
  }

  getParserByExtension(extension) {
    const ext = extension.toLowerCase();
    const language = this.extensionToLanguage.get(ext);
    
    if (!language) {
      logger.warn('No language mapping found for extension: %s', extension);
      return null;
    }
    
    return this.getParser(language);
  }

  getParserByFilePath(filePath) {
    const path = require('path');
    const ext = path.extname(filePath);
    
    if (!ext) {
      logger.warn('No extension found in file path: %s', filePath);
      return null;
    }
    
    return this.getParserByExtension(ext);
  }

  isLanguageSupported(language) {
    return this.languageToParser.has(language.toLowerCase());
  }

  isExtensionSupported(extension) {
    return this.extensionToLanguage.has(extension.toLowerCase());
  }

  getSupportedLanguages() {
    return Array.from(this.languageToParser.keys());
  }

  getSupportedExtensions() {
    return Array.from(this.extensionToLanguage.keys());
  }

  async parseCode(code, languageOrFilePath) {
    let parser;
    
    if (languageOrFilePath.includes('/') || languageOrFilePath.includes('\\') || languageOrFilePath.startsWith('.')) {
      parser = this.getParserByFilePath(languageOrFilePath);
    } else {
      parser = this.getParser(languageOrFilePath);
    }
    
    if (!parser) {
      throw new Error(`No parser available for: ${languageOrFilePath}`);
    }
    
    return await parser.parse(code);
  }

  async analyzeCode(code, languageOrFilePath) {
    let parser;
    
    if (languageOrFilePath.includes('/') || languageOrFilePath.includes('\\') || languageOrFilePath.startsWith('.')) {
      parser = this.getParserByFilePath(languageOrFilePath);
    } else {
      parser = this.getParser(languageOrFilePath);
    }
    
    if (!parser) {
      logger.warn('Skipping analysis for unsupported language: %s', languageOrFilePath);
      return {
        language: 'unknown',
        functions: [],
        total_functions: 0,
        avg_cyclomatic: 0,
        skipped: true
      };
    }
    
    return await parser.analyze(code);
  }

  async analyzeFile(filePath, content) {
    const parser = this.getParserByFilePath(filePath);
    
    if (!parser) {
      logger.warn('Skipping analysis for unsupported file: %s', filePath);
      return {
        language: 'unknown',
        functions: [],
        total_functions: 0,
        avg_cyclomatic: 0,
        skipped: true
      };
    }
    
    return await parser.analyze(content);
  }

  detectLanguageFromContent(code) {
    const lines = code.split('\n');
    
    if (lines.some(l => l.includes('package ') && !l.includes(';')) &&
        lines.some(l => l.includes('func ')) &&
        lines.some(l => l.match(/^(\s*)type\s+\w+\s+struct/))) {
      return 'go';
    }
    
    if (lines.some(l => l.match(/^(\s*)def\s+\w+\s*\(/)) &&
        lines.some(l => l.match(/^(\s*)class\s+\w+(\s*\(\w*\))?\s*:/))) {
      return 'python';
    }
    
    if (lines.some(l => l.match(/\binterface\s*\{/)) &&
        lines.some(l => l.match(/\btype\s+\w+(\s*<[^>]+>)?\s*=/)) &&
        (lines.some(l => l.match(/:\s*\w+(\[\])?(\s*<[^>]+>)?(\s*\|[^,;]+)*\s*[,;=]/)) ||
         lines.some(l => l.match(/\bimport\s+type\b/)))) {
      return 'typescript';
    }
    
    if (lines.some(l => l.match(/\bfunction\s+\w*\s*\(/)) ||
        lines.some(l => l.match(/\bconst\s+\w+\s*=\s*(async\s+)?(function|\(/)) ||
        lines.some(l => l.match(/\blet\s+\w+\s*=\s*(async\s+)?(function|\(/)) ||
        lines.some(l => l.match(/\bvar\s+\w+\s*=\s*(async\s+)?(function|\(/))) ||
        lines.some(l => l.match(/\bclass\s+\w+(\s+extends\s+\w+)?\s*\{/))) {
      return 'javascript';
    }
    
    return null;
  }

  async autoAnalyze(code, hintLanguage = null) {
    let language = hintLanguage;
    
    if (!language) {
      language = this.detectLanguageFromContent(code);
    }
    
    if (!language) {
      logger.warn('Could not detect language from content');
      return {
        language: 'unknown',
        functions: [],
        total_functions: 0,
        avg_cyclomatic: 0,
        skipped: true
      };
    }
    
    return await this.analyzeCode(code, language);
  }
}

const factoryInstance = new ParserFactory();

module.exports = ParserFactory;
module.exports.default = factoryInstance;
module.exports.factory = factoryInstance;
