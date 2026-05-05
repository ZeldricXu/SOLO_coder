const duplicateModel = require('../models/duplicateModel');
const logger = require('../config/logger');

const duplicateService = {
  calculateSimilarity(text1, text2) {
    if (!text1 || !text2) {
      return 0;
    }
    
    const set1 = new Set(this.getShingles(text1));
    const set2 = new Set(this.getShingles(text2));
    
    if (set1.size === 0 && set2.size === 0) {
      return 100;
    }
    
    if (set1.size === 0 || set2.size === 0) {
      return 0;
    }
    
    const intersection = new Set([...set1].filter(x => set2.has(x)));
    const union = new Set([...set1, ...set2]);
    
    const similarity = (intersection.size / union.size) * 100;
    
    return Math.round(similarity * 100) / 100;
  },

  getShingles(text, k = 3) {
    const normalized = this.normalizeText(text);
    const shingles = [];
    
    for (let i = 0; i <= normalized.length - k; i++) {
      shingles.push(normalized.substring(i, i + k));
    }
    
    return shingles;
  },

  normalizeText(text) {
    return text
      .toLowerCase()
      .replace(/\s+/g, ' ')
      .replace(/[{}();,\[\]]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  },

  extractFunctions(content, language) {
    const functions = [];
    
    if (!content) {
      return functions;
    }
    
    const lines = content.split('\n');
    let currentFunction = null;
    let braceDepth = 0;
    let inFunction = false;
    
    switch (language) {
      case 'javascript':
      case 'typescript':
        return this.extractJavaScriptFunctions(content);
      case 'python':
        return this.extractPythonFunctions(content);
      case 'java':
        return this.extractJavaFunctions(content);
      case 'c':
      case 'cpp':
        return this.extractCFunctions(content);
      default:
        return [content];
    }
  },

  extractJavaScriptFunctions(content) {
    const functions = [];
    
    const functionPattern = /(?:function\s+(\w+)|const\s+(\w+)\s*=\s*(?:async\s+)?function|let\s+(\w+)\s*=\s*(?:async\s+)?function|var\s+(\w+)\s*=\s*(?:async\s+)?function)/g;
    let match;
    
    while ((match = functionPattern.exec(content)) !== null) {
      const funcName = match[1] || match[2] || match[3] || match[4];
      const funcStart = match.index;
      const funcEnd = this.findFunctionEnd(content, funcStart);
      
      if (funcStart < funcEnd) {
        const funcContent = content.substring(funcStart, funcEnd);
        functions.push({
          name: funcName,
          content: funcContent,
          lines: funcContent.split('\n').length
        });
      }
    }
    
    const arrowPattern = /(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s+)?\(/g;
    while ((match = arrowPattern.exec(content)) !== null) {
      const funcName = match[1];
      const funcStart = match.index;
      
      const arrowPos = content.indexOf('=>', funcStart);
      if (arrowPos !== -1) {
        let funcEnd;
        
        let afterArrow = arrowPos + 2;
        while (afterArrow < content.length && /\s/.test(content[afterArrow])) {
          afterArrow++;
        }
        
        if (content[afterArrow] === '{') {
          funcEnd = this.findFunctionEnd(content, afterArrow);
        } else {
          funcEnd = this.findExpressionEnd(content, afterArrow);
        }
        
        if (funcStart < funcEnd) {
          const funcContent = content.substring(funcStart, funcEnd);
          if (!functions.find(f => f.name === funcName)) {
            functions.push({
              name: funcName,
              content: funcContent,
              lines: funcContent.split('\n').length
            });
          }
        }
      }
    }
    
    return functions;
  },

  extractPythonFunctions(content) {
    const functions = [];
    const lines = content.split('\n');
    
    const defPattern = /^(\s*)def\s+(\w+)\s*\(/m;
    let matchIndex = 0;
    
    while (true) {
      const match = content.indexOf('def ', matchIndex);
      if (match === -1) break;
      
      const lineStart = content.lastIndexOf('\n', match) + 1;
      const line = content.substring(lineStart, content.indexOf('\n', match) || content.length);
      
      const defMatch = line.match(/^(\s*)def\s+(\w+)\s*\(/);
      if (defMatch) {
        const baseIndent = defMatch[1].length;
        const funcName = defMatch[2];
        
        let funcLines = [line];
        let currentLine = content.indexOf('\n', match) + 1;
        
        while (currentLine < content.length) {
          const nextNewline = content.indexOf('\n', currentLine);
          const lineContent = content.substring(currentLine, nextNewline !== -1 ? nextNewline : content.length);
          
          if (lineContent.trim() === '') {
            funcLines.push(lineContent);
            currentLine = nextNewline + 1;
            continue;
          }
          
          const lineIndent = lineContent.match(/^(\s*)/)[1].length;
          
          if (lineIndent <= baseIndent && lineContent.trim()) {
            if (!lineContent.trim().startsWith('#') && !lineContent.trim().startsWith('@')) {
              break;
            }
          }
          
          funcLines.push(lineContent);
          currentLine = nextNewline !== -1 ? nextNewline + 1 : content.length;
        }
        
        const funcContent = funcLines.join('\n');
        functions.push({
          name: funcName,
          content: funcContent,
          lines: funcLines.length
        });
        
        matchIndex = currentLine;
      } else {
        matchIndex = match + 1;
      }
    }
    
    return functions;
  },

  extractJavaFunctions(content) {
    const functions = [];
    
    const methodPattern = /(?:public|private|protected|static|final)\s+\w+(?:\[\])?\s+(\w+)\s*\(/g;
    let match;
    
    while ((match = methodPattern.exec(content)) !== null) {
      const funcName = match[1];
      const funcStart = match.index;
      const funcEnd = this.findFunctionEnd(content, funcStart);
      
      if (funcStart < funcEnd) {
        const funcContent = content.substring(funcStart, funcEnd);
        functions.push({
          name: funcName,
          content: funcContent,
          lines: funcContent.split('\n').length
        });
      }
    }
    
    return functions;
  },

  extractCFunctions(content) {
    const functions = [];
    
    const funcPattern = /\w+(?:\s*\*)?\s+(\w+)\s*\([^)]*\)\s*\{/g;
    let match;
    
    const excludeNames = ['if', 'for', 'while', 'switch', 'do', 'return', 'sizeof'];
    
    while ((match = funcPattern.exec(content)) !== null) {
      const funcName = match[1];
      
      if (excludeNames.includes(funcName)) {
        continue;
      }
      
      const funcStart = match.index;
      const funcEnd = this.findFunctionEnd(content, funcStart);
      
      if (funcStart < funcEnd) {
        const funcContent = content.substring(funcStart, funcEnd);
        functions.push({
          name: funcName,
          content: funcContent,
          lines: funcContent.split('\n').length
        });
      }
    }
    
    return functions;
  },

  findFunctionEnd(content, startIndex) {
    let depth = 0;
    let inString = false;
    let stringChar = '';
    let inBlockComment = false;
    
    let startBrace = content.indexOf('{', startIndex);
    if (startBrace === -1) {
      return content.length;
    }
    
    for (let i = startBrace; i < content.length; i++) {
      const char = content[i];
      const nextChar = i < content.length - 1 ? content[i + 1] : '';
      const prevChar = i > 0 ? content[i - 1] : '';
      
      if ((char === '"' || char === "'") && prevChar !== '\\' && !inBlockComment) {
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
      
      if (char === '{' && !inString) {
        depth++;
      } else if (char === '}' && !inString) {
        depth--;
        if (depth === 0) {
          return i + 1;
        }
      }
    }
    
    return content.length;
  },

  findExpressionEnd(content, startIndex) {
    let depth = 0;
    let inString = false;
    let stringChar = '';
    
    for (let i = startIndex; i < content.length; i++) {
      const char = content[i];
      const prevChar = i > 0 ? content[i - 1] : '';
      
      if ((char === '"' || char === "'" || char === '`') && prevChar !== '\\') {
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
          if (depth === 0) {
            return i;
          }
          depth--;
        } else if (char === ';' || char === ',') {
          if (depth === 0) {
            return i;
          }
        } else if (char === '\n') {
          let nextPos = i + 1;
          while (nextPos < content.length && /\s/.test(content[nextPos])) {
            nextPos++;
          }
          if (nextPos < content.length && ['}', ')', ']', ';', ','].includes(content[nextPos])) {
            return i;
          }
        }
      }
    }
    
    return content.length;
  },

  async detectDuplicates(changedFiles, similarityThreshold = 70) {
    const duplicates = [];
    const allFunctions = [];
    
    for (const file of changedFiles) {
      if (!file.file_content) continue;
      
      const functions = this.extractFunctions(file.file_content, file.language);
      
      for (const func of functions) {
        allFunctions.push({
          file_path: file.file_path,
          language: file.language,
          function_name: func.name,
          content: func.content,
          lines: func.lines
        });
      }
    }
    
    for (let i = 0; i < allFunctions.length; i++) {
      for (let j = i + 1; j < allFunctions.length; j++) {
        const func1 = allFunctions[i];
        const func2 = allFunctions[j];
        
        if (func1.language !== func2.language) continue;
        if (func1.file_path === func2.file_path && func1.function_name === func2.function_name) continue;
        if (func1.lines < 10 || func2.lines < 10) continue;
        
        const similarity = this.calculateSimilarity(func1.content, func2.content);
        
        if (similarity >= similarityThreshold) {
          duplicates.push({
            file_path1: func1.file_path,
            function_name1: func1.function_name,
            file_path2: func2.file_path,
            function_name2: func2.function_name,
            similarity,
            lines_count: Math.max(func1.lines, func2.lines),
            fragment1: func1.content.substring(0, 500),
            fragment2: func2.content.substring(0, 500)
          });
        }
      }
    }
    
    const fileContents = changedFiles.filter(f => f.file_content);
    
    for (let i = 0; i < fileContents.length; i++) {
      for (let j = i + 1; j < fileContents.length; j++) {
        const file1 = fileContents[i];
        const file2 = fileContents[j];
        
        if (file1.language !== file2.language) continue;
        
        const fileSimilarity = this.calculateSimilarity(file1.file_content, file2.file_content);
        
        if (fileSimilarity >= 80) {
          const existingDuplicate = duplicates.find(
            d => (d.file_path1 === file1.file_path && d.file_path2 === file2.file_path) ||
                 (d.file_path1 === file2.file_path && d.file_path2 === file1.file_path)
          );
          
          if (!existingDuplicate) {
            duplicates.push({
              file_path1: file1.file_path,
              function_name1: null,
              file_path2: file2.file_path,
              function_name2: null,
              similarity: fileSimilarity,
              lines_count: Math.max(
                file1.file_content.split('\n').length,
                file2.file_content.split('\n').length
              ),
              fragment1: file1.file_content.substring(0, 500),
              fragment2: file2.file_content.substring(0, 500)
            });
          }
        }
      }
    }
    
    return duplicates.sort((a, b) => b.similarity - a.similarity);
  },

  async analyzeCommit(commit_id, changedFiles) {
    try {
      logger.info('开始重复代码检测: commit_id=%s', commit_id);
      
      await duplicateModel.deleteByCommitId(commit_id);
      
      const duplicates = await this.detectDuplicates(changedFiles);
      
      if (duplicates.length > 0) {
        await duplicateModel.createBatchResults(commit_id, duplicates);
      }
      
      const statistics = await duplicateModel.getStatistics(commit_id);
      const score = await duplicateModel.calculateScore(commit_id);
      
      logger.info('重复代码检测完成: commit_id=%s, total_duplicates=%d, score=%d',
        commit_id, statistics.total_duplicates, score);
      
      return {
        commit_id,
        duplicates,
        statistics,
        score
      };
    } catch (error) {
      logger.error('提交重复检测失败: %s', error.message);
      throw error;
    }
  },

  async getResults(commit_id) {
    try {
      const results = await duplicateModel.findByCommitId(commit_id);
      const statistics = await duplicateModel.getStatistics(commit_id);
      const score = await duplicateModel.calculateScore(commit_id);
      
      return {
        commit_id,
        score,
        statistics,
        duplicates: results
      };
    } catch (error) {
      logger.error('获取重复检测结果失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = duplicateService;
