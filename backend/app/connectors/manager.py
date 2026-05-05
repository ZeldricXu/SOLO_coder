from typing import Dict, Any, List, Optional
import asyncio
import logging

from app.connectors.base import BaseConnector
from app.connectors.mysql_connector import MySQLConnector
from app.connectors.kafka_connector import KafkaConnector
from app.core.models import DataSourceConfig, DataSourceType, RawDataEvent

logger = logging.getLogger(__name__)


class ConnectorManager:
    def __init__(self):
        self._connectors: Dict[str, BaseConnector] = {}
        self._on_data_callback: Optional[Any] = None
        self._running = False

    def _create_connector(self, config: DataSourceConfig) -> BaseConnector:
        connector_classes = {
            DataSourceType.MYSQL: MySQLConnector,
            DataSourceType.KAFKA: KafkaConnector,
        }

        connector_class = connector_classes.get(config.source_type)
        if not connector_class:
            raise ValueError(f"Unsupported data source type: {config.source_type}")

        return connector_class(config)

    async def register_connector(self, config: DataSourceConfig) -> bool:
        if config.source_id in self._connectors:
            logger.warning(f"Connector {config.source_id} already registered")
            return False

        try:
            connector = self._create_connector(config)
            if self._on_data_callback:
                connector.set_data_callback(self._on_data_callback)

            self._connectors[config.source_id] = connector
            logger.info(f"Registered connector: {config.source_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to register connector {config.source_id}: {e}")
            return False

    async def unregister_connector(self, source_id: str) -> bool:
        if source_id not in self._connectors:
            logger.warning(f"Connector {source_id} not found")
            return False

        try:
            connector = self._connectors[source_id]
            await connector.stop_listening()
            await connector.disconnect()
            del self._connectors[source_id]
            logger.info(f"Unregistered connector: {source_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to unregister connector {source_id}: {e}")
            return False

    async def start_all(self):
        self._running = True
        for source_id, connector in self._connectors.items():
            try:
                await connector.connect()
                await connector.start_listening()
            except Exception as e:
                logger.error(f"Failed to start connector {source_id}: {e}")

    async def stop_all(self):
        self._running = False
        for source_id, connector in self._connectors.items():
            try:
                await connector.stop_listening()
                await connector.disconnect()
            except Exception as e:
                logger.error(f"Failed to stop connector {source_id}: {e}")

    def set_data_callback(self, callback):
        self._on_data_callback = callback
        for connector in self._connectors.values():
            connector.set_data_callback(callback)

    def get_connector(self, source_id: str) -> Optional[BaseConnector]:
        return self._connectors.get(source_id)

    def get_all_status(self) -> List[Dict[str, Any]]:
        return [
            connector.get_status()
            for connector in self._connectors.values()
        ]

    async def start_connector(self, source_id: str) -> bool:
        connector = self._connectors.get(source_id)
        if not connector:
            logger.warning(f"Connector {source_id} not found")
            return False

        try:
            await connector.connect()
            await connector.start_listening()
            return True
        except Exception as e:
            logger.error(f"Failed to start connector {source_id}: {e}")
            return False

    async def stop_connector(self, source_id: str) -> bool:
        connector = self._connectors.get(source_id)
        if not connector:
            logger.warning(f"Connector {source_id} not found")
            return False

        try:
            await connector.stop_listening()
            await connector.disconnect()
            return True
        except Exception as e:
            logger.error(f"Failed to stop connector {source_id}: {e}")
            return False


connector_manager = ConnectorManager()
