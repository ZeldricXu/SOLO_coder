const BaseParser = require('./baseParser');
const logger = require('../config/logger');

class PythonParser extends BaseParser {
  constructor() {
    super();
    this.language = 'python';
    this.supportedExtensions = ['.py', '.pyw', '.pyi'];
  }

  getDecisionPoints() {
    return [
      'if', 'elif', 'else', 'while', 'for', 'try', 'except', 'finally',
      'with', 'and', 'or', 'not', 'match', 'case'
    ];
  }

  async parse(code) {
    if (!code || code.trim() === '') {
      return {
        language: 'python',
        functions: [],
        classes: [],
        imports: []
      };
    }

    try {
      const lines = code.split('\n');
      const result = {
        language: 'python',
        functions: [],
        classes: [],
        imports: []
      };

      const functionDefinitions = this.findFunctionDefinitions(lines);
      const classDefinitions = this.findClassDefinitions(lines);
      const imports = this.findImports(lines);

      result.functions = functionDefinitions;
      result.classes = classDefinitions;
      result.imports = imports;

      return result;
    } catch (error) {
      logger.error('Python代码解析失败: %s', error.message);
      throw error;
    }
  }

  findFunctionDefinitions(lines) {
    const functions = [];
    const defPattern = /^(\s*)def\s+(\w+)\s*\(([^)]*)\)\s*(?:->\s*[^:]+)?\s*:/;
    
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const match = line.match(defPattern);
      
      if (match) {
        const indent = match[1].length;
        const name = match[2];
        const paramsString = match[3];
        
        if (name === '__init__' || name === '__new__') {
          continue;
        }
        
        const funcStartLine = i;
        const funcEndLine = this.findFunctionEndByIndent(lines, i, indent);
        
        const funcContent = lines.slice(funcStartLine, funcEndLine + 1).join('\n');
        const params = this.parseParameters(paramsString);
        
        functions.push({
          name,
          startLine: funcStartLine + 1,
          endLine: funcEndLine + 1,
          indent,
          params,
          paramsCount: params.length,
          content: funcContent,
          isMethod: false
        });
      }
    }
    
    return functions;
  }

  findClassDefinitions(lines) {
    const classes = [];
    const classPattern = /^(\s*)class\s+(\w+)\s*(?:\(([^)]*)\))?\s*:/;
    
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const match = line.match(classPattern);
      
      if (match) {
        const indent = match[1].length;
        const name = match[2];
        const baseClasses = match[3] ? match[3].split(',').map(b => b.trim()) : [];
        
        const classStartLine = i;
        const classEndLine = this.findFunctionEndByIndent(lines, i, indent);
        
        const classContent = lines.slice(classStartLine, classEndLine + 1).join('\n');
        
        const methods = this.extractMethodsFromClass(lines, classStartLine, classEndLine);
        
        classes.push({
          name,
          startLine: classStartLine + 1,
          endLine: classEndLine + 1,
          baseClasses,
          methods,
          content: classContent
        });
      }
    }
    
    return classes;
  }

  extractMethodsFromClass(lines, classStartLine, classEndLine) {
    const methods = [];
    const defPattern = /^(\s+)def\s+(\w+)\s*\(([^)]*)\)\s*(?:->\s*[^:]+)?\s*:/;
    
    const classLines = lines.slice(classStartLine, classEndLine + 1);
    
    for (let i = 0; i < classLines.length; i++) {
      const line = classLines[i];
      const match = line.match(defPattern);
      
      if (match) {
        const indent = match[1].length;
        const name = match[2];
        const paramsString = match[3];
        
        if (name === '__init__' || name === '__new__') {
          continue;
        }
        
        const methodStartLine = classStartLine + i;
        const methodEndLine = this.findFunctionEndByIndent(lines, methodStartLine, indent);
        
        const methodContent = lines.slice(methodStartLine, methodEndLine + 1).join('\n');
        const params = this.parseParameters(paramsString);
        
        const actualParams = params.filter(p => p !== 'self' && p !== 'cls');
        
        methods.push({
          name,
          startLine: methodStartLine + 1,
          endLine: methodEndLine + 1,
          indent,
          params: actualParams,
          paramsCount: actualParams.length,
          content: methodContent,
          isMethod: true
        });
      }
    }
    
    return methods;
  }

  findFunctionEndByIndent(lines, startLine, baseIndent) {
    let endLine = startLine;
    let inString = false;
    let stringChar = '';
    let inTripleQuote = false;
    let tripleQuoteChar = '';
    let inMultilineParenthesis = 0;
    
    for (let i = startLine + 1; i < lines.length; i++) {
      const line = lines[i];
      
      if (line.trim() === '') {
        endLine = i;
        continue;
      }
      
      for (let j = 0; j < line.length; j++) {
        const char = line[j];
        const nextChar = j < line.length - 1 ? line[j + 1] : '';
        const prevChar = j > 0 ? line[j - 1] : '';
        
        if (inTripleQuote) {
          if (char === tripleQuoteChar && nextChar === tripleQuoteChar && 
              (j + 2 >= line.length || line[j + 2] === tripleQuoteChar) && 
              prevChar !== '\\') {
            inTripleQuote = false;
            j += 2;
          }
          continue;
        }
        
        if ((char === '"' && nextChar === '"' && (j + 2 >= line.length || line[j + 2] === '"')) ||
            (char === "'" && nextChar === "'" && (j + 2 >= line.length || line[j + 2] === "'"))) {
          inTripleQuote = true;
          tripleQuoteChar = char;
          j += 2;
          continue;
        }
        
        if ((char === '"' || char === "'") && prevChar !== '\\' && !inTripleQuote) {
          if (!inString) {
            inString = true;
            stringChar = char;
          } else if (char === stringChar) {
            inString = false;
          }
        }
        
        if (!inString && !inTripleQuote) {
          if (char === '(' || char === '[' || char === '{') {
            inMultilineParenthesis++;
          } else if (char === ')' || char === ']' || char === '}') {
            inMultilineParenthesis--;
          }
        }
      }
      
      if (inString || inTripleQuote || inMultilineParenthesis > 0) {
        endLine = i;
        continue;
      }
      
      if (line.trim().startsWith('#')) {
        endLine = i;
        continue;
      }
      
      const currentIndent = this.getIndentLevel(line);
      
      if (line.trim() && currentIndent <= baseIndent) {
        const firstNonSpace = line.trim()[0];
        if (firstNonSpace === '@' || firstNonSpace === '#') {
          endLine = i;
          continue;
        }
        break;
      }
      
      endLine = i;
    }
    
    return endLine;
  }

  getIndentLevel(line) {
    let indent = 0;
    for (const char of line) {
      if (char === ' ') {
        indent += 1;
      } else if (char === '\t') {
        indent += 4;
      } else {
        break;
      }
    }
    return indent;
  }

  parseParameters(paramsString) {
    if (!paramsString || paramsString.trim() === '') {
      return [];
    }
    
    const params = [];
    let currentParam = '';
    let inDefaultValue = false;
    let depth = 0;
    let inString = false;
    let stringChar = '';
    
    for (let i = 0; i < paramsString.length; i++) {
      const char = paramsString[i];
      const prevChar = i > 0 ? paramsString[i - 1] : '';
      
      if ((char === '"' || char === "'") && prevChar !== '\\') {
        if (!inString) {
          inString = true;
          stringChar = char;
        } else if (char === stringChar) {
          inString = false;
        }
      }
      
      if (!inString) {
        if (char === '(' || char === '[' || char === '{') {
          depth++;
        } else if (char === ')' || char === ']' || char === '}') {
          depth--;
        }
        
        if (char === '=' && depth === 0) {
          inDefaultValue = true;
        }
        
        if (char === ',' && depth === 0) {
          const paramName = this.extractParamName(currentParam.trim());
          if (paramName) {
            params.push(paramName);
          }
          currentParam = '';
          inDefaultValue = false;
          continue;
        }
      }
      
      currentParam += char;
    }
    
    if (currentParam.trim()) {
      const paramName = this.extractParamName(currentParam.trim());
      if (paramName) {
        params.push(paramName);
      }
    }
    
    return params;
  }

  extractParamName(paramString) {
    if (!paramString) return null;
    
    const colonIndex = paramString.indexOf(':');
    const equalIndex = paramString.indexOf('=');
    
    if (colonIndex !== -1 && (equalIndex === -1 || colonIndex < equalIndex)) {
      return paramString.substring(0, colonIndex).trim();
    }
    
    if (equalIndex !== -1) {
      return paramString.substring(0, equalIndex).trim();
    }
    
    return paramString.trim();
  }

  findImports(lines) {
    const imports = [];
    
    const importPatterns = [
      /^import\s+(\w+(?:\.\w+)*)(?:\s+as\s+(\w+))?/,
      /^from\s+(\w+(?:\.\w+)*)\s+import\s+(.+)/
    ];
    
    for (const line of lines) {
      const trimmed = line.trim();
      
      for (const pattern of importPatterns) {
        const match = trimmed.match(pattern);
        if (match) {
          if (match[0].startsWith('import')) {
            imports.push({
              type: 'import',
              module: match[1],
              alias: match[2] || null
            });
          } else {
            const items = match[2].split(',').map(item => {
              const itemMatch = item.trim().match(/(\w+)(?:\s+as\s+(\w+))?/);
              return itemMatch ? {
                name: itemMatch[1],
                alias: itemMatch[2] || null
              } : null;
            }).filter(Boolean);
            
            imports.push({
              type: 'from_import',
              module: match[1],
              items
            });
          }
          break;
        }
      }
    }
    
    return imports;
  }

  async extractFunctions(parsedResult) {
    const functions = [];
    
    for (const func of parsedResult.functions || []) {
      functions.push({
        name: func.name,
        content: func.content,
        params: func.paramsCount,
        startLine: func.startLine,
        endLine: func.endLine
      });
    }
    
    for (const cls of parsedResult.classes || []) {
      for (const method of cls.methods || []) {
        functions.push({
          name: `${cls.name}.${method.name}`,
          content: method.content,
          params: method.paramsCount,
          startLine: method.startLine,
          endLine: method.endLine
        });
      }
    }
    
    return functions;
  }
}

module.exports = PythonParser;
