import json
import logging
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Optional, Dict, Any
from collections import OrderedDict

from app.models.alert import AlertEvent, AlertStatus, AlertSeverity

logger = logging.getLogger(__name__)


class AlertHistoryManager:
    def __init__(
        self,
        storage_file: Optional[Path] = None,
        max_records: int = 10000,
        retention_days: int = 30
    ):
        self.storage_file = storage_file or Path("alert_history.json")
        self.max_records = max_records
        self.retention_days = retention_days
        
        self._alerts: OrderedDict[str, AlertEvent] = OrderedDict()
        
        if self.storage_file.exists():
            self._load_from_file()
        
        logger.info(f"AlertHistoryManager initialized with {len(self._alerts)} records")
    
    def _load_from_file(self):
        try:
            with open(self.storage_file, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            alerts_data = data.get('alerts', [])
            for alert_dict in alerts_data:
                try:
                    alert = AlertEvent.from_dict(alert_dict)
                    self._alerts[alert.alert_id] = alert
                except Exception as e:
                    logger.error(f"Failed to load alert from JSON: {e}")
            
            logger.info(f"Loaded {len(self._alerts)} alerts from {self.storage_file}")
        except Exception as e:
            logger.error(f"Failed to load alerts from file: {e}")
    
    def _save_to_file(self):
        try:
            self._cleanup_old_records()
            
            self.storage_file.parent.mkdir(parents=True, exist_ok=True)
            
            data = {
                'alerts': [alert.to_dict() for alert in self._alerts.values()],
                'total_count': len(self._alerts),
                'updated_at': datetime.utcnow().isoformat()
            }
            
            with open(self.storage_file, 'w', encoding='utf-8') as f:
                json.dump(data, f, indent=2, default=str)
            
            logger.debug(f"Saved {len(self._alerts)} alerts to {self.storage_file}")
        except Exception as e:
            logger.error(f"Failed to save alerts to file: {e}")
    
    def _cleanup_old_records(self):
        cutoff_time = datetime.utcnow() - timedelta(days=self.retention_days)
        
        to_remove = []
        for alert_id, alert in self._alerts.items():
            if alert.triggered_at and alert.triggered_at < cutoff_time:
                to_remove.append(alert_id)
        
        for alert_id in to_remove:
            del self._alerts[alert_id]
        
        while len(self._alerts) > self.max_records:
            oldest_key = next(iter(self._alerts))
            del self._alerts[oldest_key]
        
        if to_remove:
            logger.debug(f"Cleaned up {len(to_remove)} old alerts")
    
    def save_alert(self, alert: AlertEvent) -> bool:
        try:
            self._alerts[alert.alert_id] = alert
            self._save_to_file()
            logger.debug(f"Saved alert: {alert.alert_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to save alert {alert.alert_id}: {e}")
            return False
    
    def update_alert(self, alert: AlertEvent) -> bool:
        if alert.alert_id not in self._alerts:
            logger.warning(f"Alert {alert.alert_id} not found for update")
            return False
        
        try:
            self._alerts[alert.alert_id] = alert
            self._save_to_file()
            logger.debug(f"Updated alert: {alert.alert_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to update alert {alert.alert_id}: {e}")
            return False
    
    def get_alert(self, alert_id: str) -> Optional[AlertEvent]:
        return self._alerts.get(alert_id)
    
    def query_alerts(
        self,
        server_id: Optional[str] = None,
        rule_id: Optional[str] = None,
        metric_type: Optional[str] = None,
        status: Optional[str] = None,
        severity: Optional[str] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        limit: int = 100,
        offset: int = 0
    ) -> List[AlertEvent]:
        results = []
        
        for alert in self._alerts.values():
            if server_id and alert.server_id != server_id:
                continue
            if rule_id and alert.rule_id != rule_id:
                continue
            if metric_type and alert.metric_type != metric_type:
                continue
            if status:
                alert_status = alert.status.value if hasattr(alert.status, 'value') else alert.status
                if alert_status != status:
                    continue
            if severity:
                alert_severity = alert.severity.value if hasattr(alert.severity, 'value') else alert.severity
                if alert_severity != severity:
                    continue
            
            if start_time and alert.triggered_at:
                if alert.triggered_at < start_time:
                    continue
            if end_time and alert.triggered_at:
                if alert.triggered_at > end_time:
                    continue
            
            results.append(alert)
        
        results.sort(key=lambda a: a.triggered_at or datetime.min, reverse=True)
        
        return results[offset:offset + limit]
    
    def get_statistics(
        self,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None
    ) -> Dict[str, Any]:
        if start_time is None:
            start_time = datetime.utcnow() - timedelta(days=30)
        if end_time is None:
            end_time = datetime.utcnow()
        
        stats = {
            "total_alerts": 0,
            "triggered": 0,
            "resolved": 0,
            "acknowledged": 0,
            "by_severity": {
                "info": 0,
                "warning": 0,
                "critical": 0
            },
            "by_metric_type": {},
            "by_server": {},
            "time_range": {
                "start": start_time.isoformat(),
                "end": end_time.isoformat()
            }
        }
        
        for alert in self._alerts.values():
            if alert.triggered_at:
                if not (start_time <= alert.triggered_at <= end_time):
                    continue
            
            stats["total_alerts"] += 1
            
            status_val = alert.status.value if hasattr(alert.status, 'value') else alert.status
            if status_val == "triggered":
                stats["triggered"] += 1
            elif status_val == "resolved":
                stats["resolved"] += 1
            elif status_val == "acknowledged":
                stats["acknowledged"] += 1
            
            severity_val = alert.severity.value if hasattr(alert.severity, 'value') else alert.severity
            if severity_val in stats["by_severity"]:
                stats["by_severity"][severity_val] += 1
            
            if alert.metric_type not in stats["by_metric_type"]:
                stats["by_metric_type"][alert.metric_type] = 0
            stats["by_metric_type"][alert.metric_type] += 1
            
            if alert.server_id not in stats["by_server"]:
                stats["by_server"][alert.server_id] = 0
            stats["by_server"][alert.server_id] += 1
        
        return stats
    
    def acknowledge_alert(self, alert_id: str) -> Optional[AlertEvent]:
        alert = self._alerts.get(alert_id)
        if not alert:
            return None
        
        alert.status = AlertStatus.ACKNOWLEDGED
        alert.acknowledged_at = datetime.utcnow()
        
        self._save_to_file()
        logger.info(f"Acknowledged alert: {alert_id}")
        return alert
    
    def delete_alert(self, alert_id: str) -> bool:
        if alert_id not in self._alerts:
            return False
        
        del self._alerts[alert_id]
        self._save_to_file()
        logger.info(f"Deleted alert: {alert_id}")
        return True
    
    def get_active_alerts(self) -> List[AlertEvent]:
        return [
            alert for alert in self._alerts.values()
            if (alert.status.value if hasattr(alert.status, 'value') else alert.status) == "triggered"
        ]
    
    def clear_all(self) -> int:
        count = len(self._alerts)
        self._alerts.clear()
        self._save_to_file()
        logger.info(f"Cleared all {count} alerts")
        return count
