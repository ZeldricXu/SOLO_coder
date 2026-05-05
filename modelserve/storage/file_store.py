import os
import hashlib
import shutil
from datetime import datetime
from typing import Optional, Dict, List
import threading


class FileStore:
    def __init__(self, models_dir: str = "/Users/huangzitong/Desktop/SoloCoder/session43/models"):
        self.models_dir = models_dir
        self._ensure_directories()
        self._lock = threading.Lock()

    def _ensure_directories(self):
        os.makedirs(self.models_dir, exist_ok=True)

    def _get_model_dir(self, model_id: str) -> str:
        return os.path.join(self.models_dir, model_id)

    def _get_version_dir(self, model_id: str, version: str) -> str:
        return os.path.join(self._get_model_dir(model_id), version)

    def calculate_checksum(self, file_path: str) -> str:
        sha256_hash = hashlib.sha256()
        with open(file_path, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()

    def get_file_size(self, file_path: str) -> int:
        if os.path.exists(file_path):
            return os.path.getsize(file_path)
        return 0

    def save_model_file(self, model_id: str, version: str, source_path: str, filename: Optional[str] = None) -> Optional[Dict]:
        try:
            with self._lock:
                model_dir = self._get_model_dir(model_id)
                version_dir = self._get_version_dir(model_id, version)

                os.makedirs(model_dir, exist_ok=True)
                os.makedirs(version_dir, exist_ok=True)

                if filename is None:
                    filename = os.path.basename(source_path)

                dest_path = os.path.join(version_dir, filename)

                if os.path.abspath(source_path) != os.path.abspath(dest_path):
                    shutil.copy2(source_path, dest_path)

                checksum = self.calculate_checksum(dest_path)
                file_size = self.get_file_size(dest_path)

                return {
                    "model_file": filename,
                    "file_path": dest_path,
                    "model_size": file_size,
                    "checksum": checksum
                }
        except Exception as e:
            print(f"Error saving model file: {e}")
            return None

    def get_model_file_path(self, model_id: str, version: str, filename: str) -> Optional[str]:
        version_dir = self._get_version_dir(model_id, version)
        file_path = os.path.join(version_dir, filename)
        if os.path.exists(file_path):
            return file_path
        return None

    def list_model_versions(self, model_id: str) -> List[str]:
        model_dir = self._get_model_dir(model_id)
        if not os.path.exists(model_dir):
            return []
        versions = []
        for item in os.listdir(model_dir):
            item_path = os.path.join(model_dir, item)
            if os.path.isdir(item_path):
                versions.append(item)
        return sorted(versions)

    def list_version_files(self, model_id: str, version: str) -> List[str]:
        version_dir = self._get_version_dir(model_id, version)
        if not os.path.exists(version_dir):
            return []
        files = []
        for item in os.listdir(version_dir):
            item_path = os.path.join(version_dir, item)
            if os.path.isfile(item_path):
                files.append(item)
        return files

    def delete_version(self, model_id: str, version: str) -> bool:
        try:
            with self._lock:
                version_dir = self._get_version_dir(model_id, version)
                if os.path.exists(version_dir):
                    shutil.rmtree(version_dir)
                return True
        except Exception as e:
            print(f"Error deleting version: {e}")
            return False

    def delete_model(self, model_id: str) -> bool:
        try:
            with self._lock:
                model_dir = self._get_model_dir(model_id)
                if os.path.exists(model_dir):
                    shutil.rmtree(model_dir)
                return True
        except Exception as e:
            print(f"Error deleting model: {e}")
            return False

    def verify_file(self, model_id: str, version: str, filename: str, expected_checksum: str) -> bool:
        file_path = self.get_model_file_path(model_id, version, filename)
        if not file_path:
            return False
        actual_checksum = self.calculate_checksum(file_path)
        return actual_checksum == expected_checksum

    def create_download_link(self, model_id: str, version: str, filename: str) -> Optional[str]:
        file_path = self.get_model_file_path(model_id, version, filename)
        if file_path:
            return file_path
        return None


file_store = FileStore()
