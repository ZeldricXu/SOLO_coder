const pool = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const logger = require('../config/logger');

const ruleConfigModel = {
  async create(configData) {
    try {
      const { 
        config_type = 'lint', 
        scope_type = 'project', 
        scope_value, 
        language, 
        rule_id, 
        rule_name, 
        severity = 'warn', 
        is_enabled = true, 
        rule_options = {},
        is_override = false
      } = configData;
      
      const result = await pool.query(
        `INSERT INTO rule_configs 
         (config_type, scope_type, scope_value, language, rule_id, rule_name, 
          severity, is_enabled, rule_options, is_override)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
         ON CONFLICT (scope_type, scope_value, language, rule_id) 
         DO UPDATE SET 
           rule_name = EXCLUDED.rule_name,
           severity = EXCLUDED.severity,
           is_enabled = EXCLUDED.is_enabled,
           rule_options = EXCLUDED.rule_options,
           is_override = EXCLUDED.is_override,
           updated_at = CURRENT_TIMESTAMP
         RETURNING *`,
        [config_type, scope_type, scope_value, language, rule_id, rule_name,
         severity, is_enabled, JSON.stringify(rule_options), is_override]
      );
      
      logger.info('规则配置已保存: scope=%s, language=%s, rule_id=%s', 
        scope_type, language, rule_id);
      
      return result.rows[0];
    } catch (error) {
      logger.error('创建规则配置失败: %s', error.message);
      throw error;
    }
  },

  async createBatch(configs) {
    try {
      const results = [];
      for (const config of configs) {
        const result = await this.create(config);
        results.push(result);
      }
      return results;
    } catch (error) {
      logger.error('批量创建规则配置失败: %s', error.message);
      throw error;
    }
  },

  async findById(id) {
    try {
      const result = await pool.query(
        'SELECT * FROM rule_configs WHERE id = $1',
        [id]
      );
      return result.rows[0] || null;
    } catch (error) {
      logger.error('查询规则配置失败: %s', error.message);
      throw error;
    }
  },

  async findByScope(scope_type, scope_value = null) {
    try {
      let query = 'SELECT * FROM rule_configs WHERE scope_type = $1';
      const params = [scope_type];
      
      if (scope_value !== null) {
        query += ' AND scope_value = $2';
        params.push(scope_value);
      }
      
      query += ' ORDER BY language, rule_id';
      
      const result = await pool.query(query, params);
      return result.rows;
    } catch (error) {
      logger.error('按作用域查询规则配置失败: %s', error.message);
      throw error;
    }
  },

  async getGlobalRules(language = null) {
    try {
      let query = `
        SELECT * FROM rule_configs 
        WHERE scope_type = 'global' AND is_enabled = TRUE
      `;
      const params = [];
      
      if (language) {
        query += ' AND language = $1';
        params.push(language);
      }
      
      query += ' ORDER BY rule_id';
      
      const result = await pool.query(query, params);
      return result.rows;
    } catch (error) {
      logger.error('获取全局规则失败: %s', error.message);
      throw error;
    }
  },

  async getProjectRules(repo_id, language = null) {
    try {
      let query = `
        SELECT * FROM rule_configs 
        WHERE scope_type = 'project' AND scope_value = $1 AND is_enabled = TRUE
      `;
      const params = [repo_id];
      
      if (language) {
        query += ' AND language = $2';
        params.push(language);
      }
      
      query += ' ORDER BY rule_id';
      
      const result = await pool.query(query, params);
      return result.rows;
    } catch (error) {
      logger.error('获取项目规则失败: %s', error.message);
      throw error;
    }
  },

  async getMergedRules(repo_id, language = null) {
    try {
      const globalRules = await this.getGlobalRules(language);
      const projectRules = await this.getProjectRules(repo_id, language);
      
      const ruleMap = new Map();
      
      for (const rule of globalRules) {
        const key = `${rule.language}:${rule.rule_id}`;
        ruleMap.set(key, { ...rule, source: 'global' });
      }
      
      for (const rule of projectRules) {
        const key = `${rule.language}:${rule.rule_id}`;
        if (rule.is_override || !ruleMap.has(key)) {
          ruleMap.set(key, { ...rule, source: 'project' });
        }
      }
      
      return Array.from(ruleMap.values()).sort((a, b) => {
        if (a.language !== b.language) {
          return a.language.localeCompare(b.language);
        }
        return a.rule_id.localeCompare(b.rule_id);
      });
    } catch (error) {
      logger.error('获取合并规则失败: %s', error.message);
      throw error;
    }
  },

  async update(id, updates) {
    try {
      const allowedFields = [
        'rule_name', 'severity', 'is_enabled', 'rule_options', 'is_override'
      ];
      
      const fields = [];
      const values = [];
      let paramIndex = 1;
      
      for (const [key, value] of Object.entries(updates)) {
        if (allowedFields.includes(key)) {
          fields.push(`${key} = $${paramIndex}`);
          if (key === 'rule_options') {
            values.push(JSON.stringify(value));
          } else {
            values.push(value);
          }
          paramIndex++;
        }
      }
      
      if (fields.length === 0) {
        return null;
      }
      
      fields.push('updated_at = CURRENT_TIMESTAMP');
      
      const result = await pool.query(
        `UPDATE rule_configs SET ${fields.join(', ')} WHERE id = $${paramIndex} RETURNING *`,
        [...values, id]
      );
      
      if (result.rows.length > 0) {
        logger.info('规则配置已更新: id=%s', id);
      }
      
      return result.rows[0] || null;
    } catch (error) {
      logger.error('更新规则配置失败: %s', error.message);
      throw error;
    }
  },

  async delete(id) {
    try {
      const result = await pool.query(
        'DELETE FROM rule_configs WHERE id = $1 RETURNING *',
        [id]
      );
      
      if (result.rows.length > 0) {
        logger.info('规则配置已删除: id=%s', id);
      }
      
      return result.rows[0] || null;
    } catch (error) {
      logger.error('删除规则配置失败: %s', error.message);
      throw error;
    }
  },

  async deleteProjectRules(repo_id) {
    try {
      const result = await pool.query(
        'DELETE FROM rule_configs WHERE scope_type = $1 AND scope_value = $2 RETURNING *',
        ['project', repo_id]
      );
      
      logger.info('项目规则配置已删除: repo_id=%s, count=%d', repo_id, result.rows.length);
      
      return result.rows;
    } catch (error) {
      logger.error('删除项目规则配置失败: %s', error.message);
      throw error;
    }
  },

  async copyRulesFromTemplate(source_repo_id, target_repo_id) {
    try {
      const sourceRules = await this.getProjectRules(source_repo_id);
      
      if (sourceRules.length === 0) {
        logger.warn('源项目没有规则配置: repo_id=%s', source_repo_id);
        return [];
      }
      
      const copiedRules = [];
      for (const rule of sourceRules) {
        const copied = await this.create({
          ...rule,
          id: undefined,
          scope_type: 'project',
          scope_value: target_repo_id,
          created_at: undefined,
          updated_at: undefined
        });
        copiedRules.push(copied);
      }
      
      logger.info('规则配置已复制: source=%s, target=%s, count=%d', 
        source_repo_id, target_repo_id, copiedRules.length);
      
      return copiedRules;
    } catch (error) {
      logger.error('复制规则配置失败: %s', error.message);
      throw error;
    }
  },

  async getLintConfigForLanguage(language, repo_id = null) {
    try {
      let rules;
      
      if (repo_id) {
        rules = await this.getMergedRules(repo_id, language);
      } else {
        rules = await this.getGlobalRules(language);
      }
      
      const enabledRules = rules.filter(r => r.is_enabled);
      
      const config = {
        language,
        rules: {},
        options: {}
      };
      
      for (const rule of enabledRules) {
        config.rules[rule.rule_id] = {
          severity: rule.severity,
          enabled: true,
          options: rule.rule_options || {}
        };
      }
      
      return config;
    } catch (error) {
      logger.error('获取语言规则配置失败: %s', error.message);
      throw error;
    }
  },

  async getAllLanguages() {
    try {
      const result = await pool.query(
        'SELECT DISTINCT language FROM rule_configs ORDER BY language'
      );
      return result.rows.map(r => r.language);
    } catch (error) {
      logger.error('获取所有语言列表失败: %s', error.message);
      throw error;
    }
  }
};

module.exports = ruleConfigModel;
