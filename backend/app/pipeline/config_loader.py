from typing import Dict, Any, Optional, List
from datetime import datetime
import os
import yaml
import logging
import asyncio

from app.core.config import settings
from app.core.models import (
    PipelineConfig,
    FieldMapping,
    YAMLConfigRoot,
    YAMLPipelineConfig,
    YAMLFieldMapping
)

logger = logging.getLogger(__name__)


class YAMLConfigLoader:
    def __init__(self, config_path: str = None):
        self._config_path = config_path or settings.PIPELINE_CONFIG_PATH
        self._last_loaded: Optional[datetime] = None
        self._cached_config: Optional[YAMLConfigRoot] = None
        self._auto_reload = settings.PIPELINE_AUTO_RELOAD
        self._reload_interval = settings.PIPELINE_RELOAD_INTERVAL

    def _resolve_path(self) -> str:
        if os.path.isabs(self._config_path):
            return self._config_path

        base_dir = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        return os.path.join(base_dir, self._config_path)

    def load_config(self) -> Optional[YAMLConfigRoot]:
        try:
            config_path = self._resolve_path()

            if not os.path.exists(config_path):
                logger.warning(f"Pipeline config file not found: {config_path}")
                return None

            with open(config_path, 'r', encoding='utf-8') as f:
                yaml_data = yaml.safe_load(f)

            if not yaml_data:
                logger.warning("Pipeline config file is empty")
                return None

            config = self._parse_yaml(yaml_data)
            self._cached_config = config
            self._last_loaded = datetime.utcnow()

            logger.info(f"Loaded pipeline config from {config_path}")
            return config

        except yaml.YAMLError as e:
            logger.error(f"YAML parsing error: {e}")
            return None
        except Exception as e:
            logger.error(f"Failed to load pipeline config: {e}")
            return None

    def _parse_yaml(self, yaml_data: Dict[str, Any]) -> YAMLConfigRoot:
        version = yaml_data.get('version', '1.0')
        pipelines_data = yaml_data.get('pipelines', [])

        pipelines = []
        for pipeline_data in pipelines_data:
            pipeline = self._parse_pipeline(pipeline_data)
            if pipeline:
                pipelines.append(pipeline)

        return YAMLConfigRoot(
            version=version,
            pipelines=pipelines,
            updated_at=datetime.utcnow()
        )

    def _parse_pipeline(self, data: Dict[str, Any]) -> Optional[YAMLPipelineConfig]:
        try:
            source = data.get('source')
            if not source:
                logger.warning("Pipeline missing 'source' field, skipping")
                return None

            field_mappings_data = data.get('field_mappings', [])
            field_mappings = []

            for fm_data in field_mappings_data:
                fm = self._parse_field_mapping(fm_data)
                if fm:
                    field_mappings.append(fm)

            return YAMLPipelineConfig(
                source=source,
                field_mappings=field_mappings,
                drop_unspecified=data.get('drop_unspecified', False),
                quality_threshold=float(data.get('quality_threshold', 0.8)),
                description=data.get('description'),
                enabled=data.get('enabled', True)
            )

        except Exception as e:
            logger.error(f"Failed to parse pipeline: {e}")
            return None

    def _parse_field_mapping(self, data: Dict[str, Any]) -> Optional[YAMLFieldMapping]:
        try:
            source_field = data.get('source_field')
            target_field = data.get('target_field')

            if not source_field or not target_field:
                logger.warning(
                    "Field mapping missing 'source_field' or 'target_field', skipping"
                )
                return None

            return YAMLFieldMapping(
                source_field=source_field,
                target_field=target_field,
                field_type=data.get('field_type', 'string'),
                default_value=data.get('default_value'),
                is_nullable=data.get('is_nullable', True),
                validators=data.get('validators', [])
            )

        except Exception as e:
            logger.error(f"Failed to parse field mapping: {e}")
            return None

    def to_pipeline_configs(self, yaml_config: YAMLConfigRoot = None) -> List[PipelineConfig]:
        config = yaml_config or self._cached_config
        if not config:
            return []

        pipeline_configs = []

        for yaml_pipeline in config.pipelines:
            if not yaml_pipeline.enabled:
                logger.debug(f"Pipeline {yaml_pipeline.source} is disabled, skipping")
                continue

            field_mappings = [
                FieldMapping(
                    source_field=fm.source_field,
                    target_field=fm.target_field,
                    field_type=fm.field_type,
                    default_value=fm.default_value,
                    is_nullable=fm.is_nullable,
                    validators=fm.validators
                )
                for fm in yaml_pipeline.field_mappings
            ]

            pipeline_config = PipelineConfig(
                source=yaml_pipeline.source,
                field_mappings=field_mappings,
                drop_unspecified=yaml_pipeline.drop_unspecified,
                quality_threshold=yaml_pipeline.quality_threshold
            )

            pipeline_configs.append(pipeline_config)

        return pipeline_configs

    def get_cached_config(self) -> Optional[YAMLConfigRoot]:
        return self._cached_config

    def needs_reload(self) -> bool:
        if not self._auto_reload:
            return False

        if not self._last_loaded:
            return True

        elapsed = (datetime.utcnow() - self._last_loaded).total_seconds()
        return elapsed >= self._reload_interval

    def check_and_reload(self) -> bool:
        if self.needs_reload():
            new_config = self.load_config()
            if new_config:
                logger.info("Pipeline config reloaded")
                return True
        return False


yaml_config_loader = YAMLConfigLoader()
