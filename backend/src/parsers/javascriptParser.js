const BaseParser = require('./baseParser');
const logger = require('../config/logger');

class JavaScriptParser extends BaseParser {
  constructor(language = 'javascript') {
    super();
    this.language = language;
    this.supportedExtensions = language === 'typescript' 
      ? ['.ts', '.tsx', '.mts', '.cts']
      : ['.js', '.jsx', '.mjs', '.cjs'];
  }

  getDecisionPoints() {
    return [
      'if', 'else', 'while', 'for', 'switch', 'case', 'default',
      'try', 'catch', 'finally', 'throw',
      '&&', '||', '?', '??',
      'do', 'break', 'continue'
    ];
  }

  async parse(code) {
    if (!code || code.trim() === '') {
      return {
        language: this.language,
        functions: [],
        classes: [],
        imports: [],
        exports: []
      };
    }

    try {
      const tokens = this.tokenize(code);
      const result = {
        language: this.language,
        functions: [],
        classes: [],
        imports: [],
        exports: []
      };

      result.imports = this.extractImports(tokens, code);
      result.exports = this.extractExports(tokens, code);
      result.functions = this.extractTopLevelFunctions(code);
      result.classes = this.extractClasses(code);

      return result;
    } catch (error) {
      logger.error('JavaScript/TypeScript代码解析失败: %s', error.message);
      throw error;
    }
  }

  tokenize(code) {
    const tokens = [];
    let i = 0;
    
    while (i < code.length) {
      if (code[i] === '/' && code[i + 1] === '/') {
        let start = i;
        while (i < code.length && code[i] !== '\n') i++;
        tokens.push({ type: 'comment', value: code.substring(start, i), start, end: i });
        continue;
      }
      
      if (code[i] === '/' && code[i + 1] === '*') {
        let start = i;
        i += 2;
        while (i < code.length && !(code[i] === '*' && code[i + 1] === '/')) i++;
        i += 2;
        tokens.push({ type: 'block_comment', value: code.substring(start, i), start, end: i });
        continue;
      }
      
      if (code[i] === '"' || code[i] === "'" || (this.language === 'javascript' && code[i] === '`')) {
        const quoteChar = code[i];
        let start = i;
        i++;
        while (i < code.length && (code[i] !== quoteChar || code[i - 1] === '\\')) {
          if (quoteChar === '`' && code[i] === '`' && code[i - 1] !== '\\') break;
          i++;
        }
        i++;
        tokens.push({ type: 'string', value: code.substring(start, i), start, end: i });
        continue;
      }
      
      if (code[i].match(/[a-zA-Z_$]/)) {
        let start = i;
        while (i < code.length && code[i].match(/[a-zA-Z0-9_$]/)) i++;
        tokens.push({ type: 'identifier', value: code.substring(start, i), start, end: i });
        continue;
      }
      
      if (code[i].match(/[0-9]/)) {
        let start = i;
        while (i < code.length && (code[i].match(/[0-9.]/) || (code[i].toLowerCase() === 'e' && i > start))) i++;
        tokens.push({ type: 'number', value: code.substring(start, i), start, end: i });
        continue;
      }
      
      const operators = ['===', '!==', '>=', '<=', '++', '--', '&&', '||', '=>', '?.', '**',
        '==', '!=', '+=', '-=', '*=', '/=', '%=', '<<', '>>', '&=', '|=', '^=',
        '=', '+', '-', '*', '/', '%', '<', '>', '!', '~', '&', '|', '^', '?', ':'];
      
      let matched = false;
      for (const op of operators.sort((a, b) => b.length - a.length)) {
        if (code.substring(i, i + op.length) === op) {
          tokens.push({ type: 'operator', value: op, start: i, end: i + op.length });
          i += op.length;
          matched = true;
          break;
        }
      }
      if (matched) continue;
      
      const punctuators = ['(', ')', '{', '}', '[', ']', ';', ',', '.'];
      for (const p of punctuators) {
        if (code[i] === p) {
          tokens.push({ type: 'punctuator', value: p, start: i, end: i + 1 });
          i++;
          matched = true;
          break;
        }
      }
      if (matched) continue;
      
      if (code[i].match(/\s/)) {
        i++;
        continue;
      }
      
      i++;
    }
    
    return tokens;
  }

  extractTopLevelFunctions(code) {
    const functions = [];
    const lines = code.split('\n');
    
    const functionDeclPattern = /^(\s*)(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function\s+)(\w+)\s*\(/;
    const methodPattern = /^(\s*)(?:async\s+)?(\w+)\s*\([^)]*\)\s*(?:<[^>]*>)?\s*\{/;
    
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      
      let match = line.match(functionDeclPattern);
      if (match) {
        const name = match[2];
        const indent = match[1].length;
        
        if (indent > 0) continue;
        
        const funcStartIndex = this.findFunctionStartIndex(code, i);
        const funcEndIndex = this.findFunctionEndIndex(code, funcStartIndex);
        
        if (funcStartIndex !== -1 && funcEndIndex !== -1) {
          const funcContent = code.substring(funcStartIndex, funcEndIndex);
          const params = this.extractParameters(code, funcStartIndex);
          
          functions.push({
            name,
            startLine: i + 1,
            endLine: code.substring(0, funcEndIndex).split('\n').length,
            content: funcContent,
            params,
            paramsCount: params.length,
            isAsync: line.includes('async'),
            isExport: line.includes('export')
          });
        }
        continue;
      }
      
      match = line.match(/^(?:export\s+)?(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s+)?(?:function\s*\(|(?:\([^)]*\)|[a-zA-Z_$][a-zA-Z0-9_$]*)\s*=>)/);
      if (match) {
        const name = match[1];
        
        const startIndex = code.indexOf(line, code.indexOf('\n'.repeat(i)));
        const constEndIndex = code.indexOf('=', startIndex);
        
        if (constEndIndex !== -1) {
          const funcStartIndex = this.findFunctionStartAfterEquals(code, constEndIndex);
          const funcEndIndex = this.findFunctionEndIndex(code, funcStartIndex);
          
          if (funcStartIndex !== -1 && funcEndIndex !== -1) {
            const funcContent = code.substring(funcStartIndex, funcEndIndex);
            const params = this.extractParameters(code, funcStartIndex);
            
            functions.push({
              name,
              startLine: i + 1,
              endLine: code.substring(0, funcEndIndex).split('\n').length,
              content: funcContent,
              params,
              paramsCount: params.length,
              isAsync: line.includes('async') || funcContent.includes('async'),
              isArrowFunction: funcContent.includes('=>'),
              isExport: line.includes('export')
            });
          }
        }
        continue;
      }
      
      match = line.match(/^(\s*)function\s*\(/);
      if (match) {
        const funcStartIndex = this.findFunctionStartIndex(code, i);
        const funcEndIndex = this.findFunctionEndIndex(code, funcStartIndex);
        
        if (funcStartIndex !== -1 && funcEndIndex !== -1) {
          const funcContent = code.substring(funcStartIndex, funcEndIndex);
          const params = this.extractParameters(code, funcStartIndex);
          
          functions.push({
            name: '<anonymous>',
            startLine: i + 1,
            endLine: code.substring(0, funcEndIndex).split('\n').length,
            content: funcContent,
            params,
            paramsCount: params.length,
            isAsync: line.includes('async'),
            isAnonymous: true
          });
        }
      }
    }
    
    return functions;
  }

  extractClasses(code) {
    const classes = [];
    const lines = code.split('\n');
    
    const classPattern = /^(\s*)(?:export\s+)?(?:default\s+)?(?:abstract\s+)?class\s+(\w+)(?:\s+extends\s+(\w+(?:<[^>]+>)?))?(?:\s+implements\s+([^{]+))?\s*\{/;
    
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const match = line.match(classPattern);
      
      if (match) {
        const className = match[2];
        const extendsClass = match[3];
        const implementsInterfaces = match[4] ? match[4].split(',').map(s => s.trim()) : [];
        
        const classStartIndex = code.indexOf('{', code.indexOf('class ' + className));
        const classEndIndex = this.findClassEndIndex(code, classStartIndex);
        
        if (classStartIndex !== -1 && classEndIndex !== -1) {
          const classContent = code.substring(classStartIndex, classEndIndex);
          const methods = this.extractClassMethods(classContent, code, classStartIndex, i + 1);
          
          classes.push({
            name: className,
            extends: extendsClass,
            implements: implementsInterfaces,
            startLine: i + 1,
            endLine: code.substring(0, classEndIndex).split('\n').length,
            methods,
            content: classContent,
            isExport: line.includes('export')
          });
        }
      }
    }
    
    return classes;
  }

  extractClassMethods(classContent, fullCode, classStartOffset, classStartLine) {
    const methods = [];
    const lines = classContent.split('\n');
    
    const methodPatterns = [
      /^(\s*)(?:(async|static|get|set)\s+)*(\w+)\s*\(([^)]*)\)\s*(?:<[^>]*>)?\s*(?::\s*[^;{]+)?\s*\{/,
      /^(\s*)(?:(async|static)\s+)*(\w+)\s*:\s*(?:async\s+)?function\s*\(/,
      /^(\s*)(?:(async|static)\s+)*(\w+)\s*=\s*(?:async\s+)?(?:function\s*\(|\([^)]*\)\s*=>)/
    ];
    
    let braceDepth = 1;
    
    for (let i = 1; i < lines.length; i++) {
      const line = lines[i];
      
      for (const char of line) {
        if (char === '{') braceDepth++;
        else if (char === '}') braceDepth--;
      }
      
      if (braceDepth <= 0) break;
      
      for (const pattern of methodPatterns) {
        const match = line.match(pattern);
        if (match) {
          const indent = match[1].length;
          const modifiers = match[2] ? match[2].split(/\s+/) : [];
          const name = match[3];
          
          if (name === 'constructor') continue;
          
          const lineInFullCode = classStartLine + i - 1;
          const methodStartIndex = this.findMethodStartIndex(fullCode, lineInFullCode);
          const methodEndIndex = this.findFunctionEndIndex(fullCode, methodStartIndex);
          
          if (methodStartIndex !== -1 && methodEndIndex !== -1) {
            const methodContent = fullCode.substring(methodStartIndex, methodEndIndex);
            const params = this.extractParameters(fullCode, methodStartIndex);
            
            methods.push({
              name,
              startLine: lineInFullCode + 1,
              endLine: fullCode.substring(0, methodEndIndex).split('\n').length,
              content: methodContent,
              params,
              paramsCount: params.length,
              isAsync: modifiers.includes('async') || methodContent.includes('async'),
              isStatic: modifiers.includes('static'),
              isGetter: modifiers.includes('get'),
              isSetter: modifiers.includes('set')
            });
          }
          break;
        }
      }
    }
    
    return methods;
  }

  findFunctionStartIndex(code, lineIndex) {
    const lines = code.split('\n');
    let currentIndex = 0;
    
    for (let i = 0; i < lineIndex; i++) {
      currentIndex += lines[i].length + 1;
    }
    
    const line = lines[lineIndex];
    
    const functionKeyword = line.indexOf('function');
    if (functionKeyword !== -1) {
      return currentIndex + functionKeyword;
    }
    
    const equalsIndex = line.indexOf('=');
    if (equalsIndex !== -1) {
      return this.findFunctionStartAfterEquals(code, currentIndex + equalsIndex);
    }
    
    return currentIndex;
  }

  findFunctionStartAfterEquals(code, equalsIndex) {
    let i = equalsIndex + 1;
    
    while (i < code.length && /\s/.test(code[i])) i++;
    
    if (code.substring(i, i + 5) === 'async') {
      i += 5;
      while (i < code.length && /\s/.test(code[i])) i++;
    }
    
    if (code.substring(i, i + 8) === 'function') {
      return i;
    }
    
    return i;
  }

  findFunctionEndIndex(code, startIndex) {
    let braceDepth = 0;
    let inString = false;
    let stringChar = '';
    let inComment = false;
    let inBlockComment = false;
    let foundFirstBrace = false;
    
    for (let i = startIndex; i < code.length; i++) {
      const char = code[i];
      const nextChar = i < code.length - 1 ? code[i + 1] : '';
      const prevChar = i > 0 ? code[i - 1] : '';
      
      if (!inBlockComment && char === '/' && nextChar === '/' && !inString) {
        inComment = true;
        continue;
      }
      
      if (inComment) {
        if (char === '\n') inComment = false;
        continue;
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
      
      if ((char === '"' || char === "'" || (this.language === 'javascript' && char === '`')) && 
          prevChar !== '\\') {
        if (!inString) {
          inString = true;
          stringChar = char;
        } else if (char === stringChar) {
          inString = false;
        }
      }
      
      if (!inString) {
        if (char === '{') {
          braceDepth++;
          foundFirstBrace = true;
        } else if (char === '}') {
          braceDepth--;
          if (foundFirstBrace && braceDepth === 0) {
            return i + 1;
          }
        }
      }
    }
    
    return code.length;
  }

  findClassEndIndex(code, startIndex) {
    return this.findFunctionEndIndex(code, startIndex);
  }

  findMethodStartIndex(code, lineIndex) {
    return this.findFunctionStartIndex(code, lineIndex);
  }

  extractParameters(code, startIndex) {
    let i = startIndex;
    
    while (i < code.length && code[i] !== '(') i++;
    
    if (code[i] !== '(') return [];
    
    i++;
    let paramStart = i;
    let depth = 1;
    let inString = false;
    let stringChar = '';
    const params = [];
    let currentParam = '';
    
    while (i < code.length && depth > 0) {
      const char = code[i];
      const prevChar = i > 0 ? code[i - 1] : '';
      
      if ((char === '"' || char === "'" || char === '`') && prevChar !== '\\') {
        if (!inString) {
          inString = true;
          stringChar = char;
        } else if (char === stringChar) {
          inString = false;
        }
      }
      
      if (!inString) {
        if (char === '(' || char === '[' || char === '<') {
          depth++;
        } else if (char === ')' || char === ']' || char === '>') {
          depth--;
          if (depth === 0) {
            if (currentParam.trim()) {
              const paramName = this.extractParamName(currentParam.trim());
              if (paramName) params.push(paramName);
            }
            break;
          }
        }
        
        if (char === ',' && depth === 1) {
          if (currentParam.trim()) {
            const paramName = this.extractParamName(currentParam.trim());
            if (paramName) params.push(paramName);
          }
          currentParam = '';
          i++;
          continue;
        }
      }
      
      if (depth === 1 && !inString) {
        currentParam += char;
      }
      
      i++;
    }
    
    return params;
  }

  extractParamName(paramString) {
    if (!paramString) return null;
    
    const equalsIndex = paramString.indexOf('=');
    if (equalsIndex !== -1) {
      paramString = paramString.substring(0, equalsIndex).trim();
    }
    
    const colonIndex = paramString.indexOf(':');
    if (colonIndex !== -1) {
      paramString = paramString.substring(0, colonIndex).trim();
    }
    
    paramString = paramString.replace(/^(?:async\s+)?[?]?/, '');
    paramString = paramString.replace(/[?]$/, '');
    paramString = paramString.replace(/^\.{3}/, '');
    paramString = paramString.replace(/^\{/, '').replace(/\}$/, '');
    paramString = paramString.replace(/^\[/, '').replace(/\]$/, '');
    
    const commaIndex = paramString.indexOf(',');
    if (commaIndex !== -1) {
      return paramString.substring(0, commaIndex).trim();
    }
    
    return paramString.trim();
  }

  extractImports(tokens, code) {
    const imports = [];
    const lines = code.split('\n');
    
    for (const line of lines) {
      let match = line.match(/^import\s+(.+?)\s+from\s+['"](.+?)['"]/);
      if (match) {
        imports.push({
          type: 'named',
          imports: match[1],
          module: match[2]
        });
        continue;
      }
      
      match = line.match(/^import\s+['"](.+?)['"]/);
      if (match) {
        imports.push({
          type: 'side_effect',
          module: match[1]
        });
        continue;
      }
      
      match = line.match(/^import\s+(\w+)\s+from\s+['"](.+?)['"]/);
      if (match) {
        imports.push({
          type: 'default',
          name: match[1],
          module: match[2]
        });
        continue;
      }
      
      match = line.match(/^import\s*\*\s+as\s+(\w+)\s+from\s+['"](.+?)['"]/);
      if (match) {
        imports.push({
          type: 'namespace',
          alias: match[1],
          module: match[2]
        });
      }
    }
    
    return imports;
  }

  extractExports(tokens, code) {
    const exports = [];
    const lines = code.split('\n');
    
    for (const line of lines) {
      if (line.includes('export default')) {
        exports.push({ type: 'default' });
      }
      
      if (line.includes('export') && !line.includes('export default')) {
        exports.push({ type: 'named' });
      }
    }
    
    return exports;
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

class TypeScriptParser extends JavaScriptParser {
  constructor() {
    super('typescript');
  }
}

module.exports = { JavaScriptParser, TypeScriptParser };
