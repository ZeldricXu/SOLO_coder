import json
import logging
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Dict, Any
from uuid import uuid4

from app.models.alert import AlertRule, OperatorType, AlertSeverity
from app import config

logger = logging.getLogger(__name__)


class AlertRuleManager:
    def __init__(self, rules_file: Optional[Path] = None, initial_rules: Optional[List[dict]] = None):
        self.rules: Dict[str, AlertRule] = {}
        self.rules_file = rules_file
        
        if initial_rules is None and 'alert_rules' in config:
            initial_rules = config['alert_rules']
        
        if initial_rules:
            for rule_data in initial_rules:
                try:
                    rule = AlertRule.from_dict(rule_data)
                    self.rules[rule.rule_id] = rule
                except Exception as e:
                    logger.error(f"Failed to load rule: {e}")
        
        if self.rules_file and self.rules_file.exists():
            self._load_from_file()
        
        logger.info(f"AlertRuleManager initialized with {len(self.rules)} rules")
    
    def _load_from_file(self):
        try:
            with open(self.rules_file, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            for rule_data in data.get('rules', []):
                try:
                    rule = AlertRule.from_dict(rule_data)
                    self.rules[rule.rule_id] = rule
                except Exception as e:
                    logger.error(f"Failed to load rule from file: {e}")
            
            logger.info(f"Loaded {len(self.rules)} rules from {self.rules_file}")
        except Exception as e:
            logger.error(f"Failed to load rules from file: {e}")
    
    def _save_to_file(self):
        if not self.rules_file:
            return
        
        try:
            self.rules_file.parent.mkdir(parents=True, exist_ok=True)
            data = {
                'rules': [rule.to_dict() for rule in self.rules.values()],
                'updated_at': datetime.utcnow().isoformat()
            }
            with open(self.rules_file, 'w', encoding='utf-8') as f:
                json.dump(data, f, indent=2, default=str)
            logger.debug(f"Saved {len(self.rules)} rules to {self.rules_file}")
        except Exception as e:
            logger.error(f"Failed to save rules to file: {e}")
    
    def create_rule(
        self,
        metric_type: str,
        threshold: float,
        operator: str,
        duration: int,
        severity: str,
        notify_channels: List[str],
        silence_period: int = 300,
        enabled: bool = True,
        description: str = "",
        server_filter: Optional[List[str]] = None,
        rule_id: Optional[str] = None
    ) -> AlertRule:
        if rule_id is None:
            rule_id = f"rule_{uuid4().hex[:8]}"
        
        if rule_id in self.rules:
            raise ValueError(f"Rule with id {rule_id} already exists")
        
        try:
            op_type = OperatorType(operator) if isinstance(operator, str) else operator
            sev_type = AlertSeverity(severity) if isinstance(severity, str) else severity
        except ValueError as e:
            raise ValueError(f"Invalid operator or severity: {e}")
        
        rule = AlertRule(
            rule_id=rule_id,
            metric_type=metric_type,
            threshold=threshold,
            operator=op_type,
            duration=duration,
            severity=sev_type,
            notify_channels=notify_channels,
            silence_period=silence_period,
            enabled=enabled,
            description=description,
            server_filter=server_filter
        )
        
        self.rules[rule_id] = rule
        self._save_to_file()
        logger.info(f"Created rule: {rule_id} for {metric_type}")
        
        return rule
    
    def update_rule(
        self,
        rule_id: str,
        **updates
    ) -> AlertRule:
        if rule_id not in self.rules:
            raise ValueError(f"Rule with id {rule_id} not found")
        
        rule = self.rules[rule_id]
        
        allowed_fields = [
            'metric_type', 'threshold', 'operator', 'duration', 'severity',
            'notify_channels', 'silence_period', 'enabled', 'description', 'server_filter'
        ]
        
        for key, value in updates.items():
            if key not in allowed_fields:
                raise ValueError(f"Cannot update field: {key}")
            
            if key == 'operator':
                value = OperatorType(value) if isinstance(value, str) else value
            elif key == 'severity':
                value = AlertSeverity(value) if isinstance(value, str) else value
            
            setattr(rule, key, value)
        
        rule.updated_at = datetime.utcnow()
        self._save_to_file()
        logger.info(f"Updated rule: {rule_id}")
        
        return rule
    
    def delete_rule(self, rule_id: str) -> bool:
        if rule_id not in self.rules:
            return False
        
        del self.rules[rule_id]
        self._save_to_file()
        logger.info(f"Deleted rule: {rule_id}")
        return True
    
    def get_rule(self, rule_id: str) -> Optional[AlertRule]:
        return self.rules.get(rule_id)
    
    def list_rules(
        self,
        metric_type: Optional[str] = None,
        enabled_only: bool = False,
        server_id: Optional[str] = None
    ) -> List[AlertRule]:
        rules = list(self.rules.values())
        
        if metric_type:
            rules = [r for r in rules if r.metric_type == metric_type]
        
        if enabled_only:
            rules = [r for r in rules if r.enabled]
        
        if server_id:
            rules = [r for r in rules if r.matches_server(server_id)]
        
        return sorted(rules, key=lambda r: r.created_at, reverse=True)
    
    def get_rules_for_metric(
        self,
        metric_type: str,
        server_id: Optional[str] = None
    ) -> List[AlertRule]:
        return self.list_rules(
            metric_type=metric_type,
            enabled_only=True,
            server_id=server_id
        )
    
    def enable_rule(self, rule_id: str) -> bool:
        if rule_id not in self.rules:
            return False
        
        self.rules[rule_id].enabled = True
        self.rules[rule_id].updated_at = datetime.utcnow()
        self._save_to_file()
        logger.info(f"Enabled rule: {rule_id}")
        return True
    
    def disable_rule(self, rule_id: str) -> bool:
        if rule_id not in self.rules:
            return False
        
        self.rules[rule_id].enabled = False
        self.rules[rule_id].updated_at = datetime.utcnow()
        self._save_to_file()
        logger.info(f"Disabled rule: {rule_id}")
        return True
