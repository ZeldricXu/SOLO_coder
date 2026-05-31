from typing import Optional
from contextlib import asynccontextmanager

from fastapi import FastAPI

from infrastructure.persistence.database import init_db, SessionLocal
from infrastructure.persistence.repositories.device_repository import DeviceRepository
from infrastructure.persistence.repositories.device_shadow_repository import DeviceShadowRepository
from infrastructure.persistence.repositories.telemetry_repository import TelemetryRepository
from infrastructure.persistence.repositories.rule_repository import RuleRepository
from infrastructure.persistence.repositories.inference_repository import InferenceRepository
from infrastructure.persistence.repositories.ota_repository import OTARepository
from infrastructure.persistence.repositories.offline_cache_repository import OfflineCacheRepository

from modules.protocol_adapter.service import ProtocolAdapterService
from modules.device_lifecycle.service import DeviceLifecycleService
from modules.device_shadow.service import DeviceShadowService
from modules.edge_inference.service import EdgeInferenceService
from modules.data_aggregation.service import DataAggregationService
from modules.rule_engine.service import RuleEngineService
from modules.offline_cache.service import OfflineCacheService
from modules.ota_upgrade.service import OTAService as OTAUpgradeService

from application.services.device_service import DeviceService
from application.services.telemetry_service import TelemetryService
from application.services.inference_service import InferenceService
from application.services.ota_service import OTAService

from interfaces.api.v1.device_routes import set_device_service
from interfaces.api.v1.telemetry_routes import set_telemetry_service
from interfaces.api.v1.inference_routes import set_inference_service
from interfaces.api.v1.ota_routes import set_ota_service

from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class ServiceContainer:
    def __init__(self):
        self._db_initialized = False
        self._repositories = {}
        self._module_services = {}
        self._application_services = {}

    def initialize(self):
        logger.info("Initializing service container...")

        init_db()
        self._db_initialized = True
        logger.info("Database initialized")

        self._init_repositories()
        self._init_module_services()
        self._init_application_services()
        self._register_route_services()

        logger.info("Service container initialized successfully")

    def _init_repositories(self):
        db = SessionLocal()
        self._repositories = {
            "device": DeviceRepository(db),
            "device_shadow": DeviceShadowRepository(db),
            "telemetry": TelemetryRepository(db),
            "rule": RuleRepository(db),
            "inference": InferenceRepository(db),
            "ota": OTARepository(db),
            "offline_cache": OfflineCacheRepository(db),
        }
        db.close()
        logger.info("Repositories initialized")

    def _init_module_services(self):
        self._module_services = {
            "protocol_adapter": ProtocolAdapterService(
                device_repo=self._repositories["device"]
            ),
            "device_lifecycle": DeviceLifecycleService(
                device_repo=self._repositories["device"]
            ),
            "device_shadow": DeviceShadowService(
                shadow_repo=self._repositories["device_shadow"],
                device_repo=self._repositories["device"],
            ),
            "edge_inference": EdgeInferenceService(
                inference_repo=self._repositories["inference"]
            ),
            "data_aggregation": DataAggregationService(
                telemetry_repo=self._repositories["telemetry"]
            ),
            "rule_engine": RuleEngineService(
                rule_repo=self._repositories["rule"],
                event_bus=None,
            ),
            "offline_cache": OfflineCacheService(
                cache_repo=self._repositories["offline_cache"]
            ),
            "ota_upgrade": OTAUpgradeService(
                ota_repo=self._repositories["ota"],
                device_repo=self._repositories["device"],
            ),
        }
        logger.info("Module services initialized")

    def _init_application_services(self):
        self._application_services = {
            "device": DeviceService(
                lifecycle_service=self._module_services["device_lifecycle"],
                shadow_service=self._module_services["device_shadow"],
                protocol_adapter=self._module_services["protocol_adapter"],
                rule_engine=self._module_services["rule_engine"],
            ),
            "telemetry": TelemetryService(
                aggregation_service=self._module_services["data_aggregation"],
                offline_cache=self._module_services["offline_cache"],
                protocol_adapter=self._module_services["protocol_adapter"],
                rule_engine=self._module_services["rule_engine"],
                shadow_service=self._module_services["device_shadow"],
            ),
            "inference": InferenceService(
                edge_inference=self._module_services["edge_inference"],
                offline_cache=self._module_services["offline_cache"],
            ),
            "ota": OTAService(
                ota_upgrade_service=self._module_services["ota_upgrade"],
            ),
        }
        logger.info("Application services initialized")

    def _register_route_services(self):
        set_device_service(self._application_services["device"])
        set_telemetry_service(self._application_services["telemetry"])
        set_inference_service(self._application_services["inference"])
        set_ota_service(self._application_services["ota"])
        logger.info("Route services registered")

    def start_background_services(self):
        logger.info("Starting background services...")
        self._module_services["edge_inference"].start()
        self._module_services["data_aggregation"].start()
        self._module_services["offline_cache"].start()
        self._module_services["ota_upgrade"].start()
        logger.info("Background services started")

    def stop_background_services(self):
        logger.info("Stopping background services...")
        self._module_services["edge_inference"].stop()
        self._module_services["data_aggregation"].stop()
        self._module_services["offline_cache"].stop()
        self._module_services["ota_upgrade"].stop()
        logger.info("Background services stopped")

    def get_device_service(self) -> DeviceService:
        return self._application_services["device"]

    def get_telemetry_service(self) -> TelemetryService:
        return self._application_services["telemetry"]

    def get_inference_service(self) -> InferenceService:
        return self._application_services["inference"]

    def get_ota_service(self) -> OTAService:
        return self._application_services["ota"]


_container: Optional[ServiceContainer] = None


def get_container() -> ServiceContainer:
    global _container
    if _container is None:
        _container = ServiceContainer()
        _container.initialize()
    return _container


@asynccontextmanager
async def lifespan(app: FastAPI):
    container = get_container()
    container.start_background_services()
    yield
    container.stop_background_services()
