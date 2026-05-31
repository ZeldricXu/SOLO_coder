from typing import List, Optional, Dict, Any
from datetime import datetime
import re

from .schemas import (
    ModelStage,
    ModelVersionStatus,
    ModelFramework,
    ModelMetadata,
    ModelVersion,
    ModelRegistrationRequest,
    ModelVersionCreateRequest,
    StageTransitionRequest,
    ModelSearchRequest,
    ModelSearchResponse,
    ModelArtifact,
    ModelTag,
    ModelMetric,
)
from common.logger import get_logger
from common.utils import generate_id, utc_now

logger = get_logger(__name__)


class ModelRegistryService:
    def __init__(self):
        self.models: Dict[str, ModelMetadata] = {}
        self.versions: Dict[str, Dict[str, ModelVersion]] = {}
        self._register_sample_models()

    def _register_sample_models(self):
        sample_models = [
            {
                "name": "llm-chat-base",
                "display_name": "对话基础模型",
                "task_type": "text-generation",
                "description": "通用对话大语言模型",
                "versions": [
                    {"version": "1.0.0", "stage": ModelStage.ARCHIVED, "status": ModelVersionStatus.DEPLOYED},
                    {"version": "1.1.0", "stage": ModelStage.STAGING, "status": ModelVersionStatus.DEPLOYED},
                    {"version": "1.2.0", "stage": ModelStage.PRODUCTION, "status": ModelVersionStatus.DEPLOYED},
                ]
            },
            {
                "name": "text-embedding-v2",
                "display_name": "文本向量模型",
                "task_type": "embedding",
                "description": "文本向量化模型",
                "versions": [
                    {"version": "2.0.0", "stage": ModelStage.PRODUCTION, "status": ModelVersionStatus.DEPLOYED},
                ]
            },
        ]

        for sm in sample_models:
            req = ModelRegistrationRequest(
                name=sm["name"],
                display_name=sm["display_name"],
                task_type=sm["task_type"],
                description=sm["description"],
            )
            model = self.register_model(req)
            for v in sm["versions"]:
                vreq = ModelVersionCreateRequest(
                    model_id=model.model_id,
                    version=v["version"],
                    framework=ModelFramework.PYTORCH,
                )
                version = self.create_model_version(vreq)
                if v["stage"] != ModelStage.NONE:
                    treq = StageTransitionRequest(
                        model_id=model.model_id,
                        version=v["version"],
                        target_stage=v["stage"],
                    )
                    self.transition_stage(treq)

    def _validate_semver(self, version: str) -> bool:
        pattern = r'^\d+\.\d+\.\d+(-[a-zA-Z0-9]+)?$'
        return bool(re.match(pattern, version))

    def _bump_version(self, latest_version: Optional[str]) -> str:
        if not latest_version:
            return "0.1.0"
        try:
            major, minor, patch = latest_version.split('.')
            patch = int(patch) + 1
            return f"{major}.{minor}.{patch}"
        except Exception:
            return "0.1.0"

    def register_model(self, request: ModelRegistrationRequest) -> ModelMetadata:
        existing = next((m for m in self.models.values() if m.name == request.name), None)
        if existing:
            raise ValueError(f"Model with name '{request.name}' already exists")

        model_id = generate_id("mod_")
        now = utc_now()

        model = ModelMetadata(
            model_id=model_id,
            name=request.name,
            display_name=request.display_name or request.name,
            description=request.description,
            owner=request.owner,
            task_type=request.task_type,
            created_by=request.owner,
            created_at=now,
            updated_at=now,
            is_active=True,
        )

        if request.tags:
            for k, v in request.tags.items():
                model.tags.append(ModelTag(
                    tag_id=generate_id("tag_"),
                    name=k,
                    value=v,
                    created_at=now,
                ))

        self.models[model_id] = model
        self.versions[model_id] = {}

        logger.info(f"Registered model: {request.name} ({model_id})")
        return model

    def create_model_version(self, request: ModelVersionCreateRequest) -> ModelVersion:
        if request.model_id not in self.models:
            raise ValueError(f"Model {request.model_id} not found")

        model = self.models[request.model_id]
        versions = self.versions[request.model_id]

        version_str = request.version
        if not version_str:
            version_str = self._bump_version(model.latest_version)
        else:
            if not self._validate_semver(version_str):
                raise ValueError(f"Invalid semantic version: {version_str}")

        if version_str in versions:
            raise ValueError(f"Version {version_str} already exists for model {model.name}")

        version_id = generate_id("ver_")
        now = utc_now()

        model_version = ModelVersion(
            version_id=version_id,
            model_id=request.model_id,
            version=version_str,
            description=request.description,
            status=ModelVersionStatus.READY,
            stage=ModelStage.NONE,
            framework=request.framework,
            created_by=request.created_by,
            created_at=now,
            updated_at=now,
            training_run_id=request.training_run_id,
            source_code_uri=request.source_code_uri,
        )

        if request.artifacts:
            model_version.artifacts = request.artifacts
        if request.metrics:
            model_version.metrics = request.metrics
        if request.tags:
            for k, v in request.tags.items():
                model_version.tags.append(ModelTag(
                    tag_id=generate_id("tag_"),
                    name=k,
                    value=v,
                    created_at=now,
                ))

        versions[version_str] = model_version
        model.versions.append(model_version)
        model.latest_version = version_str
        model.updated_at = now

        logger.info(f"Created version {version_str} for model {model.name}")
        return model_version

    def transition_stage(self, request: StageTransitionRequest) -> ModelVersion:
        if request.model_id not in self.models:
            raise ValueError(f"Model {request.model_id} not found")

        model = self.models[request.model_id]
        versions = self.versions[request.model_id]

        if request.version not in versions:
            raise ValueError(f"Version {request.version} not found")

        version = versions[request.version]
        old_stage = version.stage
        new_stage = request.target_stage

        same_stage_versions = [v for v in versions.values() if v.stage == new_stage]
        if new_stage == ModelStage.PRODUCTION and same_stage_versions:
            for v in same_stage_versions:
                v.stage = ModelStage.ARCHIVED
                logger.info(f"Archived version {v.version} from production")

        version.stage = new_stage
        version.updated_at = utc_now()
        if new_stage in [ModelStage.PRODUCTION, ModelStage.STAGING]:
            version.deployed_at = utc_now()

        if new_stage == ModelStage.PRODUCTION:
            model.production_version = request.version
        elif new_stage == ModelStage.STAGING:
            model.staging_version = request.version

        model.updated_at = utc_now()

        logger.info(f"Transitioned model {model.name} version {request.version} from {old_stage.value} to {new_stage.value}")
        return version

    def get_model(self, model_id: str, include_versions: bool = True) -> ModelMetadata:
        if model_id not in self.models:
            raise ValueError(f"Model {model_id} not found")
        return self.models[model_id]

    def get_model_version(self, model_id: str, version: str) -> ModelVersion:
        if model_id not in self.versions:
            raise ValueError(f"Model {model_id} not found")
        if version not in self.versions[model_id]:
            raise ValueError(f"Version {version} not found")
        return self.versions[model_id][version]

    def get_latest_version(self, model_id: str) -> ModelVersion:
        model = self.get_model(model_id)
        if not model.latest_version:
            raise ValueError(f"No versions found for model {model.name}")
        return self.get_model_version(model_id, model.latest_version)

    def get_production_version(self, model_id: str) -> ModelVersion:
        model = self.get_model(model_id)
        if not model.production_version:
            raise ValueError(f"No production version for model {model.name}")
        return self.get_model_version(model_id, model.production_version)

    def search_models(self, request: ModelSearchRequest) -> ModelSearchResponse:
        results = list(self.models.values())

        if request.name:
            results = [m for m in results if request.name.lower() in m.name.lower()]
        if request.owner:
            results = [m for m in results if m.owner == request.owner]
        if request.task_type:
            results = [m for m in results if m.task_type == request.task_type]
        if request.stage:
            results = [m for m in results if any(v.stage == request.stage for v in m.versions)]
        if request.tags:
            for k, v in request.tags.items():
                results = [m for m in results if any(t.name == k and t.value == v for t in m.tags)]

        total = len(results)
        results.sort(key=lambda m: m.updated_at, reverse=True)
        results = results[request.offset:request.offset + request.limit]

        return ModelSearchResponse(
            total=total,
            limit=request.limit,
            offset=request.offset,
            models=results,
        )

    def delete_model(self, model_id: str) -> bool:
        if model_id not in self.models:
            raise ValueError(f"Model {model_id} not found")
        self.models[model_id].is_active = False
        self.models[model_id].updated_at = utc_now()
        logger.info(f"Soft deleted model: {model_id}")
        return True

    def delete_version(self, model_id: str, version: str) -> bool:
        if model_id not in self.versions:
            raise ValueError(f"Model {model_id} not found")
        if version not in self.versions[model_id]:
            raise ValueError(f"Version {version} not found")

        v = self.versions[model_id][version]
        v.status = ModelVersionStatus.DELETED
        v.stage = ModelStage.ARCHIVED
        v.updated_at = utc_now()

        logger.info(f"Deleted version {version} of model {model_id}")
        return True

    def list_models(self, limit: int = 50, offset: int = 0) -> ModelSearchResponse:
        return self.search_models(ModelSearchRequest(limit=limit, offset=offset))

    def add_artifact(self, model_id: str, version: str, artifact: ModelArtifact) -> ModelVersion:
        v = self.get_model_version(model_id, version)
        v.artifacts.append(artifact)
        v.updated_at = utc_now()
        return v

    def add_metric(self, model_id: str, version: str, metric: ModelMetric) -> ModelVersion:
        v = self.get_model_version(model_id, version)
        v.metrics.append(metric)
        v.updated_at = utc_now()
        return v

    def add_tag(self, model_id: str, tag: ModelTag, version: Optional[str] = None):
        if version:
            v = self.get_model_version(model_id, version)
            v.tags.append(tag)
            v.updated_at = utc_now()
        else:
            model = self.get_model(model_id)
            model.tags.append(tag)
            model.updated_at = utc_now()


model_registry_service = ModelRegistryService()
