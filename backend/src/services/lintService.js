const { exec } = require('child_process');
const util = require('util');
const fs = require('fs-extra');
const path = require('path');
const os = require('os');
const lintModel = require('../models/lintModel');
const ruleConfigService = require('../services/ruleConfigService');
const logger = require('../config/logger');

const execPromise = util.promisify(exec);

const lintService = {
  async analyzeFile(filePath, content, language, repo_id = null) {
    try {
      if (language === 'python') {
        return await this.runPylint(filePath, content, repo_id);
      } else if (language === 'javascript') {
        return await this.runEslint(filePath, content, 'javascript', repo_id);
      } else if (language === 'typescript') {
        return await this.runEslint(filePath, content, 'typescript', repo_id);
      }
      
      logger.warn('不支持的语言类型: %s, 跳过规范检测', language);
      return {
        success: true,
        language,
        results: [],
        skipped: true
      };
    } catch (error) {
      logger.error('文件规范检测失败: %s', error.message);
      return {
        success: false,
        language,
        error: error.message,
        results: []
      };
    }
  },

  async runPylint(filePath, content, repo_id = null) {
    const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pylint_'));
    const tempFile = path.join(tempDir, path.basename(filePath) || 'temp.py');
    
    try {
      await fs.writeFile(tempFile, content || '');
      
      const config = await this.getPylintConfig(repo_id);
      
      const pylintPath = process.env.PYLINT_PATH || 'pylint';
      
      const enableRules = config.enable.length > 0 
        ? `--enable=${config.enable.join(',')}` 
        : '';
      
      const disableRules = config.disable.length > 0 
        ? `--disable=${config.disable.join(',')}` 
        : '--disable=all';
      
      const options = [
        disableRules,
        enableRules,
        '--output-format=json',
        `--max-line-length=${config.maxLineLength}`,
        '--score=n'
      ].filter(opt => opt).join(' ');
      
      const command = `${pylintPath} ${options} "${tempFile}"`;
      
      logger.debug('Pylint命令: %s', command);
      
      let stdout, stderr;
      try {
        const result = await execPromise(command, {
          maxBuffer: 1024 * 1024 * 10,
          timeout: process.env.MAX_ANALYSIS_TIMEOUT || 120000
        });
        stdout = result.stdout;
        stderr = result.stderr;
      } catch (execError) {
        stdout = execError.stdout;
        stderr = execError.stderr;
        
        if (!stdout && execError.code !== 0) {
          logger.warn('pylint执行返回非零退出码: %d', execError.code);
        }
      }
      
      let results = [];
      
      if (stdout && stdout.trim()) {
        try {
          const parsed = JSON.parse(stdout);
          results = parsed.map(item => ({
            ruleId: item['message-id'] || 'unknown',
            severity: this.mapPylintSeverity(item.type),
            line: item.line || 1,
            column: (item.column || 0) + 1,
            message: item.message,
            source: item.symbol
          }));
        } catch (parseError) {
          logger.warn('解析pylint输出失败: %s', parseError.message);
          
          if (stderr) {
            results.push({
              ruleId: 'pylint_error',
              severity: 'warning',
              line: 1,
              column: 1,
              message: stderr.substring(0, 200),
              source: 'pylint'
            });
          }
        }
      }
      
      return {
        success: true,
        language: 'python',
        results,
        tool: 'pylint',
        config_used: {
          repo_id,
          enable_rules: config.enable.length,
          disable_rules: config.disable.length,
          max_line_length: config.maxLineLength
        }
      };
    } finally {
      await fs.remove(tempDir).catch(() => {});
    }
  },

  async getPylintConfig(repo_id = null) {
    try {
      if (repo_id) {
        return await ruleConfigService.getPylintConfig(repo_id);
      }
      
      return {
        language: 'python',
        tool: 'pylint',
        enable: ['E', 'F', 'W', 'C', 'R'],
        disable: [],
        options: {},
        maxLineLength: parseInt(process.env.PYLINT_MAX_LINE_LENGTH) || 100
      };
    } catch (error) {
      logger.warn('获取Pylint配置失败，使用默认配置: %s', error.message);
      return {
        language: 'python',
        tool: 'pylint',
        enable: ['E', 'F', 'W', 'C', 'R'],
        disable: [],
        options: {},
        maxLineLength: 100
      };
    }
  },

  async runEslint(filePath, content, language = 'javascript', repo_id = null) {
    const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'eslint_'));
    const ext = path.extname(filePath) || (language === 'typescript' ? '.ts' : '.js');
    const tempFile = path.join(tempDir, `temp${ext}`);
    
    let eslintConfig;
    try {
      if (repo_id) {
        eslintConfig = language === 'typescript'
          ? await ruleConfigService.getTypescriptEslintConfig(repo_id)
          : await ruleConfigService.getEslintConfig(repo_id);
      } else {
        eslintConfig = this.getDefaultEslintConfig(language);
      }
    } catch (error) {
      logger.warn('获取ESLint配置失败，使用默认配置: %s', error.message);
      eslintConfig = this.getDefaultEslintConfig(language);
    }
    
    logger.debug('ESLint配置: %o', eslintConfig);
    
    try {
      await fs.writeFile(tempFile, content || '');
      const configFile = path.join(tempDir, '.eslintrc.json');
      await fs.writeJSON(configFile, eslintConfig);
      
      const eslintPath = process.env.ESLINT_PATH || 'eslint';
      
      const command = `${eslintPath} "${tempFile}" --config "${configFile}" --format json --no-ignore`;
      
      logger.debug('ESLint命令: %s', command);
      
      let stdout, stderr;
      try {
        const result = await execPromise(command, {
          maxBuffer: 1024 * 1024 * 10,
          timeout: process.env.MAX_ANALYSIS_TIMEOUT || 120000
        });
        stdout = result.stdout;
        stderr = result.stderr;
      } catch (execError) {
        stdout = execError.stdout;
        stderr = execError.stderr;
        
        if (!stdout && execError.code !== 0) {
          logger.warn('eslint执行返回非零退出码: %d', execError.code);
        }
      }
      
      let results = [];
      
      if (stdout && stdout.trim()) {
        try {
          const parsed = JSON.parse(stdout);
          if (Array.isArray(parsed) && parsed.length > 0) {
            const fileResult = parsed[0];
            results = (fileResult.messages || []).map(msg => ({
              ruleId: msg.ruleId || 'unknown',
              severity: msg.severity === 2 ? 'error' : 'warning',
              line: msg.line || 1,
              column: msg.column || 1,
              message: msg.message,
              source: msg.ruleId
            }));
          }
        } catch (parseError) {
          logger.warn('解析eslint输出失败: %s', parseError.message);
        }
      }
      
      if (stderr && stderr.includes('Parsing error')) {
        results.push({
          ruleId: 'parsing_error',
          severity: 'error',
          line: 1,
          column: 1,
          message: '代码语法解析错误',
          source: 'eslint'
        });
      }
      
      return {
        success: true,
        language: language,
        results,
        tool: 'eslint',
        config_used: {
          repo_id,
          language,
          rules_count: Object.keys(eslintConfig.rules || {}).length
        }
      };
    } finally {
      await fs.remove(tempDir).catch(() => {});
    }
  },

  getDefaultEslintConfig(language = 'javascript') {
    const baseConfig = {
      env: {
        es6: true,
        node: true
      },
      parserOptions: {
        ecmaVersion: 2020,
        sourceType: 'module'
      },
      extends: ['eslint:recommended'],
      rules: {
        'no-console': 'warn',
        'no-unused-vars': 'warn',
        'semi': ['error', 'always'],
        'quotes': ['warn', 'single'],
        'indent': ['warn', 2],
        'no-multi-spaces': 'warn',
        'no-trailing-spaces': 'warn',
        'eol-last': ['warn', 'always']
      }
    };
    
    if (language === 'typescript') {
      return {
        ...baseConfig,
        parser: '@typescript-eslint/parser',
        plugins: ['@typescript-eslint'],
        extends: ['eslint:recommended', 'plugin:@typescript-eslint/recommended']
      };
    }
    
    return baseConfig;
  },

  mapPylintSeverity(type) {
    const severityMap = {
      'error': 'error',
      'E': 'error',
      'F': 'error',
      'warning': 'warning',
      'W': 'warning',
      'convention': 'info',
      'C': 'info',
      'refactor': 'info',
      'R': 'info'
    };
    return severityMap[type] || 'info';
  },

  async analyzeCommit(commit_id, changedFiles, repo_id = null) {
    try {
      logger.info('开始规范检测: commit_id=%s, repo_id=%s', commit_id, repo_id);
      
      await lintModel.deleteByCommitId(commit_id);
      
      const results = [];
      
      for (const file of changedFiles) {
        if (!file.file_content || file.language === 'unknown') {
          continue;
        }
        
        const analysisResult = await this.analyzeFile(
          file.file_path,
          file.file_content,
          file.language,
          repo_id
        );
        
        if (analysisResult.success && analysisResult.results.length > 0) {
          await lintModel.createBatchResults(
            commit_id,
            file.file_path,
            analysisResult.results
          );
          
          results.push({
            file_path: file.file_path,
            language: file.language,
            issues: analysisResult.results.length,
            tool: analysisResult.tool,
            config_used: analysisResult.config_used
          });
        }
      }
      
      const statistics = await lintModel.getStatistics(commit_id);
      const score = await lintModel.calculateScore(commit_id);
      
      logger.info('规范检测完成: commit_id=%s, total_issues=%d, score=%d', 
        commit_id, statistics.total, score);
      
      return {
        commit_id,
        repo_id,
        results,
        statistics,
        score
      };
    } catch (error) {
      logger.error('提交规范检测失败: %s', error.message);
      throw error;
    }
  },

  async getResults(commit_id) {
    try {
      const results = await lintModel.findByCommitId(commit_id);
      const statistics = await lintModel.getStatistics(commit_id);
      const score = await lintModel.calculateScore(commit_id);
      
      const groupedByFile = {};
      results.forEach(result => {
        if (!groupedByFile[result.file_path]) {
          groupedByFile[result.file_path] = [];
        }
        groupedByFile[result.file_path].push(result);
      });
      
      return {
        commit_id,
        score,
        statistics,
        files: groupedByFile,
        raw_results: results
      };
    } catch (error) {
      logger.error('获取规范检测结果失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = lintService;
