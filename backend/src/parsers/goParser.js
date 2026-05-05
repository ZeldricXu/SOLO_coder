const BaseParser = require('./baseParser');
const logger = require('../config/logger');

class GoParser extends BaseParser {
  constructor() {
    super();
    this.language = 'go';
    this.supportedExtensions = ['.go'];
  }

  getDecisionPoints() {
    return [
      'if', 'else', 'switch', 'case', 'default',
      'for', 'range', 'select',
      '&&', '||',
      'break', 'continue', 'goto',
      'fallthrough', 'defer'
    ];
  }

  async parse(code) {
    if (!code || code.trim() === '') {
      return {
        language: 'go',
        package: '',
        imports: [],
        functions: [],
        methods: [],
        structs: [],
        interfaces: []
      };
    }

    try {
      const result = {
        language: 'go',
        package: '',
        imports: [],
        functions: [],
        methods: [],
        structs: [],
        interfaces: []
      };

      result.package = this.extractPackage(code);
      result.imports = this.extractImports(code);
      result.structs = this.extractStructs(code);
      result.interfaces = this.extractInterfaces(code);
      result.functions = this.extractTopLevelFunctions(code);
      result.methods = this.extractMethods(code, result.structs);

      return result;
    } catch (error) {
      logger.error('Go代码解析失败: %s', error.message);
      throw error;
    }
  }

  extractPackage(code) {
    const match = code.match(/^package\s+(\w+)/m);
    return match ? match[1] : '';
  }

  extractImports(code) {
    const imports = [];
    
    const singleImportPattern = /^import\s+"([^"]+)"/gm;
    let match;
    while ((match = singleImportPattern.exec(code)) !== null) {
      imports.push({
        type: 'single',
        path: match[1]
      });
    }
    
    const blockImportPattern = /^import\s*\(\s*([\s\S]*?)\s*\)/gm;
    while ((match = blockImportPattern.exec(code)) !== null) {
      const blockContent = match[1];
      const linePattern = /"([^"]+)"/g;
      let lineMatch;
      while ((lineMatch = linePattern.exec(blockContent)) !== null) {
        imports.push({
          type: 'block',
          path: lineMatch[1]
        });
      }
    }
    
    return imports;
  }

  extractStructs(code) {
    const structs = [];
    const structPattern = /^(\s*)type\s+(\w+)\s+struct\s*\{/gm;
    const lines = code.split('\n');
    
    let match;
    while ((match = structPattern.exec(code)) !== null) {
      const structName = match[2];
      const indent = match[1].length;
      
      const startLine = this.findLineNumber(code, match.index);
      const startIndex = match.index;
      
      const braceStart = code.indexOf('{', match.index);
      const braceEnd = this.findMatchingBrace(code, braceStart);
      
      if (braceStart !== -1 && braceEnd !== -1) {
        const structContent = code.substring(braceStart, braceEnd + 1);
        const fields = this.extractStructFields(structContent);
        
        structs.push({
          name: structName,
          startLine,
          endLine: this.findLineNumber(code, braceEnd),
          content: structContent,
          fields
        });
      }
    }
    
    return structs;
  }

  extractStructFields(structContent) {
    const fields = [];
    const lines = structContent.split('\n');
    
    for (let i = 1; i < lines.length - 1; i++) {
      const line = lines[i].trim();
      if (!line || line.startsWith('//')) continue;
      
      const fieldMatch = line.match(/^(\w+)\s+([^/]+)(?:\/\/.*)?$/);
      if (fieldMatch) {
        fields.push({
          name: fieldMatch[1],
          type: fieldMatch[2].trim(),
          tag: ''
        });
      }
      
      const tagMatch = line.match(/`([^`]+)`/);
      if (tagMatch && fields.length > 0) {
        fields[fields.length - 1].tag = tagMatch[1];
      }
    }
    
    return fields;
  }

  extractInterfaces(code) {
    const interfaces = [];
    const interfacePattern = /^(\s*)type\s+(\w+)\s+interface\s*\{/gm;
    
    let match;
    while ((match = interfacePattern.exec(code)) !== null) {
      const interfaceName = match[2];
      
      const startLine = this.findLineNumber(code, match.index);
      const braceStart = code.indexOf('{', match.index);
      const braceEnd = this.findMatchingBrace(code, braceStart);
      
      if (braceStart !== -1 && braceEnd !== -1) {
        const interfaceContent = code.substring(braceStart, braceEnd + 1);
        const methods = this.extractInterfaceMethods(interfaceContent);
        
        interfaces.push({
          name: interfaceName,
          startLine,
          endLine: this.findLineNumber(code, braceEnd),
          content: interfaceContent,
          methods
        });
      }
    }
    
    return interfaces;
  }

  extractInterfaceMethods(interfaceContent) {
    const methods = [];
    const lines = interfaceContent.split('\n');
    
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('//') || trimmed === '{' || trimmed === '}') continue;
      
      const methodMatch = trimmed.match(/^(\w+)\s*\(([^)]*)\)\s*(.*?)(?:\s*\/\/.*)?$/);
      if (methodMatch) {
        methods.push({
          name: methodMatch[1],
          params: methodMatch[2],
          returns: methodMatch[3].trim()
        });
      }
    }
    
    return methods;
  }

  extractTopLevelFunctions(code) {
    const functions = [];
    
    const funcPatterns = [
      /^(\s*)func\s+(\w+)\s*\(([^)]*)\)\s*(?:\(([^)]*)\))?\s*\{/gm,
      /^(\s*)func\s+(\w+)\s*\(([^)]*)\)\s+([^{]+?)\s*\{/gm
    ];
    
    for (const pattern of funcPatterns) {
      let match;
      while ((match = pattern.exec(code)) !== null) {
        const indent = match[1].length;
        
        if (indent > 0) continue;
        
        const funcName = match[2];
        
        const startLine = this.findLineNumber(code, match.index);
        const braceStart = code.indexOf('{', match.index);
        const braceEnd = this.findMatchingBrace(code, braceStart);
        
        if (braceStart !== -1 && braceEnd !== -1) {
          const funcContent = code.substring(match.index, braceEnd + 1);
          const params = this.parseGoParameters(match[3]);
          const returns = this.parseGoReturns(match[4] || '');
          
          functions.push({
            name: funcName,
            startLine,
            endLine: this.findLineNumber(code, braceEnd),
            params,
            paramsCount: params.length,
            returns,
            returnsCount: returns.length,
            content: funcContent,
            isMethod: false
          });
        }
      }
    }
    
    return functions;
  }

  extractMethods(code, structs) {
    const methods = [];
    
    const methodPattern = /^(\s*)func\s*\(\s*(\w+)\s+(\*?)(\w+)\s*\)\s+(\w+)\s*\(([^)]*)\)\s*(?:\(([^)]*)\))?\s*\{/gm;
    
    let match;
    while ((match = methodPattern.exec(code)) !== null) {
      const indent = match[1].length;
      const receiverName = match[2];
      const receiverPointer = match[3] === '*';
      const receiverType = match[4];
      const methodName = match[5];
      
      const startLine = this.findLineNumber(code, match.index);
      const braceStart = code.indexOf('{', match.index);
      const braceEnd = this.findMatchingBrace(code, braceStart);
      
      if (braceStart !== -1 && braceEnd !== -1) {
        const methodContent = code.substring(match.index, braceEnd + 1);
        const params = this.parseGoParameters(match[6]);
        const returns = this.parseGoReturns(match[7] || '');
        
        methods.push({
          name: methodName,
          receiver: {
            name: receiverName,
            type: receiverType,
            isPointer: receiverPointer
          },
          startLine,
          endLine: this.findLineNumber(code, braceEnd),
          params,
          paramsCount: params.length,
          returns,
          returnsCount: returns.length,
          content: methodContent,
          isMethod: true
        });
      }
    }
    
    return methods;
  }

  parseGoParameters(paramsString) {
    if (!paramsString || paramsString.trim() === '') {
      return [];
    }
    
    const params = [];
    let depth = 0;
    let currentParam = '';
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
        if (char === '[' || char === '{' || char === '(') {
          depth++;
        } else if (char === ']' || char === '}' || char === ')') {
          depth--;
        }
        
        if (char === ',' && depth === 0) {
          const param = this.parseGoParamType(currentParam.trim());
          if (param) params.push(param);
          currentParam = '';
          continue;
        }
      }
      
      currentParam += char;
    }
    
    if (currentParam.trim()) {
      const param = this.parseGoParamType(currentParam.trim());
      if (param) params.push(param);
    }
    
    return params;
  }

  parseGoParamType(paramString) {
    if (!paramString) return null;
    
    const parts = paramString.split(/\s+/);
    
    if (parts.length === 2) {
      return {
        name: parts[0],
        type: parts[1]
      };
    }
    
    if (parts.length === 1) {
      return {
        name: '',
        type: parts[0]
      };
    }
    
    return {
      name: '',
      type: paramString
    };
  }

  parseGoReturns(returnsString) {
    if (!returnsString || returnsString.trim() === '') {
      return [];
    }
    
    const returns = [];
    let depth = 0;
    let currentType = '';
    let inString = false;
    let stringChar = '';
    
    const trimmed = returnsString.trim();
    
    if (trimmed.startsWith('(') && trimmed.endsWith(')')) {
      return this.parseGoParameters(trimmed.substring(1, trimmed.length - 1));
    }
    
    for (let i = 0; i < trimmed.length; i++) {
      const char = trimmed[i];
      const prevChar = i > 0 ? trimmed[i - 1] : '';
      
      if ((char === '"' || char === "'") && prevChar !== '\\') {
        if (!inString) {
          inString = true;
          stringChar = char;
        } else if (char === stringChar) {
          inString = false;
        }
      }
      
      if (!inString) {
        if (char === '[' || char === '{' || char === '(') {
          depth++;
        } else if (char === ']' || char === '}' || char === ')') {
          depth--;
        }
        
        if (char === ',' && depth === 0) {
          returns.push({ name: '', type: currentType.trim() });
          currentType = '';
          continue;
        }
      }
      
      currentType += char;
    }
    
    if (currentType.trim()) {
      returns.push({ name: '', type: currentType.trim() });
    }
    
    return returns;
  }

  findMatchingBrace(code, startIndex) {
    let depth = 0;
    let inString = false;
    let stringChar = '';
    let inComment = false;
    let inBlockComment = false;
    
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
      
      if ((char === '"' || char === "'") && prevChar !== '\\') {
        if (!inString) {
          inString = true;
          stringChar = char;
        } else if (char === stringChar) {
          inString = false;
        }
      }
      
      if (!inString) {
        if (char === '{') {
          depth++;
        } else if (char === '}') {
          depth--;
          if (depth === 0) {
            return i;
          }
        }
      }
    }
    
    return -1;
  }

  findLineNumber(code, index) {
    const before = code.substring(0, index);
    return (before.match(/\n/g) || []).length + 1;
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
    
    for (const method of parsedResult.methods || []) {
      functions.push({
        name: `${method.receiver.type}.${method.name}`,
        content: method.content,
        params: method.paramsCount,
        startLine: method.startLine,
        endLine: method.endLine
      });
    }
    
    return functions;
  }
}

module.exports = GoParser;
