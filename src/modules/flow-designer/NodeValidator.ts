import { FlowNode, ValidationError, ValidationWarning, ValidationResult } from '../../types/flow';
import { isEmpty } from '../../common/utils';

export class NodeValidator {
  private nodeConfigRules: Record<string, { required: string[]; optional: string[] }> = {
    start: { required: [], optional: ['parameters'] },
    end: { required: [], optional: ['parameters'] },
    action: { required: ['action'], optional: ['timeout', 'retries', 'parameters'] },
    condition: { required: ['conditions'], optional: ['parameters'] },
    delay: { required: ['delayMs'], optional: ['parameters'] },
    parallel: { required: [], optional: ['parameters'] },
    subflow: { required: ['subflowId'], optional: ['parameters'] }
  };

  validate(node: FlowNode): ValidationResult {
    const errors: ValidationError[] = [];
    const warnings: ValidationWarning[] = [];

    if (!node.id || isEmpty(node.id)) {
      errors.push({
        code: 'NODE_ID_REQUIRED',
        message: '节点ID不能为空',
        severity: 'error'
      });
    }

    if (!node.name || isEmpty(node.name)) {
      errors.push({
        code: 'NODE_NAME_REQUIRED',
        message: '节点名称不能为空',
        nodeId: node.id,
        severity: 'error'
      });
    }

    if (!node.type) {
      errors.push({
        code: 'NODE_TYPE_REQUIRED',
        message: '节点类型不能为空',
        nodeId: node.id,
        severity: 'error'
      });
    } else if (!this.nodeConfigRules[node.type]) {
      errors.push({
        code: 'INVALID_NODE_TYPE',
        message: `无效的节点类型: ${node.type}`,
        nodeId: node.id,
        severity: 'error'
      });
    } else {
      const configErrors = this.validateNodeConfig(node);
      errors.push(...configErrors);
    }

    if (typeof node.x !== 'number' || typeof node.y !== 'number') {
      warnings.push({
        code: 'NODE_POSITION_MISSING',
        message: '节点位置信息缺失',
        nodeId: node.id
      });
    }

    if (node.type === 'condition' && (!node.config.conditions || node.config.conditions.length === 0)) {
      errors.push({
        code: 'CONDITION_RULES_REQUIRED',
        message: '条件节点必须配置至少一个条件规则',
        nodeId: node.id,
        severity: 'error'
      });
    }

    if (node.type === 'delay' && (typeof node.config.delayMs !== 'number' || node.config.delayMs < 0)) {
      errors.push({
        code: 'INVALID_DELAY_VALUE',
        message: '延迟时间必须是非负整数',
        nodeId: node.id,
        severity: 'error'
      });
    }

    if (node.config.timeout !== undefined && (typeof node.config.timeout !== 'number' || node.config.timeout < 0)) {
      errors.push({
        code: 'INVALID_TIMEOUT_VALUE',
        message: '超时时间必须是非负整数',
        nodeId: node.id,
        severity: 'error'
      });
    }

    if (node.config.retries !== undefined && (typeof node.config.retries !== 'number' || node.config.retries < 0)) {
      errors.push({
        code: 'INVALID_RETRIES_VALUE',
        message: '重试次数必须是非负整数',
        nodeId: node.id,
        severity: 'error'
      });
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings
    };
  }

  private validateNodeConfig(node: FlowNode): ValidationError[] {
    const errors: ValidationError[] = [];
    const rules = this.nodeConfigRules[node.type];
    if (!rules) return errors;

    for (const required of rules.required) {
      if (!(required in node.config) || node.config[required as keyof typeof node.config] === undefined) {
        errors.push({
          code: `MISSING_${required.toUpperCase()}`,
          message: `节点配置缺少必填字段: ${required}`,
          nodeId: node.id,
          severity: 'error'
        });
      }
    }

    if (node.type === 'condition' && node.config.conditions) {
      for (let i = 0; i < node.config.conditions.length; i++) {
        const condition = node.config.conditions[i];
        if (!condition.field) {
          errors.push({
            code: 'CONDITION_FIELD_REQUIRED',
            message: `条件规则[${i}]缺少字段名`,
            nodeId: node.id,
            severity: 'error'
          });
        }
        if (!condition.operator) {
          errors.push({
            code: 'CONDITION_OPERATOR_REQUIRED',
            message: `条件规则[${i}]缺少操作符`,
            nodeId: node.id,
            severity: 'error'
          });
        }
        if (condition.value === undefined) {
          errors.push({
            code: 'CONDITION_VALUE_REQUIRED',
            message: `条件规则[${i}]缺少值`,
            nodeId: node.id,
            severity: 'error'
          });
        }
      }
    }

    return errors;
  }

  validateAll(nodes: FlowNode[]): ValidationResult {
    const allErrors: ValidationError[] = [];
    const allWarnings: ValidationWarning[] = [];

    const nodeIds = new Set<string>();
    for (const node of nodes) {
      if (nodeIds.has(node.id)) {
        allErrors.push({
          code: 'DUPLICATE_NODE_ID',
          message: `重复的节点ID: ${node.id}`,
          nodeId: node.id,
          severity: 'error'
        });
      }
      nodeIds.add(node.id);

      const result = this.validate(node);
      allErrors.push(...result.errors);
      allWarnings.push(...result.warnings);
    }

    const startNodes = nodes.filter(n => n.type === 'start');
    if (startNodes.length === 0) {
      allErrors.push({
        code: 'NO_START_NODE',
        message: '流程必须包含至少一个开始节点',
        severity: 'error'
      });
    } else if (startNodes.length > 1) {
      allWarnings.push({
        code: 'MULTIPLE_START_NODES',
        message: '流程包含多个开始节点'
      });
    }

    const endNodes = nodes.filter(n => n.type === 'end');
    if (endNodes.length === 0) {
      allWarnings.push({
        code: 'NO_END_NODE',
        message: '建议流程包含至少一个结束节点'
      });
    }

    return {
      valid: allErrors.length === 0,
      errors: allErrors,
      warnings: allWarnings
    };
  }
}
