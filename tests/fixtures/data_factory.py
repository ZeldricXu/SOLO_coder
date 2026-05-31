from typing import Any, Dict, List, Optional


class TicketAssignmentDataFactory:
    @staticmethod
    def create_ticket_data(
        title: str = "测试工单",
        description: Optional[str] = "工单描述",
        priority: str = "medium",
        channel: str = "web",
        required_skills: Optional[Dict[str, float]] = None,
        requester_id: str = "usr_001",
        tenant_id: str = "tnt_001",
        custom_fields: Optional[Dict[str, Any]] = None,
        assignment_strategy: str = "hybrid",
    ) -> Dict[str, Any]:
        return {
            "title": title,
            "description": description,
            "priority": priority,
            "channel": channel,
            "required_skills": required_skills or {"python": 0.8},
            "requester_id": requester_id,
            "tenant_id": tenant_id,
            "custom_fields": custom_fields or {},
            "assignment_strategy": assignment_strategy,
        }

    @staticmethod
    def create_agent_data(
        name: str = "测试客服",
        email: str = "agent@example.com",
        department: str = "技术支持",
        skills: Optional[Dict[str, float]] = None,
        max_concurrent_tickets: int = 10,
        tenant_id: str = "tnt_001",
        status: str = "available",
    ) -> Dict[str, Any]:
        return {
            "name": name,
            "email": email,
            "department": department,
            "skills": skills or {"python": 0.9, "java": 0.7},
            "max_concurrent_tickets": max_concurrent_tickets,
            "tenant_id": tenant_id,
            "status": status,
        }

    @staticmethod
    def create_invalid_ticket_data(scenario: str) -> Dict[str, Any]:
        scenarios = {
            "empty_title": {
                "title": "",
                "requester_id": "usr_001",
                "tenant_id": "tnt_001",
            },
            "whitespace_title": {
                "title": "   ",
                "requester_id": "usr_001",
                "tenant_id": "tnt_001",
            },
            "missing_title": {
                "requester_id": "usr_001",
                "tenant_id": "tnt_001",
            },
            "null_title": {
                "title": None,
                "requester_id": "usr_001",
                "tenant_id": "tnt_001",
            },
            "empty_requester": {
                "title": "有效标题",
                "requester_id": "",
                "tenant_id": "tnt_001",
            },
            "missing_requester": {
                "title": "有效标题",
                "tenant_id": "tnt_001",
            },
            "null_requester": {
                "title": "有效标题",
                "requester_id": None,
                "tenant_id": "tnt_001",
            },
            "invalid_priority": {
                "title": "有效标题",
                "requester_id": "usr_001",
                "priority": "invalid_priority",
                "tenant_id": "tnt_001",
            },
            "invalid_channel": {
                "title": "有效标题",
                "requester_id": "usr_001",
                "channel": "invalid_channel",
                "tenant_id": "tnt_001",
            },
        }
        return scenarios.get(scenario, {})

    @staticmethod
    def create_invalid_agent_data(scenario: str) -> Dict[str, Any]:
        scenarios = {
            "empty_name": {
                "name": "",
                "email": "agent@example.com",
            },
            "whitespace_name": {
                "name": "   ",
                "email": "agent@example.com",
            },
            "missing_name": {
                "email": "agent@example.com",
            },
            "null_name": {
                "name": None,
                "email": "agent@example.com",
            },
            "empty_email": {
                "name": "测试客服",
                "email": "",
            },
            "missing_email": {
                "name": "测试客服",
            },
            "null_email": {
                "name": "测试客服",
                "email": None,
            },
            "invalid_email_no_at": {
                "name": "测试客服",
                "email": "invalid-email",
            },
            "invalid_email_empty_domain": {
                "name": "测试客服",
                "email": "test@",
            },
        }
        return scenarios.get(scenario, {})

    @staticmethod
    def create_skill_matching_scenarios() -> List[Dict[str, Any]]:
        return [
            {
                "name": "完美匹配",
                "required": {"python": 0.8},
                "agent": {"python": 0.9, "java": 0.7},
                "expected_score": 1.0,
            },
            {
                "name": "部分匹配",
                "required": {"python": 0.8, "java": 0.6},
                "agent": {"python": 0.7, "java": 0.5},
                "expected_score": 0.857,
            },
            {
                "name": "无匹配技能",
                "required": {"golang": 0.8},
                "agent": {"python": 0.9},
                "expected_score": 0.0,
            },
            {
                "name": "无要求技能",
                "required": {},
                "agent": {"python": 0.9},
                "expected_score": 1.0,
            },
            {
                "name": "多技能加权",
                "required": {"python": 0.8, "sql": 0.6, "docker": 0.7},
                "agent": {"python": 0.9, "sql": 0.5, "docker": 0.8},
                "expected_score": 0.952,
            },
        ]


class MultiTenantDataFactory:
    @staticmethod
    def create_tenant_data(
        name: str = "测试租户",
        display_name: Optional[str] = None,
        contact_email: str = "tenant@example.com",
        tier: str = "basic",
    ) -> Dict[str, Any]:
        return {
            "name": name,
            "display_name": display_name,
            "contact_email": contact_email,
            "tier": tier,
        }

    @staticmethod
    def create_invalid_tenant_data(scenario: str) -> Dict[str, Any]:
        scenarios = {
            "empty_name": {
                "name": "",
                "contact_email": "tenant@example.com",
            },
            "whitespace_name": {
                "name": "   ",
                "contact_email": "tenant@example.com",
            },
            "missing_name": {
                "contact_email": "tenant@example.com",
            },
            "null_name": {
                "name": None,
                "contact_email": "tenant@example.com",
            },
            "empty_email": {
                "name": "测试租户",
                "contact_email": "",
            },
            "missing_email": {
                "name": "测试租户",
            },
            "null_email": {
                "name": "测试租户",
                "contact_email": None,
            },
            "invalid_email": {
                "name": "测试租户",
                "contact_email": "invalid-email",
            },
            "invalid_tier": {
                "name": "测试租户",
                "contact_email": "tenant@example.com",
                "tier": "invalid_tier",
            },
        }
        return scenarios.get(scenario, {})

    @staticmethod
    def create_quota_data(
        tenant_id: str = "tnt_001",
        resource_type: str = "api_calls",
        limit: float = 1000.0,
        warning_threshold: float = 80.0,
        unit: str = "calls",
        reset_period: str = "monthly",
        is_hard_limit: bool = True,
    ) -> Dict[str, Any]:
        return {
            "tenant_id": tenant_id,
            "resource_type": resource_type,
            "limit": limit,
            "used": 0.0,
            "warning_threshold": warning_threshold,
            "unit": unit,
            "reset_period": reset_period,
            "is_hard_limit": is_hard_limit,
        }

    @staticmethod
    def create_config_data(
        tenant_id: str = "tnt_001",
        namespace: str = "features",
        key: str = "enabled_modules",
        value: Optional[Dict[str, Any]] = None,
        value_type: str = "json",
        is_encrypted: bool = False,
        is_overridable: bool = True,
    ) -> Dict[str, Any]:
        return {
            "tenant_id": tenant_id,
            "namespace": namespace,
            "key": key,
            "value": value or {"tickets": True, "billing": True},
            "value_type": value_type,
            "is_encrypted": is_encrypted,
            "is_overridable": is_overridable,
        }

    @staticmethod
    def create_member_data(
        tenant_id: str = "tnt_001",
        user_id: str = "usr_001",
        role: str = "member",
        permissions: Optional[List[str]] = None,
    ) -> Dict[str, Any]:
        return {
            "tenant_id": tenant_id,
            "user_id": user_id,
            "role": role,
            "permissions": permissions or ["tickets:read", "tickets:write"],
        }


class ApprovalEngineDataFactory:
    @staticmethod
    def create_condition_data(
        field: str = "amount",
        operator: str = "greater_than",
        value: Any = 1000,
    ) -> Dict[str, Any]:
        return {
            "field": field,
            "operator": operator,
            "value": value,
        }

    @staticmethod
    def create_approver_data(
        approver_type: str = "USER",
        value: str = "usr_001",
        name: Optional[str] = None,
    ) -> Dict[str, Any]:
        return {
            "id": value,
            "type": approver_type,
            "value": value,
            "name": name,
        }

    @staticmethod
    def create_rule_data(
        name: str = "测试审批规则",
        description: str = "金额超过1000需要审批",
        rule_type: str = "expense",
        conditions: Optional[List[Dict[str, Any]]] = None,
        condition_operator: str = "and",
        approval_type: str = "all",
        approvers: Optional[List[Dict[str, Any]]] = None,
        priority: int = 1,
        tenant_id: str = "tnt_001",
    ) -> Dict[str, Any]:
        return {
            "name": name,
            "description": description,
            "rule_type": rule_type,
            "conditions": conditions or [
                ApprovalEngineDataFactory.create_condition_data()
            ],
            "condition_operator": condition_operator,
            "approval_type": approval_type,
            "approvers": approvers or [
                ApprovalEngineDataFactory.create_approver_data()
            ],
            "priority": priority,
            "tenant_id": tenant_id,
        }

    @staticmethod
    def create_process_data(
        entity_id: str = "exp_001",
        entity_type: str = "expense",
        rule_id: Optional[str] = None,
        title: str = "报销审批",
        description: str = "报销金额审批流程",
        approval_type: Optional[str] = None,
        context: Optional[Dict[str, Any]] = None,
        approvers: Optional[List[Dict[str, Any]]] = None,
        timeout_seconds: Optional[int] = None,
        tenant_id: str = "tnt_001",
    ) -> Dict[str, Any]:
        return {
            "entity_id": entity_id,
            "entity_type": entity_type,
            "rule_id": rule_id,
            "title": title,
            "description": description,
            "approval_type": approval_type,
            "context": context or {"amount": 2000, "type": "travel"},
            "approvers": approvers or [
                ApprovalEngineDataFactory.create_approver_data(value="usr_001", name="审批人1"),
                ApprovalEngineDataFactory.create_approver_data(value="usr_002", name="审批人2"),
            ],
            "timeout_seconds": timeout_seconds,
            "tenant_id": tenant_id,
        }

    @staticmethod
    def create_action_data(
        action: str = "APPROVE",
        approver_id: str = "usr_001",
        comment: str = "同意",
    ) -> Dict[str, Any]:
        return {
            "action": action,
            "approver_id": approver_id,
            "comment": comment,
        }

    @staticmethod
    def create_condition_evaluation_scenarios() -> List[Dict[str, Any]]:
        return [
            {
                "name": "数值大于比较",
                "condition": {"field": "amount", "operator": "greater_than", "value": 1000},
                "context": {"amount": 2000},
                "expected": True,
            },
            {
                "name": "数值小于比较",
                "condition": {"field": "amount", "operator": "less_than", "value": 1000},
                "context": {"amount": 500},
                "expected": True,
            },
            {
                "name": "字符串相等比较",
                "condition": {"field": "status", "operator": "equals", "value": "pending"},
                "context": {"status": "pending"},
                "expected": True,
            },
            {
                "name": "字符串包含",
                "condition": {"field": "title", "operator": "contains", "value": "报销"},
                "context": {"title": "差旅费报销"},
                "expected": True,
            },
            {
                "name": "列表包含",
                "condition": {"field": "tags", "operator": "in", "value": ["urgent", "high"]},
                "context": {"tags": "urgent"},
                "expected": True,
            },
            {
                "name": "嵌套字段访问",
                "condition": {"field": "user.department", "operator": "equals", "value": "finance"},
                "context": {"user": {"department": "finance"}},
                "expected": True,
            },
            {
                "name": "正则匹配",
                "condition": {"field": "email", "operator": "regex", "value": "^.*@company\\.com$"},
                "context": {"email": "user@company.com"},
                "expected": True,
            },
        ]

    @staticmethod
    def create_invalid_rule_data(scenario: str) -> Dict[str, Any]:
        base_data = ApprovalEngineDataFactory.create_rule_data()
        scenarios = {
            "empty_name": {**base_data, "name": ""},
            "missing_name": {k: v for k, v in base_data.items() if k != "name"},
            "empty_rule_type": {**base_data, "rule_type": ""},
            "missing_rule_type": {
                k: v for k, v in base_data.items() if k != "rule_type"
            },
            "invalid_approval_type": {**base_data, "approval_type": "invalid"},
            "empty_approvers": {**base_data, "approvers": []},
            "invalid_condition_operator": {**base_data, "condition_operator": "invalid"},
        }
        return scenarios.get(scenario, base_data)
