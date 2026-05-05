const fs = require('fs');
const path = require('path');

class StatusTransitionEngine {
  constructor() {
    this.configPath = path.join(__dirname, '../config/statusTransitionRules.json');
    this.config = null;
    this.transitionRules = null;
    this.statusFlow = null;
    this.statusesMap = null;
    this.builtInValidators = this.initializeBuiltInValidators();
    
    this.loadConfig();
    this.watchConfigFile();
  }

  initializeBuiltInValidators() {
    return {
      validateCanStartTask: (task, newStatus, context, conditions) => {
        if (newStatus === 'in_progress') {
          return { valid: true };
        }
        return { valid: true };
      },

      validateCanCancelTask: (task, newStatus, context, conditions) => {
        if (newStatus === 'cancelled') {
          return { valid: true };
        }
        return { valid: true };
      },

      validateCanPauseTask: (task, newStatus, context, conditions) => {
        if (newStatus === 'todo') {
          return { valid: true };
        }
        return { valid: true };
      },

      validateCanCompleteTask: (task, newStatus, context, conditions) => {
        if (newStatus === 'completed') {
          const config = conditions || {};
          if (config.progressMustBe100 && task.progress < 100) {
            return {
              valid: false,
              message: config.errorMessage || '任务进度必须达到100%才能标记为完成'
            };
          }
          return { valid: true };
        }
        return { valid: true };
      },

      validateCanCancelInProgressTask: (task, newStatus, context, conditions) => {
        if (newStatus === 'cancelled') {
          return { valid: true };
        }
        return { valid: true };
      },

      validateCanReopenTask: (task, newStatus, context, conditions) => {
        if (newStatus === 'in_progress') {
          const config = conditions || {};
          const maxDays = config.maxDaysSinceCompletion || 7;
          
          if (task.completed_at) {
            const now = new Date();
            const completedAt = new Date(task.completed_at);
            const daysSinceCompletion = Math.floor((now - completedAt) / (1000 * 60 * 60 * 24));
            
            if (daysSinceCompletion > maxDays) {
              const errorMessage = config.errorMessage 
                ? config.errorMessage.replace('{days}', maxDays)
                : `任务已完成超过${maxDays}天，无法重新打开`;
              
              return {
                valid: false,
                message: errorMessage
              };
            }
          }
          return { valid: true };
        }
        return { valid: true };
      },

      validateCanRestoreTask: (task, newStatus, context, conditions) => {
        if (newStatus === 'todo') {
          return { valid: true };
        }
        return { valid: true };
      }
    };
  }

  loadConfig() {
    try {
      if (fs.existsSync(this.configPath)) {
        const configContent = fs.readFileSync(this.configPath, 'utf8');
        this.config = JSON.parse(configContent);
        console.log('[StatusTransitionEngine] 配置文件加载成功:', this.config.statusConfig?.version);
      } else {
        console.log('[StatusTransitionEngine] 配置文件不存在，使用默认配置');
        this.config = this.getDefaultConfig();
      }
      
      this.initializeRulesFromConfig();
    } catch (error) {
      console.error('[StatusTransitionEngine] 加载配置文件失败:', error.message);
      console.log('[StatusTransitionEngine] 回退到默认配置');
      this.config = this.getDefaultConfig();
      this.initializeRulesFromConfig();
    }
  }

  getDefaultConfig() {
    return {
      statusConfig: {
        version: 'default-1.0.0',
        description: '默认配置'
      },
      statuses: [
        { value: 'todo', label: '待办', color: '#d9d9d9' },
        { value: 'in_progress', label: '进行中', color: '#1890ff' },
        { value: 'completed', label: '已完成', color: '#52c41a' },
        { value: 'cancelled', label: '已取消', color: '#ff4d4f' }
      ],
      primaryFlow: ['todo', 'in_progress', 'completed'],
      alternativeFlows: [['todo', 'cancelled'], ['in_progress', 'cancelled']],
      revertFlow: ['completed', 'in_progress', 'todo'],
      transitionRules: {
        todo: { allowedTransitions: ['in_progress', 'cancelled'], businessRules: [] },
        in_progress: { allowedTransitions: ['todo', 'completed', 'cancelled'], businessRules: [] },
        completed: { allowedTransitions: ['in_progress'], businessRules: [] },
        cancelled: { allowedTransitions: ['todo'], businessRules: [] }
      },
      globalSettings: {
        allowSameStatusTransition: false,
        optimisticLocking: { enabled: true, versionField: 'version' },
        historyTracking: { enabled: true, trackFields: ['status', 'progress'] }
      }
    };
  }

  initializeRulesFromConfig() {
    this.transitionRules = {};
    this.statusesMap = new Map();

    if (this.config.statuses) {
      this.config.statuses.forEach(status => {
        this.statusesMap.set(status.value, status);
      });
    }

    if (this.config.transitionRules) {
      for (const [status, rules] of Object.entries(this.config.transitionRules)) {
        this.transitionRules[status] = {
          allowedTransitions: rules.allowedTransitions || [],
          businessRules: rules.businessRules || []
        };
      }
    }

    this.statusFlow = {
      primaryFlow: this.config.primaryFlow || ['todo', 'in_progress', 'completed'],
      alternativeFlows: this.config.alternativeFlows || [],
      revertFlow: this.config.revertFlow || []
    };

    console.log('[StatusTransitionEngine] 规则初始化完成');
    console.log('[StatusTransitionEngine] 可用状态:', Array.from(this.statusesMap.keys()));
  }

  watchConfigFile() {
    try {
      if (fs.existsSync(this.configPath)) {
        fs.watch(this.configPath, (eventType) => {
          if (eventType === 'change') {
            console.log('[StatusTransitionEngine] 检测到配置文件变化，重新加载...');
            setTimeout(() => this.loadConfig(), 500);
          }
        });
        console.log('[StatusTransitionEngine] 配置文件监听器已启动');
      }
    } catch (error) {
      console.error('[StatusTransitionEngine] 配置文件监听失败:', error.message);
    }
  }

  reloadConfig() {
    console.log('[StatusTransitionEngine] 手动触发配置重载...');
    this.loadConfig();
    return { success: true, config: this.config };
  }

  getConfig() {
    return { ...this.config };
  }

  validateTransition(currentStatus, newStatus, task = {}, context = {}) {
    const errors = [];
    const warnings = [];

    if (!this.transitionRules[currentStatus]) {
      return {
        valid: false,
        errors: [`无效的当前状态: ${currentStatus}`],
        availableStatuses: this.getAvailableStatusList()
      };
    }

    const ruleSet = this.transitionRules[currentStatus];

    const globalSettings = this.config.globalSettings || {};
    if (globalSettings.allowSameStatusTransition === false && currentStatus === newStatus) {
      return {
        valid: true,
        skipped: true,
        message: '状态未发生变化',
        transition: {
          from: currentStatus,
          to: newStatus,
          fromLabel: this.getStatusLabel(currentStatus),
          toLabel: this.getStatusLabel(newStatus)
        }
      };
    }

    if (!ruleSet.allowedTransitions.includes(newStatus)) {
      return {
        valid: false,
        errors: [`无法从状态 "${this.getStatusLabel(currentStatus)}" 转换到 "${this.getStatusLabel(newStatus)}"`],
        allowedTransitions: ruleSet.allowedTransitions.map(s => this.getStatusLabel(s)),
        allowedTransitionValues: ruleSet.allowedTransitions
      };
    }

    for (const rule of ruleSet.businessRules) {
      if (!rule.enabled) continue;

      let result = { valid: true };
      
      if (rule.validateFunction && this.builtInValidators[rule.validateFunction]) {
        result = this.builtInValidators[rule.validateFunction](
          task, 
          newStatus, 
          context, 
          rule.conditions
        );
      }

      if (this.config.customRules?.enabled) {
        for (const customRule of this.config.customRules.rules) {
          if (!customRule.enabled) continue;
          
          const trigger = customRule.trigger;
          if ((trigger.fromStatus === '*' || trigger.fromStatus === currentStatus) &&
              (trigger.toStatus === '*' || trigger.toStatus === newStatus)) {
            
            try {
              if (customRule.condition?.type === 'javascript') {
                const customResult = this.evaluateCustomRule(customRule, task, context);
                if (!customResult.valid) {
                  result = customResult;
                }
              }
            } catch (customError) {
              console.error('[StatusTransitionEngine] 自定义规则执行失败:', customError.message);
            }
          }
        }
      }

      if (!result.valid) {
        errors.push(result.message || `规则 "${rule.name}" 验证失败`);
      }
    }

    if (errors.length > 0) {
      return {
        valid: false,
        errors,
        warnings
      };
    }

    return {
      valid: true,
      errors: [],
      warnings,
      transition: {
        from: currentStatus,
        to: newStatus,
        fromLabel: this.getStatusLabel(currentStatus),
        toLabel: this.getStatusLabel(newStatus)
      }
    };
  }

  evaluateCustomRule(rule, task, context) {
    try {
      const code = rule.condition.code;
      const fn = new Function('task', 'context', `return (${code})`);
      const result = fn(task, context);
      
      if (typeof result === 'boolean') {
        return result ? { valid: true } : { valid: false, message: rule.description || '自定义规则验证失败' };
      }
      
      return result;
    } catch (error) {
      console.error('[StatusTransitionEngine] 自定义规则执行错误:', error);
      return { valid: true };
    }
  }

  getStatusLabel(status) {
    const statusConfig = this.statusesMap.get(status);
    if (statusConfig) {
      return statusConfig.label;
    }
    const fallbackLabels = {
      'todo': '待办',
      'in_progress': '进行中',
      'completed': '已完成',
      'cancelled': '已取消'
    };
    return fallbackLabels[status] || status;
  }

  getStatusConfig(status) {
    return this.statusesMap.get(status) || null;
  }

  getAvailableStatusList() {
    return Array.from(this.statusesMap.entries()).map(([value, config]) => ({
      value,
      label: config.label,
      color: config.color,
      icon: config.icon,
      description: config.description
    }));
  }

  getAvailableTransitions(currentStatus) {
    if (!this.transitionRules[currentStatus]) {
      return [];
    }
    return this.transitionRules[currentStatus].allowedTransitions.map(status => {
      const config = this.statusesMap.get(status);
      return {
        value: status,
        label: config?.label || status,
        color: config?.color,
        icon: config?.icon
      };
    });
  }

  getPrimaryFlow() {
    return this.statusFlow.primaryFlow.map(status => {
      const config = this.statusesMap.get(status);
      return {
        value: status,
        label: config?.label || status,
        color: config?.color
      };
    });
  }

  isPrimaryFlowTransition(from, to) {
    const flow = this.statusFlow.primaryFlow;
    const fromIndex = flow.indexOf(from);
    const toIndex = flow.indexOf(to);
    return fromIndex !== -1 && toIndex !== -1 && toIndex > fromIndex;
  }

  isRevertFlowTransition(from, to) {
    const flow = this.statusFlow.revertFlow;
    const fromIndex = flow.indexOf(from);
    const toIndex = flow.indexOf(to);
    return fromIndex !== -1 && toIndex !== -1 && toIndex > fromIndex;
  }

  getAllTransitions() {
    const transitions = [];
    for (const [from, rules] of Object.entries(this.transitionRules)) {
      for (const to of rules.allowedTransitions) {
        transitions.push({
          from,
          to,
          fromLabel: this.getStatusLabel(from),
          toLabel: this.getStatusLabel(to),
          isPrimary: this.isPrimaryFlowTransition(from, to),
          isRevert: this.isRevertFlowTransition(from, to)
        });
      }
    }
    return transitions;
  }

  getGlobalSettings() {
    return { ...(this.config.globalSettings || {}) };
  }
}

const statusTransitionEngine = new StatusTransitionEngine();

module.exports = statusTransitionEngine;
