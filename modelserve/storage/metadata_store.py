import os
import json
from datetime import datetime
from typing import Dict, List, Optional, Any, TypeVar, Type
import threading

T = TypeVar('T')


class MetadataStore:
    def __init__(self, data_dir: str = "/Users/huangzitong/Desktop/SoloCoder/session43/data"):
        self.data_dir = data_dir
        self._ensure_directories()
        self._locks: Dict[str, threading.Lock] = {}

    def _ensure_directories(self):
        os.makedirs(self.data_dir, exist_ok=True)
        for subdir in ["models", "versions", "deployments", "inferences", "stats", "trainings"]:
            os.makedirs(os.path.join(self.data_dir, subdir), exist_ok=True)

    def _get_lock(self, key: str) -> threading.Lock:
        if key not in self._locks:
            self._locks[key] = threading.Lock()
        return self._locks[key]

    def _get_file_path(self, collection: str, item_id: str) -> str:
        return os.path.join(self.data_dir, collection, f"{item_id}.json")

    def _get_collection_path(self, collection: str) -> str:
        return os.path.join(self.data_dir, collection)

    def save(self, collection: str, item_id: str, data: Dict) -> bool:
        try:
            lock = self._get_lock(collection)
            with lock:
                file_path = self._get_file_path(collection, item_id)
                with open(file_path, 'w', encoding='utf-8') as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
            return True
        except Exception as e:
            print(f"Error saving to metadata store: {e}")
            return False

    def load(self, collection: str, item_id: str) -> Optional[Dict]:
        try:
            file_path = self._get_file_path(collection, item_id)
            if not os.path.exists(file_path):
                return None
            with open(file_path, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception as e:
            print(f"Error loading from metadata store: {e}")
            return None

    def delete(self, collection: str, item_id: str) -> bool:
        try:
            lock = self._get_lock(collection)
            with lock:
                file_path = self._get_file_path(collection, item_id)
                if os.path.exists(file_path):
                    os.remove(file_path)
            return True
        except Exception as e:
            print(f"Error deleting from metadata store: {e}")
            return False

    def list_all(self, collection: str) -> List[Dict]:
        try:
            collection_path = self._get_collection_path(collection)
            if not os.path.exists(collection_path):
                return []
            items = []
            for filename in os.listdir(collection_path):
                if filename.endswith('.json'):
                    file_path = os.path.join(collection_path, filename)
                    with open(file_path, 'r', encoding='utf-8') as f:
                        items.append(json.load(f))
            return items
        except Exception as e:
            print(f"Error listing collection: {e}")
            return []

    def list_by_field(self, collection: str, field: str, value: Any) -> List[Dict]:
        all_items = self.list_all(collection)
        return [item for item in all_items if item.get(field) == value]

    def exists(self, collection: str, item_id: str) -> bool:
        file_path = self._get_file_path(collection, item_id)
        return os.path.exists(file_path)

    def update(self, collection: str, item_id: str, updates: Dict) -> Optional[Dict]:
        existing = self.load(collection, item_id)
        if existing is None:
            return None
        existing.update(updates)
        if self.save(collection, item_id, existing):
            return existing
        return None


metadata_store = MetadataStore()
