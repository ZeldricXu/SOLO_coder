import os
import shutil
from typing import Optional
from datetime import datetime, timedelta

from reporthub.config import settings


class StorageModule:
    def __init__(self):
        self.reports_path = settings.reports_storage_path
        self.exports_path = settings.exports_storage_path
        self._ensure_directories()

    def _ensure_directories(self) -> None:
        os.makedirs(self.reports_path, exist_ok=True)
        os.makedirs(self.exports_path, exist_ok=True)

    def save_report_file(self, file_name: str, file_data: bytes) -> str:
        file_path = os.path.join(self.reports_path, file_name)
        with open(file_path, "wb") as f:
            f.write(file_data)
        return file_path

    def save_export_file(self, file_name: str, file_data: bytes) -> str:
        file_path = os.path.join(self.exports_path, file_name)
        with open(file_path, "wb") as f:
            f.write(file_data)
        return file_path

    def get_file(self, file_path: str) -> Optional[bytes]:
        if not os.path.exists(file_path):
            return None
        with open(file_path, "rb") as f:
            return f.read()

    def delete_file(self, file_path: str) -> bool:
        if not os.path.exists(file_path):
            return False
        try:
            os.remove(file_path)
            return True
        except Exception:
            return False

    def file_exists(self, file_path: str) -> bool:
        return os.path.exists(file_path)

    def get_file_size(self, file_path: str) -> Optional[int]:
        if not os.path.exists(file_path):
            return None
        return os.path.getsize(file_path)

    def copy_file(self, source_path: str, dest_path: str) -> bool:
        if not os.path.exists(source_path):
            return False
        try:
            shutil.copy2(source_path, dest_path)
            return True
        except Exception:
            return False

    def list_files(self, directory: str) -> list:
        if not os.path.exists(directory):
            return []
        return [f for f in os.listdir(directory) if os.path.isfile(os.path.join(directory, f))]

    def list_reports(self) -> list:
        return self.list_files(self.reports_path)

    def list_exports(self) -> list:
        return self.list_files(self.exports_path)

    def clean_expired_files(self, expire_days: int = 30) -> int:
        cutoff_date = datetime.utcnow() - timedelta(days=expire_days)
        deleted_count = 0
        for directory in [self.reports_path, self.exports_path]:
            if not os.path.exists(directory):
                continue
            for filename in os.listdir(directory):
                file_path = os.path.join(directory, filename)
                if os.path.isfile(file_path):
                    file_mtime = datetime.fromtimestamp(os.path.getmtime(file_path))
                    if file_mtime < cutoff_date:
                        try:
                            os.remove(file_path)
                            deleted_count += 1
                        except Exception:
                            pass
        return deleted_count

    def get_storage_usage(self) -> dict:
        total_size = 0
        reports_count = 0
        exports_count = 0
        reports_size = 0
        exports_size = 0
        if os.path.exists(self.reports_path):
            for filename in os.listdir(self.reports_path):
                file_path = os.path.join(self.reports_path, filename)
                if os.path.isfile(file_path):
                    reports_count += 1
                    reports_size += os.path.getsize(file_path)
        if os.path.exists(self.exports_path):
            for filename in os.listdir(self.exports_path):
                file_path = os.path.join(self.exports_path, filename)
                if os.path.isfile(file_path):
                    exports_count += 1
                    exports_size += os.path.getsize(file_path)
        total_size = reports_size + exports_size
        return {
            "total_size_bytes": total_size,
            "total_size_mb": round(total_size / (1024 * 1024), 2),
            "reports_count": reports_count,
            "reports_size_bytes": reports_size,
            "reports_size_mb": round(reports_size / (1024 * 1024), 2),
            "exports_count": exports_count,
            "exports_size_bytes": exports_size,
            "exports_size_mb": round(exports_size / (1024 * 1024), 2)
        }
