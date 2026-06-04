from datetime import datetime
from typing import List, Optional, Dict, Any
import json

from sqlalchemy.orm import Session

from app.models import Preference, PinnedComponent
from app.schemas import PreferenceUpdate, PinnedComponentRequest, LayoutConfig


class PreferenceService:
    def __init__(self, db: Session):
        self.db = db

    def get_preference(self, user_id: int) -> Optional[Preference]:
        return self.db.query(Preference).filter(Preference.user_id == user_id).first()

    def get_or_create_preference(self, user_id: int) -> Preference:
        pref = self.get_preference(user_id)
        if not pref:
            pref = Preference(
                user_id=user_id,
                layout_config=json.dumps({
                    "grid_cols": 3,
                    "theme": "dark",
                    "refresh_interval": 30,
                }, ensure_ascii=False),
            )
            self.db.add(pref)
            self.db.commit()
            self.db.refresh(pref)
        return pref

    def update_preference(self, user_id: int, data: PreferenceUpdate) -> Preference:
        pref = self.get_or_create_preference(user_id)
        if data.layout_config:
            try:
                existing = json.loads(pref.layout_config) if pref.layout_config else {}
                existing.update(data.layout_config)
                pref.layout_config = json.dumps(existing, ensure_ascii=False)
            except (json.JSONDecodeError, TypeError):
                pref.layout_config = json.dumps(data.layout_config, ensure_ascii=False)
        pref.updated_at = datetime.now()
        self.db.commit()
        self.db.refresh(pref)
        return pref

    def get_layout_config(self, user_id: int) -> Dict[str, Any]:
        pref = self.get_or_create_preference(user_id)
        try:
            return json.loads(pref.layout_config) if pref.layout_config else {}
        except (json.JSONDecodeError, TypeError):
            return {}

    def save_layout_config(self, user_id: int, config: LayoutConfig) -> Dict[str, Any]:
        pref = self.get_or_create_preference(user_id)
        existing = self.get_layout_config(user_id)
        if config.layout:
            existing.update(config.layout)
        pref.layout_config = json.dumps(existing, ensure_ascii=False)
        pref.updated_at = datetime.now()
        self.db.commit()
        self.db.refresh(pref)
        return existing

    def get_pinned_components(self, user_id: int) -> List[Dict[str, Any]]:
        pref = self.get_or_create_preference(user_id)
        components = self.db.query(PinnedComponent).filter(
            PinnedComponent.preference_id == pref.id
        ).order_by(PinnedComponent.position, PinnedComponent.created_at).all()

        result = []
        for c in components:
            result.append({
                "id": c.id,
                "component_type": c.component_type,
                "component_key": c.component_key,
                "position": c.position,
                "config": self._get_component_config(c.component_type, c.component_key),
            })
        return result

    def _get_component_config(self, component_type: str, component_key: str) -> Dict[str, Any]:
        configs = {
            "health_summary": {
                "title": "服务健康概览",
                "description": "所有服务的运行状态汇总",
                "icon": "activity",
                "color": "green",
            },
            "alert_list": {
                "title": "当前告警",
                "description": "正在触发的告警列表",
                "icon": "alert-triangle",
                "color": "red",
            },
            "metric_chart": {
                "title": f"{component_key} 趋势图",
                "description": "最近24小时指标趋势",
                "icon": "trending-up",
                "color": "blue",
            },
            "slow_sql_top": {
                "title": "Top 10 慢SQL",
                "description": "最耗时的SQL查询",
                "icon": "database",
                "color": "yellow",
            },
            "duty_info": {
                "title": "今日值班",
                "description": "当前值班人员信息",
                "icon": "users",
                "color": "purple",
            },
            "asset_summary": {
                "title": "资产概览",
                "description": "资产数量和状态分布",
                "icon": "server",
                "color": "cyan",
            },
        }
        return configs.get(component_type, {
            "title": f"{component_type} - {component_key}",
            "description": "自定义组件",
            "icon": "box",
            "color": "gray",
        })

    def pin_component(self, user_id: int, data: PinnedComponentRequest) -> PinnedComponent:
        pref = self.get_or_create_preference(user_id)

        existing = self.db.query(PinnedComponent).filter(
            PinnedComponent.preference_id == pref.id,
            PinnedComponent.component_type == data.component_type,
            PinnedComponent.component_key == data.component_key,
        ).first()

        if existing:
            existing.position = data.position
            self.db.commit()
            self.db.refresh(existing)
            return existing

        component = PinnedComponent(
            preference_id=pref.id,
            component_type=data.component_type,
            component_key=data.component_key,
            position=data.position,
        )
        self.db.add(component)
        self.db.commit()
        self.db.refresh(component)
        return component

    def unpin_component(self, user_id: int, component_id: int) -> bool:
        pref = self.get_preference(user_id)
        if not pref:
            return False

        component = self.db.query(PinnedComponent).filter(
            PinnedComponent.id == component_id,
            PinnedComponent.preference_id == pref.id,
        ).first()

        if not component:
            return False

        self.db.delete(component)
        self.db.commit()
        return True

    def update_component_position(self, user_id: int, component_id: int, new_position: int) -> bool:
        pref = self.get_preference(user_id)
        if not pref:
            return False

        component = self.db.query(PinnedComponent).filter(
            PinnedComponent.id == component_id,
            PinnedComponent.preference_id == pref.id,
        ).first()

        if not component:
            return False

        component.position = new_position
        self.db.commit()
        return True

    def get_available_components(self) -> List[Dict[str, Any]]:
        return [
            {
                "type": "health_summary",
                "key": "all",
                "title": "服务健康概览",
                "description": "显示所有服务的运行状态汇总",
                "icon": "activity",
            },
            {
                "type": "alert_list",
                "key": "firing",
                "title": "当前告警",
                "description": "显示正在触发的告警列表",
                "icon": "alert-triangle",
            },
            {
                "type": "metric_chart",
                "key": "cpu_usage",
                "title": "CPU使用率趋势",
                "description": "最近24小时CPU使用率趋势图",
                "icon": "cpu",
            },
            {
                "type": "metric_chart",
                "key": "memory_usage",
                "title": "内存使用率趋势",
                "description": "最近24小时内存使用率趋势图",
                "icon": "hard-drive",
            },
            {
                "type": "metric_chart",
                "key": "qps",
                "title": "QPS趋势",
                "description": "最近24小时每秒请求数趋势图",
                "icon": "zap",
            },
            {
                "type": "slow_sql_top",
                "key": "top10",
                "title": "Top 10 慢SQL",
                "description": "显示最耗时的10条SQL查询",
                "icon": "database",
            },
            {
                "type": "duty_info",
                "key": "today",
                "title": "今日值班",
                "description": "显示今天的值班人员信息",
                "icon": "users",
            },
            {
                "type": "asset_summary",
                "key": "all",
                "title": "资产概览",
                "description": "显示资产数量和状态分布",
                "icon": "server",
            },
        ]

    def reset_preference(self, user_id: int) -> bool:
        pref = self.get_preference(user_id)
        if not pref:
            return False
        self.db.delete(pref)
        self.db.commit()
        return True

    def get_user_dashboard_data(self, user_id: int) -> Dict[str, Any]:
        from app.services import (
            HealthService, AlertService, MetricsService,
            SlowSQLService, DutyService, AssetService,
        )

        health_service = HealthService(self.db)
        alert_service = AlertService(self.db)
        metrics_service = MetricsService(self.db)
        slow_sql_service = SlowSQLService(self.db)
        duty_service = DutyService(self.db)
        asset_service = AssetService(self.db)

        components = self.get_pinned_components(user_id)

        component_data = {}
        for c in components:
            key = f"{c['component_type']}_{c['component_key']}"
            if c["component_type"] == "health_summary":
                component_data[key] = health_service.get_summary()
            elif c["component_type"] == "alert_list":
                alerts = alert_service.get_alert_history(status="firing", limit=10)
                component_data[key] = {"alerts": alerts, "count": len(alerts)}
            elif c["component_type"] == "metric_chart":
                component_data[key] = metrics_service.get_chart_data_for_frontend(c["component_key"])
            elif c["component_type"] == "slow_sql_top":
                stats = slow_sql_service.get_statistics(days=1)
                component_data[key] = {"top_sqls": stats.get("top_10", []), "total": stats.get("total_executions", 0)}
            elif c["component_type"] == "duty_info":
                component_data[key] = {"today": duty_service.get_today_duty(), "current": duty_service.get_duty_user()}
            elif c["component_type"] == "asset_summary":
                component_data[key] = asset_service.get_summary()

        return {
            "layout": self.get_layout_config(user_id),
            "pinned_components": components,
            "component_data": component_data,
        }
