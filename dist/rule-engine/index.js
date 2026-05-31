"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.RuleEngine = void 0;
const uuid_1 = require("uuid");
const logger_1 = __importDefault(require("../common/logger"));
class RuleEngine {
    constructor() {
        this.rules = new Map();
        this.actionExecutors = new Map();
        this.eventBus = new Map();
        this.registerDefaultActions();
    }
    registerDefaultActions() {
        this.registerActionExecutor('log', async (action, context) => {
            const level = action.config.level || 'info';
            const message = action.config.message || '规则触发';
            const logFn = logger_1.default[level];
            if (typeof logFn === 'function') {
                logFn({ eventId: context.eventId, ruleId: action.config.ruleId, data: context.data }, message);
            }
            else {
                logger_1.default.info({ eventId: context.eventId, ruleId: action.config.ruleId, data: context.data }, message);
            }
        });
        this.registerActionExecutor('alert', async (action, context) => {
            const alertType = action.config.alertType || 'info';
            const message = action.config.message || '告警触发';
            logger_1.default.warn({ alertType, eventId: context.eventId, data: context.data }, message);
        });
        this.registerActionExecutor('set_state', async (action, context) => {
            const key = action.config.key;
            const value = action.config.value;
            logger_1.default.info({ key, value, eventId: context.eventId }, '设置状态');
        });
    }
    registerActionExecutor(actionType, executor) {
        this.actionExecutors.set(actionType, executor);
        logger_1.default.info({ actionType }, '注册动作执行器');
    }
    createRule(definition) {
        const rule = {
            ruleId: (0, uuid_1.v4)(),
            name: definition.name,
            description: definition.description || '',
            enabled: definition.enabled ?? true,
            priority: definition.priority ?? 5,
            condition: definition.condition,
            actions: definition.actions,
            eventTypes: definition.eventTypes,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
            triggerCount: 0
        };
        this.rules.set(rule.ruleId, rule);
        logger_1.default.info({ ruleId: rule.ruleId, name: rule.name, eventTypes: rule.eventTypes }, '创建规则');
        return rule;
    }
    updateRule(ruleId, updates) {
        const rule = this.rules.get(ruleId);
        if (!rule)
            return null;
        Object.assign(rule, updates, { updatedAt: new Date().toISOString() });
        logger_1.default.info({ ruleId }, '更新规则');
        return rule;
    }
    deleteRule(ruleId) {
        const deleted = this.rules.delete(ruleId);
        if (deleted) {
            logger_1.default.info({ ruleId }, '删除规则');
        }
        return deleted;
    }
    getRule(ruleId) {
        return this.rules.get(ruleId);
    }
    listRules(eventType) {
        let rules = Array.from(this.rules.values());
        if (eventType) {
            rules = rules.filter(r => r.eventTypes.includes(eventType));
        }
        return rules.sort((a, b) => b.priority - a.priority);
    }
    enableRule(ruleId) {
        const rule = this.rules.get(ruleId);
        if (!rule)
            return false;
        rule.enabled = true;
        rule.updatedAt = new Date().toISOString();
        logger_1.default.info({ ruleId }, '启用规则');
        return true;
    }
    disableRule(ruleId) {
        const rule = this.rules.get(ruleId);
        if (!rule)
            return false;
        rule.enabled = false;
        rule.updatedAt = new Date().toISOString();
        logger_1.default.info({ ruleId }, '禁用规则');
        return true;
    }
    async processEvent(event) {
        const matchingRules = this.listRules(event.eventType).filter(r => r.enabled);
        const results = [];
        logger_1.default.debug({ eventId: event.eventId, eventType: event.eventType, ruleCount: matchingRules.length }, '处理事件');
        for (const rule of matchingRules) {
            try {
                const matched = this.evaluateCondition(rule.condition, event.data);
                let actionsExecuted = 0;
                if (matched) {
                    rule.lastTriggeredAt = new Date().toISOString();
                    rule.triggerCount++;
                    for (const action of rule.actions) {
                        try {
                            await this.executeAction(action, event, rule);
                            actionsExecuted++;
                        }
                        catch (error) {
                            logger_1.default.error({ ruleId: rule.ruleId, actionType: action.type, error }, '动作执行失败');
                        }
                    }
                }
                results.push({ ruleId: rule.ruleId, matched, actionsExecuted });
            }
            catch (error) {
                logger_1.default.error({ ruleId: rule.ruleId, error }, '规则评估失败');
                results.push({ ruleId: rule.ruleId, matched: false, actionsExecuted: 0 });
            }
        }
        this.emit(event.eventType, event);
        return results;
    }
    evaluateCondition(condition, data) {
        const results = [];
        for (const cond of condition.conditions) {
            if ('logicalOp' in cond) {
                results.push(this.evaluateCondition(cond, data));
            }
            else {
                results.push(this.evaluateSingleCondition(cond, data));
            }
        }
        if (condition.logicalOp === 'AND') {
            return results.every(r => r);
        }
        else {
            return results.some(r => r);
        }
    }
    evaluateSingleCondition(condition, data) {
        const fieldValue = this.getNestedValue(data, condition.field);
        const expectedValue = condition.value;
        switch (condition.operator) {
            case 'eq':
                return fieldValue === expectedValue;
            case 'ne':
                return fieldValue !== expectedValue;
            case 'gt':
                return Number(fieldValue) > Number(expectedValue);
            case 'gte':
                return Number(fieldValue) >= Number(expectedValue);
            case 'lt':
                return Number(fieldValue) < Number(expectedValue);
            case 'lte':
                return Number(fieldValue) <= Number(expectedValue);
            case 'contains':
                return String(fieldValue).includes(String(expectedValue));
            case 'startsWith':
                return String(fieldValue).startsWith(String(expectedValue));
            case 'endsWith':
                return String(fieldValue).endsWith(String(expectedValue));
            case 'in':
                return Array.isArray(expectedValue) && expectedValue.includes(fieldValue);
            case 'notIn':
                return Array.isArray(expectedValue) && !expectedValue.includes(fieldValue);
            case 'regex':
                return new RegExp(String(expectedValue)).test(String(fieldValue));
            default:
                return false;
        }
    }
    getNestedValue(obj, path) {
        return path.split('.').reduce((current, key) => {
            if (current && typeof current === 'object' && !Array.isArray(current)) {
                return current[key];
            }
            return undefined;
        }, obj);
    }
    async executeAction(action, context, rule) {
        const executor = this.actionExecutors.get(action.type);
        if (!executor) {
            logger_1.default.warn({ actionType: action.type }, '未找到动作执行器');
            return;
        }
        await executor(action, context, rule);
    }
    on(eventType, handler) {
        if (!this.eventBus.has(eventType)) {
            this.eventBus.set(eventType, []);
        }
        this.eventBus.get(eventType).push(handler);
        return () => {
            const handlers = this.eventBus.get(eventType);
            if (handlers) {
                const index = handlers.indexOf(handler);
                if (index > -1)
                    handlers.splice(index, 1);
            }
        };
    }
    emit(eventType, context) {
        const handlers = this.eventBus.get(eventType) || [];
        for (const handler of handlers) {
            try {
                handler(context);
            }
            catch (error) {
                logger_1.default.error({ eventType, error }, '事件处理函数异常');
            }
        }
    }
    createEvent(eventType, data, source = 'rule-engine') {
        return {
            eventId: (0, uuid_1.v4)(),
            eventType,
            timestamp: Date.now(),
            data,
            source,
            metadata: {}
        };
    }
    getStats() {
        return {
            totalRules: this.rules.size,
            enabledRules: Array.from(this.rules.values()).filter(r => r.enabled).length,
            totalTriggers: Array.from(this.rules.values()).reduce((sum, r) => sum + r.triggerCount, 0),
            actionTypes: Array.from(this.actionExecutors.keys())
        };
    }
}
exports.RuleEngine = RuleEngine;
//# sourceMappingURL=index.js.map