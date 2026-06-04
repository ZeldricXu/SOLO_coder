"""API路由层（Routes）—— HTTP 接口定义。

本包包含运维监控大盘的所有 HTTP 路由，负责参数校验、调用 Service 层和返回响应。
遵循 API层 → Service层 的调用方向，路由层不包含业务逻辑。

路由模块清单：

- pages: 页面路由，渲染 Jinja2 HTML 模板（首页、健康面板、告警页面等）
- health_api: 健康检查 API，提供服务状态查询和 timeline 组件的 HTMX partial
- metrics_api: 监控指标 API，提供指标数据查询和图表渲染
- alert_api: 告警 API，提供告警规则 CRUD 和告警生命周期操作
- slow_sql_api: 慢SQL API，提供慢SQL查询、上报和执行计划分析
- log_api: 日志 API，提供日志搜索和查询模板管理
- asset_api: 资产 API，提供资产和变更日志管理
- duty_api: 值班 API，提供排班和交接报告管理
- preference_api: 偏好 API，提供用户偏好和钉住组件管理
"""

from app.routes.pages import router as pages_router
from app.routes.health_api import router as health_router
from app.routes.metrics_api import router as metrics_router
from app.routes.alert_api import router as alert_router
from app.routes.slow_sql_api import router as slow_sql_router
from app.routes.asset_api import router as asset_router
from app.routes.duty_api import router as duty_router
from app.routes.log_api import router as log_router
from app.routes.preference_api import router as preference_router

__all__ = [
    "pages_router",
    "health_router",
    "metrics_router",
    "alert_router",
    "slow_sql_router",
    "asset_router",
    "duty_router",
    "log_router",
    "preference_router",
]
