import uuid
import os
import tempfile
import shutil
from typing import Dict, Any, List, Optional
from datetime import datetime
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from dataclasses import dataclass, field

from reporthub.models.base import Base


class TestDataBuilder:
    def __init__(self, temp_dir: Optional[str] = None):
        self.temp_dir = temp_dir or tempfile.mkdtemp(prefix="reporthub_test_")
        self.storage_path = os.path.join(self.temp_dir, "storage")
        self.db_path = os.path.join(self.temp_dir, "test_reporthub.db")
        self._engine = None
        self._session = None
        os.makedirs(self.storage_path, exist_ok=True)
        os.makedirs(os.path.join(self.storage_path, "reports"), exist_ok=True)
        os.makedirs(os.path.join(self.storage_path, "exports"), exist_ok=True)

    @property
    def database_url(self) -> str:
        return f"sqlite:///{self.db_path}"

    @property
    def storage_directory(self) -> str:
        return self.storage_path

    def cleanup(self) -> None:
        if self._session:
            self._session.close()
        if self._engine:
            self._engine.dispose()
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir, ignore_errors=True)

    def get_engine(self):
        if not self._engine:
            self._engine = create_engine(
                self.database_url,
                connect_args={"check_same_thread": False}
            )
            Base.metadata.create_all(bind=self._engine)
        return self._engine

    def get_session(self):
        if not self._session:
            Session = sessionmaker(bind=self.get_engine())
            self._session = Session()
        return self._session

    def create_report_template_data(
        self,
        template_name: str = "销售报表模板",
        template_type: str = "table",
        source_type: str = "mysql",
        fields: Optional[List[Dict[str, Any]]] = None,
        filters: Optional[List[Dict[str, Any]]] = None
    ) -> Dict[str, Any]:
        default_fields = [
            {"field_id": "date", "field_name": "日期", "field_type": "date"},
            {"field_id": "product", "field_name": "产品", "field_type": "string"},
            {"field_id": "sales", "field_name": "销售额", "field_type": "number", "aggregation": "sum"},
            {"field_id": "quantity", "field_name": "数量", "field_type": "number", "aggregation": "sum"},
            {"field_id": "region", "field_name": "区域", "field_type": "string"}
        ]
        default_filters = [
            {"field": "date", "operator": "range", "value": "last_month"}
        ]
        return {
            "template_id": f"template_{uuid.uuid4().hex[:12]}",
            "template_name": template_name,
            "template_type": template_type,
            "data_source": {
                "source_type": source_type,
                "source_config": {
                    "host": "localhost",
                    "port": 3306,
                    "database": "test_db",
                    "username": "test_user",
                    "password": "test_password"
                }
            },
            "fields": fields or default_fields,
            "filters": filters or default_filters,
            "created_at": datetime.utcnow().isoformat(),
            "updated_at": datetime.utcnow().isoformat()
        }

    def create_report_data(
        self,
        template_id: str,
        report_name: str = "2026年4月销售报表",
        report_format: str = "xlsx",
        generator: str = "user_001",
        status: str = "completed",
        report_params: Optional[Dict[str, Any]] = None,
        row_count: int = 10
    ) -> Dict[str, Any]:
        columns = ["date", "product", "sales", "quantity", "region"]
        rows = []
        for i in range(row_count):
            rows.append({
                "date": f"2026-04-{10 + i:02d}",
                "product": f"Product_{i + 1}",
                "sales": 1000 + i * 500,
                "quantity": 10 + i * 2,
                "region": f"Region_{(i % 4) + 1}"
            })
        return {
            "report_id": f"report_{uuid.uuid4().hex[:12]}",
            "template_id": template_id,
            "report_name": report_name,
            "report_data": {
                "columns": columns,
                "rows": rows,
                "summary": {
                    "sales_total": sum(r["sales"] for r in rows),
                    "sales_avg": sum(r["sales"] for r in rows) / len(rows),
                    "quantity_total": sum(r["quantity"] for r in rows),
                    "quantity_avg": sum(r["quantity"] for r in rows) / len(rows),
                    "total_rows": len(rows)
                },
                "query_structure": {
                    "select_fields": columns,
                    "aggregations": [
                        {"field": "sales", "function": "sum"},
                        {"field": "quantity", "function": "sum"}
                    ],
                    "filters": [{"field": "date", "operator": "range", "value": "last_month"}]
                },
                "generated_at": datetime.utcnow().isoformat()
            },
            "report_file": None,
            "report_format": report_format,
            "generated_at": datetime.utcnow(),
            "generator": generator,
            "status": status,
            "report_params": report_params or {"date_range": "2026-04"}
        }

    def create_export_config_data(
        self,
        template_id: str,
        export_formats: Optional[List[str]] = None,
        export_options: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        default_formats = ["xlsx", "pdf", "csv"]
        default_options = {
            "xlsx": {
                "sheet_name": "销售数据",
                "header_style": "bold",
                "column_width": 18
            },
            "pdf": {
                "page_size": "A4",
                "orientation": "landscape",
                "include_summary": True
            },
            "csv": {
                "delimiter": ",",
                "encoding": "utf-8",
                "include_header": True
            }
        }
        return {
            "export_id": f"export_{uuid.uuid4().hex[:12]}",
            "template_id": template_id,
            "export_formats": export_formats or default_formats,
            "export_options": export_options or default_options,
            "created_at": datetime.utcnow().isoformat(),
            "updated_at": datetime.utcnow().isoformat()
        }

    def create_schedule_data(
        self,
        template_id: str,
        schedule_type: str = "cron",
        schedule_cron: Optional[str] = "0 8 * * *",
        schedule_interval: Optional[int] = None,
        export_format: str = "xlsx",
        notify_users: Optional[List[str]] = None,
        enabled: bool = True
    ) -> Dict[str, Any]:
        return {
            "schedule_id": f"schedule_{uuid.uuid4().hex[:12]}",
            "template_id": template_id,
            "schedule_type": schedule_type,
            "schedule_cron": schedule_cron,
            "schedule_interval": schedule_interval,
            "export_format": export_format,
            "notify_users": notify_users or ["user_001", "user_002"],
            "enabled": enabled,
            "last_run_at": None,
            "next_run_at": None,
            "created_at": datetime.utcnow().isoformat(),
            "updated_at": datetime.utcnow().isoformat()
        }

    def create_large_report_data(
        self,
        template_id: str,
        row_count: int = 1000
    ) -> Dict[str, Any]:
        columns = ["date", "product", "sales", "quantity", "region", "category"]
        rows = []
        products = ["Product_A", "Product_B", "Product_C", "Product_D", "Product_E"]
        regions = ["North", "South", "East", "West", "Central"]
        categories = ["Electronics", "Clothing", "Food", "Beauty"]
        import random
        for i in range(row_count):
            rows.append({
                "date": f"2026-04-{(i % 30) + 1:02d}",
                "product": random.choice(products),
                "sales": random.randint(100, 10000),
                "quantity": random.randint(1, 100),
                "region": random.choice(regions),
                "category": random.choice(categories)
            })
        return {
            "report_id": f"report_{uuid.uuid4().hex[:12]}",
            "template_id": template_id,
            "report_name": f"大数据量报表_{row_count}行",
            "report_data": {
                "columns": columns,
                "rows": rows,
                "summary": {
                    "sales_total": sum(r["sales"] for r in rows),
                    "quantity_total": sum(r["quantity"] for r in rows),
                    "total_rows": len(rows)
                }
            },
            "report_file": None,
            "report_format": "xlsx",
            "generated_at": datetime.utcnow(),
            "generator": "system",
            "status": "completed",
            "report_params": {"large_dataset": True, "row_count": row_count}
        }

    def create_statistics_data(
        self,
        template_id: str,
        stat_month: Optional[str] = None
    ) -> Dict[str, Any]:
        if not stat_month:
            stat_month = datetime.utcnow().strftime("%Y-%m")
        return {
            "stat_id": f"stat_{uuid.uuid4().hex[:12]}",
            "template_id": template_id,
            "stat_month": stat_month,
            "generate_count": 5,
            "export_count": 20,
            "total_rows": 1000,
            "avg_generate_time": 30,
            "total_generate_time": 150,
            "created_at": datetime.utcnow().isoformat(),
            "updated_at": datetime.utcnow().isoformat()
        }

    def create_version_data(
        self,
        report_id: str,
        version: str = "v1",
        change_desc: str = "初始版本"
    ) -> Dict[str, Any]:
        return {
            "version_id": f"ver_{report_id}_{uuid.uuid4().hex[:8]}",
            "report_id": report_id,
            "version": version,
            "report_file": None,
            "report_data": None,
            "generated_at": datetime.utcnow().isoformat(),
            "change_desc": change_desc
        }

    def create_permission_data(
        self,
        template_id: str,
        user_id: str = "user_001",
        role: str = "admin"
    ) -> Dict[str, Any]:
        role_permissions = {
            "admin": {"can_view": True, "can_generate": True, "can_export": True, "can_manage": True},
            "editor": {"can_view": True, "can_generate": True, "can_export": True, "can_manage": False},
            "viewer": {"can_view": True, "can_generate": False, "can_export": False, "can_manage": False}
        }
        perms = role_permissions.get(role, role_permissions["viewer"])
        return {
            "permission_id": f"perm_{uuid.uuid4().hex[:12]}",
            "template_id": template_id,
            "user_id": user_id,
            "role": role,
            "can_view": perms["can_view"],
            "can_generate": perms["can_generate"],
            "can_export": perms["can_export"],
            "can_manage": perms["can_manage"],
            "created_at": datetime.utcnow().isoformat(),
            "updated_at": datetime.utcnow().isoformat()
        }

    def create_mock_report(self, session, template_id: str, row_count: int = 10):
        from reporthub.models.reports import Report
        report_data = self.create_report_data(template_id, row_count=row_count)
        report = Report(
            report_id=report_data["report_id"],
            template_id=report_data["template_id"],
            report_name=report_data["report_name"],
            report_data=report_data["report_data"],
            report_file=report_data["report_file"],
            report_format=report_data["report_format"],
            generated_at=report_data["generated_at"],
            generator=report_data["generator"],
            status=report_data["status"],
            report_params=report_data["report_params"]
        )
        session.add(report)
        session.commit()
        session.refresh(report)
        return report

    def create_mock_template(self, session, custom_id: Optional[str] = None):
        from reporthub.models.templates import ReportTemplate
        template_data = self.create_report_template_data()
        if custom_id:
            template_data["template_id"] = custom_id
        template = ReportTemplate(
            template_id=template_data["template_id"],
            template_name=template_data["template_name"],
            template_type=template_data["template_type"],
            data_source=template_data["data_source"],
            fields=template_data["fields"],
            filters=template_data["filters"]
        )
        session.add(template)
        session.commit()
        session.refresh(template)
        return template

    def create_mock_schedule(self, session, template_id: str):
        from reporthub.models.schedules import Schedule
        schedule_data = self.create_schedule_data(template_id)
        schedule = Schedule(
            schedule_id=schedule_data["schedule_id"],
            template_id=schedule_data["template_id"],
            schedule_type=schedule_data["schedule_type"],
            schedule_cron=schedule_data["schedule_cron"],
            schedule_interval=schedule_data["schedule_interval"],
            export_format=schedule_data["export_format"],
            notify_users=schedule_data["notify_users"],
            enabled=schedule_data["enabled"],
            last_run_at=schedule_data["last_run_at"],
            next_run_at=schedule_data["next_run_at"]
        )
        session.add(schedule)
        session.commit()
        session.refresh(schedule)
        return schedule

    def create_mock_export_config(self, session, template_id: str):
        from reporthub.models.exports import ExportConfig
        config_data = self.create_export_config_data(template_id)
        config = ExportConfig(
            export_id=config_data["export_id"],
            template_id=config_data["template_id"],
            export_formats=config_data["export_formats"],
            export_options=config_data["export_options"]
        )
        session.add(config)
        session.commit()
        session.refresh(config)
        return config
