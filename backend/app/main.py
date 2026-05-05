from contextlib import asynccontextmanager
from typing import Dict, Any, List
import logging
import sys

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from app.core.config import settings
from app.core.models import (
    RawDataEvent,
    CleanedDataEvent,
    MetricResult,
    AlertNotification,
    MetricConfig,
    NotificationChannelType
)
from app.api.routes import router as api_router
from app.connectors.manager import connector_manager
from app.connectors.kafka_connector import KafkaConnector
from app.pipeline.manager import pipeline_manager
from app.metrics.manager import metric_manager
from app.storage.influxdb_store import influxdb_store
from app.visualization.websocket_manager import websocket_manager
from app.alerts.engine import alert_engine
from app.core.models import AlertRule

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)
    ]
)

logger = logging.getLogger(__name__)


class DataFlowOrchestrator:
    def __init__(self):
        self._setup_callbacks()

    def _setup_callbacks(self):
        connector_manager.set_data_callback(self._on_raw_data)
        pipeline_manager.set_global_callback(self._on_cleaned_data)
        metric_manager.set_result_callback(self._on_metric_result)
        alert_engine.set_alert_callback(self._on_alert)

    def _on_raw_data(self, event: RawDataEvent):
        logger.debug(f"Received raw data from {event.source}")
        pipeline_manager.process_event(event)

    def _on_cleaned_data(self, event: CleanedDataEvent):
        logger.debug(f"Processed cleaned data from {event.source}, quality: {event.quality_score}")

        try:
            metric_results = metric_manager.process_event(event)

            if event.quality_score >= 0.8:
                import asyncio
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    asyncio.create_task(influxdb_store.write_cleaned_event(event))

            if metric_results:
                for result in metric_results:
                    self._on_metric_result(result)

            self._acknowledge_message_success(event)

        except Exception as e:
            logger.error(f"Error processing cleaned data from {event.source}: {e}")
            self._acknowledge_message_failure(event, str(e))
            raise

    def _acknowledge_message_success(self, event: CleanedDataEvent):
        if not event.message_id:
            return

        connector = connector_manager.get_connector(event.source)
        if isinstance(connector, KafkaConnector):
            import asyncio
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.create_task(
                    connector.acknowledge_message_success(event.message_id)
                )

    def _acknowledge_message_failure(self, event: CleanedDataEvent, error: str):
        if not event.message_id:
            return

        connector = connector_manager.get_connector(event.source)
        if isinstance(connector, KafkaConnector):
            import asyncio
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.create_task(
                    connector.acknowledge_message_failure(event.message_id, error)
                )

    def _on_metric_result(self, result: MetricResult):
        logger.debug(f"Metric result: {result.metric_id} = {result.value}")

        config = metric_manager.get_metric_config(result.metric_id)
        if config:
            import asyncio
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.create_task(
                    websocket_manager.broadcast_metric(
                        result,
                        config.chart_type or "line"
                    )
                )

                if result.window_end:
                    asyncio.create_task(influxdb_store.write_metric(result))

                alerts = alert_engine.check_metric(result, config)
                for alert in alerts:
                    self._on_alert(alert, config.alert_rules)

    def _on_alert(self, notification: AlertNotification, rules: List[AlertRule] = None):
        logger.warning(
            f"Alert triggered: {notification.alert_id} - {notification.message}"
        )

        channel_types = [NotificationChannelType.SLACK]

        if rules:
            for rule in rules:
                for ct in rule.get_notify_channels():
                    if ct not in channel_types:
                        channel_types.append(ct)

        import asyncio
        loop = asyncio.get_event_loop()
        if loop.is_running():
            asyncio.create_task(
                alert_engine.queue_alert(notification, channel_types=channel_types)
            )


orchestrator = DataFlowOrchestrator()


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting DataFlow platform...")

    logger.info("Loading pipeline configurations from YAML...")
    loaded_count = await pipeline_manager.load_from_yaml()
    logger.info(f"Loaded {loaded_count} pipelines from YAML config")

    if settings.PIPELINE_AUTO_RELOAD:
        await pipeline_manager.start_auto_reload()
        logger.info("Started pipeline auto-reload task")

    await influxdb_store.connect()
    await influxdb_store.start()

    await websocket_manager.start()

    await alert_engine.start()

    logger.info("DataFlow platform started successfully")

    yield

    logger.info("Shutting down DataFlow platform...")

    await connector_manager.stop_all()

    if settings.PIPELINE_AUTO_RELOAD:
        await pipeline_manager.stop_auto_reload()

    await influxdb_store.stop()
    await influxdb_store.disconnect()

    await websocket_manager.stop()

    await alert_engine.stop()

    logger.info("DataFlow platform shutdown complete")


app = FastAPI(
    title=settings.APP_NAME,
    version=settings.APP_VERSION,
    description="DataFlow 实时数据流分析与可视化监控平台",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(api_router, prefix="/api/v1")


@app.get("/")
async def root():
    return {
        "name": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "description": "DataFlow 实时数据流分析与可视化监控平台",
        "docs": "/docs",
        "api_prefix": "/api/v1"
    }


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()

    client_id = await websocket_manager.connect(websocket)

    try:
        while True:
            data = await websocket.receive_text()
            await websocket_manager.handle_message(client_id, data)

    except WebSocketDisconnect:
        await websocket_manager.disconnect(client_id)
    except Exception as e:
        logger.error(f"WebSocket error for {client_id}: {e}")
        await websocket_manager.disconnect(client_id)
