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


class ColdStorage:
    def __init__(self, config: StorageTierConfig):
        self._config = config
        self._base_path = Path(config.base_path) if config.base_path else Path("/data/streamsql/cold")
        self._base_path.mkdir(parents=True, exist_ok=True)

    def _get_partition_path(self, database_name: str, table_name: str, date_str: Optional[str] = None) -> Path:
        if date_str is None:
            date_str = datetime.utcnow().strftime("%Y%m%d")
        partition_path = self._base_path / database_name / table_name / date_str
        partition_path.mkdir(parents=True, exist_ok=True)
        return partition_path

    def write_parquet(
        self,
        database_name: str,
        table_name: str,
        df: pd.DataFrame,
        date_str: Optional[str] = None,
        compression: Optional[str] = None,
    ) -> str:
        partition_path = self._get_partition_path(database_name, table_name, date_str)
        timestamp = datetime.utcnow().strftime("%Y%m%d%H%M%S")
        file_name = f"part-{timestamp}.parquet"
        file_path = partition_path / file_name
        comp = compression or self._config.compression or "snappy"
        df.to_parquet(file_path, engine="pyarrow", compression=comp, index=False)
        logger.info(f"Wrote {len(df)} rows to cold storage: {file_path}")
        return str(file_path)

    def read_parquet(
        self,
        database_name: str,
        table_name: str,
        date_str: Optional[str] = None,
        columns: Optional[List[str]] = None,
        filters: Optional[List] = None,
    ) -> pd.DataFrame:
        partition_path = self._get_partition_path(database_name, table_name, date_str)
        parquet_files = list(partition_path.glob("*.parquet"))
        if not parquet_files:
            return pd.DataFrame()
        dfs = []
        for pf in parquet_files:
            try:
                df = pd.read_parquet(pf, engine="pyarrow", columns=columns, filters=filters)
                dfs.append(df)
            except Exception as e:
                logger.error(f"Failed to read parquet file {pf}: {e}")
        if not dfs:
            return pd.DataFrame()
        return pd.concat(dfs, ignore_index=True)

    def list_partitions(self, database_name: str, table_name: str) -> List[str]:
        table_path = self._base_path / database_name / table_name
        if not table_path.exists():
            return []
        return [d.name for d in table_path.iterdir() if d.is_dir()]

    def get_partition_size(self, database_name: str, table_name: str, date_str: str) -> int:
        partition_path = self._get_partition_path(database_name, table_name, date_str)
        total = 0
        for f in partition_path.glob("*.parquet"):
            total += f.stat().st_size
        return total

    def delete_partition(self, database_name: str, table_name: str, date_str: str) -> bool:
        partition_path = self._get_partition_path(database_name, table_name, date_str)
        if not partition_path.exists():
            return False
        import shutil
        shutil.rmtree(partition_path)
        logger.info(f"Deleted cold storage partition: {partition_path}")
        return True

    def migrate_from_hot(
        self,
        records: List[Dict[str, Any]],
        database_name: str,
        table_name: str,
        date_str: Optional[str] = None,
    ) -> str:
        df = pd.DataFrame(records)
        return self.write_parquet(database_name, table_name, df, date_str)
