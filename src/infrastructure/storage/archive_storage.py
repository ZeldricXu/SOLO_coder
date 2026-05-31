import os
os.environ.setdefault('OPENBLAS_NUM_THREADS', '1')
os.environ.setdefault('OMP_NUM_THREADS', '1')

import logging
from pathlib import Path
from typing import Any, Dict, List, Optional
from datetime import datetime

import pandas as pd

from src.infrastructure.config.settings import StorageTierConfig

logger = logging.getLogger(__name__)


class ArchiveStorage:
    def __init__(self, config: StorageTierConfig):
        self._config = config
        self._base_path = Path(config.base_path) if config.base_path else Path("/data/streamsql/archive")
        self._base_path.mkdir(parents=True, exist_ok=True)

    def _get_archive_path(self, database_name: str, table_name: str, date_str: Optional[str] = None) -> Path:
        if date_str is None:
            date_str = datetime.utcnow().strftime("%Y%m%d")
        archive_path = self._base_path / database_name / table_name / date_str
        archive_path.mkdir(parents=True, exist_ok=True)
        return archive_path

    def archive_data(
        self,
        database_name: str,
        table_name: str,
        df: pd.DataFrame,
        date_str: Optional[str] = None,
    ) -> str:
        archive_path = self._get_archive_path(database_name, table_name, date_str)
        timestamp = datetime.utcnow().strftime("%Y%m%d%H%M%S")
        file_name = f"archive-{timestamp}.parquet"
        file_path = archive_path / file_name
        comp = self._config.compression or "snappy"
        df.to_parquet(file_path, engine="pyarrow", compression=comp, index=False)

        meta_path = archive_path / f"archive-{timestamp}.meta"
        meta = {
            "database": database_name,
            "table": table_name,
            "row_count": len(df),
            "columns": list(df.columns),
            "archived_at": datetime.utcnow().isoformat(),
            "compression": comp,
        }
        import json
        meta_path.write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")

        logger.info(f"Archived {len(df)} rows to: {file_path}")
        return str(file_path)

    def read_archive(
        self,
        database_name: str,
        table_name: str,
        date_str: Optional[str] = None,
        columns: Optional[List[str]] = None,
    ) -> pd.DataFrame:
        archive_path = self._get_archive_path(database_name, table_name, date_str)
        parquet_files = sorted(archive_path.glob("archive-*.parquet"))
        if not parquet_files:
            return pd.DataFrame()
        dfs = []
        for pf in parquet_files:
            try:
                df = pd.read_parquet(pf, engine="pyarrow", columns=columns)
                dfs.append(df)
            except Exception as e:
                logger.error(f"Failed to read archive file {pf}: {e}")
        if not dfs:
            return pd.DataFrame()
        return pd.concat(dfs, ignore_index=True)

    def list_archives(self, database_name: str, table_name: str) -> List[Dict[str, Any]]:
        table_path = self._base_path / database_name / table_name
        if not table_path.exists():
            return []
        archives = []
        for date_dir in sorted(table_path.iterdir()):
            if not date_dir.is_dir():
                continue
            for meta_file in date_dir.glob("*.meta"):
                try:
                    import json
                    meta = json.loads(meta_file.read_text(encoding="utf-8"))
                    meta["date"] = date_dir.name
                    archives.append(meta)
                except Exception:
                    pass
        return archives

    def delete_archive(self, database_name: str, table_name: str, date_str: str) -> bool:
        archive_path = self._get_archive_path(database_name, table_name, date_str)
        if not archive_path.exists():
            return False
        import shutil
        shutil.rmtree(archive_path)
        logger.info(f"Deleted archive: {archive_path}")
        return True

    def cleanup_expired(self, retention_days: int) -> List[str]:
        from datetime import timedelta
        cutoff = datetime.utcnow() - timedelta(days=retention_days)
        deleted = []
        for db_dir in self._base_path.iterdir():
            if not db_dir.is_dir():
                continue
            for tbl_dir in db_dir.iterdir():
                if not tbl_dir.is_dir():
                    continue
                for date_dir in tbl_dir.iterdir():
                    if not date_dir.is_dir():
                        continue
                    try:
                        dir_date = datetime.strptime(date_dir.name, "%Y%m%d")
                        if dir_date < cutoff:
                            import shutil
                            shutil.rmtree(date_dir)
                            deleted.append(str(date_dir))
                            logger.info(f"Cleaned up expired archive: {date_dir}")
                    except ValueError:
                        continue
        return deleted
