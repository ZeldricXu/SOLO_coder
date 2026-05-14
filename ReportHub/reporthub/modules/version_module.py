import uuid
import shutil
from typing import Optional, List, Dict, Any
from datetime import datetime
from sqlalchemy.orm import Session

from reporthub.models import ReportVersion, Report


class VersionModule:
    def __init__(self, db: Session, storage_module=None):
        self.db = db
        self.storage_module = storage_module

    def _get_next_version(self, report_id: str) -> str:
        versions = self.get_report_versions(report_id)
        if not versions:
            return "v1"
        last_version = versions[-1].version
        if last_version.startswith("v"):
            try:
                version_num = int(last_version[1:])
                return f"v{version_num + 1}"
            except ValueError:
                return f"v{len(versions) + 1}"
        return f"v{len(versions) + 1}"

    def create_version(self, report: Report, version: Optional[str] = None,
                       change_desc: Optional[str] = None) -> ReportVersion:
        version_id = f"ver_{report.report_id}_{uuid.uuid4().hex[:8]}"
        next_version = version or self._get_next_version(report.report_id)
        version_file = None
        if report.report_file and self.storage_module:
            if self.storage_module.file_exists(report.report_file):
                import os
                dir_name = os.path.dirname(report.report_file)
                base_name, ext = os.path.splitext(os.path.basename(report.report_file))
                version_file = os.path.join(dir_name, f"{base_name}_{next_version}{ext}")
                self.storage_module.copy_file(report.report_file, version_file)
        report_version = ReportVersion(
            version_id=version_id,
            report_id=report.report_id,
            version=next_version,
            report_file=version_file,
            report_data=report.report_data,
            change_desc=change_desc
        )
        self.db.add(report_version)
        self.db.commit()
        self.db.refresh(report_version)
        return report_version

    def get_version(self, version_id: str) -> Optional[ReportVersion]:
        return self.db.query(ReportVersion).filter(ReportVersion.version_id == version_id).first()

    def get_report_versions(self, report_id: str) -> List[ReportVersion]:
        return self.db.query(ReportVersion).filter(
            ReportVersion.report_id == report_id
        ).order_by(ReportVersion.generated_at.asc()).all()

    def get_latest_version(self, report_id: str) -> Optional[ReportVersion]:
        versions = self.get_report_versions(report_id)
        return versions[-1] if versions else None

    def get_specific_version(self, report_id: str, version: str) -> Optional[ReportVersion]:
        return self.db.query(ReportVersion).filter(
            ReportVersion.report_id == report_id,
            ReportVersion.version == version
        ).first()

    def restore_version(self, report: Report, version: str) -> Optional[Report]:
        report_version = self.get_specific_version(report.report_id, version)
        if not report_version:
            return None
        if report_version.report_data:
            report.report_data = report_version.report_data
        if report_version.report_file and self.storage_module:
            if self.storage_module.file_exists(report_version.report_file):
                self.storage_module.copy_file(report_version.report_file, report.report_file)
        self.db.commit()
        self.db.refresh(report)
        return report

    def compare_versions(self, report_id: str, version1: str, version2: str) -> Dict[str, Any]:
        v1 = self.get_specific_version(report_id, version1)
        v2 = self.get_specific_version(report_id, version2)
        if not v1 or not v2:
            return {"error": "版本不存在"}
        comparison = {
            "report_id": report_id,
            "version1": version1,
            "version2": version2,
            "generated_at_diff": {
                "v1": v1.generated_at.isoformat() if v1.generated_at else None,
                "v2": v2.generated_at.isoformat() if v2.generated_at else None
            }
        }
        if v1.report_data and v2.report_data:
            rows1 = v1.report_data.get("rows", [])
            rows2 = v2.report_data.get("rows", [])
            comparison["rows_diff"] = {
                "v1_rows": len(rows1),
                "v2_rows": len(rows2),
                "row_count_change": len(rows2) - len(rows1)
            }
            summary1 = v1.report_data.get("summary", {})
            summary2 = v2.report_data.get("summary", {})
            comparison["summary_diff"] = {
                "v1": summary1,
                "v2": summary2
            }
        return comparison

    def delete_version(self, version_id: str) -> bool:
        version = self.get_version(version_id)
        if not version:
            return False
        if version.report_file and self.storage_module:
            self.storage_module.delete_file(version.report_file)
        self.db.delete(version)
        self.db.commit()
        return True

    def get_version_history(self, report_id: str, limit: int = 20) -> List[Dict[str, Any]]:
        versions = self.get_report_versions(report_id)
        history = []
        for v in versions[-limit:]:
            history.append({
                "version_id": v.version_id,
                "version": v.version,
                "generated_at": v.generated_at.isoformat() if v.generated_at else None,
                "change_desc": v.change_desc,
                "has_file": bool(v.report_file)
            })
        return history
