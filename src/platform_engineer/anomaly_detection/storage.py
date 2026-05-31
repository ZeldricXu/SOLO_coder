import json
import os
from datetime import datetime
from typing import Any, Dict, List, Optional

from .detector import AnomalyResult, BaselineProfile


class AnomalyStorage:
    def __init__(self, storage_dir: str = "./anomalies", max_files: int = 10000, logger=None):
        self._storage_dir = storage_dir
        self._max_files = max_files
        self._logger = logger
        self._anomalies_dir = os.path.join(storage_dir, "anomalies")
        self._baselines_dir = os.path.join(storage_dir, "baselines")
        os.makedirs(self._anomalies_dir, exist_ok=True)
        os.makedirs(self._baselines_dir, exist_ok=True)

    def save_anomaly(self, result: AnomalyResult) -> str:
        date_str = result.timestamp.strftime("%Y%m%d")
        day_dir = os.path.join(self._anomalies_dir, date_str)
        os.makedirs(day_dir, exist_ok=True)
        filename = f"{result.metric_name}_{result.timestamp.strftime('%H%M%S')}.json"
        file_path = os.path.join(day_dir, filename)
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(result.to_dict(), f, indent=2)
        if self._logger:
            self._logger.info(f"Saved anomaly: {result.metric_name} score={result.score}")
        return file_path

    def save_baseline(self, baseline: BaselineProfile) -> str:
        filename = f"{baseline.metric_name}.json"
        file_path = os.path.join(self._baselines_dir, filename)
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(baseline.to_dict(), f, indent=2)
        if self._logger:
            self._logger.info(f"Saved baseline: {baseline.metric_name}")
        return file_path

    def load_baseline(self, metric_name: str) -> Optional[BaselineProfile]:
        filename = f"{metric_name}.json"
        file_path = os.path.join(self._baselines_dir, filename)
        if not os.path.exists(file_path):
            return None
        with open(file_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return BaselineProfile(
            metric_name=data["metric_name"],
            algorithm=data["algorithm"],
            created_at=datetime.fromisoformat(data["created_at"]),
            updated_at=datetime.fromisoformat(data["updated_at"]),
            baseline_data=data.get("baseline_data", {}),
            sample_count=data.get("sample_count", 0),
        )

    def list_anomalies(
        self,
        metric_name: Optional[str] = None,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        limit: int = 100,
    ) -> List[Dict[str, Any]]:
        anomalies = []
        for date_dir in sorted(os.listdir(self._anomalies_dir)):
            if not os.path.isdir(os.path.join(self._anomalies_dir, date_dir)):
                continue
            try:
                date_val = datetime.strptime(date_dir, "%Y%m%d")
                if start_date and date_val.date() < start_date.date():
                    continue
                if end_date and date_val.date() > end_date.date():
                    continue
            except ValueError:
                continue
            day_path = os.path.join(self._anomalies_dir, date_dir)
            for filename in sorted(os.listdir(day_path)):
                if not filename.endswith(".json"):
                    continue
                if metric_name and not filename.startswith(metric_name):
                    continue
                file_path = os.path.join(day_path, filename)
                with open(file_path, "r", encoding="utf-8") as f:
                    anomalies.append(json.load(f))
                if len(anomalies) >= limit:
                    return anomalies
        return anomalies

    def get_stats(self) -> Dict[str, Any]:
        total_anomaly_files = 0
        for date_dir in os.listdir(self._anomalies_dir):
            day_path = os.path.join(self._anomalies_dir, date_dir)
            if os.path.isdir(day_path):
                total_anomaly_files += len([f for f in os.listdir(day_path) if f.endswith(".json")])
        baseline_count = len([f for f in os.listdir(self._baselines_dir) if f.endswith(".json")])
        return {
            "storage_dir": self._storage_dir,
            "anomaly_files": total_anomaly_files,
            "baseline_files": baseline_count,
        }
