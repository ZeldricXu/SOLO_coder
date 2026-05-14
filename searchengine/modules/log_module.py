import logging
import json
import os
import uuid
from typing import Dict, Any, Optional, List
from datetime import datetime
from searchengine.config.settings import settings
from searchengine.models.base import SearchLog


class LogModule:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self._logs: List[SearchLog] = []
        self._log_counter = 0
        self._max_logs = 10000
        self._load_from_file()
    
    def _generate_log_id(self) -> str:
        self._log_counter += 1
        return f"log_{self._log_counter:06d}"
    
    def _generate_request_id(self) -> str:
        return f"req_{uuid.uuid4().hex[:12]}"
    
    def log_search(self,
                   request_id: str,
                   user_id: Optional[str],
                   keyword: str,
                   result_count: int,
                   search_duration: int,
                   click_result: Optional[str] = None) -> SearchLog:
        log = SearchLog(
            log_id=self._generate_log_id(),
            request_id=request_id,
            user_id=user_id,
            keyword=keyword,
            result_count=result_count,
            click_result=click_result,
            search_time=datetime.utcnow(),
            search_duration=search_duration
        )
        
        self._logs.append(log)
        
        if len(self._logs) > self._max_logs:
            self._logs = self._logs[-self._max_logs // 2:]
        
        self._save_to_file()
        return log
    
    def log_click(self,
                  request_id: str,
                  result_content_id: str) -> Optional[SearchLog]:
        for log in reversed(self._logs):
            if log.request_id == request_id:
                log.click_result = result_content_id
                self._save_to_file()
                return log
        return None
    
    def get_logs_by_request(self, request_id: str) -> List[SearchLog]:
        return [log for log in self._logs if log.request_id == request_id]
    
    def get_logs_by_user(self, user_id: str, limit: int = 100) -> List[SearchLog]:
        user_logs = [log for log in self._logs if log.user_id == user_id]
        user_logs.sort(key=lambda x: x.search_time, reverse=True)
        return user_logs[:limit]
    
    def get_logs_by_keyword(self, keyword: str, limit: int = 100) -> List[SearchLog]:
        keyword_lower = keyword.lower()
        keyword_logs = [
            log for log in self._logs
            if keyword_lower in log.keyword.lower()
        ]
        keyword_logs.sort(key=lambda x: x.search_time, reverse=True)
        return keyword_logs[:limit]
    
    def get_logs_by_date_range(self,
                                start_date: datetime,
                                end_date: datetime,
                                limit: int = 1000) -> List[SearchLog]:
        filtered = [
            log for log in self._logs
            if start_date <= log.search_time <= end_date
        ]
        filtered.sort(key=lambda x: x.search_time, reverse=True)
        return filtered[:limit]
    
    def get_latest_logs(self, limit: int = 100) -> List[SearchLog]:
        logs = sorted(self._logs, key=lambda x: x.search_time, reverse=True)
        return logs[:limit]
    
    def get_log_count(self) -> int:
        return len(self._logs)
    
    def clear_old_logs(self, days_to_keep: int = 7) -> int:
        now = datetime.utcnow()
        cutoff = now.replace(day=now.day - days_to_keep) if now.day > days_to_keep else now
        
        original_count = len(self._logs)
        self._logs = [
            log for log in self._logs
            if log.search_time >= cutoff
        ]
        
        deleted_count = original_count - len(self._logs)
        self._save_to_file()
        self.logger.info(f"Cleared {deleted_count} old log entries")
        return deleted_count
    
    def clear_all_logs(self) -> None:
        self._logs.clear()
        self._save_to_file()
    
    def _save_to_file(self) -> None:
        try:
            data = [log.model_dump() for log in self._logs]
            with open(settings.LOG_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, default=str, indent=2, ensure_ascii=False)
        except Exception as e:
            self.logger.warning(f"Failed to save logs to file: {e}")
    
    def _load_from_file(self) -> None:
        if not os.path.exists(settings.LOG_FILE):
            return
        
        try:
            with open(settings.LOG_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            
            loaded_count = 0
            for log_data in data:
                try:
                    log = SearchLog(**log_data)
                    self._logs.append(log)
                    log_id = log_data.get("log_id", "")
                    if log_id.startswith("log_"):
                        try:
                            num = int(log_id.replace("log_", ""))
                            self._log_counter = max(self._log_counter, num)
                        except:
                            pass
                    loaded_count += 1
                except Exception as e:
                    self.logger.warning(f"Failed to load log entry: {e}")
            
            self.logger.info(f"Loaded {loaded_count} log entries from file")
        except Exception as e:
            self.logger.warning(f"Failed to load logs from file: {e}")


log_module = LogModule()
