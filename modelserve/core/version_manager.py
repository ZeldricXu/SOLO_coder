from typing import Dict, List, Optional
from datetime import datetime
import os
import hashlib
import difflib
import json

from .models import ModelVersion, generate_id, VersionDiffReport
from ..storage import metadata_store, file_store
from .model_manager import model_manager
from .monitoring_manager import monitoring_manager


class FileDiffResult:
    def __init__(self):
        self.added: List[Dict] = []
        self.removed: List[Dict] = []
        self.modified: List[Dict] = []
        self.unchanged: List[Dict] = []
        self.renamed: List[Dict] = []

    def to_dict(self) -> Dict:
        return {
            "added": self.added,
            "removed": self.removed,
            "modified": self.modified,
            "unchanged": self.unchanged,
            "renamed": self.renamed,
            "summary": {
                "added_count": len(self.added),
                "removed_count": len(self.removed),
                "modified_count": len(self.modified),
                "unchanged_count": len(self.unchanged),
                "renamed_count": len(self.renamed),
                "total_changes": len(self.added) + len(self.removed) + len(self.modified) + len(self.renamed)
            }
        }


class VersionManager:
    def __init__(self):
        self.collection = "versions"

    def create_version(
        self,
        model_id: str,
        version: str,
        model_file: str,
        model_size: int,
        training_params: Optional[Dict] = None,
        accuracy: Optional[float] = None,
        checksum: str = "",
        notes: str = ""
    ) -> Optional[ModelVersion]:
        if not model_manager.model_exists(model_id):
            print(f"Model {model_id} does not exist")
            return None

        existing_versions = self.get_model_versions(model_id)
        for v in existing_versions:
            if v.version == version:
                print(f"Version {version} already exists for model {model_id}")
                return None

        version_id = generate_id("ver")

        model_version = ModelVersion(
            version_id=version_id,
            model_id=model_id,
            version=version,
            model_file=model_file,
            model_size=model_size,
            training_params=training_params if training_params else {},
            accuracy=accuracy,
            checksum=checksum,
            notes=notes
        )

        if metadata_store.save(self.collection, version_id, model_version.to_dict()):
            model_manager.update_current_version(model_id, version)
            return model_version
        return None

    def get_version(self, version_id: str) -> Optional[ModelVersion]:
        data = metadata_store.load(self.collection, version_id)
        if data:
            return ModelVersion.from_dict(data)
        return None

    def get_version_by_model_and_version(self, model_id: str, version: str) -> Optional[ModelVersion]:
        versions = self.get_model_versions(model_id)
        for v in versions:
            if v.version == version:
                return v
        return None

    def get_model_versions(self, model_id: str) -> List[ModelVersion]:
        versions_data = metadata_store.list_by_field(self.collection, "model_id", model_id)
        return [ModelVersion.from_dict(v) for v in versions_data]

    def update_version(self, version_id: str, updates: Dict) -> Optional[ModelVersion]:
        allowed_fields = ["training_params", "accuracy", "checksum", "notes", "additional_files", "model_metrics"]
        filtered_updates = {k: v for k, v in updates.items() if k in allowed_fields}

        if not filtered_updates:
            return self.get_version(version_id)

        updated = metadata_store.update(self.collection, version_id, filtered_updates)
        if updated:
            return ModelVersion.from_dict(updated)
        return None

    def delete_version(self, version_id: str) -> bool:
        version = self.get_version(version_id)
        if not version:
            return False

        success = metadata_store.delete(self.collection, version_id)
        if success:
            versions = self.get_model_versions(version.model_id)
            if versions:
                latest_version = sorted(versions, key=lambda v: v.created_at, reverse=True)[0]
                model_manager.update_current_version(version.model_id, latest_version.version)
            else:
                model_manager.update_current_version(version.model_id, "")

        return success

    def _calculate_file_checksum(self, file_path: str) -> str:
        if not os.path.exists(file_path):
            return ""
        try:
            sha256_hash = hashlib.sha256()
            with open(file_path, "rb") as f:
                for byte_block in iter(lambda: f.read(8192), b""):
                    sha256_hash.update(byte_block)
            return sha256_hash.hexdigest()
        except Exception:
            return ""

    def _get_file_info(self, model_id: str, version: str, filename: str) -> Optional[Dict]:
        file_path = file_store.get_model_file_path(model_id, version, filename)
        if not file_path or not os.path.exists(file_path):
            return None

        try:
            stat = os.stat(file_path)
            return {
                "filename": filename,
                "size": stat.st_size,
                "modified_time": datetime.fromtimestamp(stat.st_mtime).isoformat() + "Z",
                "checksum": self._calculate_file_checksum(file_path),
                "extension": os.path.splitext(filename)[1].lower()
            }
        except Exception:
            return None

    def _get_all_files_for_version(self, model_id: str, version: str) -> Dict[str, Dict]:
        files = {}

        main_files = file_store.list_version_files(model_id, version)
        for filename in main_files:
            file_info = self._get_file_info(model_id, version, filename)
            if file_info:
                files[filename] = file_info

        return files

    def _compare_files(
        self,
        v1_files: Dict[str, Dict],
        v2_files: Dict[str, Dict]
    ) -> FileDiffResult:
        result = FileDiffResult()

        all_filenames = set(v1_files.keys()).union(set(v2_files.keys()))

        for filename in all_filenames:
            in_v1 = filename in v1_files
            in_v2 = filename in v2_files

            if not in_v1 and in_v2:
                result.added.append(v2_files[filename])
            elif in_v1 and not in_v2:
                result.removed.append(v1_files[filename])
            else:
                file1 = v1_files[filename]
                file2 = v2_files[filename]

                if file1.get("checksum") == file2.get("checksum"):
                    result.unchanged.append({
                        "filename": filename,
                        "size_v1": file1.get("size"),
                        "size_v2": file2.get("size"),
                        "checksum": file1.get("checksum")
                    })
                else:
                    result.modified.append({
                        "filename": filename,
                        "size_v1": file1.get("size"),
                        "size_v2": file2.get("size"),
                        "size_change": file2.get("size", 0) - file1.get("size", 0),
                        "checksum_v1": file1.get("checksum"),
                        "checksum_v2": file2.get("checksum"),
                        "extension": file1.get("extension")
                    })

        return result

    def _compare_params(
        self,
        v1_params: Dict,
        v2_params: Dict
    ) -> Dict:
        if not v1_params:
            v1_params = {}
        if not v2_params:
            v2_params = {}

        differences = {
            "added": {},
            "removed": {},
            "modified": {},
            "unchanged": {}
        }

        all_keys = set(v1_params.keys()).union(set(v2_params.keys()))

        for key in all_keys:
            in_v1 = key in v1_params
            in_v2 = key in v2_params

            if not in_v1 and in_v2:
                differences["added"][key] = v2_params[key]
            elif in_v1 and not in_v2:
                differences["removed"][key] = v1_params[key]
            else:
                val1 = v1_params[key]
                val2 = v2_params[key]

                if isinstance(val1, dict) and isinstance(val2, dict):
                    nested_diff = self._compare_params(val1, val2)
                    if (nested_diff["added"] or nested_diff["removed"] or
                            nested_diff["modified"]):
                        differences["modified"][key] = {
                            "old": val1,
                            "new": val2,
                            "nested_changes": nested_diff
                        }
                    else:
                        differences["unchanged"][key] = val1
                elif val1 != val2:
                    differences["modified"][key] = {
                        "old": val1,
                        "new": val2,
                        "change": self._calculate_param_change(val1, val2)
                    }
                else:
                    differences["unchanged"][key] = val1

        return differences

    def _calculate_param_change(self, old_val, new_val) -> Optional[Dict]:
        try:
            if isinstance(old_val, (int, float)) and isinstance(new_val, (int, float)):
                absolute_change = new_val - old_val
                relative_change = (absolute_change / old_val * 100) if old_val != 0 else 0
                return {
                    "type": "numeric",
                    "absolute_change": absolute_change,
                    "relative_change_percent": round(relative_change, 2),
                    "direction": "increased" if absolute_change > 0 else "decreased"
                }
            elif isinstance(old_val, bool) and isinstance(new_val, bool):
                return {
                    "type": "boolean",
                    "old_value": old_val,
                    "new_value": new_val
                }
            else:
                return {
                    "type": "other",
                    "old_type": type(old_val).__name__,
                    "new_type": type(new_val).__name__
                }
        except Exception:
            return None

    def _compare_performance(
        self,
        v1: ModelVersion,
        v2: ModelVersion,
        model_id: str
    ) -> Dict:
        performance = {
            "version1": {
                "version": v1.version,
                "accuracy": v1.accuracy,
                "model_size": v1.model_size,
                "model_metrics": v1.model_metrics or {}
            },
            "version2": {
                "version": v2.version,
                "accuracy": v2.accuracy,
                "model_size": v2.model_size,
                "model_metrics": v2.model_metrics or {}
            }
        }

        changes = {}

        if v1.accuracy is not None and v2.accuracy is not None:
            acc_change = v2.accuracy - v1.accuracy
            acc_change_pct = (acc_change / v1.accuracy * 100) if v1.accuracy > 0 else 0
            changes["accuracy"] = {
                "old": v1.accuracy,
                "new": v2.accuracy,
                "absolute_change": acc_change,
                "relative_change_percent": round(acc_change_pct, 2),
                "improved": acc_change > 0
            }

        size_change = v2.model_size - v1.model_size
        size_change_pct = (size_change / v1.model_size * 100) if v1.model_size > 0 else 0
        changes["model_size"] = {
            "old": v1.model_size,
            "new": v2.model_size,
            "absolute_change": size_change,
            "relative_change_percent": round(size_change_pct, 2),
            "human_readable": {
                "old": self._human_readable_size(v1.model_size),
                "new": self._human_readable_size(v2.model_size),
                "change": self._human_readable_size(size_change)
            }
        }

        metrics1 = v1.model_metrics or {}
        metrics2 = v2.model_metrics or {}
        all_metric_keys = set(metrics1.keys()).union(set(metrics2.keys()))

        metric_changes = {}
        for key in all_metric_keys:
            val1 = metrics1.get(key)
            val2 = metrics2.get(key)

            if val1 is not None and val2 is not None:
                if isinstance(val1, (int, float)) and isinstance(val2, (int, float)):
                    change = val2 - val1
                    change_pct = (change / val1 * 100) if val1 != 0 else 0
                    metric_changes[key] = {
                        "old": val1,
                        "new": val2,
                        "absolute_change": change,
                        "relative_change_percent": round(change_pct, 2)
                    }
                else:
                    metric_changes[key] = {
                        "old": val1,
                        "new": val2,
                        "type_changed": type(val1).__name__ != type(val2).__name__
                    }
            else:
                metric_changes[key] = {
                    "old": val1,
                    "new": val2,
                    "status": "added" if val1 is None else "removed"
                }

        if metric_changes:
            changes["model_metrics"] = metric_changes

        monitoring_stats = self._get_monitoring_performance_comparison(
            model_id, v1.version, v2.version
        )
        if monitoring_stats:
            changes["inference_performance"] = monitoring_stats

        performance["changes"] = changes
        performance["summary"] = self._generate_performance_summary(changes)

        return performance

    def _get_monitoring_performance_comparison(
        self,
        model_id: str,
        version1: str,
        version2: str
    ) -> Optional[Dict]:
        try:
            today = datetime.now().strftime("%Y-%m-%d")

            v1_stats = monitoring_manager.get_stats(model_id, today)
            if not v1_stats:
                return None

            return {
                "current_stats": {
                    "request_count": v1_stats.request_count,
                    "avg_latency_ms": v1_stats.avg_latency,
                    "p50_latency_ms": v1_stats.p50_latency,
                    "p95_latency_ms": v1_stats.p95_latency,
                    "p99_latency_ms": v1_stats.p99_latency,
                    "throughput": v1_stats.throughput,
                    "error_rate": v1_stats.error_count / v1_stats.request_count if v1_stats.request_count > 0 else 0
                }
            }
        except Exception:
            return None

    def _generate_performance_summary(self, changes: Dict) -> Dict:
        improved = 0
        degraded = 0
        unchanged = 0

        if "accuracy" in changes:
            if changes["accuracy"]["improved"]:
                improved += 1
            else:
                degraded += 1

        if "model_size" in changes:
            if changes["model_size"]["absolute_change"] < 0:
                improved += 1
            elif changes["model_size"]["absolute_change"] > 0:
                degraded += 1
            else:
                unchanged += 1

        return {
            "improved_count": improved,
            "degraded_count": degraded,
            "unchanged_count": unchanged,
            "overall": "improved" if improved > degraded else "degraded" if degraded > improved else "unchanged"
        }

    def _human_readable_size(self, size_bytes: int) -> str:
        if size_bytes < 0:
            return f"-{self._human_readable_size(-size_bytes)}"
        for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
            if size_bytes < 1024:
                return f"{size_bytes:.2f} {unit}"
            size_bytes /= 1024
        return f"{size_bytes:.2f} PB"

    def compare_versions(
        self,
        model_id: str,
        version1: str,
        version2: str
    ) -> Optional[Dict]:
        v1 = self.get_version_by_model_and_version(model_id, version1)
        v2 = self.get_version_by_model_and_version(model_id, version2)

        if not v1 or not v2:
            return None

        comparison = {
            "model_id": model_id,
            "version1": {
                "version": v1.version,
                "version_id": v1.version_id,
                "accuracy": v1.accuracy,
                "model_size": v1.model_size,
                "training_params": v1.training_params,
                "created_at": v1.created_at.isoformat() + "Z",
                "model_file": v1.model_file,
                "notes": v1.notes
            },
            "version2": {
                "version": v2.version,
                "version_id": v2.version_id,
                "accuracy": v2.accuracy,
                "model_size": v2.model_size,
                "training_params": v2.training_params,
                "created_at": v2.created_at.isoformat() + "Z",
                "model_file": v2.model_file,
                "notes": v2.notes
            },
            "differences": self._find_differences(v1, v2)
        }

        return comparison

    def _find_differences(self, v1: ModelVersion, v2: ModelVersion) -> Dict:
        differences = {}

        if v1.accuracy != v2.accuracy:
            differences["accuracy"] = {
                "version1": v1.accuracy,
                "version2": v2.accuracy,
                "change": v2.accuracy - v1.accuracy if v1.accuracy and v2.accuracy else None
            }

        if v1.model_size != v2.model_size:
            differences["model_size"] = {
                "version1": v1.model_size,
                "version2": v2.model_size,
                "change": v2.model_size - v1.model_size
            }

        if v1.training_params != v2.training_params:
            param_diff = {}
            all_keys = set(v1.training_params.keys()).union(set(v2.training_params.keys()))
            for key in all_keys:
                val1 = v1.training_params.get(key)
                val2 = v2.training_params.get(key)
                if val1 != val2:
                    param_diff[key] = {"version1": val1, "version2": val2}
            if param_diff:
                differences["training_params"] = param_diff

        return differences

    def generate_diff_report(
        self,
        model_id: str,
        version1: str,
        version2: str
    ) -> Optional[VersionDiffReport]:
        v1 = self.get_version_by_model_and_version(model_id, version1)
        v2 = self.get_version_by_model_and_version(model_id, version2)

        if not v1 or not v2:
            return None

        v1_files = self._get_all_files_for_version(model_id, version1)
        v2_files = self._get_all_files_for_version(model_id, version2)

        file_diff = self._compare_files(v1_files, v2_files)

        param_changes = self._compare_params(v1.training_params, v2.training_params)

        performance_changes = self._compare_performance(v1, v2, model_id)

        training_param_changes = self._compare_params(
            v1.training_params if v1.training_params else {},
            v2.training_params if v2.training_params else {}
        )

        report = VersionDiffReport(
            model_id=model_id,
            version1=version1,
            version2=version2,
            file_changes=file_diff.to_dict(),
            param_changes=param_changes,
            performance_changes=performance_changes,
            training_param_changes=training_param_changes
        )

        return report

    def get_diff_report_as_dict(
        self,
        model_id: str,
        version1: str,
        version2: str
    ) -> Optional[Dict]:
        report = self.generate_diff_report(model_id, version1, version2)
        if report:
            return report.to_dict()
        return None

    def get_latest_version(self, model_id: str) -> Optional[ModelVersion]:
        versions = self.get_model_versions(model_id)
        if not versions:
            return None
        return sorted(versions, key=lambda v: v.created_at, reverse=True)[0]

    def list_all_versions(self) -> List[ModelVersion]:
        versions_data = metadata_store.list_all(self.collection)
        return [ModelVersion.from_dict(v) for v in versions_data]

    def version_exists(self, version_id: str) -> bool:
        return metadata_store.exists(self.collection, version_id)


version_manager = VersionManager()
