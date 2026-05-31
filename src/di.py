from typing import Optional
from src.domain import (
    DocumentPipelineService,
    FeatureStoreService,
    APIGatewayService,
)
from src.modules import (
    SchedulerService,
    ModelRegistryService,
    EvaluationDashboardService,
    StorageManagerService,
    GpuSchedulerService,
    DataAccessService,
    NotificationService,
)
import logging

logger = logging.getLogger(__name__)


class DIContainer:
    _instance: Optional["DIContainer"] = None
    _initialized: bool = False

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True

        self._document_pipeline: Optional[DocumentPipelineService] = None
        self._feature_store: Optional[FeatureStoreService] = None
        self._api_gateway: Optional[APIGatewayService] = None
        self._scheduler: Optional[SchedulerService] = None
        self._model_registry: Optional[ModelRegistryService] = None
        self._evaluation_dashboard: Optional[EvaluationDashboardService] = None
        self._storage_manager: Optional[StorageManagerService] = None
        self._gpu_scheduler: Optional[GpuSchedulerService] = None
        self._data_access: Optional[DataAccessService] = None
        self._notification: Optional[NotificationService] = None

        logger.info("DI Container initialized")

    @property
    def document_pipeline(self) -> DocumentPipelineService:
        if self._document_pipeline is None:
            self._document_pipeline = DocumentPipelineService()
            logger.info("DocumentPipelineService created")
        return self._document_pipeline

    @property
    def feature_store(self) -> FeatureStoreService:
        if self._feature_store is None:
            self._feature_store = FeatureStoreService()
            logger.info("FeatureStoreService created")
        return self._feature_store

    @property
    def api_gateway(self) -> APIGatewayService:
        if self._api_gateway is None:
            self._api_gateway = APIGatewayService()
            logger.info("APIGatewayService created")
        return self._api_gateway

    @property
    def scheduler(self) -> SchedulerService:
        if self._scheduler is None:
            self._scheduler = SchedulerService()
            logger.info("SchedulerService created")
        return self._scheduler

    @property
    def model_registry(self) -> ModelRegistryService:
        if self._model_registry is None:
            self._model_registry = ModelRegistryService()
            logger.info("ModelRegistryService created")
        return self._model_registry

    @property
    def evaluation_dashboard(self) -> EvaluationDashboardService:
        if self._evaluation_dashboard is None:
            self._evaluation_dashboard = EvaluationDashboardService()
            logger.info("EvaluationDashboardService created")
        return self._evaluation_dashboard

    @property
    def storage_manager(self) -> StorageManagerService:
        if self._storage_manager is None:
            self._storage_manager = StorageManagerService()
            logger.info("StorageManagerService created")
        return self._storage_manager

    @property
    def gpu_scheduler(self) -> GpuSchedulerService:
        if self._gpu_scheduler is None:
            self._gpu_scheduler = GpuSchedulerService()
            logger.info("GpuSchedulerService created")
        return self._gpu_scheduler

    @property
    def data_access(self) -> DataAccessService:
        if self._data_access is None:
            self._data_access = DataAccessService()
            logger.info("DataAccessService created")
        return self._data_access

    @property
    def notification(self) -> NotificationService:
        if self._notification is None:
            self._notification = NotificationService()
            logger.info("NotificationService created")
        return self._notification

    async def close(self):
        logger.info("Closing DI Container")
        if self._api_gateway:
            await self._api_gateway.close()


container = DIContainer()


def get_container() -> DIContainer:
    return container
