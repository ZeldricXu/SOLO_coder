from typing import Dict, Any, List, Optional, Callable
from datetime import datetime
import asyncio
import logging

from app.pipeline.pipeline import DataPipeline
from app.pipeline.config_loader import yaml_config_loader, YAMLConfigLoader
from app.core.config import settings
from app.core.models import PipelineConfig, RawDataEvent, CleanedDataEvent

logger = logging.getLogger(__name__)


class PipelineManager:
    def __init__(self):
        self._pipelines: Dict[str, DataPipeline] = {}
        self._on_cleaned_callback: Optional[Callable[[CleanedDataEvent], None]] = None
        self._config_loader: YAMLConfigLoader = yaml_config_loader
        self._auto_reload_task: Optional[asyncio.Task] = None
        self._is_running = False
        self._last_reload_check: Optional[datetime] = None

    def set_global_callback(self, callback: Callable[[CleanedDataEvent], None]):
        self._on_cleaned_callback = callback
        for pipeline in self._pipelines.values():
            pipeline.set_cleaned_callback(callback)

    async def register_pipeline(self, config: PipelineConfig) -> bool:
        if config.source in self._pipelines:
            logger.warning(f"Pipeline for source {config.source} already registered")
            return False

        try:
            pipeline = DataPipeline(config)
            if self._on_cleaned_callback:
                pipeline.set_cleaned_callback(self._on_cleaned_callback)

            self._pipelines[config.source] = pipeline
            logger.info(f"Registered pipeline for source: {config.source}")
            return True
        except Exception as e:
            logger.error(f"Failed to register pipeline {config.source}: {e}")
            return False

    async def unregister_pipeline(self, source: str) -> bool:
        if source not in self._pipelines:
            logger.warning(f"Pipeline for source {source} not found")
            return False

        try:
            del self._pipelines[source]
            logger.info(f"Unregistered pipeline for source: {source}")
            return True
        except Exception as e:
            logger.error(f"Failed to unregister pipeline {source}: {e}")
            return False

    async def load_from_yaml(self, config_loader: YAMLConfigLoader = None) -> int:
        loader = config_loader or self._config_loader

        yaml_config = loader.load_config()
        if not yaml_config:
            logger.warning("No pipeline config loaded from YAML")
            return 0

        pipeline_configs = loader.to_pipeline_configs(yaml_config)

        registered_count = 0
        for config in pipeline_configs:
            if config.source in self._pipelines:
                await self.unregister_pipeline(config.source)

            success = await self.register_pipeline(config)
            if success:
                registered_count += 1

        logger.info(
            f"Loaded {registered_count} pipelines from YAML "
            f"(total in config: {len(pipeline_configs)})"
        )

        return registered_count

    async def start_auto_reload(self):
        if self._is_running:
            return

        self._is_running = True
        self._auto_reload_task = asyncio.create_task(self._auto_reload_loop())
        logger.info("Started pipeline auto-reload task")

    async def stop_auto_reload(self):
        self._is_running = False

        if self._auto_reload_task and not self._auto_reload_task.done():
            self._auto_reload_task.cancel()
            try:
                await self._auto_reload_task
            except asyncio.CancelledError:
                pass

        logger.info("Stopped pipeline auto-reload task")

    async def _auto_reload_loop(self):
        while self._is_running:
            try:
                if self._config_loader.check_and_reload():
                    await self._sync_configs()

                self._last_reload_check = datetime.utcnow()
                await asyncio.sleep(settings.PIPELINE_RELOAD_INTERVAL)

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in pipeline auto-reload loop: {e}")
                await asyncio.sleep(settings.PIPELINE_RELOAD_INTERVAL)

    async def _sync_configs(self):
        yaml_config = self._config_loader.get_cached_config()
        if not yaml_config:
            return

        new_sources = set()
        for yaml_pipeline in yaml_config.pipelines:
            if yaml_pipeline.enabled:
                new_sources.add(yaml_pipeline.source)

        current_sources = set(self._pipelines.keys())

        sources_to_remove = current_sources - new_sources
        for source in sources_to_remove:
            await self.unregister_pipeline(source)

        pipeline_configs = self._config_loader.to_pipeline_configs(yaml_config)
        for config in pipeline_configs:
            if config.source in current_sources:
                self.update_pipeline_config(config.source, config)
                logger.info(f"Updated pipeline config for source: {config.source}")
            else:
                await self.register_pipeline(config)

    def process_event(self, raw_event: RawDataEvent) -> Optional[CleanedDataEvent]:
        pipeline = self._pipelines.get(raw_event.source)
        if not pipeline:
            logger.debug(f"No pipeline registered for source: {raw_event.source}")
            cleaned = CleanedDataEvent(
                source=raw_event.source,
                data=raw_event.data,
                timestamp=raw_event.timestamp,
                original_data=raw_event.data.copy() if raw_event.data else {},
                quality_score=1.0,
                message_id=raw_event.message_id,
                kafka_topic=raw_event.kafka_topic,
                kafka_partition=raw_event.kafka_partition,
                kafka_offset=raw_event.kafka_offset
            )
            if self._on_cleaned_callback:
                try:
                    self._on_cleaned_callback(cleaned)
                except Exception as e:
                    logger.error(f"Error in callback for unregistered source: {e}")
            return cleaned

        return pipeline.process_event(raw_event)

    def process_batch(self, events: List[RawDataEvent]) -> List[CleanedDataEvent]:
        cleaned_events = []
        for event in events:
            cleaned = self.process_event(event)
            if cleaned:
                cleaned_events.append(cleaned)
        return cleaned_events

    def get_pipeline(self, source: str) -> Optional[DataPipeline]:
        return self._pipelines.get(source)

    def get_all_pipelines(self) -> Dict[str, DataPipeline]:
        return self._pipelines.copy()

    def has_pipeline(self, source: str) -> bool:
        return source in self._pipelines

    def update_pipeline_config(self, source: str, config: PipelineConfig) -> bool:
        pipeline = self._pipelines.get(source)
        if not pipeline:
            logger.warning(f"Pipeline for source {source} not found")
            return False

        pipeline.update_mappings(config.field_mappings)
        pipeline.config = config
        logger.info(f"Updated pipeline config for source: {source}")
        return True

    def get_stats(self) -> Dict[str, Any]:
        return {
            "total_pipelines": len(self._pipelines),
            "sources": list(self._pipelines.keys()),
            "auto_reload_running": self._is_running,
            "last_reload_check": (
                self._last_reload_check.isoformat() + "Z"
                if self._last_reload_check else None
            ),
            "yaml_config_loaded": self._config_loader.get_cached_config() is not None
        }


pipeline_manager = PipelineManager()
