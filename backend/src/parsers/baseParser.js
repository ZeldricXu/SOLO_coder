const logger = require('../config/logger');

class BaseParser {
  constructor() {
    this.language = 'unknown';
    this.supportedExtensions = [];
  }

  getLanguage() {
    return this.language;
  }

  getSupportedExtensions() {
    return this.supportedExtensions;
  }

  supportsExtension(extension) {
    return this.supportedExtensions.includes(extension.toLowerCase());
  }

  async parse(code) {
    throw new Error('Method not implemented: parse() must be implemented by subclass');
  }

  async extractFunctions(parsedResult) {
    throw new Error('Method not implemented: extractFunctions() must be implemented by subclass');
  }

  calculateCyclomaticComplexity(functionNode) {
    let complexity = 1;
    
    const decisionPoints = this.getDecisionPoints();
    
    const code = functionNode.content || '';
    const lines = code.split('\n');
    
    let inString = false;
    let inComment = false;
    let inBlockComment = false;
    let stringChar = '';
    
    for (const line of lines) {
      const trimmed = line.trim();
      
      if (trimmed.startsWith('//') || 
          (this.language === 'python' && trimmed.startsWith('#'))) {
        continue;
      }
      
      for (let i = 0; i < line.length; i++) {
        const char = line[i];
        const nextChar = i < line.length - 1 ? line[i + 1] : '';
        const prevChar = i > 0 ? line[i - 1] : '';
        
        if (this.language === 'python' && char === '#' && !inString) {
          break;
        }
        
        if ((char === '"' || char === "'" || (this.language === 'javascript' && char === '`')) && 
            prevChar !== '\\') {
          if (!inString) {
            inString = true;
            stringChar = char;
          } else if (char === stringChar) {
            inString = false;
          }
        }
        
        if (char === '/' && nextChar === '*' && !inString) {
          inBlockComment = true;
          i++;
          continue;
        }
        
        if (inBlockComment) {
          if (char === '*' && nextChar === '/') {
            inBlockComment = false;
            i++;
          }
          continue;
        }
        
        if (!inString && !inComment && !inBlockComment) {
          for (const dp of decisionPoints) {
            const substring = line.substring(i, i + dp.length);
            if (substring === dp) {
              const before = i > 0 ? line[i - 1] : '';
              const after = i + dp.length < line.length ? line[i + dp.length] : '';
              
              if (!/[a-zA-Z0-9_]/.test(before) && !/[a-zA-Z0-9_]/.test(after)) {
                complexity++;
              }
            }
          }
        }
      }
    }
    
    return complexity;
  }

  getDecisionPoints() {
    return ['if', 'else', 'elif', 'while', 'for', 'switch', 'case', '&&', '||', 'catch', 'try', 'except'];
  }

  calculateLines(functionNode) {
    if (!functionNode.content) return 0;
    const lines = functionNode.content.split('\n');
    return lines.length;
  }

  calculateParams(functionNode) {
    return functionNode.params || 0;
  }

  async analyze(code) {
    try {
      const parsedResult = await this.parse(code);
      const functions = await this.extractFunctions(parsedResult);
      
      const analyzedFunctions = functions.map(func => ({
        name: func.name,
        cyclomatic: this.calculateCyclomaticComplexity(func),
        lines: this.calculateLines(func),
        params: this.calculateParams(func),
        is_above_threshold: false,
        content: func.content
      }));
      
      const threshold = parseInt(process.env.COMPLEXITY_THRESHOLD) || 10;
      analyzedFunctions.forEach(f => {
        f.is_above_threshold = f.cyclomatic > threshold;
      });
      
      return {
        language: this.language,
        functions: analyzedFunctions,
        total_functions: analyzedFunctions.length,
        avg_cyclomatic: analyzedFunctions.length > 0 
          ? analyzedFunctions.reduce((sum, f) => sum + f.cyclomatic, 0) / analyzedFunctions.length 
          : 0
      };
    } catch (error) {
      logger.error('代码分析失败 [%s]: %s', this.language, error.message);
      throw error;
    }
  }
}

module.exports = BaseParser;
