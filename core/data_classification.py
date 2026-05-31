import re
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple
from pydantic import BaseModel, Field

from .utils import generate_id


class ClassificationLevel(BaseModel):
    level_id: str = Field(..., description="等级ID")
    name: str = Field(..., description="等级名称")
    priority: int = Field(..., description="优先级(数字越大越敏感)")
    description: str = Field(..., description="描述")
    color: str = Field(default="#888888", description="标识颜色")


class SensitivePattern(BaseModel):
    pattern_id: str = Field(..., description="模式ID")
    name: str = Field(..., description="模式名称")
    regex: str = Field(..., description="正则表达式")
    level: str = Field(..., description="分类等级")
    category: str = Field(..., description="数据类别")
    description: Optional[str] = Field(None, description="描述")
    enabled: bool = Field(default=True, description="是否启用")


class ClassificationPolicy(BaseModel):
    policy_id: str = Field(..., description="策略ID")
    name: str = Field(..., description="策略名称")
    rules: List[str] = Field(default_factory=list, description="规则列表")
    actions: Dict[str, Any] = Field(default_factory=dict, description="触发动作")
    enabled: bool = Field(default=True, description="是否启用")


class ClassificationResult(BaseModel):
    field: str = Field(..., description="字段名")
    category: str = Field(..., description="数据类别")
    level: str = Field(..., description="分类等级")
    confidence: float = Field(..., description="置信度")
    matches: List[str] = Field(default_factory=list, description="匹配内容")
    pattern_id: Optional[str] = Field(None, description="匹配的模式ID")


class ScanReport(BaseModel):
    scan_id: str = Field(..., description="扫描ID")
    timestamp: str = Field(..., description="时间戳")
    total_fields: int = Field(..., description="总字段数")
    sensitive_fields: int = Field(..., description="敏感字段数")
    results: List[ClassificationResult] = Field(default_factory=list, description="分类结果")
    summary: Dict[str, int] = Field(default_factory=dict, description="按等级统计")


class DataClassificationEngine:
    def __init__(self):
        self.levels: Dict[str, ClassificationLevel] = {}
        self.patterns: Dict[str, SensitivePattern] = {}
        self.policies: Dict[str, ClassificationPolicy] = {}
        self._init_default_levels()
        self._init_default_patterns()
        self._init_default_policies()

    def _init_default_levels(self):
        default_levels = [
            ClassificationLevel(level_id="lvl_001", name="公开", priority=1, description="可公开数据", color="#22c55e"),
            ClassificationLevel(level_id="lvl_002", name="内部", priority=2, description="内部使用数据", color="#3b82f6"),
            ClassificationLevel(level_id="lvl_003", name="秘密", priority=3, description="敏感数据", color="#f59e0b"),
            ClassificationLevel(level_id="lvl_004", name="机密", priority=4, description="高度敏感数据", color="#ef4444"),
            ClassificationLevel(level_id="lvl_005", name="绝密", priority=5, description="最高级敏感数据", color="#991b1b")
        ]
        for level in default_levels:
            self.levels[level.level_id] = level

    def _init_default_patterns(self):
        default_patterns = [
            SensitivePattern(
                pattern_id="pat_001",
                name="身份证号",
                regex=r"\d{17}[\dXx]",
                level="lvl_004",
                category="身份信息",
                description="中国居民身份证号"
            ),
            SensitivePattern(
                pattern_id="pat_002",
                name="手机号",
                regex=r"1[3-9]\d{9}",
                level="lvl_003",
                category="联系方式",
                description="中国大陆手机号"
            ),
            SensitivePattern(
                pattern_id="pat_003",
                name="邮箱地址",
                regex=r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}",
                level="lvl_002",
                category="联系方式",
                description="电子邮件地址"
            ),
            SensitivePattern(
                pattern_id="pat_004",
                name="银行卡号",
                regex=r"\d{16,19}",
                level="lvl_004",
                category="金融信息",
                description="银行借记卡/信用卡号"
            ),
            SensitivePattern(
                pattern_id="pat_005",
                name="IP地址",
                regex=r"\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}",
                level="lvl_002",
                category="网络信息",
                description="IPv4地址"
            ),
            SensitivePattern(
                pattern_id="pat_006",
                name="姓名",
                regex=r"[\u4e00-\u9fa5]{2,4}",
                level="lvl_002",
                category="身份信息",
                description="中文姓名"
            ),
            SensitivePattern(
                pattern_id="pat_007",
                name="住址",
                regex=r"[\u4e00-\u9fa5]{5,}(省|市|区|县|街道|路|号|村)",
                level="lvl_003",
                category="位置信息",
                description="中国地址格式"
            ),
            SensitivePattern(
                pattern_id="pat_008",
                name="密码",
                regex=r"(password|pwd|passwd).{0,10}",
                level="lvl_005",
                category="认证信息",
                description="密码相关字段"
            ),
            SensitivePattern(
                pattern_id="pat_009",
                name="API密钥",
                regex=r"(api[_-]?key|secret[_-]?key).{0,20}",
                level="lvl_005",
                category="认证信息",
                description="API密钥相关字段"
            ),
            SensitivePattern(
                pattern_id="pat_010",
                name="营业执照号",
                regex=r"\d{15}|\d{18}",
                level="lvl_003",
                category="企业信息",
                description="统一社会信用代码"
            )
        ]
        for pattern in default_patterns:
            self.patterns[pattern.pattern_id] = pattern

    def _init_default_policies(self):
        default_policies = [
            ClassificationPolicy(
                policy_id="pol_001",
                name="默认脱敏策略",
                rules=["pat_001", "pat_002", "pat_004", "pat_008", "pat_009"],
                actions={"mask": True, "encrypt": False, "block": False}
            ),
            ClassificationPolicy(
                policy_id="pol_002",
                name="严格加密策略",
                rules=["pat_001", "pat_004", "pat_008", "pat_009"],
                actions={"mask": False, "encrypt": True, "block": False}
            )
        ]
        for policy in default_policies:
            self.policies[policy.policy_id] = policy

    def add_level(self, level: ClassificationLevel) -> None:
        self.levels[level.level_id] = level

    def remove_level(self, level_id: str) -> bool:
        if level_id in self.levels:
            del self.levels[level_id]
            return True
        return False

    def add_pattern(self, pattern: SensitivePattern) -> None:
        self.patterns[pattern.pattern_id] = pattern

    def remove_pattern(self, pattern_id: str) -> bool:
        if pattern_id in self.patterns:
            del self.patterns[pattern_id]
            return True
        return False

    def add_policy(self, policy: ClassificationPolicy) -> None:
        self.policies[policy.policy_id] = policy

    def remove_policy(self, policy_id: str) -> bool:
        if policy_id in self.policies:
            del self.policies[policy_id]
            return True
        return False

    def _classify_value(self, field_name: str, value: Any) -> Optional[ClassificationResult]:
        if value is None:
            return None

        value_str = str(value)

        best_result: Optional[ClassificationResult] = None
        highest_priority = -1

        for pattern_id, pattern in self.patterns.items():
            if not pattern.enabled:
                continue

            try:
                regex = re.compile(pattern.regex, re.IGNORECASE)
                matches = regex.findall(value_str)

                field_match = re.search(pattern.regex, field_name, re.IGNORECASE)

                if matches or field_match:
                    level = self.levels.get(pattern.level)
                    if level and level.priority > highest_priority:
                        highest_priority = level.priority
                        confidence = min(1.0, len(matches) * 0.2 + (0.5 if field_match else 0))
                        best_result = ClassificationResult(
                            field=field_name,
                            category=pattern.category,
                            level=level.name,
                            confidence=confidence,
                            matches=matches[:10],
                            pattern_id=pattern_id
                        )
            except re.error:
                continue

        return best_result

    def _classify_dict(self, data: Dict[str, Any], prefix: str = "") -> List[ClassificationResult]:
        results = []

        for key, value in data.items():
            full_key = f"{prefix}.{key}" if prefix else key

            if isinstance(value, dict):
                results.extend(self._classify_dict(value, full_key))
            elif isinstance(value, list):
                for i, item in enumerate(value):
                    if isinstance(item, dict):
                        results.extend(self._classify_dict(item, f"{full_key}[{i}]"))
                    elif item is not None:
                        result = self._classify_value(f"{full_key}[{i}]", item)
                        if result:
                            results.append(result)
            elif value is not None:
                result = self._classify_value(full_key, value)
                if result:
                    results.append(result)

        return results

    def classify(self, data: Any) -> List[ClassificationResult]:
        if isinstance(data, dict):
            return self._classify_dict(data)
        elif isinstance(data, list):
            results = []
            for i, item in enumerate(data):
                if isinstance(item, dict):
                    results.extend(self._classify_dict(item, f"[{i}]"))
                elif item is not None:
                    result = self._classify_value(f"[{i}]", item)
                    if result:
                        results.append(result)
            return results
        else:
            result = self._classify_value("value", data)
            return [result] if result else []

    def scan_data(self, data: Any, policy_id: Optional[str] = None) -> ScanReport:
        results = self.classify(data)

        total_fields = self._count_fields(data)
        sensitive_fields = len(results)

        summary: Dict[str, int] = {}
        for result in results:
            summary[result.level] = summary.get(result.level, 0) + 1

        if policy_id and policy_id in self.policies:
            policy = self.policies[policy_id]
            filtered_results = []
            for result in results:
                if result.pattern_id in policy.rules:
                    filtered_results.append(result)
            results = filtered_results

        return ScanReport(
            scan_id=generate_id("scan_"),
            timestamp=datetime.utcnow().isoformat(),
            total_fields=total_fields,
            sensitive_fields=sensitive_fields,
            results=results,
            summary=summary
        )

    def _count_fields(self, data: Any) -> int:
        count = 0
        if isinstance(data, dict):
            for value in data.values():
                if isinstance(value, (dict, list)):
                    count += self._count_fields(value)
                else:
                    count += 1
        elif isinstance(data, list):
            for item in data:
                if isinstance(item, (dict, list)):
                    count += self._count_fields(item)
                else:
                    count += 1
        else:
            count = 1
        return count

    def get_level_by_name(self, name: str) -> Optional[ClassificationLevel]:
        for level in self.levels.values():
            if level.name == name:
                return level
        return None

    def get_sensitive_patterns(self, category: Optional[str] = None) -> List[SensitivePattern]:
        patterns = list(self.patterns.values())
        if category:
            patterns = [p for p in patterns if p.category == category]
        return patterns

    def apply_policy(self, data: Any, policy_id: str) -> Dict[str, Any]:
        if policy_id not in self.policies:
            return {"error": "Policy not found"}

        policy = self.policies[policy_id]
        report = self.scan_data(data, policy_id)

        result = {
            "policy_id": policy_id,
            "policy_name": policy.name,
            "scan_report": report.dict(),
            "actions_applied": policy.actions,
            "modified_data": self._apply_actions(data, report.results, policy.actions)
        }

        return result

    def _apply_actions(self, data: Any, results: List[ClassificationResult], actions: Dict[str, Any]) -> Any:
        if not actions.get("mask", False):
            return data

        sensitive_fields = {r.field for r in results}

        def mask_dict(d: Dict[str, Any], prefix: str = "") -> Dict[str, Any]:
            result = {}
            for key, value in d.items():
                full_key = f"{prefix}.{key}" if prefix else key
                if full_key in sensitive_fields:
                    result[key] = "***"
                elif isinstance(value, dict):
                    result[key] = mask_dict(value, full_key)
                elif isinstance(value, list):
                    result[key] = [mask_dict(item, full_key) if isinstance(item, dict) else item for item in value]
                else:
                    result[key] = value
            return result

        if isinstance(data, dict):
            return mask_dict(data)
        return data

    def get_categories(self) -> List[str]:
        return list(set(p.category for p in self.patterns.values()))

    def get_statistics(self) -> Dict[str, Any]:
        return {
            "levels_count": len(self.levels),
            "patterns_count": len(self.patterns),
            "policies_count": len(self.policies),
            "categories": self.get_categories(),
            "patterns_by_level": {
                level.name: len([p for p in self.patterns.values() if p.level == level.level_id])
                for level in self.levels.values()
            }
        }


_classification_engine_instance: Optional[DataClassificationEngine] = None


def get_classification_engine() -> DataClassificationEngine:
    global _classification_engine_instance
    if _classification_engine_instance is None:
        _classification_engine_instance = DataClassificationEngine()
    return _classification_engine_instance
