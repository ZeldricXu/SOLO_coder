from datetime import datetime
from typing import List, Optional, Dict, Any
import json

from sqlalchemy import text
from sqlalchemy.orm import Session

from app.models import Asset, AssetChangeLog
from app.schemas import AssetCreate, AssetUpdate, ChangeLogEntry


class AssetService:
    def __init__(self, db: Session):
        self.db = db

    def get_all_assets(
        self,
        category: Optional[str] = None,
        status: Optional[str] = None,
        owner: Optional[str] = None,
        keyword: Optional[str] = None,
    ) -> List[Asset]:
        query = self.db.query(Asset)

        if category:
            query = query.filter(Asset.category == category)
        if status:
            query = query.filter(Asset.status == status)
        if owner:
            query = query.filter(Asset.owner == owner)
        if keyword:
            query = query.filter(
                (Asset.name.like(f"%{keyword}%")) |
                (Asset.ip.like(f"%{keyword}%"))
            )

        return query.order_by(Asset.category, Asset.name).all()

    def get_asset_by_id(self, asset_id: int) -> Optional[Asset]:
        return self.db.query(Asset).filter(Asset.id == asset_id).first()

    def create_asset(self, data: AssetCreate, operator_id: int) -> Asset:
        asset = Asset(**data.model_dump())
        self.db.add(asset)
        self.db.flush()

        for field, value in data.model_dump(exclude_unset=True).items():
            if value is not None:
                self._log_change(asset.id, field, None, str(value), operator_id, flush=False)

        self.db.commit()
        self.db.refresh(asset)
        return asset

    def update_asset(self, asset_id: int, data: AssetUpdate, operator_id: int) -> Optional[Asset]:
        asset = self.get_asset_by_id(asset_id)
        if not asset:
            return None

        for field, new_value in data.model_dump(exclude_unset=True).items():
            old_value = getattr(asset, field)
            if old_value != new_value:
                self._log_change(
                    asset.id,
                    field,
                    str(old_value) if old_value is not None else None,
                    str(new_value) if new_value is not None else None,
                    operator_id,
                    flush=False
                )
                setattr(asset, field, new_value)

        self.db.commit()
        self.db.refresh(asset)
        return asset

    def delete_asset(self, asset_id: int) -> bool:
        asset = self.get_asset_by_id(asset_id)
        if not asset:
            return False
        self.db.delete(asset)
        self.db.commit()
        return True

    def _log_change(
        self,
        asset_id: int,
        field_name: str,
        old_value: Optional[str],
        new_value: Optional[str],
        operator_id: int,
        flush: bool = True,
    ):
        log = AssetChangeLog(
            asset_id=asset_id,
            field_name=field_name,
            old_value=old_value,
            new_value=new_value,
            operator_id=operator_id,
        )
        self.db.add(log)
        if flush:
            self.db.commit()

    def get_change_log(self, asset_id: Optional[int] = None, limit: int = 100) -> List[AssetChangeLog]:
        query = self.db.query(AssetChangeLog).order_by(AssetChangeLog.changed_at.desc())
        if asset_id:
            query = query.filter(AssetChangeLog.asset_id == asset_id)
        return query.limit(limit).all()

    def get_categories(self) -> List[Dict[str, Any]]:
        result = self.db.execute(text("""
            SELECT category,
                   COUNT(*) as total,
                   SUM(CASE WHEN status = 'normal' THEN 1 ELSE 0 END) as normal_count,
                   SUM(CASE WHEN status = 'warning' THEN 1 ELSE 0 END) as warning_count,
                   SUM(CASE WHEN status = 'critical' THEN 1 ELSE 0 END) as critical_count
            FROM assets
            GROUP BY category
            ORDER BY category
        """)).fetchall()

        category_names = {
            "server": "服务器",
            "database": "数据库",
            "cache": "缓存",
            "mq": "消息队列",
            "load_balancer": "负载均衡",
            "storage": "存储",
            "network": "网络设备",
        }

        return [
            {
                "category": r.category,
                "display_name": category_names.get(r.category, r.category),
                "total": r.total,
                "normal": r.normal_count or 0,
                "warning": r.warning_count or 0,
                "critical": r.critical_count or 0,
            }
            for r in result
        ]

    def get_owners(self) -> List[str]:
        result = self.db.query(Asset.owner).filter(
            Asset.owner.isnot(None)
        ).distinct().all()
        return [r[0] for r in result]

    def get_summary(self) -> Dict[str, Any]:
        assets = self.get_all_assets()
        categories = self.get_categories()

        return {
            "total": len(assets),
            "by_category": categories,
            "by_status": {
                "normal": sum(1 for a in assets if a.status == "normal"),
                "warning": sum(1 for a in assets if a.status == "warning"),
                "critical": sum(1 for a in assets if a.status == "critical"),
                "maintenance": sum(1 for a in assets if a.status == "maintenance"),
            },
        }

    def get_asset_with_changes(self, asset_id: int) -> Optional[Dict[str, Any]]:
        asset = self.get_asset_by_id(asset_id)
        if not asset:
            return None

        changes = self.get_change_log(asset_id, limit=50)

        return {
            "asset": asset,
            "changes": changes,
        }

    def batch_update_status(self, asset_ids: List[int], status: str, operator_id: int) -> int:
        count = 0
        for asset_id in asset_ids:
            if self.update_asset(asset_id, AssetUpdate(status=status), operator_id):
                count += 1
        return count

    def search_by_ip(self, ip_pattern: str) -> List[Asset]:
        return self.db.query(Asset).filter(
            Asset.ip.like(f"%{ip_pattern}%")
        ).all()
