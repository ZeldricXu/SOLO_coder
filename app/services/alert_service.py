from datetime import datetime, timedelta
from typing import List, Optional, Dict, Any
import json
import random
import re
import asyncio

from sqlalchemy import text
from sqlalchemy.orm import Session
import httpx

from app.models import AlertRule, AlertHistory
from app.config import settings
from app.schemas import AlertRuleCreate, AlertRuleUpdate, AlertAck, AlertTrigger
from app.services.notification_service import NotificationService


class AlertService:
    """告警规则引擎，负责告警规则的CRUD、规则评估和告警生命周期管理。

    主要职责：
    - 告警规则的创建、更新、删除和查询
    - 规则评估：根据条件表达式和指标上下文判断是否触发告警
    - 告警生命周期：触发 → 确认 → 解决
    - 告警汇总统计

    通知逻辑委托给 NotificationService 处理，本服务不直接发送通知。

    依赖的外部服务：
    - 数据库（AlertRule, AlertHistory 模型）
    - Prometheus（通过 _get_metrics_context 获取指标上下文，当前为SQL查询模拟）
    - NotificationService（通知分发）
    """

    def __init__(self, db: Session):
        self.db = db
        self.use_mock = True
        self._notification_service = NotificationService(db)

    def evaluate_rules(self) -> List[AlertHistory]:
        """评估所有启用的告警规则，返回本次触发的告警列表。

        :return: 本次评估新触发的 AlertHistory 对象列表
        """
        rules = self.db.query(AlertRule).filter(AlertRule.enabled == True).all()
        triggered = []

        for rule in rules:
            if self._evaluate_rule(rule):
                alert = self._trigger_alert(rule)
                if alert:
                    triggered.append(alert)
                    asyncio.create_task(
                        self._notification_service.send(
                            rule.notification_channels, alert
                        )
                    )

        return triggered

    def _evaluate_rule(self, rule: AlertRule) -> bool:
        """评估单条告警规则是否满足触发条件。

        :param rule: 告警规则对象
        :return: 是否触发告警
        """
        if self.use_mock:
            return self._mock_evaluate(rule)
        return self._real_evaluate(rule)

    def _mock_evaluate(self, rule: AlertRule) -> bool:
        """Mock模式下的规则评估，按告警级别随机触发。

        :param rule: 告警规则对象
        :return: 是否触发告警
        """
        trigger_chance = {
            "P0": 0.05,
            "P1": 0.15,
            "P2": 0.25,
            "P3": 0.35,
        }.get(rule.level, 0.1)

        active_alert = self.db.query(AlertHistory).filter(
            AlertHistory.rule_id == rule.id,
            AlertHistory.status == "firing"
        ).first()

        if active_alert:
            return random.random() < 0.7

        return random.random() < trigger_chance

    def _real_evaluate(self, rule: AlertRule) -> bool:
        """正式模式下的规则评估，根据指标上下文和条件表达式判断。

        :param rule: 告警规则对象
        :return: 是否触发告警
        """
        try:
            context = self._get_metrics_context(rule)
            result = self._evaluate_expression(rule.condition_expr, context)
            return result
        except Exception as e:
            print(f"Rule evaluation error for {rule.name}: {e}")
            return False

    def _get_metrics_context(self, rule: AlertRule) -> Dict[str, Any]:
        """获取告警规则评估所需的指标上下文。

        从 health_checks 表中聚合窗口期内的检查结果，生成指标上下文。
        时间窗口为 [now - window_seconds, now]，左闭右闭。

        :param rule: 告警规则对象，使用其 window_seconds 字段确定时间窗口
        :return: 指标上下文字典，包含 total_checks、error_rate、avg_response_time 等键
        """
        window_start = datetime.now() - timedelta(seconds=rule.window_seconds)

        checks = self.db.execute(text("""
            SELECT h.status, h.response_time_ms, h.details, s.name as service_name
            FROM health_checks h
            JOIN services s ON h.service_id = s.id
            WHERE h.checked_at >= :window_start
            ORDER BY h.checked_at DESC
        """), {"window_start": window_start}).fetchall()

        context = {
            "total_checks": len(checks),
            "critical_count": sum(1 for c in checks if c.status == "critical"),
            "warning_count": sum(1 for c in checks if c.status == "warning"),
            "healthy_count": sum(1 for c in checks if c.status == "healthy"),
            "avg_response_time": sum(c.response_time_ms for c in checks if c.response_time_ms) / max(1, sum(1 for c in checks if c.response_time_ms)),
            "error_rate": (sum(1 for c in checks if c.status in ["critical", "warning"]) / max(1, len(checks))) * 100,
        }

        if context["total_checks"] > 0:
            context["qps"] = context["total_checks"] * 2
            context["cpu_usage"] = random.uniform(30, 95)
            context["memory_usage"] = random.uniform(40, 90)
            context["disk_usage"] = random.uniform(50, 85)
            context["slow_sql_count"] = random.randint(0, 20)

        return context

    def _evaluate_expression(self, expr: str, context: Dict[str, Any]) -> bool:
        """在受限上下文中评估条件表达式。

        :param expr: Python 条件表达式字符串，如 "error_rate > 80"
        :param context: 可用变量上下文，仅包含数值型字段
        :return: 表达式评估结果
        :raises: 表达式语法错误时返回 False，不抛出异常
        """
        try:
            safe_context = {k: v for k, v in context.items() if isinstance(v, (int, float, str, bool))}
            code = compile(expr, "<string>", "eval")
            result = eval(code, {"__builtins__": {}}, safe_context)
            return bool(result)
        except Exception as e:
            print(f"Expression evaluation error: {e}")
            return False

    def _trigger_alert(self, rule: AlertRule, service_id: Optional[int] = None, custom_message: Optional[str] = None) -> Optional[AlertHistory]:
        """触发一条告警，创建 AlertHistory 记录。

        同一规则同时只允许一条 firing 状态的告警存在（告警收敛）。

        :param rule: 触发的告警规则
        :param service_id: 关联的服务ID，可选
        :param custom_message: 自定义告警消息，可选
        :return: 新创建的 AlertHistory 对象；如果已有同规则 firing 告警则返回 None
        """
        existing = self.db.query(AlertHistory).filter(
            AlertHistory.rule_id == rule.id,
            AlertHistory.status == "firing"
        ).first()

        if existing:
            return None

        message = custom_message or self._generate_alert_message(rule)

        alert = AlertHistory(
            rule_id=rule.id,
            service_id=service_id,
            level=rule.level,
            message=message,
            status="firing",
        )
        self.db.add(alert)
        self.db.commit()
        self.db.refresh(alert)
        return alert

    def _generate_alert_message(self, rule: AlertRule) -> str:
        """根据告警级别生成默认告警消息。

        :param rule: 告警规则对象
        :return: 格式化的告警消息字符串
        """
        level_messages = {
            "P0": f"【紧急故障】{rule.name} 触发告警，请立即处理！",
            "P1": f"【严重告警】{rule.name} 触发，请尽快排查。",
            "P2": f"【一般告警】{rule.name} 触发，建议关注。",
            "P3": f"【提示信息】{rule.name} 触发，可择机处理。",
        }
        return level_messages.get(rule.level, f"{rule.name} 触发告警")

    def get_all_rules(self) -> List[AlertRule]:
        """获取所有告警规则。

        :return: AlertRule 对象列表
        """
        return self.db.query(AlertRule).all()

    def get_rule_by_id(self, rule_id: int) -> Optional[AlertRule]:
        """根据ID获取告警规则。

        :param rule_id: 规则ID
        :return: AlertRule 对象，不存在则返回 None
        """
        return self.db.query(AlertRule).filter(AlertRule.id == rule_id).first()

    def create_rule(self, data: AlertRuleCreate) -> AlertRule:
        """创建新的告警规则。

        :param data: 告警规则创建参数
        :return: 新创建的 AlertRule 对象
        """
        rule = AlertRule(**data.model_dump())
        self.db.add(rule)
        self.db.commit()
        self.db.refresh(rule)
        return rule

    def update_rule(self, rule_id: int, data: AlertRuleUpdate) -> Optional[AlertRule]:
        """更新告警规则，仅更新传入的非空字段。

        :param rule_id: 规则ID
        :param data: 告警规则更新参数（部分更新）
        :return: 更新后的 AlertRule 对象，规则不存在则返回 None
        """
        rule = self.get_rule_by_id(rule_id)
        if not rule:
            return None
        for key, value in data.model_dump(exclude_unset=True).items():
            setattr(rule, key, value)
        self.db.commit()
        self.db.refresh(rule)
        return rule

    def delete_rule(self, rule_id: int) -> bool:
        """删除告警规则。

        :param rule_id: 规则ID
        :return: 删除成功返回 True，规则不存在返回 False
        """
        rule = self.get_rule_by_id(rule_id)
        if not rule:
            return False
        self.db.delete(rule)
        self.db.commit()
        return True

    def get_alert_history(self, status: Optional[str] = None, limit: int = 100) -> List[AlertHistory]:
        """获取告警历史记录。

        :param status: 按状态过滤，可选值为 "firing"/"acknowledged"/"resolved"，
            传 None 则不过滤
        :param limit: 返回记录数量上限，默认100
        :return: 按触发时间倒序排列的 AlertHistory 对象列表
        """
        query = self.db.query(AlertHistory).order_by(AlertHistory.triggered_at.desc())
        if status:
            query = query.filter(AlertHistory.status == status)
        return query.limit(limit).all()

    def get_alert_by_id(self, alert_id: int) -> Optional[AlertHistory]:
        """根据ID获取告警记录。

        :param alert_id: 告警记录ID
        :return: AlertHistory 对象，不存在则返回 None
        """
        return self.db.query(AlertHistory).filter(AlertHistory.id == alert_id).first()

    def acknowledge_alert(self, alert_id: int, data: AlertAck) -> Optional[AlertHistory]:
        """确认告警，将状态从 firing 变为 acknowledged。

        :param alert_id: 告警记录ID
        :param data: 确认参数，包含 user_id
        :return: 更新后的 AlertHistory 对象，告警不存在则返回 None
        """
        alert = self.get_alert_by_id(alert_id)
        if not alert:
            return None
        alert.status = "acknowledged"
        alert.ack_user_id = data.user_id
        alert.ack_at = datetime.now()
        self.db.commit()
        self.db.refresh(alert)
        return alert

    def resolve_alert(self, alert_id: int, user_id: int) -> Optional[AlertHistory]:
        """解决告警，将状态变为 resolved。

        :param alert_id: 告警记录ID
        :param user_id: 操作用户ID
        :return: 更新后的 AlertHistory 对象，告警不存在则返回 None
        """
        alert = self.get_alert_by_id(alert_id)
        if not alert:
            return None
        alert.status = "resolved"
        if not alert.ack_user_id:
            alert.ack_user_id = user_id
            alert.ack_at = datetime.now()
        self.db.commit()
        self.db.refresh(alert)
        return alert

    def manual_trigger(self, data: AlertTrigger) -> AlertHistory:
        """手动触发告警。

        :param data: 手动触发参数，包含 rule_id、可选的 service_id 和 message
        :return: 新创建的 AlertHistory 对象
        :raises ValueError: 规则不存在时抛出
        """
        rule = self.get_rule_by_id(data.rule_id)
        if not rule:
            raise ValueError(f"Rule {data.rule_id} not found")
        return self._trigger_alert(rule, data.service_id, data.message) or self.get_alert_history(limit=1)[0]

    def get_summary(self) -> Dict[str, Any]:
        """获取告警汇总统计。

        :return: 包含 firing/acknowledged/resolved 计数和按级别分组的统计字典
        """
        alerts = self.get_alert_history()
        firing = [a for a in alerts if a.status == "firing"]
        ack = [a for a in alerts if a.status == "acknowledged"]
        resolved = [a for a in alerts if a.status == "resolved"]

        by_level = {}
        for level in ["P0", "P1", "P2", "P3"]:
            by_level[level] = {
                "firing": sum(1 for a in firing if a.level == level),
                "total": sum(1 for a in alerts if a.level == level),
            }

        return {
            "firing": len(firing),
            "acknowledged": len(ack),
            "resolved": len(resolved),
            "by_level": by_level,
            "active_alerts": firing[:10],
        }
