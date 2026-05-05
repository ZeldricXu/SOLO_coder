const express = require('express');
const router = express.Router();
const logger = require('../config/logger');
const ruleConfigService = require('../services/ruleConfigService');

router.get('/global', async (req, res) => {
  try {
    const { language } = req.query;
    
    const rules = await ruleConfigService.getGlobalRules(language);
    
    res.json({
      code: 200,
      data: {
        rules,
        count: rules.length
      }
    });
  } catch (error) {
    logger.error('获取全局规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取全局规则失败',
      error: error.message
    });
  }
});

router.get('/project/:repo_id', async (req, res) => {
  try {
    const { repo_id } = req.params;
    const { language } = req.query;
    
    const rules = await ruleConfigService.getProjectRules(repo_id, language);
    
    res.json({
      code: 200,
      data: {
        repo_id,
        rules,
        count: rules.length
      }
    });
  } catch (error) {
    logger.error('获取项目规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取项目规则失败',
      error: error.message
    });
  }
});

router.get('/merged/:repo_id', async (req, res) => {
  try {
    const { repo_id } = req.params;
    const { language } = req.query;
    
    const rules = await ruleConfigService.getMergedRules(repo_id, language);
    
    res.json({
      code: 200,
      data: {
        repo_id,
        rules,
        count: rules.length
      }
    });
  } catch (error) {
    logger.error('获取合并规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取合并规则失败',
      error: error.message
    });
  }
});

router.post('/global', async (req, res) => {
  try {
    const ruleData = req.body;
    
    if (!ruleData.language || !ruleData.rule_id) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: language 或 rule_id'
      });
    }
    
    const rule = await ruleConfigService.createGlobalRule(ruleData);
    
    logger.info('全局规则已创建: language=%s, rule_id=%s', rule.language, rule.rule_id);
    
    res.json({
      code: 200,
      data: rule
    });
  } catch (error) {
    logger.error('创建全局规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '创建全局规则失败',
      error: error.message
    });
  }
});

router.post('/project/:repo_id', async (req, res) => {
  try {
    const { repo_id } = req.params;
    const ruleData = req.body;
    
    if (!ruleData.language || !ruleData.rule_id) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: language 或 rule_id'
      });
    }
    
    const rule = await ruleConfigService.createProjectRule(repo_id, ruleData);
    
    logger.info('项目规则已创建: repo_id=%s, language=%s, rule_id=%s', 
      repo_id, rule.language, rule.rule_id);
    
    res.json({
      code: 200,
      data: rule
    });
  } catch (error) {
    logger.error('创建项目规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '创建项目规则失败',
      error: error.message
    });
  }
});

router.post('/project/:repo_id/batch', async (req, res) => {
  try {
    const { repo_id } = req.params;
    const { rules } = req.body;
    
    if (!rules || !Array.isArray(rules)) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: rules (必须是数组)'
      });
    }
    
    const results = await ruleConfigService.createBatchProjectRules(repo_id, rules);
    
    logger.info('项目规则已批量创建: repo_id=%s, count=%d', repo_id, results.length);
    
    res.json({
      code: 200,
      data: {
        repo_id,
        created_count: results.length,
        rules: results
      }
    });
  } catch (error) {
    logger.error('批量创建项目规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '批量创建项目规则失败',
      error: error.message
    });
  }
});

router.put('/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const updates = req.body;
    
    const rule = await ruleConfigService.updateRule(id, updates);
    
    if (!rule) {
      return res.status(404).json({
        code: 404,
        message: '规则不存在'
      });
    }
    
    logger.info('规则已更新: id=%s', id);
    
    res.json({
      code: 200,
      data: rule
    });
  } catch (error) {
    logger.error('更新规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '更新规则失败',
      error: error.message
    });
  }
});

router.delete('/:id', async (req, res) => {
  try {
    const { id } = req.params;
    
    const rule = await ruleConfigService.deleteRule(id);
    
    if (!rule) {
      return res.status(404).json({
        code: 404,
        message: '规则不存在'
      });
    }
    
    logger.info('规则已删除: id=%s', id);
    
    res.json({
      code: 200,
      data: rule
    });
  } catch (error) {
    logger.error('删除规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '删除规则失败',
      error: error.message
    });
  }
});

router.delete('/project/:repo_id', async (req, res) => {
  try {
    const { repo_id } = req.params;
    
    const rules = await ruleConfigService.deleteProjectRules(repo_id);
    
    logger.info('项目规则已删除: repo_id=%s, count=%d', repo_id, rules.length);
    
    res.json({
      code: 200,
      data: {
        repo_id,
        deleted_count: rules.length,
        rules
      }
    });
  } catch (error) {
    logger.error('删除项目规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '删除项目规则失败',
      error: error.message
    });
  }
});

router.post('/copy/:source_repo_id/to/:target_repo_id', async (req, res) => {
  try {
    const { source_repo_id, target_repo_id } = req.params;
    
    const rules = await ruleConfigService.copyRulesFromTemplate(source_repo_id, target_repo_id);
    
    logger.info('规则已复制: source=%s, target=%s, count=%d', 
      source_repo_id, target_repo_id, rules.length);
    
    res.json({
      code: 200,
      data: {
        source_repo_id,
        target_repo_id,
        copied_count: rules.length,
        rules
      }
    });
  } catch (error) {
    logger.error('复制规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '复制规则失败',
      error: error.message
    });
  }
});

router.get('/config/:tool/:language', async (req, res) => {
  try {
    const { tool, language } = req.params;
    const { repo_id } = req.query;
    
    const config = await ruleConfigService.getConfigForTool(tool, language, repo_id);
    
    res.json({
      code: 200,
      data: {
        tool,
        language,
        repo_id,
        config
      }
    });
  } catch (error) {
    logger.error('获取工具配置失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取工具配置失败',
      error: error.message
    });
  }
});

router.get('/languages', async (req, res) => {
  try {
    const languages = await ruleConfigService.getAllLanguages();
    
    res.json({
      code: 200,
      data: {
        languages
      }
    });
  } catch (error) {
    logger.error('获取语言列表失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '获取语言列表失败',
      error: error.message
    });
  }
});

router.get('/export/:repo_id?', async (req, res) => {
  try {
    const { repo_id } = req.params;
    
    const exportData = await ruleConfigService.exportRules(repo_id);
    
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Content-Disposition', `attachment; filename="rules_${repo_id || 'global'}.json"`);
    
    res.json({
      code: 200,
      data: exportData
    });
  } catch (error) {
    logger.error('导出规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '导出规则失败',
      error: error.message
    });
  }
});

router.post('/import/:repo_id', async (req, res) => {
  try {
    const { repo_id } = req.params;
    const ruleData = req.body;
    
    if (!ruleData || !ruleData.rules) {
      return res.status(400).json({
        code: 400,
        message: '缺少必要参数: rules'
      });
    }
    
    const result = await ruleConfigService.importRules(repo_id, ruleData);
    
    logger.info('规则已导入: repo_id=%s, created=%d, errors=%d', 
      repo_id, result.created, result.errors.length);
    
    res.json({
      code: 200,
      data: result
    });
  } catch (error) {
    logger.error('导入规则失败: %s', error.message);
    res.status(500).json({
      code: 500,
      message: '导入规则失败',
      error: error.message
    });
  }
});

module.exports = router;
