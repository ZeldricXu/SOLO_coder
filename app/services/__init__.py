"""服务层（Service Layer）—— 业务逻辑的核心实现。

本包包含运维监控大盘所有核心业务逻辑，遵循 API层 → Service层 → Repository层 的分层架构。
Route 层仅负责参数校验和响应格式化，业务逻辑全部委托给 Service 层。

模块职责划分：

- HealthService: 服务健康聚合面板
  - 对外接口: check_service(), check_all_services(), get_summary(), get_check_history()
  - 依赖: Prometheus API（健康检查）、数据库（Service, HealthCheck 模型）

- AlertService: 告警规则引擎
  - 对外接口: evaluate_rules(), create_rule(), acknowledge_alert(), resolve_alert()
  - 依赖: 数据库（AlertRule, AlertHistory 模型）、NotificationService（通知分发）

- NotificationService: 告警通知分发
  - 对外接口: send()
  - 依赖: 钉钉/企业微信 Webhook、SMTP 邮件服务、电话告警 API

- SlowSQLService: 慢SQL采集分析器
  - 对外接口: record_slow_sql(), generate_fingerprint(), generate_explain()
  - 依赖: 数据库（SlowSQL, SQLExplain 模型）

- LogService: 日志采集搜索
  - 对外接口: search(), get_templates(), create_template()
  - 依赖: Elasticsearch API、lucene_parser（查询语法解析）

- MetricsService: 监控指标查询
  - 对外接口: query_metrics(), get_available_metrics(), get_chart_data_for_frontend()
  - 依赖: Prometheus API（指标查询）

- EmailService: SMTP 邮件发送
  - 对外接口: send_alert_email(), send_custom_email()
  - 依赖: SMTP 服务器、Jinja2 模板引擎

- AssetService: 资产管理
  - 对外接口: CRUD 资产和变更记录
  - 依赖: 数据库（Asset, AssetChangeLog 模型）

- DutyService: 值班管理
  - 对外接口: CRUD 值班排班和交接报告
  - 依赖: 数据库（DutySchedule, HandoverReport 模型）

- PreferenceService: 用户偏好
  - 对外接口: CRUD 用户偏好和钉住组件
  - 依赖: 数据库（Preference, PinnedComponent 模型）

- lucene_parser: Lucene 风格查询语法解析器
  - 对外接口: lucene_parser.parse()
  - 依赖: pyparsing 库
"""

from app.services.health_service import HealthService
from app.services.metrics_service import MetricsService
from app.services.alert_service import AlertService
from app.services.notification_service import NotificationService
from app.services.slow_sql_service import SlowSQLService
from app.services.asset_service import AssetService
from app.services.duty_service import DutyService
from app.services.log_service import LogService
from app.services.preference_service import PreferenceService

__all__ = [
    "HealthService",
    "MetricsService",
    "AlertService",
    "NotificationService",
    "SlowSQLService",
    "AssetService",
    "DutyService",
    "LogService",
    "PreferenceService",
]
