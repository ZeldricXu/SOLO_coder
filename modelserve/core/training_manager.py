from typing import Dict, List, Optional
from datetime import datetime

from .models import TrainingRecord, generate_id
from ..storage import metadata_store
from .model_manager import model_manager
from .version_manager import version_manager


class TrainingManager:
    def __init__(self):
        self.collection = "trainings"

    def start_training(
        self,
        model_id: str,
        version_id: str,
        training_params: Optional[Dict] = None,
        dataset_info: Optional[Dict] = None
    ) -> Optional[TrainingRecord]:
        if not model_manager.model_exists(model_id):
            print(f"Model {model_id} does not exist")
            return None

        if not version_manager.version_exists(version_id):
            print(f"Version {version_id} does not exist")
            return None

        training_id = generate_id("train")

        training_record = TrainingRecord(
            training_id=training_id,
            model_id=model_id,
            version_id=version_id,
            training_params=training_params if training_params else {},
            dataset_info=dataset_info if dataset_info else {},
            status="running"
        )

        if metadata_store.save(self.collection, training_id, training_record.to_dict()):
            return training_record
        return None

    def get_training(self, training_id: str) -> Optional[TrainingRecord]:
        data = metadata_store.load(self.collection, training_id)
        if data:
            return TrainingRecord.from_dict(data)
        return None

    def complete_training(
        self,
        training_id: str,
        training_metrics: Dict,
        training_time: float
    ) -> Optional[TrainingRecord]:
        training = self.get_training(training_id)
        if not training:
            return None

        updates = {
            "training_metrics": training_metrics,
            "training_time": training_time,
            "completed_at": datetime.utcnow().isoformat() + "Z",
            "status": "completed"
        }

        updated = metadata_store.update(self.collection, training_id, updates)
        if updated:
            if "accuracy" in training_metrics:
                version = version_manager.get_version(training.version_id)
                if version:
                    version_manager.update_version(
                        version.version_id,
                        {"accuracy": training_metrics["accuracy"]}
                    )
            return TrainingRecord.from_dict(updated)
        return None

    def fail_training(self, training_id: str, error_message: str) -> Optional[TrainingRecord]:
        training = self.get_training(training_id)
        if not training:
            return None

        updates = {
            "completed_at": datetime.utcnow().isoformat() + "Z",
            "status": "failed"
        }

        updated = metadata_store.update(self.collection, training_id, updates)
        if updated:
            record = TrainingRecord.from_dict(updated)
            record.dataset_info["error_message"] = error_message
            metadata_store.save(self.collection, training_id, record.to_dict())
            return record
        return None

    def get_model_trainings(self, model_id: str) -> List[TrainingRecord]:
        trainings_data = metadata_store.list_by_field(self.collection, "model_id", model_id)
        return [TrainingRecord.from_dict(t) for t in trainings_data]

    def get_version_trainings(self, version_id: str) -> List[TrainingRecord]:
        trainings_data = metadata_store.list_by_field(self.collection, "version_id", version_id)
        return [TrainingRecord.from_dict(t) for t in trainings_data]

    def list_all_trainings(self) -> List[TrainingRecord]:
        trainings_data = metadata_store.list_all(self.collection)
        return [TrainingRecord.from_dict(t) for t in trainings_data]

    def update_training_metrics(
        self,
        training_id: str,
        metrics: Dict
    ) -> Optional[TrainingRecord]:
        training = self.get_training(training_id)
        if not training:
            return None

        updated_metrics = {**training.training_metrics, **metrics}
        updated = metadata_store.update(
            self.collection,
            training_id,
            {"training_metrics": updated_metrics}
        )
        if updated:
            return TrainingRecord.from_dict(updated)
        return None

    def delete_training(self, training_id: str) -> bool:
        return metadata_store.delete(self.collection, training_id)

    def get_latest_training(self, model_id: str) -> Optional[TrainingRecord]:
        trainings = self.get_model_trainings(model_id)
        if not trainings:
            return None
        return sorted(trainings, key=lambda t: t.started_at, reverse=True)[0]

    def get_best_training(self, model_id: str, metric_key: str = "accuracy") -> Optional[TrainingRecord]:
        trainings = self.get_model_trainings(model_id)
        completed_trainings = [t for t in trainings if t.status == "completed"]

        if not completed_trainings:
            return None

        best = None
        best_value = None

        for training in completed_trainings:
            value = training.training_metrics.get(metric_key)
            if value is not None:
                if best_value is None or value > best_value:
                    best_value = value
                    best = training

        return best

    def training_exists(self, training_id: str) -> bool:
        return metadata_store.exists(self.collection, training_id)


training_manager = TrainingManager()
