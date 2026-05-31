"""
Log storage for in-memory log querying and retention.
"""

from datetime import datetime, timedelta
from typing import Dict, List, Optional

from app.models import LogEntry


class LogStorage:
    def __init__(self):
        self._entries: Dict[str, LogEntry] = {}
    
    def store(self, entry: LogEntry):
        self._entries[entry.timestamp.isoformat()] = entry
    
    def query_logs(
        self,
        level: Optional[str] = None,
        module: Optional[str] = None,
        since: Optional[datetime] = None,
        limit: int = 100
    ) -> List[LogEntry]:
        entries = list(self._entries.values())
        
        if level:
            entries = [e for e in entries if e.level.upper() == level.upper()]
        if module:
            entries = [e for e in entries if e.module == module]
        if since:
            entries = [e for e in entries if e.timestamp >= since]
        
        entries.sort(key=lambda x: x.timestamp, reverse=True)
        return entries[:limit]
    
    def cleanup_old_logs(self, retention_days: int = 7):
        cutoff = datetime.utcnow() - timedelta(days=retention_days)
        keys_to_remove = [
            k for k, v in self._entries.items()
            if v.timestamp < cutoff
        ]
        for k in keys_to_remove:
            del self._entries[k]
