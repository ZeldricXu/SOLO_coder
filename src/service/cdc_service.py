import logging
import time
from typing import Any, Dict, Iterator, List, Optional

from src.domain.cdc.binlog_parser import BinlogParser, BinlogEvent, RowEventType
from src.domain.cdc.wal_parser import WALParser, WALEvent, WALEventType
from src.domain.cdc.event_serializer import CDCEvent, CDCEventSerializer, CDCOperation, SerializationFormat
from src.domain.cdc.output_adapter import CDCOutputAdapter, OutputDestination
from src.infrastructure.config.settings import CDCMySQLConfig, CDCPostgreSQLConfig, KafkaConfig
from src.infrastructure.messaging.kafka_producer import KafkaProducer

logger = logging.getLogger(__name__)


class CDCService:
    def __init__(
        self,
        mysql_config: Optional[CDCMySQLConfig] = None,
        pg_config: Optional[CDCPostgreSQLConfig] = None,
        kafka_config: Optional[KafkaConfig] = None,
    ):
        self._mysql_config = mysql_config
        self._pg_config = pg_config
        self._binlog_parser = BinlogParser(mysql_config) if mysql_config else None
        self._wal_parser = WALParser(pg_config) if pg_config else None
        self._serializer = CDCEventSerializer(SerializationFormat.DEBEZIUM_JSON)
        self._output_adapter = CDCOutputAdapter()

        if kafka_config:
            kafka_producer = KafkaProducer(kafka_config)
            self._output_adapter.add_destination("kafka", OutputDestination.KAFKA, {
                "topic": kafka_config.topics.get("cdc_events", "streamsql.cdc.events"),
            })
            self._output_adapter._kafka_producer = kafka_producer

        self._running = False
        self._event_handlers: List[Any] = []

    def add_event_handler(self, handler) -> None:
        self._event_handlers.append(handler)

    def add_output_destination(self, name: str, dest_type: str, config: Dict[str, Any]) -> None:
        self._output_adapter.add_destination(name, dest_type, config)

    def set_serialization_format(self, format_type: str) -> None:
        self._serializer = CDCEventSerializer(SerializationFormat(format_type))
        self._output_adapter.set_serializer_format(SerializationFormat(format_type))

    def start_mysql_cdc(self) -> None:
        if not self._binlog_parser:
            raise ValueError("MySQL CDC not configured")
        self._running = True
        self._binlog_parser.start()
        logger.info("MySQL CDC started")

    def stop_mysql_cdc(self) -> None:
        self._running = False
        if self._binlog_parser:
            self._binlog_parser.stop()
        logger.info("MySQL CDC stopped")

    def process_binlog_event(self, event: BinlogEvent) -> Optional[Dict[str, Any]]:
        if event.row_type is None:
            return None

        op_map = {
            RowEventType.INSERT: CDCOperation.CREATE,
            RowEventType.UPDATE: CDCOperation.UPDATE,
            RowEventType.DELETE: CDCOperation.DELETE,
        }
        operation = op_map.get(event.row_type)
        if operation is None:
            return None

        cdc_event = CDCEvent(
            operation=operation,
            source_database=event.database,
            source_table=event.table,
            timestamp=int(time.time() * 1000),
            before=event.before_data,
            after=event.after_data,
            changed_columns=event.changed_columns,
            transaction_id=event.transaction_id or "",
            lsn=str(event.log_position),
            metadata={"server_id": event.server_id, "gtid": event.gtid},
        )

        emit_results = self._output_adapter.emit(cdc_event)

        for handler in self._event_handlers:
            try:
                handler(cdc_event)
            except Exception as e:
                logger.error(f"Event handler failed: {e}")

        return {
            "event_id": cdc_event.event_id,
            "operation": cdc_event.operation.value,
            "database": cdc_event.source_database,
            "table": cdc_event.source_table,
            "emit_results": emit_results,
        }

    def process_wal_event(self, event: WALEvent) -> Optional[Dict[str, Any]]:
        op_map = {
            WALEventType.INSERT: CDCOperation.CREATE,
            WALEventType.UPDATE: CDCOperation.UPDATE,
            WALEventType.DELETE: CDCOperation.DELETE,
        }
        operation = op_map.get(event.event_type)
        if operation is None:
            return None

        cdc_event = CDCEvent(
            operation=operation,
            source_database=event.database,
            source_schema=event.schema,
            source_table=event.table,
            timestamp=int(time.time() * 1000),
            before=event.before_data,
            after=event.after_data,
            changed_columns=event.changed_columns,
            transaction_id=event.transaction_id,
            lsn=event.lsn,
        )

        emit_results = self._output_adapter.emit(cdc_event)

        for handler in self._event_handlers:
            try:
                handler(cdc_event)
            except Exception as e:
                logger.error(f"Event handler failed: {e}")

        return {
            "event_id": cdc_event.event_id,
            "operation": cdc_event.operation.value,
            "database": cdc_event.source_database,
            "table": cdc_event.source_table,
            "emit_results": emit_results,
        }

    def parse_binlog_file(self, file_path: str) -> List[Dict[str, Any]]:
        if not self._binlog_parser:
            raise ValueError("MySQL CDC not configured")
        results = []
        for event in self._binlog_parser.parse_file(file_path):
            result = self.process_binlog_event(event)
            if result:
                results.append(result)
        return results

    def parse_wal_message(self, message: str) -> Optional[Dict[str, Any]]:
        if not self._wal_parser:
            raise ValueError("PostgreSQL CDC not configured")
        event = self._wal_parser.parse_message(message)
        if event:
            return self.process_wal_event(event)
        return None

    def get_status(self) -> Dict[str, Any]:
        return {
            "running": self._running,
            "mysql_configured": self._mysql_config is not None,
            "postgresql_configured": self._pg_config is not None,
            "serialization_format": self._serializer.format.value,
            "destinations": self._output_adapter.get_destinations(),
            "event_handlers": len(self._event_handlers),
        }
