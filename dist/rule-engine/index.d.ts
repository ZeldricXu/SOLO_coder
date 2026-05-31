export type Operator = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'contains' | 'startsWith' | 'endsWith' | 'in' | 'notIn' | 'regex';
export type LogicalOperator = 'AND' | 'OR';
export interface Condition {
    field: string;
    operator: Operator;
    value: unknown;
}
export interface RuleCondition {
    logicalOp: LogicalOperator;
    conditions: Array<Condition | RuleCondition>;
}
export interface RuleAction {
    type: 'webhook' | 'log' | 'alert' | 'command' | 'set_state';
    config: Record<string, unknown>;
}
export interface Rule {
    ruleId: string;
    name: string;
    description: string;
    enabled: boolean;
    priority: number;
    condition: RuleCondition;
    actions: RuleAction[];
    eventTypes: string[];
    createdAt: string;
    updatedAt: string;
    lastTriggeredAt?: string;
    triggerCount: number;
}
export interface RuleDefinition {
    name: string;
    description?: string;
    priority?: number;
    condition: RuleCondition;
    actions: RuleAction[];
    eventTypes: string[];
    enabled?: boolean;
}
export interface EventContext {
    eventId: string;
    eventType: string;
    timestamp: number;
    data: Record<string, unknown>;
    source: string;
    metadata: Record<string, unknown>;
}
export interface ActionExecutor {
    (action: RuleAction, context: EventContext, rule: Rule): Promise<void>;
}
export declare class RuleEngine {
    private rules;
    private actionExecutors;
    private eventBus;
    constructor();
    private registerDefaultActions;
    registerActionExecutor(actionType: string, executor: ActionExecutor): void;
    createRule(definition: RuleDefinition): Rule;
    updateRule(ruleId: string, updates: Partial<Omit<Rule, 'ruleId' | 'createdAt' | 'triggerCount' | 'lastTriggeredAt'>>): Rule | null;
    deleteRule(ruleId: string): boolean;
    getRule(ruleId: string): Rule | undefined;
    listRules(eventType?: string): Rule[];
    enableRule(ruleId: string): boolean;
    disableRule(ruleId: string): boolean;
    processEvent(event: EventContext): Promise<Array<{
        ruleId: string;
        matched: boolean;
        actionsExecuted: number;
    }>>;
    private evaluateCondition;
    private evaluateSingleCondition;
    private getNestedValue;
    private executeAction;
    on(eventType: string, handler: (context: EventContext) => void): () => void;
    private emit;
    createEvent(eventType: string, data: Record<string, unknown>, source?: string): EventContext;
    getStats(): {
        totalRules: number;
        enabledRules: number;
        totalTriggers: number;
        actionTypes: string[];
    };
}
//# sourceMappingURL=index.d.ts.map