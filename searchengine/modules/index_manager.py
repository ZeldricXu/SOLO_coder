import logging
import threading
import json
import hashlib
from typing import List, Optional, Dict, Any, Deque, Callable
from datetime import datetime
from collections import deque
from enum import Enum
from dataclasses import dataclass, field

from searchengine.config.settings import settings
from searchengine.models.base import SearchIndex, IndexUpdateRequest


class IndexUpdateError(Exception):
    pass


class IndexRollbackError(Exception):
    pass


class IndexStatus(Enum):
    DRAFT = "draft"
    ACTIVE = "active"
    ARCHIVED = "archived"
    ROLLBACK = "rollback"


@dataclass
class IndexVersion:
    version: int
    index: SearchIndex
    status: IndexStatus = IndexStatus.ACTIVE
    timestamp: datetime = field(default_factory=datetime.utcnow)
    checksum: str = ""
    
    def calculate_checksum(self) -> str:
        content = f"{self.index.title}|{self.index.content}|{self.index.keywords}|{self.index.category}|{self.index.author}"
        return hashlib.md5(content.encode('utf-8')).hexdigest()
    
    def __post_init__(self):
        if not self.checksum:
            self.checksum = self.calculate_checksum()


@dataclass
class IndexVersionMetadata:
    content_id: str
    current_version: int
    previous_version: Optional[int] = None
    update_count: int = 0
    last_rollback_time: Optional[datetime] = None
    rollback_count: int = 0


class IndexEventListener:
    def __init__(self):
        self._listeners: Dict[str, List[Callable]] = {}
    
    def add_listener(self, event_type: str, callback: Callable) -> None:
        if event_type not in self._listeners:
            self._listeners[event_type] = []
        self._listeners[event_type].append(callback)
    
    def emit_event(self, event_type: str, data: Any) -> None:
        for callback in self._listeners.get(event_type, []):
            try:
                callback(data)
            except Exception as e:
                logging.getLogger(__name__).error(f"Event listener error: {e}")
    
    def remove_listener(self, event_type: str, callback: Callable) -> bool:
        if event_type in self._listeners:
            try:
                self._listeners[event_type].remove(callback)
                return True
            except ValueError:
                return False
        return False


class IndexManager:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self.index_name = settings.ELASTICSEARCH_INDEX
        self._indexes: Dict[str, SearchIndex] = {}
        self._by_content_id: Dict[str, str] = {}
        self._index_counter = 0
        self._version_counters: Dict[str, int] = {}
        self._version_history: Dict[str, Deque[IndexVersion]] = {}
        self._version_metadata: Dict[str, IndexVersionMetadata] = {}
        self._max_versions = 10
        self._lock = threading.RLock()
        self._event_listener = IndexEventListener()
        self._update_stats: Dict[str, Any] = {
            "total_updates": 0,
            "successful_updates": 0,
            "failed_updates": 0,
            "rollbacks": 0
        }
    
    def _generate_index_id(self, content_id: str, version: int = 0) -> str:
        with self._lock:
            self._index_counter += 1
            if version > 0:
                return f"index_{content_id}_v{version}_{self._index_counter:03d}"
            return f"index_{content_id}_{self._index_counter:03d}"
    
    def _get_next_version(self, content_id: str) -> int:
        with self._lock:
            if content_id not in self._version_counters:
                self._version_counters[content_id] = 0
            self._version_counters[content_id] += 1
            return self._version_counters[content_id]
    
    def _get_or_create_metadata(self, content_id: str) -> IndexVersionMetadata:
        if content_id not in self._version_metadata:
            self._version_metadata[content_id] = IndexVersionMetadata(
                content_id=content_id,
                current_version=0,
                previous_version=None
            )
        return self._version_metadata[content_id]
    
    def _save_version(self, content_id: str, index: SearchIndex, status: IndexStatus = IndexStatus.ACTIVE) -> int:
        with self._lock:
            if content_id not in self._version_history:
                self._version_history[content_id] = deque(maxlen=self._max_versions)
            
            version = self._get_next_version(content_id)
            version_entry = IndexVersion(
                version=version,
                index=index.model_copy(),
                status=status
            )
            self._version_history[content_id].append(version_entry)
            
            metadata = self._get_or_create_metadata(content_id)
            metadata.previous_version = metadata.current_version
            metadata.current_version = version
            metadata.update_count += 1
            
            self.logger.info(
                f"Saved version {version} for content {content_id}, "
                f"checksum: {version_entry.checksum[:8]}"
            )
            
            return version
    
    def add_event_listener(self, event_type: str, callback: Callable) -> None:
        self._event_listener.add_listener(event_type, callback)
    
    def remove_event_listener(self, event_type: str, callback: Callable) -> bool:
        return self._event_listener.remove_listener(event_type, callback)
    
    def _emit_update_event(self, content_id: str, action: str, version: int) -> None:
        event_data = {
            "content_id": content_id,
            "action": action,
            "version": version,
            "timestamp": datetime.utcnow().isoformat()
        }
        self._event_listener.emit_event("index.updated", event_data)
        self.logger.debug(f"Emitted index update event: {event_data}")
    
    def get_version_history(self, content_id: str) -> List[IndexVersion]:
        with self._lock:
            if content_id in self._version_history:
                return list(self._version_history[content_id])
            return []
    
    def get_current_version(self, content_id: str) -> Optional[int]:
        with self._lock:
            metadata = self._version_metadata.get(content_id)
            if metadata:
                return metadata.current_version
            return None
    
    def get_version_metadata(self, content_id: str) -> Optional[IndexVersionMetadata]:
        with self._lock:
            return self._version_metadata.get(content_id)
    
    def get_version(self, content_id: str, version: int) -> Optional[IndexVersion]:
        with self._lock:
            if content_id not in self._version_history:
                return None
            
            for entry in self._version_history[content_id]:
                if entry.version == version:
                    return entry
            return None
    
    def rollback_to_version(self, content_id: str, target_version: int) -> bool:
        with self._lock:
            if content_id not in self._version_history:
                self.logger.warning(f"No version history found for {content_id}")
                return False
            
            history = self._version_history[content_id]
            target_entry = None
            
            for entry in history:
                if entry.version == target_version:
                    target_entry = entry
                    break
            
            if not target_entry:
                self.logger.warning(f"Target version {target_version} not found for {content_id}")
                return False
            
            current_index_id = self._by_content_id.get(content_id)
            if not current_index_id:
                self.logger.warning(f"No active index found for {content_id}")
                return False
            
            try:
                old_checksum = self._indexes[current_index_id].index_id if hasattr(self._indexes[current_index_id], 'index_id') else "unknown"
                self._indexes[current_index_id] = target_entry.index.model_copy()
                target_entry.status = IndexStatus.ACTIVE
                
                metadata = self._get_or_create_metadata(content_id)
                metadata.last_rollback_time = datetime.utcnow()
                metadata.rollback_count += 1
                metadata.previous_version = metadata.current_version
                metadata.current_version = target_version
                
                self._update_stats["rollbacks"] += 1
                
                self.logger.info(
                    f"Successfully rolled back {content_id} to version {target_version}. "
                    f"Checksum: {target_entry.checksum[:8]}"
                )
                
                self._emit_update_event(content_id, "rollback", target_version)
                
                return True
                
            except Exception as e:
                self.logger.error(f"Rollback failed for {content_id} to version {target_version}: {e}")
                raise IndexRollbackError(f"Rollback failed: {e}")
    
    def rollback_to_previous(self, content_id: str) -> bool:
        with self._lock:
            history = self.get_version_history(content_id)
            if len(history) >= 2:
                previous_version = history[-2].version
                return self.rollback_to_version(content_id, previous_version)
            self.logger.warning(f"Not enough history for rollback: {content_id}")
            return False
    
    def create_index(self, request: IndexUpdateRequest) -> SearchIndex:
        with self._lock:
            existing_index_id = self._by_content_id.get(request.content_id)
            if existing_index_id and existing_index_id in self._indexes:
                return self.update_index(request)
            
            version = self._get_next_version(request.content_id)
            index_id = self._generate_index_id(request.content_id, version)
            search_index = SearchIndex(
                index_id=index_id,
                content_id=request.content_id,
                content_type=request.content_type,
                title=request.title,
                content=request.content,
                keywords=request.keywords,
                category=request.category,
                author=request.author,
                publish_time=request.publish_time,
                click_count=0,
                index_time=datetime.utcnow()
            )
            
            self._indexes[index_id] = search_index
            self._by_content_id[request.content_id] = index_id
            
            self._save_version(request.content_id, search_index, IndexStatus.ACTIVE)
            self._update_stats["total_updates"] += 1
            self._update_stats["successful_updates"] += 1
            
            self.logger.info(f"Created index: {index_id} for content: {request.content_id}")
            self._emit_update_event(request.content_id, "create", 1)
            
            return search_index
    
    def update_index(self, request: IndexUpdateRequest) -> SearchIndex:
        with self._lock:
            existing_index_id = self._by_content_id.get(request.content_id)
            if not existing_index_id or existing_index_id not in self._indexes:
                return self.create_index(request)
            
            existing_index = self._indexes[existing_index_id]
            previous_version = self.get_current_version(request.content_id)
            
            self._update_stats["total_updates"] += 1
            
            try:
                updated_index = SearchIndex(
                    index_id=existing_index_id,
                    content_id=request.content_id,
                    content_type=request.content_type,
                    title=request.title,
                    content=request.content,
                    keywords=request.keywords,
                    category=request.category,
                    author=request.author,
                    publish_time=request.publish_time,
                    click_count=existing_index.click_count,
                    index_time=datetime.utcnow()
                )
                
                self._indexes[existing_index_id] = updated_index
                new_version = self._save_version(request.content_id, updated_index, IndexStatus.ACTIVE)
                
                self._update_stats["successful_updates"] += 1
                
                self.logger.info(
                    f"Updated index: {existing_index_id} for content: {request.content_id}, "
                    f"version: {previous_version} -> {new_version}"
                )
                
                self._emit_update_event(request.content_id, "update", new_version)
                
                return updated_index
                
            except Exception as e:
                self._update_stats["failed_updates"] += 1
                self.logger.error(f"Update failed for {request.content_id}, attempting rollback: {e}")
                
                if previous_version is not None:
                    try:
                        self.rollback_to_version(request.content_id, previous_version)
                        self.logger.info(f"Successfully rolled back to version {previous_version}")
                    except Exception as rollback_err:
                        self.logger.error(f"Rollback also failed: {rollback_err}")
                
                raise IndexUpdateError(f"Index update failed: {e}")
    
    def incremental_update(self, request: IndexUpdateRequest) -> SearchIndex:
        with self._lock:
            existing_index_id = self._by_content_id.get(request.content_id)
            if not existing_index_id or existing_index_id not in self._indexes:
                return self.create_index(request)
            
            existing_index = self._indexes[existing_index_id]
            previous_version = self.get_current_version(request.content_id)
            
            new_title = request.title if request.title else existing_index.title
            new_content = request.content if request.content else existing_index.content
            new_keywords = request.keywords if request.keywords else existing_index.keywords
            new_category = request.category if request.category else existing_index.category
            new_author = request.author if request.author else existing_index.author
            new_publish_time = request.publish_time if request.publish_time else existing_index.publish_time
            
            self._update_stats["total_updates"] += 1
            
            try:
                updated_index = SearchIndex(
                    index_id=existing_index_id,
                    content_id=request.content_id,
                    content_type=request.content_type,
                    title=new_title,
                    content=new_content,
                    keywords=new_keywords,
                    category=new_category,
                    author=new_author,
                    publish_time=new_publish_time,
                    click_count=existing_index.click_count,
                    index_time=datetime.utcnow()
                )
                
                self._indexes[existing_index_id] = updated_index
                new_version = self._save_version(request.content_id, updated_index, IndexStatus.ACTIVE)
                
                self._update_stats["successful_updates"] += 1
                
                self.logger.info(
                    f"Incrementally updated index: {existing_index_id}, "
                    f"version: {previous_version} -> {new_version}"
                )
                
                self._emit_update_event(request.content_id, "incremental", new_version)
                
                return updated_index
                
            except Exception as e:
                self._update_stats["failed_updates"] += 1
                self.logger.error(f"Incremental update failed, attempting rollback: {e}")
                
                if previous_version is not None:
                    try:
                        self.rollback_to_version(request.content_id, previous_version)
                    except Exception as rollback_err:
                        self.logger.error(f"Rollback failed: {rollback_err}")
                
                raise IndexUpdateError(f"Incremental update failed: {e}")
    
    def safe_update(self, request: IndexUpdateRequest) -> SearchIndex:
        return self.update_index(request)
    
    def delete_index(self, content_id: str) -> bool:
        with self._lock:
            index_id = self._by_content_id.get(content_id)
            if index_id and index_id in self._indexes:
                deleted_index = self._indexes[index_id]
                
                if content_id in self._version_history:
                    for entry in self._version_history[content_id]:
                        entry.status = IndexStatus.ARCHIVED
                
                del self._indexes[index_id]
                del self._by_content_id[content_id]
                
                self.logger.info(f"Deleted index: {index_id} for content: {content_id}")
                self._emit_update_event(content_id, "delete", 0)
                
                return True
            return False
    
    def get_index(self, index_id: str) -> Optional[SearchIndex]:
        with self._lock:
            if index_id in self._indexes:
                return self._indexes[index_id].model_copy()
            return None
    
    def get_index_by_content_id(self, content_id: str) -> Optional[SearchIndex]:
        with self._lock:
            index_id = self._by_content_id.get(content_id)
            if index_id and index_id in self._indexes:
                return self._indexes[index_id].model_copy()
            return None
    
    def get_index_by_version(self, content_id: str, version: int) -> Optional[SearchIndex]:
        with self._lock:
            version_entry = self.get_version(content_id, version)
            if version_entry:
                return version_entry.index.model_copy()
            return None
    
    def get_all_indexes(self) -> List[SearchIndex]:
        with self._lock:
            return [idx.model_copy() for idx in self._indexes.values()]
    
    def increment_click_count(self, content_id: str) -> int:
        with self._lock:
            index_id = self._by_content_id.get(content_id)
            if index_id and index_id in self._indexes:
                self._indexes[index_id].click_count += 1
                return self._indexes[index_id].click_count
            return 0
    
    def search_indexes(self, keyword: str, filters: Dict[str, Any] = None) -> List[SearchIndex]:
        with self._lock:
            keyword_lower = keyword.lower()
            results = []
            filters = filters or {}
            
            for index in self._indexes.values():
                match_filter = True
                for key, value in filters.items():
                    if hasattr(index, key):
                        index_value = getattr(index, key)
                        if index_value != value:
                            match_filter = False
                            break
                    else:
                        match_filter = False
                        break
                
                if not match_filter:
                    continue
                
                title_match = keyword_lower in index.title.lower()
                content_match = keyword_lower in index.content.lower()
                keyword_match = any(keyword_lower in kw.lower() for kw in index.keywords)
                
                if title_match or content_match or keyword_match:
                    results.append(index.model_copy())
            
            return results
    
    def get_index_count(self) -> int:
        with self._lock:
            return len(self._indexes)
    
    def get_update_stats(self) -> Dict[str, Any]:
        with self._lock:
            stats = self._update_stats.copy()
            if stats["total_updates"] > 0:
                stats["success_rate"] = stats["successful_updates"] / stats["total_updates"]
            else:
                stats["success_rate"] = 1.0
            return stats
    
    def clear_all_indexes(self) -> None:
        with self._lock:
            self._indexes.clear()
            self._by_content_id.clear()
            self._index_counter = 0
            self._version_counters.clear()
            self._version_history.clear()
            self._version_metadata.clear()
            self._update_stats = {
                "total_updates": 0,
                "successful_updates": 0,
                "failed_updates": 0,
                "rollbacks": 0
            }
    
    def set_max_versions(self, max_versions: int) -> None:
        with self._lock:
            self._max_versions = max_versions
            for content_id, history in self._version_history.items():
                new_history = deque(maxlen=max_versions)
                for entry in list(history)[-max_versions:]:
                    new_history.append(entry)
                self._version_history[content_id] = new_history
    
    def compare_versions(self, content_id: str, version1: int, version2: int) -> Dict[str, Any]:
        with self._lock:
            v1 = self.get_version(content_id, version1)
            v2 = self.get_version(content_id, version2)
            
            if not v1 or not v2:
                return {"error": "One or both versions not found"}
            
            changes = {
                "title_changed": v1.index.title != v2.index.title,
                "content_changed": v1.index.content != v2.index.content,
                "keywords_changed": v1.index.keywords != v2.index.keywords,
                "category_changed": v1.index.category != v2.index.category,
                "author_changed": v1.index.author != v2.index.author,
                "checksum_v1": v1.checksum,
                "checksum_v2": v2.checksum,
                "checksums_match": v1.checksum == v2.checksum
            }
            
            return changes
    
    def export_version_history(self, content_id: str) -> Optional[str]:
        with self._lock:
            history = self.get_version_history(content_id)
            if not history:
                return None
            
            export_data = []
            for entry in history:
                export_data.append({
                    "version": entry.version,
                    "status": entry.status.value,
                    "timestamp": entry.timestamp.isoformat(),
                    "checksum": entry.checksum,
                    "content_id": entry.index.content_id,
                    "title": entry.index.title,
                    "keywords": entry.index.keywords
                })
            
            return json.dumps(export_data, indent=2, ensure_ascii=False)


index_manager = IndexManager()
