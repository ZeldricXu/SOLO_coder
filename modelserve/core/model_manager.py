from typing import Dict, List, Optional
from datetime import datetime

from .models import Model, generate_id
from ..storage import metadata_store


class ModelManager:
    def __init__(self):
        self.collection = "models"

    def create_model(
        self,
        model_name: str,
        model_type: str,
        framework: str,
        model_id: Optional[str] = None,
        tags: Optional[List[str]] = None,
        description: str = ""
    ) -> Optional[Model]:
        if model_id is None:
            model_id = generate_id("model")

        if self.get_model(model_id):
            print(f"Model with id {model_id} already exists")
            return None

        model = Model(
            model_id=model_id,
            model_name=model_name,
            model_type=model_type,
            framework=framework,
            status="draft",
            tags=tags if tags else [],
            description=description
        )

        if metadata_store.save(self.collection, model_id, model.to_dict()):
            return model
        return None

    def get_model(self, model_id: str) -> Optional[Model]:
        data = metadata_store.load(self.collection, model_id)
        if data:
            return Model.from_dict(data)
        return None

    def update_model(self, model_id: str, updates: Dict) -> Optional[Model]:
        model = self.get_model(model_id)
        if not model:
            return None

        allowed_fields = ["model_name", "description", "status", "tags", "current_version"]
        filtered_updates = {k: v for k, v in updates.items() if k in allowed_fields}

        if not filtered_updates:
            return model

        updated = metadata_store.update(self.collection, model_id, filtered_updates)
        if updated:
            return Model.from_dict(updated)
        return None

    def delete_model(self, model_id: str) -> bool:
        return metadata_store.delete(self.collection, model_id)

    def list_models(
        self,
        model_type: Optional[str] = None,
        framework: Optional[str] = None,
        status: Optional[str] = None
    ) -> List[Model]:
        models = metadata_store.list_all(self.collection)
        filtered_models = []

        for model_data in models:
            model = Model.from_dict(model_data)
            if model_type and model.model_type != model_type:
                continue
            if framework and model.framework != framework:
                continue
            if status and model.status != status:
                continue
            filtered_models.append(model)

        return filtered_models

    def add_tags(self, model_id: str, tags: List[str]) -> Optional[Model]:
        model = self.get_model(model_id)
        if not model:
            return None

        new_tags = list(set(model.tags + tags))
        return self.update_model(model_id, {"tags": new_tags})

    def remove_tags(self, model_id: str, tags: List[str]) -> Optional[Model]:
        model = self.get_model(model_id)
        if not model:
            return None

        new_tags = [t for t in model.tags if t not in tags]
        return self.update_model(model_id, {"tags": new_tags})

    def update_status(self, model_id: str, status: str) -> Optional[Model]:
        valid_statuses = ["draft", "ready", "deployed", "archived"]
        if status not in valid_statuses:
            print(f"Invalid status: {status}. Must be one of {valid_statuses}")
            return None
        return self.update_model(model_id, {"status": status})

    def update_current_version(self, model_id: str, version: str) -> Optional[Model]:
        return self.update_model(model_id, {"current_version": version})

    def model_exists(self, model_id: str) -> bool:
        return metadata_store.exists(self.collection, model_id)

    def get_models_by_tag(self, tag: str) -> List[Model]:
        all_models = self.list_models()
        return [m for m in all_models if tag in m.tags]


model_manager = ModelManager()
