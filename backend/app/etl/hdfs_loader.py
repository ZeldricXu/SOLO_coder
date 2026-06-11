import logging
import json
from typing import List, Dict, Optional
from datetime import datetime
from hdfs import InsecureClient
import pandas as pd
import io

from app.config import settings

logger = logging.getLogger(__name__)


class HDFSManager:
    def __init__(self):
        self.client = None
        self._connect()

    def _connect(self):
        try:
            url = f"http://{settings.HDFS_HOST}:{settings.HDFS_PORT}"
            self.client = InsecureClient(url, user=settings.HDFS_USER)
            logger.info(f"HDFS connection established: {url}")
        except Exception as e:
            logger.error(f"Failed to connect to HDFS: {e}")
            self.client = None

    def list_files(self, path: str) -> List[str]:
        if not self.client:
            logger.warning("HDFS client not available")
            return []

        try:
            files = self.client.list(path)
            return files
        except Exception as e:
            logger.error(f"Failed to list files in {path}: {e}")
            return []

    def read_file(self, file_path: str) -> Optional[str]:
        if not self.client:
            logger.warning("HDFS client not available")
            return None

        try:
            with self.client.read(file_path) as reader:
                content = reader.read()
                if isinstance(content, bytes):
                    content = content.decode('utf-8')
                return content
        except Exception as e:
            logger.error(f"Failed to read file {file_path}: {e}")
            return None

    def read_csv(self, file_path: str) -> Optional[pd.DataFrame]:
        if not self.client:
            return None

        try:
            with self.client.read(file_path) as reader:
                df = pd.read_csv(io.BytesIO(reader.read()))
                return df
        except Exception as e:
            logger.error(f"Failed to read CSV {file_path}: {e}")
            return None

    def read_json(self, file_path: str) -> Optional[List[Dict]]:
        content = self.read_file(file_path)
        if content:
            try:
                return json.loads(content)
            except json.JSONDecodeError as e:
                logger.error(f"Failed to parse JSON from {file_path}: {e}")
        return None

    def write_file(self, file_path: str, data: str, overwrite: bool = True):
        if not self.client:
            return False

        try:
            self.client.write(file_path, data=data, overwrite=overwrite)
            return True
        except Exception as e:
            logger.error(f"Failed to write file {file_path}: {e}")
            return False

    def write_dataframe(self, file_path: str, df: pd.DataFrame, format: str = 'csv'):
        if not self.client:
            return False

        try:
            if format == 'csv':
                data = df.to_csv(index=False)
            elif format == 'json':
                data = df.to_json(orient='records')
            elif format == 'parquet':
                buffer = io.BytesIO()
                df.to_parquet(buffer, index=False)
                data = buffer.getvalue()
            else:
                raise ValueError(f"Unsupported format: {format}")

            self.client.write(file_path, data=data, overwrite=True)
            return True
        except Exception as e:
            logger.error(f"Failed to write dataframe to {file_path}: {e}")
            return False

    def delete_file(self, file_path: str) -> bool:
        if not self.client:
            return False

        try:
            self.client.delete(file_path, recursive=True)
            return True
        except Exception as e:
            logger.error(f"Failed to delete {file_path}: {e}")
            return False

    def exists(self, path: str) -> bool:
        if not self.client:
            return False

        try:
            return self.client.status(path) is not None
        except Exception:
            return False

    def mkdir(self, path: str) -> bool:
        if not self.client:
            return False

        try:
            self.client.makedirs(path)
            return True
        except Exception as e:
            logger.error(f"Failed to create directory {path}: {e}")
            return False

    def get_file_status(self, file_path: str) -> Optional[Dict]:
        if not self.client:
            return None

        try:
            return self.client.status(file_path)
        except Exception as e:
            logger.error(f"Failed to get status for {file_path}: {e}")
            return None


class HDFSDataLoader:
    def __init__(self):
        self.hdfs = HDFSManager()
        self.base_path = settings.HDFS_BASE_PATH

    def load_traffic_data(self, date: str = None) -> List[Dict]:
        if date is None:
            date = datetime.utcnow().strftime('%Y-%m-%d')

        path = f"{self.base_path}/traffic/{date}"
        files = self.hdfs.list_files(path)

        all_data = []
        for file in files:
            if file.endswith('.json'):
                data = self.hdfs.read_json(f"{path}/{file}")
                if data:
                    all_data.extend(data)
            elif file.endswith('.csv'):
                df = self.hdfs.read_csv(f"{path}/{file}")
                if df is not None:
                    all_data.extend(df.to_dict('records'))

        logger.info(f"Loaded {len(all_data)} records from HDFS path: {path}")
        return all_data

    def load_sensor_data(self) -> List[Dict]:
        path = f"{self.base_path}/metadata/sensors.json"
        data = self.hdfs.read_json(path)
        return data or []

    def load_road_data(self) -> List[Dict]:
        path = f"{self.base_path}/metadata/roads.json"
        data = self.hdfs.read_json(path)
        return data or []

    def save_processed_data(self, data: List[Dict], date: str, type: str = 'cleaned'):
        if date is None:
            date = datetime.utcnow().strftime('%Y-%m-%d')

        path = f"{self.base_path}/processed/{type}/{date}"
        self.hdfs.mkdir(path)

        timestamp = datetime.utcnow().strftime('%H%M%S')
        file_path = f"{path}/data_{timestamp}.json"

        return self.hdfs.write_file(file_path, json.dumps(data, ensure_ascii=False))


hdfs_manager = HDFSManager()
hdfs_loader = HDFSDataLoader()
