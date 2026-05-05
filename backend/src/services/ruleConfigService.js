const ruleConfigModel = require('../models/ruleConfigModel');
const logger = require('../config/logger');

const ruleConfigService = {
  async createGlobalRule(ruleData) {
    try {
      const rule = await ruleConfigModel.create({
        ...ruleData,
        scope_type: 'global',
        scope_value: null
      });
      
      logger.info('全局规则已创建: language=%s, rule_id=%s', rule.language, rule.rule_id);
      return rule;
    } catch (error) {
      logger.error('创建全局规则失败: %s', error.message);
      throw error;
    }
  },

  async createProjectRule(repo_id, ruleData) {
    try {
      const rule = await ruleConfigModel.create({
        ...ruleData,
        scope_type: 'project',
        scope_value: repo_id
      });
      
      logger.info('项目规则已创建: repo_id=%s, language=%s, rule_id=%s', 
        repo_id, rule.language, rule.rule_id);
      return rule;
    } catch (error) {
      logger.error('创建项目规则失败: %s', error.message);
      throw error;
    }
  },

  async createBatchProjectRules(repo_id, rules) {
    try {
      const results = [];
      for (const rule of rules) {
        const result = await this.createProjectRule(repo_id, rule);
        results.push(result);
      }
      return results;
    } catch (error) {
      logger.error('批量创建项目规则失败: %s', error.message);
      throw error;
    }
  },

  async getGlobalRules(language = null) {
    try {
      return await ruleConfigModel.getGlobalRules(language);
    } catch (error) {
      logger.error('获取全局规则失败: %s', error.message);
      throw error;
    }
  },

  async getProjectRules(repo_id, language = null) {
    try {
      return await ruleConfigModel.getProjectRules(repo_id, language);
    } catch (error) {
      logger.error('获取项目规则失败: %s', error.message);
      throw error;
    }
  },

  async getMergedRules(repo_id, language = null) {
    try {
      return await ruleConfigModel.getMergedRules(repo_id, language);
    } catch (error) {
      logger.error('获取合并规则失败: %s', error.message);
      throw error;
    }
  },

  async getRuleConfig(id) {
    try {
      return await ruleConfigModel.findById(id);
    } catch (error) {
      logger.error('获取规则配置失败: %s', error.message);
      throw error;
    }
  },

  async updateRule(id, updates) {
    try {
      return await ruleConfigModel.update(id, updates);
    } catch (error) {
      logger.error('更新规则失败: %s', error.message);
      throw error;
    }
  },

  async deleteRule(id) {
    try {
      return await ruleConfigModel.delete(id);
    } catch (error) {
      logger.error('删除规则失败: %s', error.message);
      throw error;
    }
  },

  async deleteProjectRules(repo_id) {
    try {
      return await ruleConfigModel.deleteProjectRules(repo_id);
    } catch (error) {
      logger.error('删除项目规则失败: %s', error.message);
      throw error;
    }
  },

  async copyRulesFromTemplate(source_repo_id, target_repo_id) {
    try {
      return await ruleConfigModel.copyRulesFromTemplate(source_repo_id, target_repo_id);
    } catch (error) {
      logger.error('复制规则模板失败: %s', error.message);
      throw error;
    }
  },

  async getLintConfigForLanguage(language, repo_id = null) {
    try {
      return await ruleConfigModel.getLintConfigForLanguage(language, repo_id);
    } catch (error) {
      logger.error('获取语言规则配置失败: %s', error.message);
      throw error;
    }
  },

  async getEslintConfig(repo_id = null) {
    try {
      const rules = await this.getMergedRules(repo_id, 'javascript');
      
      const eslintConfig = {
        env: {
          es6: true,
          node: true
        },
        parserOptions: {
          ecmaVersion: 2020,
          sourceType: 'module'
        },
        extends: ['eslint:recommended'],
        rules: {}
      };
      
      for (const rule of rules) {
        if (!rule.is_enabled) continue;
        
        const severityMap = {
          'error': 2,
          'warn': 1,
          'info': 1,
          'off': 0
        };
        
        const severity = severityMap[rule.severity] || 1;
        const options = rule.rule_options || {};
        
        if (Object.keys(options).length > 0) {
          eslintConfig.rules[rule.rule_id] = [severity, options];
        } else {
          eslintConfig.rules[rule.rule_id] = severity;
        }
      }
      
      return eslintConfig;
    } catch (error) {
      logger.error('获取ESLint配置失败: %s', error.message);
      throw error;
    }
  },

  async getTypescriptEslintConfig(repo_id = null) {
    try {
      const rules = await this.getMergedRules(repo_id, 'typescript');
      
      const eslintConfig = {
        env: {
          es6: true,
          node: true
        },
        parser: '@typescript-eslint/parser',
        parserOptions: {
          ecmaVersion: 2020,
          sourceType: 'module'
        },
        plugins: ['@typescript-eslint'],
        extends: [
          'eslint:recommended',
          'plugin:@typescript-eslint/recommended'
        ],
        rules: {}
      };
      
      for (const rule of rules) {
        if (!rule.is_enabled) continue;
        
        const severityMap = {
          'error': 2,
          'warn': 1,
          'info': 1,
          'off': 0
        };
        
        const severity = severityMap[rule.severity] || 1;
        const options = rule.rule_options || {};
        
        if (Object.keys(options).length > 0) {
          eslintConfig.rules[rule.rule_id] = [severity, options];
        } else {
          eslintConfig.rules[rule.rule_id] = severity;
        }
      }
      
      return eslintConfig;
    } catch (error) {
      logger.error('获取TypeScript ESLint配置失败: %s', error.message);
      throw error;
    }
  },

  async getPylintConfig(repo_id = null) {
    try {
      const rules = await this.getMergedRules(repo_id, 'python');
      
      const enableRules = [];
      const disableRules = [];
      const options = {};
      
      for (const rule of rules) {
        if (rule.is_enabled) {
          enableRules.push(rule.rule_id);
        } else {
          disableRules.push(rule.rule_id);
        }
        
        if (rule.rule_options && Object.keys(rule.rule_options).length > 0) {
          options[rule.rule_id] = rule.rule_options;
        }
      }
      
      return {
        language: 'python',
        tool: 'pylint',
        enable: enableRules,
        disable: disableRules,
        options,
        maxLineLength: options['C']?.['max-line-length'] || 100
      };
    } catch (error) {
      logger.error('获取Pylint配置失败: %s', error.message);
      throw error;
    }
  },

  async getGoLintConfig(repo_id = null) {
    try {
      const rules = await this.getMergedRules(repo_id, 'go');
      
      return {
        language: 'go',
        tools: ['golint', 'govet', 'gofmt'],
        rules: rules.filter(r => r.is_enabled).map(r => ({
          rule_id: r.rule_id,
          severity: r.severity,
          options: r.rule_options || {}
        }))
      };
    } catch (error) {
      logger.error('获取Go Lint配置失败: %s', error.message);
      throw error;
    }
  },

  async getConfigForTool(tool, language, repo_id = null) {
    try {
      switch (tool.toLowerCase()) {
        case 'eslint':
        case 'javascript':
          return await this.getEslintConfig(repo_id);
        case 'typescript-eslint':
        case 'typescript':
          return await this.getTypescriptEslintConfig(repo_id);
        case 'pylint':
        case 'python':
          return await this.getPylintConfig(repo_id);
        case 'golint':
        case 'govet':
        case 'go':
          return await this.getGoLintConfig(repo_id);
        default:
          return await this.getLintConfigForLanguage(language, repo_id);
      }
    } catch (error) {
      logger.error('获取工具配置失败: tool=%s, language=%s, error=%s', 
        tool, language, error.message);
      throw error;
    }
  },

  async getAllLanguages() {
    try {
      return await ruleConfigModel.getAllLanguages();
    } catch (error) {
      logger.error('获取所有语言列表失败: %s', error.message);
      throw error;
    }
  },

  async exportRules(repo_id = null) {
    try {
      let rules;
      if (repo_id) {
        rules = await this.getMergedRules(repo_id);
      } else {
        rules = await this.getGlobalRules();
      }
      
      const grouped = {};
      for (const rule of rules) {
        if (!grouped[rule.language]) {
          grouped[rule.language] = [];
        }
        grouped[rule.language].push({
          rule_id: rule.rule_id,
          rule_name: rule.rule_name,
          severity: rule.severity,
          is_enabled: rule.is_enabled,
          rule_options: rule.rule_options,
          is_override: rule.is_override,
          source: rule.source || 'global'
        });
      }
      
      return {
        exported_at: new Date().toISOString(),
        repo_id,
        rules: grouped
      };
    } catch (error) {
      logger.error('导出规则配置失败: %s', error.message);
      throw error;
    }
  },

  async importRules(repo_id, ruleData) {
    try {
      const results = {
        created: 0,
        updated: 0,
        errors: []
      };
      
      for (const [language, rules] of Object.entries(ruleData.rules || {})) {
        for (const rule of rules) {
          try {
            const existing = await this.createProjectRule(repo_id, {
              language,
              rule_id: rule.rule_id,
              rule_name: rule.rule_name,
              severity: rule.severity,
              is_enabled: rule.is_enabled,
              rule_options: rule.rule_options,
              is_override: rule.is_override
            });
            
            results.created++;
          } catch (error) {
            results.errors.push({
              language,
              rule_id: rule.rule_id,
              error: error.message
            });
          }
        }
      }
      
      logger.info('规则导入完成: created=%d, errors=%d', results.created, results.errors.length);
      return results;
    } catch (error) {
      logger.error('导入规则配置失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = ruleConfigService;
