from typing import Dict, List, Optional
from datetime import datetime
from collections import defaultdict
from .types import (
    ModelMetadata,
    ModelVersion,
    ModelStage,
    ModelStatus,
    ModelRegisterRequest,
    VersionCreateRequest,
    StageTransitionRequest,
    StageTransition,
    ModelVersionSummary,
)
from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    NotFoundError,
    ValidationError,
    PlatformError,
    generate_id,
)
import logging

logger = logging.getLogger(__name__)


class ModelRegistryService:
    def __init__(self):
        self._models: Dict[str, ModelMetadata] = {}
        self._versions: Dict[str, ModelVersion] = {}
        self._model_versions: Dict[str, List[str]] = defaultdict(list)
        self._transitions: Dict[str, List[StageTransition]] = defaultdict(list)
        self._metrics = get_metrics_collector()

    async def register_model(
        self,
        request: ModelRegisterRequest,
        trace_id: Optional[str] = None,
    ) -> ModelMetadata:
        with init_context(trace_id, operation="register_model"):
            try:
                model_id = generate_id("model")
                model = ModelMetadata(
                    model_id=model_id,
                    name=request.name,
                    description=request.description,
                    framework=request.framework,
                    framework_version=request.framework_version,
                    tags=request.tags,
                    labels=request.labels,
                )

                for existing in self._models.values():
                    if existing.name == request.name:
                        raise ValidationError(f"Model with name '{request.name}' already exists")

                self._models[model_id] = model

                emit_event(
                    "model.registered",
                    {"model_id": model_id, "name": request.name, "framework": request.framework.value},
                    source="model_registry",
                )

                self._metrics.increment("model_registry_models_registered")
                return model

            except ValidationError:
                raise
            except Exception as e:
                logger.error(f"Failed to register model: {e}")
                raise PlatformError(f"模型注册失败: {str(e)}")

    async def get_model(self, model_id: str, trace_id: Optional[str] = None) -> ModelMetadata:
        with init_context(trace_id, operation="get_model"):
            model = self._models.get(model_id)
            if not model:
                raise NotFoundError(f"Model not found: {model_id}")
            return model

    async def get_model_by_name(self, name: str, trace_id: Optional[str] = None) -> ModelMetadata:
        with init_context(trace_id, operation="get_model_by_name"):
            for model in self._models.values():
                if model.name == name:
                    return model
            raise NotFoundError(f"Model not found: {name}")

    async def list_models(
        self,
        framework: Optional[str] = None,
        tags: Optional[List[str]] = None,
        trace_id: Optional[str] = None,
    ) -> List[ModelMetadata]:
        with init_context(trace_id, operation="list_models"):
            models = list(self._models.values())
            if framework:
                models = [m for m in models if m.framework.value == framework]
            if tags:
                models = [m for m in models if any(tag in m.tags for tag in tags)]
            return sorted(models, key=lambda m: m.created_at, reverse=True)

    async def create_version(
        self,
        request: VersionCreateRequest,
        trace_id: Optional[str] = None,
    ) -> ModelVersion:
        with init_context(trace_id, operation="create_version"):
            try:
                if request.model_id not in self._models:
                    raise NotFoundError(f"Model not found: {request.model_id}")

                existing_versions = self._model_versions[request.model_id]
                for vid in existing_versions:
                    v = self._versions[vid]
                    if v.version == request.version:
                        raise ValidationError(
                            f"Version '{request.version}' already exists for model {request.model_id}"
                        )

                version_id = generate_id("ver")
                model_version = ModelVersion(
                    version_id=version_id,
                    model_id=request.model_id,
                    version=request.version,
                    description=request.description,
                    metrics=request.metrics,
                    artifacts_uri=request.artifacts_uri,
                    signature=request.signature,
                    dependencies=request.dependencies,
                )

                self._versions[version_id] = model_version
                self._model_versions[request.model_id].append(version_id)
                self._model_versions[request.model_id].sort(
                    key=lambda vid: self._versions[vid].version, reverse=True
                )

                emit_event(
                    "model.version.created",
                    {"version_id": version_id, "model_id": request.model_id, "version": request.version},
                    source="model_registry",
                )

                self._metrics.increment("model_registry_versions_created")
                return model_version

            except (NotFoundError, ValidationError):
                raise
            except Exception as e:
                logger.error(f"Failed to create version: {e}")
                raise PlatformError(f"版本创建失败: {str(e)}")

    async def get_version(self, version_id: str, trace_id: Optional[str] = None) -> ModelVersion:
        with init_context(trace_id, operation="get_version"):
            version = self._versions.get(version_id)
            if not version:
                raise NotFoundError(f"Version not found: {version_id}")
            return version

    async def get_model_versions(
        self,
        model_id: str,
        stage: Optional[ModelStage] = None,
        trace_id: Optional[str] = None,
    ) -> List[ModelVersion]:
        with init_context(trace_id, operation="get_model_versions"):
            if model_id not in self._models:
                raise NotFoundError(f"Model not found: {model_id}")

            version_ids = self._model_versions.get(model_id, [])
            versions = [self._versions[vid] for vid in version_ids]
            if stage:
                versions = [v for v in versions if v.stage == stage]
            return versions

    async def transition_stage(
        self,
        request: StageTransitionRequest,
        trace_id: Optional[str] = None,
    ) -> ModelVersion:
        with init_context(trace_id, operation="transition_stage"):
            try:
                version = await self.get_version(request.version_id)
                from_stage = version.stage
                to_stage = request.target_stage

                self._validate_stage_transition(from_stage, to_stage)

                if to_stage in [ModelStage.PRODUCTION, ModelStage.STAGING]:
                    same_stage_versions = [
                        v for v in self._versions.values()
                        if v.model_id == version.model_id and v.stage == to_stage and v.version_id != version.version_id
                    ]
                    for v in same_stage_versions:
                        v.stage = ModelStage.ARCHIVED
                        logger.info(f"Archived version {v.version_id} from {to_stage.value}")

                version.stage = to_stage
                version.updated_at = datetime.utcnow()
                self._versions[version.version_id] = version

                transition = StageTransition(
                    transition_id=generate_id("trans"),
                    version_id=request.version_id,
                    from_stage=from_stage,
                    to_stage=to_stage,
                    comment=request.comment,
                )
                self._transitions[version.version_id].append(transition)

                emit_event(
                    "model.stage.transitioned",
                    {
                        "version_id": request.version_id,
                        "from": from_stage.value,
                        "to": to_stage.value,
                    },
                    source="model_registry",
                )

                self._metrics.increment("model_registry_stage_transitions")
                return version

            except (NotFoundError, ValidationError):
                raise
            except Exception as e:
                logger.error(f"Failed to transition stage: {e}")
                raise PlatformError(f"阶段转换失败: {str(e)}")

    def _validate_stage_transition(self, from_stage: ModelStage, to_stage: ModelStage) -> None:
        valid_transitions = {
            ModelStage.NONE: [ModelStage.STAGING, ModelStage.ARCHIVED],
            ModelStage.STAGING: [ModelStage.PRODUCTION, ModelStage.ARCHIVED, ModelStage.NONE],
            ModelStage.PRODUCTION: [ModelStage.STAGING, ModelStage.ARCHIVED],
            ModelStage.ARCHIVED: [ModelStage.NONE],
        }

        if to_stage not in valid_transitions.get(from_stage, []):
            raise ValidationError(
                f"Invalid stage transition: {from_stage.value} -> {to_stage.value}"
            )

    async def get_version_transitions(
        self,
        version_id: str,
        trace_id: Optional[str] = None,
    ) -> List[StageTransition]:
        with init_context(trace_id, operation="get_version_transitions"):
            if version_id not in self._versions:
                raise NotFoundError(f"Version not found: {version_id}")
            return self._transitions.get(version_id, [])

    async def get_model_summary(
        self,
        model_id: str,
        trace_id: Optional[str] = None,
    ) -> ModelVersionSummary:
        with init_context(trace_id, operation="get_model_summary"):
            model = await self.get_model(model_id)
            versions = await self.get_model_versions(model_id)

            latest = versions[0] if versions else None
            production = next((v for v in versions if v.stage == ModelStage.PRODUCTION), None)
            staging = next((v for v in versions if v.stage == ModelStage.STAGING), None)

            return ModelVersionSummary(
                model_id=model_id,
                model_name=model.name,
                total_versions=len(versions),
                latest_version=latest.version if latest else None,
                production_version=production.version if production else None,
                staging_version=staging.version if staging else None,
            )

    async def update_version_status(
        self,
        version_id: str,
        status: ModelStatus,
        trace_id: Optional[str] = None,
    ) -> ModelVersion:
        with init_context(trace_id, operation="update_version_status"):
            version = await self.get_version(version_id)
            version.status = status
            version.updated_at = datetime.utcnow()
            self._versions[version_id] = version
            return version
