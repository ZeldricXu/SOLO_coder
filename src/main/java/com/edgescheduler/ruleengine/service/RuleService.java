package com.edgescheduler.ruleengine.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.ruleengine.dto.RuleDTO;
import com.edgescheduler.ruleengine.dto.RuleTriggerRequest;
import com.edgescheduler.ruleengine.entity.Rule;
import com.edgescheduler.ruleengine.entity.RuleExecution;

import java.util.List;
import java.util.Map;

public interface RuleService {

    RuleDTO createRule(RuleDTO ruleDTO);

    RuleDTO getRule(String ruleId);

    IPage<RuleDTO> listRules(Page<Rule> page, String triggerType, Integer enabled);

    RuleDTO updateRule(String ruleId, RuleDTO ruleDTO);

    RuleDTO setRuleEnabled(String ruleId, boolean enabled);

    void deleteRule(String ruleId);

    RuleExecution triggerRule(RuleTriggerRequest request);

    RuleExecution getExecutionStatus(String runId);

    List<RuleExecution> getRuleExecutions(String ruleId, int limit);

    void executeTimerRules();

    Map<String, Object> evaluateCondition(String expression, Map<String, Object> context);

    Map<String, Object> executeAction(Rule rule, Map<String, Object> context);
}
