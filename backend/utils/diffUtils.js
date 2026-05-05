const logger = require('./logger');

const diffUtils = {
  
  computeLineDiff(oldContent, newContent) {
    const oldLines = oldContent.split('\n');
    const newLines = newContent.split('\n');
    
    const m = oldLines.length;
    const n = newLines.length;
    
    const dp = Array(m + 1).fill(null).map(() => Array(n + 1).fill(0));
    
    for (let i = 1; i <= m; i++) {
      for (let j = 1; j <= n; j++) {
        if (oldLines[i - 1] === newLines[j - 1]) {
          dp[i][j] = dp[i - 1][j - 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
        }
      }
    }
    
    const operations = [];
    let i = m, j = n;
    
    while (i > 0 || j > 0) {
      if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
        operations.unshift({
          type: 'unchanged',
          index: i - 1,
          line: oldLines[i - 1]
        });
        i--;
        j--;
      } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
        operations.unshift({
          type: 'added',
          index: j - 1,
          line: newLines[j - 1]
        });
        j--;
      } else if (i > 0) {
        operations.unshift({
          type: 'removed',
          index: i - 1,
          line: oldLines[i - 1]
        });
        i--;
      }
    }
    
    return operations;
  },

  computeCharDiff(oldContent, newContent) {
    const oldChars = oldContent.split('');
    const newChars = newContent.split('');
    
    const m = oldChars.length;
    const n = newChars.length;
    
    const dp = Array(m + 1).fill(null).map(() => Array(n + 1).fill(0));
    
    for (let i = 1; i <= m; i++) {
      for (let j = 1; j <= n; j++) {
        if (oldChars[i - 1] === newChars[j - 1]) {
          dp[i][j] = dp[i - 1][j - 1] + 1;
        } else {
          dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
        }
      }
    }
    
    const operations = [];
    let i = m, j = n;
    
    while (i > 0 || j > 0) {
      if (i > 0 && j > 0 && oldChars[i - 1] === newChars[j - 1]) {
        operations.unshift({
          type: 'unchanged',
          index: i - 1,
          char: oldChars[i - 1]
        });
        i--;
        j--;
      } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
        operations.unshift({
          type: 'added',
          index: j - 1,
          char: newChars[j - 1]
        });
        j--;
      } else if (i > 0) {
        operations.unshift({
          type: 'removed',
          index: i - 1,
          char: oldChars[i - 1]
        });
        i--;
      }
    }
    
    return operations;
  },

  createDelta(oldContent, newContent, options = {}) {
    const useLineDiff = options.useLineDiff !== false;
    
    const operations = useLineDiff 
      ? this.computeLineDiff(oldContent, newContent)
      : this.computeCharDiff(oldContent, newContent);
    
    const addedOps = operations.filter(op => op.type === 'added');
    const removedOps = operations.filter(op => op.type === 'removed');
    const unchangedOps = operations.filter(op => op.type === 'unchanged');
    
    const delta = {
      version: '1.0',
      type: useLineDiff ? 'line' : 'char',
      originalLength: oldContent.length,
      newLength: newContent.length,
      originalLineCount: oldContent.split('\n').length,
      newLineCount: newContent.split('\n').length,
      operations: [],
      addedCount: addedOps.length,
      removedCount: removedOps.length,
      unchangedCount: unchangedOps.length
    };
    
    for (const op of operations) {
      if (op.type === 'added') {
        delta.operations.push({
          type: 'add',
          index: op.index,
          value: useLineDiff ? op.line : op.char
        });
      } else if (op.type === 'removed') {
        delta.operations.push({
          type: 'remove',
          index: op.index,
          value: useLineDiff ? op.line : op.char
        });
      }
    }
    
    const changeRatio = delta.operations.length / Math.max(unchangedOps.length + delta.operations.length, 1);
    delta.changeRatio = changeRatio;
    
    return delta;
  },

  applyDelta(originalContent, delta) {
    if (!delta || !delta.operations) {
      return originalContent;
    }
    
    if (delta.type === 'line') {
      return this.applyLineDelta(originalContent, delta);
    }
    
    return this.applyCharDelta(originalContent, delta);
  },

  applyLineDelta(originalContent, delta) {
    let lines = originalContent.split('\n');
    
    const addOps = delta.operations
      .filter(op => op.type === 'add')
      .sort((a, b) => b.index - a.index);
    
    for (const op of addOps) {
      lines.splice(op.index, 0, op.value);
    }
    
    const removeOps = delta.operations
      .filter(op => op.type === 'remove')
      .sort((a, b) => b.index - a.index);
    
    for (const op of removeOps) {
      if (op.index < lines.length) {
        lines.splice(op.index, 1);
      }
    }
    
    return lines.join('\n');
  },

  applyCharDelta(originalContent, delta) {
    let chars = originalContent.split('');
    
    const addOps = delta.operations
      .filter(op => op.type === 'add')
      .sort((a, b) => b.index - a.index);
    
    for (const op of addOps) {
      chars.splice(op.index, 0, op.value);
    }
    
    const removeOps = delta.operations
      .filter(op => op.type === 'remove')
      .sort((a, b) => b.index - a.index);
    
    for (const op of removeOps) {
      if (op.index < chars.length) {
        chars.splice(op.index, 1);
      }
    }
    
    return chars.join('');
  },

  shouldUseDelta(oldContent, newContent, options = {}) {
    const minChangeRatio = options.minChangeRatio || 0.01;
    const maxChangeRatio = options.maxChangeRatio || 0.8;
    const minLength = options.minLength || 100;
    
    if (oldContent.length < minLength || newContent.length < minLength) {
      return false;
    }
    
    const oldLines = oldContent.split('\n');
    const newLines = newContent.split('\n');
    
    let commonLines = 0;
    const oldLineSet = new Set(oldLines);
    
    for (const line of newLines) {
      if (oldLineSet.has(line)) {
        commonLines++;
      }
    }
    
    const changeRatio = 1 - (commonLines / Math.max(oldLines.length, newLines.length));
    
    if (changeRatio < minChangeRatio) {
      return false;
    }
    
    if (changeRatio > maxChangeRatio) {
      return false;
    }
    
    return true;
  },

  estimateDeltaSize(delta) {
    if (!delta || !delta.operations) {
      return 0;
    }
    
    let size = 0;
    
    for (const op of delta.operations) {
      size += 8;
      
      if (op.value) {
        size += Buffer.byteLength(op.value, 'utf8');
      }
    }
    
    return size;
  },

  validateDelta(originalContent, delta, expectedResult) {
    try {
      const result = this.applyDelta(originalContent, delta);
      const isValid = result === expectedResult;
      
      if (!isValid) {
        logger.warn('Delta验证失败: 应用后的结果与预期不符');
      }
      
      return isValid;
    } catch (error) {
      logger.error('Delta验证出错:', { error: error.message });
      return false;
    }
  },

  createSimpleDiff(oldContent, newContent) {
    const lines1 = oldContent.split('\n');
    const lines2 = newContent.split('\n');
    
    const diff = [];
    const maxLength = Math.max(lines1.length, lines2.length);

    for (let i = 0; i < maxLength; i++) {
      const line1 = lines1[i] || '';
      const line2 = lines2[i] || '';

      if (line1 === line2) {
        diff.push({
          type: 'unchanged',
          line: i + 1,
          content: line1
        });
      } else if (i >= lines1.length) {
        diff.push({
          type: 'added',
          line: i + 1,
          content: line2
        });
      } else if (i >= lines2.length) {
        diff.push({
          type: 'removed',
          line: i + 1,
          content: line1
        });
      } else {
        diff.push({
          type: 'modified',
          line: i + 1,
          original: line1,
          modified: line2
        });
      }
    }

    return diff;
  },

  diffToText(diff, options = {}) {
    const showUnchanged = options.showUnchanged || false;
    const contextLines = options.contextLines || 3;
    
    let text = '';
    
    for (let i = 0; i < diff.length; i++) {
      const d = diff[i];
      
      if (d.type === 'unchanged' && !showUnchanged) {
        continue;
      }
      
      switch (d.type) {
        case 'added':
          text += `+ ${d.content}\n`;
          break;
        case 'removed':
          text += `- ${d.content}\n`;
          break;
        case 'modified':
          text += `- ${d.original}\n`;
          text += `+ ${d.modified}\n`;
          break;
        case 'unchanged':
          text += `  ${d.content}\n`;
          break;
      }
    }
    
    return text;
  },

  computeDiffStats(oldContent, newContent) {
    const lines1 = oldContent.split('\n');
    const lines2 = newContent.split('\n');
    
    const diff = this.computeLineDiff(oldContent, newContent);
    
    const stats = {
      originalLines: lines1.length,
      newLines: lines2.length,
      linesChanged: 0,
      linesAdded: 0,
      linesRemoved: 0,
      linesUnchanged: 0,
      charsAdded: 0,
      charsRemoved: 0
    };
    
    for (const op of diff) {
      switch (op.type) {
        case 'added':
          stats.linesAdded++;
          stats.linesChanged++;
          stats.charsAdded += Buffer.byteLength(op.line, 'utf8');
          break;
        case 'removed':
          stats.linesRemoved++;
          stats.linesChanged++;
          stats.charsRemoved += Buffer.byteLength(op.line, 'utf8');
          break;
        case 'unchanged':
          stats.linesUnchanged++;
          break;
      }
    }
    
    stats.changeRatio = stats.linesChanged / Math.max(stats.originalLines, 1);
    
    return stats;
  }
};

module.exports = diffUtils;
