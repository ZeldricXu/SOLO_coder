"""数据模型层（Models）—— ORM 模型定义。

本包定义了运维监控大盘的所有数据库模型，使用 SQLAlchemy ORM 映射。

模型清单：

- User: 用户模型
- Service: 监控服务模型，记录被监控服务的基本信息和当前状态
- HealthCheck: 健康检查记录模型，每次检查生成一条记录
- AlertRule: 告警规则模型，定义告警触发条件和通知渠道
- AlertHistory: 告警历史记录模型，记录告警的完整生命周期
- SlowSQL: 慢SQL记录模型，按指纹聚合
- SQLExplain: SQL执行计划模型，存储 EXPLAIN 分析结果
- Asset: 资产模型
- AssetChangeLog: 资产变更日志模型
- DutySchedule: 值班排班模型
- HandoverReport: 交接报告模型
- Preference: 用户偏好模型
- PinnedComponent: 钉住组件模型
- LogTemplate: 日志查询模板模型
"""

from app.models.user import User
from app.models.service import Service, HealthCheck
from app.models.alert import AlertRule, AlertHistory
from app.models.slow_sql import SlowSQL, SQLExplain
from app.models.asset import Asset, AssetChangeLog
from app.models.duty import DutySchedule, HandoverReport
from app.models.preference import Preference, PinnedComponent, LogTemplate

__all__ = [
    "User",
    "Service",
    "HealthCheck",
    "AlertRule",
    "AlertHistory",
    "SlowSQL",
    "SQLExplain",
    "Asset",
    "AssetChangeLog",
    "DutySchedule",
    "HandoverReport",
    "Preference",
    "PinnedComponent",
    "LogTemplate",
]
