from typing import Dict, Any, List, Optional, Callable
from datetime import datetime
import logging

from app.pipeline.validators import DataValidators
from app.pipeline.transformers import TypeTransformers
from app.core.models import (
    RawDataEvent,
    CleanedDataEvent,
    PipelineConfig,
    FieldMapping
)

logger = logging.getLogger(__name__)


class DataPipeline:
    def __init__(self, config: PipelineConfig):
        self.config = config
        self.source = config.source
        self._field_mappings: Dict[str, FieldMapping] = {
            fm.source_field: fm for fm in config.field_mappings
        }
        self._on_cleaned_callback: Optional[Callable[[CleanedDataEvent], None]] = None

    def set_cleaned_callback(self, callback: Callable[[CleanedDataEvent], None]):
        self._on_cleaned_callback = callback

    def process_event(self, raw_event: RawDataEvent) -> Optional[CleanedDataEvent]:
        try:
            cleaned_data = {}
            quality_score = 1.0
            original_data = raw_event.data.copy() if raw_event.data else {}

            if self._field_mappings:
                for source_field, mapping in self._field_mappings.items():
                    if source_field in raw_event.data:
                        value = raw_event.data[source_field]
                        result = self._process_field(value, mapping)
                        cleaned_data[mapping.target_field] = result['value']
                        if not result['valid']:
                            quality_score -= 0.1
                    elif mapping.default_value is not None:
                        cleaned_data[mapping.target_field] = mapping.default_value
                    elif not mapping.is_nullable:
                        cleaned_data[mapping.target_field] = None
                        quality_score -= 0.2
                        logger.warning(
                            f"Field {source_field} is null but not nullable, "
                            f"source: {self.source}"
                        )

                if not self.config.drop_unspecified:
                    for field, value in raw_event.data.items():
                        if field not in self._field_mappings:
                            cleaned_data[field] = value
            else:
                cleaned_data = raw_event.data.copy()

            if quality_score < self.config.quality_threshold:
                logger.warning(
                    f"Data quality score {quality_score:.2f} below threshold "
                    f"{self.config.quality_threshold}, source: {self.source}"
                )

            cleaned_event = CleanedDataEvent(
                source=self.source,
                data=cleaned_data,
                timestamp=raw_event.timestamp,
                original_data=original_data,
                quality_score=max(0.0, quality_score),
                message_id=raw_event.message_id,
                kafka_topic=raw_event.kafka_topic,
                kafka_partition=raw_event.kafka_partition,
                kafka_offset=raw_event.kafka_offset
            )

            if self._on_cleaned_callback:
                try:
                    self._on_cleaned_callback(cleaned_event)
                except Exception as e:
                    logger.error(f"Error in cleaned data callback: {e}")

            return cleaned_event

        except Exception as e:
            logger.error(f"Error processing event in pipeline {self.source}: {e}")
            return None

    def _process_field(self, value: Any, mapping: FieldMapping) -> Dict[str, Any]:
        result = {
            'value': value,
            'valid': True,
            'errors': []
        }

        if value is None and mapping.default_value is not None:
            result['value'] = mapping.default_value
        elif value is None and mapping.is_nullable:
            result['value'] = None
        elif value is None:
            result['valid'] = False
            result['errors'].append(f"Field {mapping.source_field} cannot be null")
            result['value'] = None
        else:
            transformed = TypeTransformers.transform(
                value,
                mapping.field_type,
                mapping.default_value
            )
            result['value'] = transformed

            if mapping.validators:
                is_valid, errors = DataValidators.validate_all(
                    transformed,
                    mapping.validators
                )
                if not is_valid:
                    result['valid'] = False
                    result['errors'].extend(errors)

        return result

    def process_batch(self, events: List[RawDataEvent]) -> List[CleanedDataEvent]:
        cleaned_events = []
        for event in events:
            cleaned = self.process_event(event)
            if cleaned:
                cleaned_events.append(cleaned)
        return cleaned_events

    def update_mappings(self, mappings: List[FieldMapping]):
        self._field_mappings = {fm.source_field: fm for fm in mappings}
        self.config.field_mappings = mappings
        logger.info(f"Updated field mappings for pipeline: {self.source}")

    def get_mappings(self) -> Dict[str, FieldMapping]:
        return self._field_mappings.copy()
