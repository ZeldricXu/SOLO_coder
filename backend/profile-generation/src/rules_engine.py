import logging
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple, Union
from dataclasses import dataclass

from .rule_parser import (
    RuleCondition,
    TagRule,
    RuleEvaluationResult,
    RuleConditionParser,
    TagRuleParser,
    RuleParserManager,
    ConditionType,
    LogicalOperator,
)

logger = logging.getLogger(__name__)


class TagRulesEngine:
    def __init__(self):
        self._rules: List[TagRule] = []
        self._rules_by_category: Dict[str, List[TagRule]] = {}
        self._exclusive_groups: Dict[str, List[TagRule]] = {}
        self._config_version: str = "1.0.0"
        self._loaded_at: Optional[datetime] = None
        self._default_rules: List[TagRule] = []
        
        self._init_default_rules()
    
    def _init_default_rules(self):
        default_rules_config = [
            {
                "rule_id": "activity_high_default",
                "tag_name": "高活跃",
                "category": "activity",
                "description": "高活跃玩家",
                "condition": {
                    "logical_op": "and",
                    "conditions": [
                        {"field": "unique_active_days", "operator": "gte", "value": 3},
                        {"field": "avg_events_per_day", "operator": "gte", "value": 20}
                    ]
                },
                "confidence": 0.85,
                "reasoning_template": "近90天活跃{unique_active_days}天，日均{avg_events_per_day:.1f}次行为",
                "priority": 100,
                "exclusive_group": "activity_level"
            },
            {
                "rule_id": "activity_medium_default",
                "tag_name": "中活跃",
                "category": "activity",
                "description": "中等活跃玩家",
                "condition": {
                    "logical_op": "and",
                    "conditions": [
                        {"field": "unique_active_days", "operator": "gte", "value": 3},
                        {"field": "avg_events_per_day", "operator": "gte", "value": 10},
                        {"field": "avg_events_per_day", "operator": "lt", "value": 20}
                    ]
                },
                "confidence": 0.8,
                "reasoning_template": "近90天活跃{unique_active_days}天，日均{avg_events_per_day:.1f}次行为",
                "priority": 90,
                "exclusive_group": "activity_level"
            },
            {
                "rule_id": "activity_low_default",
                "tag_name": "低活跃",
                "category": "activity",
                "description": "低活跃玩家",
                "condition": {
                    "logical_op": "and",
                    "conditions": [
                        {"field": "unique_active_days", "operator": "gte", "value": 3},
                        {"field": "avg_events_per_day", "operator": "lt", "value": 10}
                    ]
                },
                "confidence": 0.7,
                "reasoning_template": "近90天活跃{unique_active_days}天，日均{avg_events_per_day:.1f}次行为",
                "priority": 80,
                "exclusive_group": "activity_level"
            },
            {
                "rule_id": "churn_risk_default",
                "tag_name": "流失风险",
                "category": "activity",
                "description": "有流失风险的玩家",
                "condition": {
                    "field": "unique_active_days",
                    "operator": "lt",
                    "value": 3
                },
                "confidence": 0.75,
                "reasoning_template": "近90天仅活跃{unique_active_days}天",
                "priority": 10,
                "exclusive_group": "activity_level"
            },
            {
                "rule_id": "login_user_default",
                "tag_name": "登录用户",
                "category": "activity",
                "description": "登录过的玩家",
                "condition": {
                    "field": "login_count",
                    "operator": "gt",
                    "value": 0
                },
                "confidence": 1.0,
                "reasoning_template": "累计登录{login_count}次",
                "priority": 5
            },
            {
                "rule_id": "payment_high_default",
                "tag_name": "高付费",
                "category": "payment",
                "description": "高付费玩家",
                "condition": {
                    "field": "total_payment_amount",
                    "operator": "gte",
                    "value": 100.0
                },
                "confidence": 0.9,
                "reasoning_template": "累计付费超过100元",
                "priority": 100,
                "exclusive_group": "payment_level"
            },
            {
                "rule_id": "payment_medium_default",
                "tag_name": "中付费",
                "category": "payment",
                "description": "中等付费玩家",
                "condition": {
                    "logical_op": "and",
                    "conditions": [
                        {"field": "total_payment_amount", "operator": "gte", "value": 10.0},
                        {"field": "total_payment_amount", "operator": "lt", "value": 100.0}
                    ]
                },
                "confidence": 0.85,
                "reasoning_template": "累计付费在10-100元之间",
                "priority": 90,
                "exclusive_group": "payment_level"
            },
            {
                "rule_id": "payment_low_default",
                "tag_name": "低付费",
                "category": "payment",
                "description": "低付费玩家",
                "condition": {
                    "logical_op": "and",
                    "conditions": [
                        {"field": "total_payment_amount", "operator": "gt", "value": 0.0},
                        {"field": "total_payment_amount", "operator": "lt", "value": 10.0}
                    ]
                },
                "confidence": 0.7,
                "reasoning_template": "累计付费低于10元",
                "priority": 80,
                "exclusive_group": "payment_level"
            },
            {
                "rule_id": "non_payment_default",
                "tag_name": "非付费",
                "category": "payment",
                "description": "非付费玩家",
                "condition": {
                    "field": "total_payment_amount",
                    "operator": "eq",
                    "value": 0.0
                },
                "confidence": 1.0,
                "reasoning_template": "无付费记录",
                "priority": 10,
                "exclusive_group": "payment_level"
            },
            {
                "rule_id": "social_active_default",
                "tag_name": "社交型",
                "category": "social",
                "description": "积极参与社交的玩家",
                "condition": {
                    "field": "social_interaction_count",
                    "operator": "gt",
                    "value": 20
                },
                "confidence": 0.85,
                "reasoning_template": "累计社交互动{social_interaction_count}次",
                "priority": 100,
                "exclusive_group": "social_level"
            },
            {
                "rule_id": "social_light_default",
                "tag_name": "轻度社交",
                "category": "social",
                "description": "轻度社交玩家",
                "condition": {
                    "logical_op": "and",
                    "conditions": [
                        {"field": "social_interaction_count", "operator": "gt", "value": 5},
                        {"field": "social_interaction_count", "operator": "lte", "value": 20}
                    ]
                },
                "confidence": 0.7,
                "reasoning_template": "累计社交互动{social_interaction_count}次",
                "priority": 90,
                "exclusive_group": "social_level"
            },
            {
                "rule_id": "social_lone_default",
                "tag_name": "独狼型",
                "category": "social",
                "description": "喜欢独自游戏的玩家",
                "condition": {
                    "field": "social_interaction_count",
                    "operator": "lte",
                    "value": 5
                },
                "confidence": 0.7,
                "reasoning_template": "社交互动较少，仅{social_interaction_count}次",
                "priority": 80,
                "exclusive_group": "social_level"
            },
            {
                "rule_id": "quest_master_default",
                "tag_name": "任务达人",
                "category": "gameplay",
                "description": "完成大量任务的玩家",
                "condition": {
                    "field": "quest_complete_count",
                    "operator": "gt",
                    "value": 50
                },
                "confidence": 0.8,
                "reasoning_template": "完成任务{quest_complete_count}次",
                "priority": 50
            }
        ]
        
        self._default_rules = TagRuleParser.parse_rules(default_rules_config)
    
    def load_rules(self, rules_list: List[Dict[str, Any]], version: str = "1.0.0"):
        logger.info(f"Loading {len(rules_list)} rules, version: {version}")
        
        self._rules = TagRuleParser.parse_rules(rules_list)
        self._config_version = version
        self._loaded_at = datetime.now()
        
        self._build_indices()
        
        logger.info(f"Loaded {len(self._rules)} rules across {len(self._rules_by_category)} categories")
    
    def load_rules_from_yaml(self, yaml_path: str):
        try:
            import yaml
            
            with open(yaml_path, 'r', encoding='utf-8') as f:
                config = yaml.safe_load(f)
            
            rules_list = config.get('rules', [])
            version = config.get('version', '1.0.0')
            
            self.load_rules(rules_list, version)
            
        except ImportError:
            logger.warning("PyYAML not installed, falling back to default rules")
            self._use_default_rules()
        except Exception as e:
            logger.error(f"Failed to load rules from {yaml_path}: {e}, using default rules")
            self._use_default_rules()
    
    def _use_default_rules(self):
        self._rules = self._default_rules.copy()
        self._config_version = "default"
        self._loaded_at = datetime.now()
        self._build_indices()
        logger.info(f"Using {len(self._rules)} default rules")
    
    def _build_indices(self):
        self._rules_by_category = {}
        self._exclusive_groups = {}
        
        for rule in self._rules:
            if rule.category not in self._rules_by_category:
                self._rules_by_category[rule.category] = []
            self._rules_by_category[rule.category].append(rule)
            
            if rule.exclusive_group:
                if rule.exclusive_group not in self._exclusive_groups:
                    self._exclusive_groups[rule.exclusive_group] = []
                self._exclusive_groups[rule.exclusive_group].append(rule)
        
        for group in self._exclusive_groups.values():
            group.sort(key=lambda r: r.priority, reverse=True)
        
        for category in self._rules_by_category.values():
            category.sort(key=lambda r: r.priority, reverse=True)
    
    def evaluate(
        self,
        context: Dict[str, Any],
        categories: Optional[List[str]] = None
    ) -> List[RuleEvaluationResult]:
        results = []
        matched_exclusive_groups = set()
        
        rules_to_evaluate = []
        if categories:
            for cat in categories:
                rules_to_evaluate.extend(self._rules_by_category.get(cat, []))
        else:
            rules_to_evaluate = self._rules
        
        for rule in rules_to_evaluate:
            if rule.exclusive_group and rule.exclusive_group in matched_exclusive_groups:
                continue
            
            try:
                matched = rule.matches(context)
                result = RuleEvaluationResult(
                    rule_id=rule.rule_id,
                    tag_name=rule.tag_name,
                    category=rule.category,
                    matched=matched,
                    confidence=rule.confidence,
                    reasoning=rule.get_reasoning(context) if matched else ""
                )
                results.append(result)
                
                if matched and rule.exclusive_group:
                    matched_exclusive_groups.add(rule.exclusive_group)
                    
            except Exception as e:
                logger.error(f"Error evaluating rule {rule.rule_id}: {e}")
                results.append(RuleEvaluationResult(
                    rule_id=rule.rule_id,
                    tag_name=rule.tag_name,
                    category=rule.category,
                    matched=False,
                    confidence=0.0,
                    reasoning=f"Evaluation error: {str(e)}"
                ))
        
        return results
    
    def get_matched_tags(
        self,
        context: Dict[str, Any],
        categories: Optional[List[str]] = None,
        include_reasoning: bool = False
    ) -> Union[List[str], List[Tuple[str, str, float, str]]]:
        results = self.evaluate(context, categories)
        matched = [r for r in results if r.matched]
        
        if include_reasoning:
            return [(r.tag_name, r.category, r.confidence, r.reasoning) for r in matched]
        
        return [r.tag_name for r in matched]
    
    def add_rule(self, rule: TagRule):
        self._rules.append(rule)
        self._build_indices()
        logger.info(f"Added rule: {rule.rule_id}")
    
    def remove_rule(self, rule_id: str):
        self._rules = [r for r in self._rules if r.rule_id != rule_id]
        self._build_indices()
        logger.info(f"Removed rule: {rule_id}")
    
    def get_all_rules(self) -> List[TagRule]:
        return self._rules.copy()
    
    def get_rules_by_category(self, category: str) -> List[TagRule]:
        return self._rules_by_category.get(category, []).copy()
    
    def get_config_info(self) -> Dict[str, Any]:
        return {
            "version": self._config_version,
            "loaded_at": self._loaded_at.isoformat() if self._loaded_at else None,
            "total_rules": len(self._rules),
            "categories": list(self._rules_by_category.keys()),
            "exclusive_groups": list(self._exclusive_groups.keys())
        }
    
    def add_rule_from_expression(
        self,
        rule_id: str,
        tag_name: str,
        category: str,
        expression: str,
        description: str = "",
        confidence: float = 0.8,
        field: str = None
    ) -> TagRule:
        condition = TagRuleParser.parse_expression(expression, field)
        
        rule = TagRule(
            rule_id=rule_id,
            tag_name=tag_name,
            category=category,
            description=description,
            condition=condition,
            confidence=confidence
        )
        
        self.add_rule(rule)
        return rule
    
    def get_extension_info(self) -> Dict[str, Any]:
        return {
            "extensions": list(RuleParserManager.get_all_extensions().keys()),
            "operators": RuleParserManager.get_all_operators()
        }
