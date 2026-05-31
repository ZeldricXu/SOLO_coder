from __future__ import annotations

import json
import os
import tarfile
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

from streamsql.core.models import generate_id


class ArchiveFormat(str):
    JSON = "json"
    PARQUET = "parquet"
    CSV = "csv"
    TAR_GZ = "tar.gz"


class ArchiveManager:
    def __init__(self, archive_dir: str = "./archives", compression_level: int = 6):
        self.archive_dir = Path(archive_dir)
        self.archive_dir.mkdir(parents=True, exist_ok=True)
        self.compression_level = compression_level
        self._index_path = self.archive_dir / "_archive_index.json"
        self._index = self._load_index()

    def archive(
        self,
        data: dict[str, Any],
        name: Optional[str] = None,
        format: str = ArchiveFormat.JSON,
        metadata: Optional[dict[str, Any]] = None,
    ) -> str:
        archive_id = generate_id("arc")
        archive_name = name or f"archive_{archive_id}"
        timestamp = datetime.utcnow()

        archive_path = self._get_archive_path(archive_name, format, timestamp)

        if format == ArchiveFormat.JSON:
            self._save_json(data, archive_path)
        elif format == ArchiveFormat.CSV:
            self._save_csv(data, archive_path)
        elif format == ArchiveFormat.PARQUET:
            self._save_parquet(data, archive_path)
        else:
            self._save_json(data, archive_path)

        archive_info = {
            "archive_id": archive_id,
            "name": archive_name,
            "format": format,
            "path": str(archive_path),
            "size_bytes": archive_path.stat().st_size,
            "created_at": timestamp.isoformat(),
            "metadata": metadata or {},
            "checksum": self._compute_checksum(archive_path),
        }

        self._index[archive_id] = archive_info
        self._save_index()

        return archive_id

    def restore(self, archive_id: str) -> Optional[dict[str, Any]]:
        if archive_id not in self._index:
            return None

        archive_info = self._index[archive_id]
        archive_path = Path(archive_info["path"])

        if not archive_path.exists():
            return None

        format = archive_info["format"]
        if format == ArchiveFormat.JSON:
            return self._load_json(archive_path)
        elif format == ArchiveFormat.CSV:
            return self._load_csv(archive_path)
        elif format == ArchiveFormat.PARQUET:
            return self._load_parquet(archive_path)
        else:
            return self._load_json(archive_path)

    def delete(self, archive_id: str) -> bool:
        if archive_id not in self._index:
            return False

        archive_info = self._index[archive_id]
        archive_path = Path(archive_info["path"])

        try:
            if archive_path.exists():
                archive_path.unlink()
            del self._index[archive_id]
            self._save_index()
            return True
        except Exception:
            return False

    def list_archives(
        self,
        prefix: Optional[str] = None,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
    ) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []

        for archive_info in self._index.values():
            created_at = datetime.fromisoformat(archive_info["created_at"])

            if prefix and not archive_info["name"].startswith(prefix):
                continue
            if start_date and created_at < start_date:
                continue
            if end_date and created_at > end_date:
                continue

            results.append(archive_info)

        results.sort(key=lambda x: x["created_at"], reverse=True)
        return results

    def batch_archive(
        self,
        items: list[tuple[str, dict[str, Any], Optional[dict[str, Any]]]],
        format: str = ArchiveFormat.JSON,
    ) -> list[str]:
        archive_ids: list[str] = []
        for name, data, metadata in items:
            aid = self.archive(data, name=name, format=format, metadata=metadata)
            archive_ids.append(aid)
        return archive_ids

    def batch_delete(self, archive_ids: list[str]) -> list[bool]:
        return [self.delete(aid) for aid in archive_ids]

    def cleanup_expired(self, retention_days: int = 365) -> list[str]:
        cutoff = datetime.utcnow() - timedelta(days=retention_days)
        expired_ids: list[str] = []

        for archive_id, archive_info in self._index.items():
            created_at = datetime.fromisoformat(archive_info["created_at"])
            if created_at < cutoff:
                expired_ids.append(archive_id)

        deleted = [aid for aid in expired_ids if self.delete(aid)]
        return deleted

    def get_archive_info(self, archive_id: str) -> Optional[dict[str, Any]]:
        return self._index.get(archive_id)

    def get_total_size(self) -> int:
        return sum(info["size_bytes"] for info in self._index.values())

    def _get_archive_path(self, name: str, format: str, timestamp: datetime) -> Path:
        date_dir = timestamp.strftime("%Y/%m/%d")
        full_dir = self.archive_dir / date_dir
        full_dir.mkdir(parents=True, exist_ok=True)
        return full_dir / f"{name}.{format}"

    def _save_json(self, data: dict[str, Any], path: Path) -> None:
        with open(path, "w") as f:
            json.dump(data, f, indent=2, default=str)

    def _load_json(self, path: Path) -> dict[str, Any]:
        with open(path, "r") as f:
            return json.load(f)

    def _save_csv(self, data: dict[str, Any], path: Path) -> None:
        try:
            import pandas as pd
            df = pd.DataFrame(data.get("records", [data]))
            df.to_csv(path, index=False)
        except ImportError:
            self._save_json(data, path.with_suffix(".json"))

    def _load_csv(self, path: Path) -> dict[str, Any]:
        try:
            import pandas as pd
            df = pd.read_csv(path)
            return {"records": df.to_dict("records")}
        except ImportError:
            return self._load_json(path.with_suffix(".json"))

    def _save_parquet(self, data: dict[str, Any], path: Path) -> None:
        try:
            import pandas as pd
            import pyarrow as pa
            import pyarrow.parquet as pq

            df = pd.DataFrame(data.get("records", [data]))
            table = pa.Table.from_pandas(df)
            pq.write_table(table, path, compression="zstd")
        except ImportError:
            self._save_json(data, path.with_suffix(".json"))

    def _load_parquet(self, path: Path) -> dict[str, Any]:
        try:
            import pyarrow.parquet as pq
            table = pq.read_table(path)
            return {"records": table.to_pandas().to_dict("records")}
        except ImportError:
            return self._load_json(path.with_suffix(".json"))

    def _compute_checksum(self, path: Path) -> str:
        import hashlib
        hash_obj = hashlib.md5()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(4096), b""):
                hash_obj.update(chunk)
        return hash_obj.hexdigest()

    def _load_index(self) -> dict[str, Any]:
        if self._index_path.exists():
            try:
                with open(self._index_path, "r") as f:
                    return json.load(f)
            except Exception:
                return {}
        return {}

    def _save_index(self) -> None:
        with open(self._index_path, "w") as f:
            json.dump(self._index, f, indent=2)
