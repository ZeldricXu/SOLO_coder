from __future__ import annotations

from streamsql.modules.cdc_capture.capture import CDCCapture
from streamsql.modules.cdc_capture.binlog_parser import (
    BinlogParser,
    CDCEvent,
    OperationType,
)
from streamsql.modules.cdc_capture.event_serializer import (
    EventSerializer,
    JSONEventSerializer,
    AvroEventSerializer,
    CompressedJSONSerializer,
    PickleSerializer,
    SerializerFactory,
)
from streamsql.modules.cdc_capture.output_adapter import (
    OutputAdapter,
    ConsoleOutputAdapter,
    KafkaOutputAdapter,
    FileOutputAdapter,
    WebhookOutputAdapter,
)
from streamsql.modules.cdc_capture.strategies import (
    DeduplicationStrategy,
    FilterStrategy,
    MaskingStrategy,
    ProcessingContext,
    ProcessingStage,
    ProcessingStrategy,
    RouteStrategy,
    StrategyPipeline,
    StrategyRegistry,
    ThrottlingStrategy,
    TransformStrategy,
)

__all__ = [
    "CDCCapture",
    "BinlogParser",
    "CDCEvent",
    "OperationType",
    "EventSerializer",
    "JSONEventSerializer",
    "AvroEventSerializer",
    "CompressedJSONSerializer",
    "PickleSerializer",
    "SerializerFactory",
    "OutputAdapter",
    "ConsoleOutputAdapter",
    "KafkaOutputAdapter",
    "FileOutputAdapter",
    "WebhookOutputAdapter",
    "ProcessingStrategy",
    "ProcessingContext",
    "ProcessingStage",
    "FilterStrategy",
    "TransformStrategy",
    "DeduplicationStrategy",
    "ThrottlingStrategy",
    "MaskingStrategy",
    "RouteStrategy",
    "StrategyPipeline",
    "StrategyRegistry",
]
