import logging
import re
import operator
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple, Union, Callable
from enum import Enum

logger = logging.getLogger(__name__)


class ConditionType(Enum):
    COMPARISON = "comparison"
    LOGICAL = "logical"
    MEMBERSHIP = "membership"
    RANGE = "range"
    REGEX = "regex"
    DATE_COMPARISON = "date_comparison"


class LogicalOperator(Enum):
    AND = "and"
    OR = "or"
    NOT = "not"
    NAND = "nand"
    NOR = "nor"


class ComparisonOperator(Enum):
    EQ = "eq"
    NE = "ne"
    GT = "gt"
    GTE = "gte"
    LT = "lt"
    LTE = "lte"
    CONTAINS = "contains"
    NOT_CONTAINS = "not_contains"
    STARTS_WITH = "starts_with"
    ENDS_WITH = "ends_with"
    MATCHES = "matches"
    NOT_MATCHES = "not_matches"
    IN = "in"
    NOT_IN = "not_in"
    BETWEEN = "between"
    NOT_BETWEEN = "not_between"


class RuleCondition:
    def __init__(
        self,
        condition_type: ConditionType,
        field: Optional[str] = None,
        operator: Optional[str] = None,
        value: Any = None,
        conditions: List['RuleCondition'] = None,
        logical_op: Optional[LogicalOperator] = None,
        regex_flags: int = 0,
        date_format: str = None
    ):
        self.condition_type = condition_type
        self.field = field
        self.operator = operator
        self.value = value
        self.conditions = conditions or []
        self.logical_op = logical_op
        self.regex_flags = regex_flags
        self.date_format = date_format
    
    def evaluate(self, context: Dict[str, Any]) -> bool:
        if self.condition_type == ConditionType.COMPARISON:
            return self._evaluate_comparison(context)
        elif self.condition_type == ConditionType.LOGICAL:
            return self._evaluate_logical(context)
        elif self.condition_type == ConditionType.MEMBERSHIP:
            return self._evaluate_membership(context)
        elif self.condition_type == ConditionType.RANGE:
            return self._evaluate_range(context)
        elif self.condition_type == ConditionType.REGEX:
            return self._evaluate_regex(context)
        elif self.condition_type == ConditionType.DATE_COMPARISON:
            return self._evaluate_date_comparison(context)
        return False
    
    def _evaluate_comparison(self, context: Dict[str, Any]) -> bool:
        if not self.field or self.operator is None:
            return False
        
        field_value = context.get(self.field)
        if field_value is None:
            return False
        
        comparison_funcs = {
            'eq': operator.eq,
            'ne': operator.ne,
            'gt': operator.gt,
            'gte': operator.ge,
            'lt': operator.lt,
            'lte': operator.le,
        }
        
        if self.operator in comparison_funcs:
            try:
                if isinstance(field_value, str) and isinstance(self.value, (int, float)):
                    field_value = float(field_value)
                elif isinstance(self.value, str) and isinstance(field_value, (int, float)):
                    compare_value = float(self.value)
                    return comparison_funcs[self.operator](field_value, compare_value)
                return comparison_funcs[self.operator](field_value, self.value)
            except (ValueError, TypeError):
                return False
        
        if self.operator == 'contains':
            return self._check_contains(field_value, self.value, inverted=False)
        
        if self.operator == 'not_contains':
            return self._check_contains(field_value, self.value, inverted=True)
        
        if self.operator == 'starts_with':
            return self._check_starts_with(field_value, self.value)
        
        if self.operator == 'ends_with':
            return self._check_ends_with(field_value, self.value)
        
        if self.operator == 'matches':
            return self._check_matches(field_value, self.value, inverted=False)
        
        if self.operator == 'not_matches':
            return self._check_matches(field_value, self.value, inverted=True)
        
        return False
    
    def _check_contains(self, field_value: Any, search_value: Any, inverted: bool) -> bool:
        if isinstance(field_value, str) and isinstance(search_value, str):
            result = search_value.lower() in field_value.lower()
            return not result if inverted else result
        elif isinstance(field_value, (list, set, tuple)):
            result = search_value in field_value
            return not result if inverted else result
        return inverted
    
    def _check_starts_with(self, field_value: Any, prefix: Any) -> bool:
        if isinstance(field_value, str) and isinstance(prefix, str):
            return field_value.lower().startswith(prefix.lower())
        return False
    
    def _check_ends_with(self, field_value: Any, suffix: Any) -> bool:
        if isinstance(field_value, str) and isinstance(suffix, str):
            return field_value.lower().endswith(suffix.lower())
        return False
    
    def _check_matches(self, field_value: Any, pattern: Any, inverted: bool) -> bool:
        if not isinstance(field_value, str) or not isinstance(pattern, str):
            return inverted
        
        try:
            regex = re.compile(pattern, self.regex_flags)
            result = bool(regex.search(field_value))
            return not result if inverted else result
        except re.error as e:
            logger.warning(f"Invalid regex pattern '{pattern}': {e}")
            return inverted
    
    def _evaluate_logical(self, context: Dict[str, Any]) -> bool:
        if not self.conditions or self.logical_op is None:
            return False
        
        results = [c.evaluate(context) for c in self.conditions]
        
        if self.logical_op == LogicalOperator.AND:
            return all(results)
        elif self.logical_op == LogicalOperator.OR:
            return any(results)
        elif self.logical_op == LogicalOperator.NOT:
            return not all(results) if results else False
        elif self.logical_op == LogicalOperator.NAND:
            return not all(results)
        elif self.logical_op == LogicalOperator.NOR:
            return not any(results)
        
        return False
    
    def _evaluate_membership(self, context: Dict[str, Any]) -> bool:
        if not self.field:
            return False
        
        field_value = context.get(self.field)
        if field_value is None:
            return False
        
        if isinstance(self.value, (list, set, tuple)):
            if self.operator == 'not_in':
                return field_value not in self.value
            return field_value in self.value
        return False
    
    def _evaluate_range(self, context: Dict[str, Any]) -> bool:
        if not self.field:
            return False
        
        field_value = context.get(self.field)
        if field_value is None:
            return False
        
        if isinstance(self.value, dict):
            min_val = self.value.get('min')
            max_val = self.value.get('max')
            inclusive_min = self.value.get('inclusive_min', True)
            inclusive_max = self.value.get('inclusive_max', False)
            
            try:
                field_num = float(field_value)
                result = True
                
                if min_val is not None:
                    min_num = float(min_val)
                    if inclusive_min:
                        result = result and (field_num >= min_num)
                    else:
                        result = result and (field_num > min_num)
                
                if max_val is not None:
                    max_num = float(max_val)
                    if inclusive_max:
                        result = result and (field_num <= max_num)
                    else:
                        result = result and (field_num < max_num)
                
                if self.operator == 'not_between':
                    return not result
                return result
                
            except (ValueError, TypeError):
                return False
        
        return False
    
    def _evaluate_date_comparison(self, context: Dict[str, Any]) -> bool:
        if not self.field or self.operator is None:
            return False
        
        field_value = context.get(self.field)
        if field_value is None:
            return False
        
        try:
            field_date = self._parse_date(field_value)
            compare_date = self._parse_date(self.value)
            
            if field_date is None or compare_date is None:
                return False
            
            comparison_funcs = {
                'gt': operator.gt,
                'gte': operator.ge,
                'lt': operator.lt,
                'lte': operator.le,
                'eq': operator.eq,
                'ne': operator.ne,
            }
            
            if self.operator in comparison_funcs:
                return comparison_funcs[self.operator](field_date, compare_date)
            
        except (ValueError, TypeError, AttributeError):
            pass
        
        return False
    
    def _parse_date(self, value: Any) -> Optional[datetime]:
        if value is None:
            return None
        
        if isinstance(value, datetime):
            return value
        
        if isinstance(value, str):
            if self.date_format:
                try:
                    return datetime.strptime(value, self.date_format)
                except ValueError:
                    pass
            
            common_formats = [
                "%Y-%m-%dT%H:%M:%S",
                "%Y-%m-%dT%H:%M:%S.%f",
                "%Y-%m-%d %H:%M:%S",
                "%Y-%m-%d",
                "%d/%m/%Y",
                "%m/%d/%Y",
            ]
            
            for fmt in common_formats:
                try:
                    return datetime.strptime(value, fmt)
                except ValueError:
                    continue
            
            try:
                return datetime.fromisoformat(value.replace('Z', '+00:00'))
            except ValueError:
                pass
        
        return None
    
    def to_dict(self) -> Dict[str, Any]:
        result = {
            "condition_type": self.condition_type.value,
        }
        
        if self.field is not None:
            result["field"] = self.field
        if self.operator is not None:
            result["operator"] = self.operator
        if self.value is not None:
            result["value"] = self.value
        if self.conditions:
            result["conditions"] = [c.to_dict() for c in self.conditions]
        if self.logical_op is not None:
            result["logical_op"] = self.logical_op.value
        if self.regex_flags != 0:
            result["regex_flags"] = self.regex_flags
        if self.date_format is not None:
            result["date_format"] = self.date_format
        
        return result


@dataclass
class TagRule:
    rule_id: str
    tag_name: str
    category: str
    description: str
    condition: RuleCondition
    confidence: float = 0.8
    reasoning_template: str = ""
    priority: int = 0
    enabled: bool = True
    exclusive_group: Optional[str] = None
    
    def matches(self, context: Dict[str, Any]) -> bool:
        if not self.enabled:
            return False
        return self.condition.evaluate(context)
    
    def get_reasoning(self, context: Dict[str, Any]) -> str:
        if not self.reasoning_template:
            return self.description
        
        template = self.reasoning_template
        
        placeholders = re.findall(r'\{(\w+(\.\w+)*)\}', template)
        for placeholder in placeholders:
            placeholder_key = placeholder[0] if isinstance(placeholder, tuple) else placeholder
            value = self._get_nested_value(context, placeholder_key)
            
            if isinstance(value, float):
                template = template.replace(f'{{{placeholder_key}}}', f'{value:.2f}')
            elif isinstance(value, int):
                template = template.replace(f'{{{placeholder_key}}}', f'{value}')
            else:
                template = template.replace(f'{{{placeholder_key}}}', str(value) if value else '')
        
        return template
    
    def _get_nested_value(self, context: Dict[str, Any], path: str) -> Any:
        keys = path.split('.')
        value = context
        for key in keys:
            if isinstance(value, dict):
                value = value.get(key)
            elif hasattr(value, key):
                value = getattr(value, key)
            else:
                return None
            if value is None:
                return None
        return value


@dataclass
class RuleEvaluationResult:
    rule_id: str
    tag_name: str
    category: str
    matched: bool
    confidence: float
    reasoning: str


class RuleConditionParser:
    @staticmethod
    def parse_condition(condition_dict: Dict[str, Any]) -> RuleCondition:
        if 'logical_op' in condition_dict:
            return RuleConditionParser._parse_logical_condition(condition_dict)
        elif 'field' in condition_dict:
            return RuleConditionParser._parse_simple_condition(condition_dict)
        elif 'conditions' in condition_dict:
            return RuleConditionParser._parse_implicit_and(condition_dict)
        else:
            raise ValueError(f"Invalid condition format: {condition_dict}")
    
    @staticmethod
    def _parse_implicit_and(condition_dict: Dict[str, Any]) -> RuleCondition:
        conditions = condition_dict.get('conditions', [])
        if not conditions:
            raise ValueError("Implicit AND condition requires 'conditions' list")
        
        parsed_conditions = [
            RuleConditionParser.parse_condition(c) for c in conditions
        ]
        
        return RuleCondition(
            condition_type=ConditionType.LOGICAL,
            logical_op=LogicalOperator.AND,
            conditions=parsed_conditions
        )
    
    @staticmethod
    def _parse_simple_condition(condition_dict: Dict[str, Any]) -> RuleCondition:
        field = condition_dict.get('field')
        op = condition_dict.get('operator', 'eq')
        value = condition_dict.get('value')
        regex_flags = condition_dict.get('regex_flags', 0)
        date_format = condition_dict.get('date_format')
        
        if op in ['in', 'not_in']:
            condition_type = ConditionType.MEMBERSHIP
            return RuleCondition(
                condition_type=condition_type,
                field=field,
                operator=op,
                value=value
            )
        
        if op in ['range', 'between', 'not_between']:
            return RuleCondition(
                condition_type=ConditionType.RANGE,
                field=field,
                operator=op,
                value=value
            )
        
        if op in ['matches', 'not_matches']:
            return RuleCondition(
                condition_type=ConditionType.REGEX,
                field=field,
                operator=op,
                value=value,
                regex_flags=regex_flags
            )
        
        if op in ['date_gt', 'date_gte', 'date_lt', 'date_lte', 'date_eq', 'date_ne']:
            date_op = op.replace('date_', '')
            return RuleCondition(
                condition_type=ConditionType.DATE_COMPARISON,
                field=field,
                operator=date_op,
                value=value,
                date_format=date_format
            )
        
        return RuleCondition(
            condition_type=ConditionType.COMPARISON,
            field=field,
            operator=op,
            value=value
        )
    
    @staticmethod
    def _parse_logical_condition(condition_dict: Dict[str, Any]) -> RuleCondition:
        logical_op_str = condition_dict.get('logical_op', 'and').lower()
        conditions = condition_dict.get('conditions', [])
        
        logical_op_map = {
            'and': LogicalOperator.AND,
            'or': LogicalOperator.OR,
            'not': LogicalOperator.NOT,
            'nand': LogicalOperator.NAND,
            'nor': LogicalOperator.NOR,
        }
        
        logical_op = logical_op_map.get(logical_op_str, LogicalOperator.AND)
        
        parsed_conditions = [
            RuleConditionParser.parse_condition(c) for c in conditions
        ]
        
        return RuleCondition(
            condition_type=ConditionType.LOGICAL,
            logical_op=logical_op,
            conditions=parsed_conditions
        )
    
    @staticmethod
    def parse_conditions(conditions_list: List[Dict[str, Any]]) -> List[RuleCondition]:
        return [RuleConditionParser.parse_condition(c) for c in conditions_list]


class TagRuleParser:
    @staticmethod
    def parse_rule(rule_dict: Dict[str, Any]) -> TagRule:
        rule_id = rule_dict.get('rule_id', '')
        tag_name = rule_dict.get('tag_name', '')
        category = rule_dict.get('category', 'general')
        description = rule_dict.get('description', '')
        confidence = rule_dict.get('confidence', 0.8)
        reasoning_template = rule_dict.get('reasoning_template', '')
        priority = rule_dict.get('priority', 0)
        enabled = rule_dict.get('enabled', True)
        exclusive_group = rule_dict.get('exclusive_group')
        
        condition_dict = rule_dict.get('condition', {})
        condition = RuleConditionParser.parse_condition(condition_dict)
        
        return TagRule(
            rule_id=rule_id,
            tag_name=tag_name,
            category=category,
            description=description,
            condition=condition,
            confidence=confidence,
            reasoning_template=reasoning_template,
            priority=priority,
            enabled=enabled,
            exclusive_group=exclusive_group
        )
    
    @staticmethod
    def parse_rules(rules_list: List[Dict[str, Any]]) -> List[TagRule]:
        return [TagRuleParser.parse_rule(r) for r in rules_list]
    
    @staticmethod
    def parse_expression(expression: str, field: str = None) -> RuleCondition:
        expression = expression.strip()
        
        if re.match(r'^[\w\s]+$', expression):
            return RuleConditionParser._parse_comparison_expression(expression, field)
        
        if ' AND ' in expression.upper() or ' OR ' in expression.upper() or ' NOT ' in expression.upper():
            return RuleConditionParser._parse_logical_expression(expression, field)
        
        return RuleConditionParser._parse_comparison_expression(expression, field)
    
    @staticmethod
    def _parse_comparison_expression(expression: str, default_field: str = None) -> RuleCondition:
        comparison_patterns = [
            (r'^(\w+)\s*(>=|>=|gte)\s*(.+)$', 'gte'),
            (r'^(\w+)\s*(<=|<=|lte)\s*(.+)$', 'lte'),
            (r'^(\w+)\s*(>|gt)\s*(.+)$', 'gt'),
            (r'^(\w+)\s*(<|lt)\s*(.+)$', 'lt'),
            (r'^(\w+)\s*(==|=|eq)\s*(.+)$', 'eq'),
            (r'^(\w+)\s*(!=|<>|ne)\s*(.+)$', 'ne'),
            (r'^(\w+)\s+(contains|includes)\s+(.+)$', 'contains'),
            (r'^(\w+)\s+(starts_with|begins_with)\s+(.+)$', 'starts_with'),
            (r'^(\w+)\s+(ends_with)\s+(.+)$', 'ends_with'),
            (r'^(\w+)\s+(matches|regex)\s+(.+)$', 'matches'),
            (r'^(\w+)\s+(in)\s+\[(.+)\]$', 'in'),
        ]
        
        for pattern, op in comparison_patterns:
            match = re.match(pattern, expression, re.IGNORECASE)
            if match:
                field = match.group(1)
                value_str = match.group(3).strip()
                
                value = RuleConditionParser._parse_value(value_str)
                
                if op == 'in':
                    items = [v.strip() for v in value_str.split(',')]
                    parsed_items = []
                    for item in items:
                        parsed_items.append(RuleConditionParser._parse_value(item))
                    return RuleCondition(
                        condition_type=ConditionType.MEMBERSHIP,
                        field=field,
                        operator='in',
                        value=parsed_items
                    )
                
                if op in ['matches', 'regex']:
                    return RuleCondition(
                        condition_type=ConditionType.REGEX,
                        field=field,
                        operator='matches',
                        value=value
                    )
                
                return RuleCondition(
                    condition_type=ConditionType.COMPARISON,
                    field=field,
                    operator=op,
                    value=value
                )
        
        if default_field:
            value = RuleConditionParser._parse_value(expression)
            return RuleCondition(
                condition_type=ConditionType.COMPARISON,
                field=default_field,
                operator='eq',
                value=value
            )
        
        raise ValueError(f"Cannot parse expression: {expression}")
    
    @staticmethod
    def _parse_logical_expression(expression: str, default_field: str = None) -> RuleCondition:
        tokens = re.split(r'(\s+AND\s+|\s+OR\s+|\s+NOT\s+)', expression, flags=re.IGNORECASE)
        
        conditions = []
        current_logical_op = None
        current_conditions = []
        
        i = 0
        while i < len(tokens):
            token = tokens[i].strip()
            
            if not token:
                i += 1
                continue
            
            if token.upper() in ['AND', 'OR', 'NOT']:
                current_logical_op = token.upper()
            else:
                condition = RuleConditionParser._parse_comparison_expression(token, default_field)
                
                if current_logical_op == 'NOT':
                    condition = RuleCondition(
                        condition_type=ConditionType.LOGICAL,
                        logical_op=LogicalOperator.NOT,
                        conditions=[condition]
                    )
                    current_logical_op = None
                
                current_conditions.append(condition)
            
            i += 1
        
        if not current_conditions:
            raise ValueError(f"No valid conditions in expression: {expression}")
        
        if len(current_conditions) == 1:
            return current_conditions[0]
        
        logical_op = LogicalOperator.AND
        if ' OR ' in expression.upper() and ' AND ' not in expression.upper():
            logical_op = LogicalOperator.OR
        
        return RuleCondition(
            condition_type=ConditionType.LOGICAL,
            logical_op=logical_op,
            conditions=current_conditions
        )
    
    @staticmethod
    def _parse_value(value_str: str) -> Any:
        value_str = value_str.strip()
        
        if value_str.lower() == 'true':
            return True
        if value_str.lower() == 'false':
            return False
        if value_str.lower() == 'null' or value_str.lower() == 'none':
            return None
        
        if value_str.startswith("'") and value_str.endswith("'"):
            return value_str[1:-1]
        if value_str.startswith('"') and value_str.endswith('"'):
            return value_str[1:-1]
        
        try:
            if '.' in value_str:
                return float(value_str)
            return int(value_str)
        except ValueError:
            return value_str


class RuleEngineExtension(ABC):
    @abstractmethod
    def get_name(self) -> str:
        pass
    
    @abstractmethod
    def get_operators(self) -> List[str]:
        pass
    
    @abstractmethod
    def evaluate(
        self, 
        operator: str, 
        field_value: Any, 
        compare_value: Any, 
        context: Dict[str, Any]
    ) -> bool:
        pass


class AggregationExtension(RuleEngineExtension):
    def get_name(self) -> str:
        return "aggregation"
    
    def get_operators(self) -> List[str]:
        return ['count_gt', 'count_lt', 'count_gte', 'count_lte', 'sum_gt', 'avg_gt']
    
    def evaluate(
        self, 
        operator: str, 
        field_value: Any, 
        compare_value: Any, 
        context: Dict[str, Any]
    ) -> bool:
        if operator == 'count_gt':
            if isinstance(field_value, (list, set, tuple)):
                return len(field_value) > float(compare_value)
            return False
        
        if operator == 'count_lt':
            if isinstance(field_value, (list, set, tuple)):
                return len(field_value) < float(compare_value)
            return False
        
        if operator == 'count_gte':
            if isinstance(field_value, (list, set, tuple)):
                return len(field_value) >= float(compare_value)
            return False
        
        if operator == 'count_lte':
            if isinstance(field_value, (list, set, tuple)):
                return len(field_value) <= float(compare_value)
            return False
        
        if operator == 'sum_gt':
            if isinstance(field_value, (list, tuple)):
                try:
                    total = sum(float(v) for v in field_value if v is not None)
                    return total > float(compare_value)
                except (ValueError, TypeError):
                    return False
            return False
        
        if operator == 'avg_gt':
            if isinstance(field_value, (list, tuple)) and len(field_value) > 0:
                try:
                    total = sum(float(v) for v in field_value if v is not None)
                    avg = total / len([v for v in field_value if v is not None])
                    return avg > float(compare_value)
                except (ValueError, TypeError):
                    return False
            return False
        
        return False


class RuleParserManager:
    _extensions: Dict[str, RuleEngineExtension] = {}
    
    @classmethod
    def register_extension(cls, extension: RuleEngineExtension):
        cls._extensions[extension.get_name()] = extension
        logger.info(f"Registered rule engine extension: {extension.get_name()}")
    
    @classmethod
    def get_extension(cls, name: str) -> Optional[RuleEngineExtension]:
        return cls._extensions.get(name)
    
    @classmethod
    def get_all_extensions(cls) -> Dict[str, RuleEngineExtension]:
        return cls._extensions.copy()
    
    @classmethod
    def get_all_operators(cls) -> Dict[str, List[str]]:
        operators = {}
        for name, ext in cls._extensions.items():
            operators[name] = ext.get_operators()
        return operators


RuleParserManager.register_extension(AggregationExtension())
