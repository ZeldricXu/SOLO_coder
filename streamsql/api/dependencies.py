from streamsql.services.metadata_service import MetadataService
from streamsql.services.cdc_service import CDCService
from streamsql.services.query_service import QueryService
from streamsql.services.vector_service import VectorService
from streamsql.services.lifecycle_service import LifecycleService
from streamsql.services.lineage_service import LineageService
from streamsql.services.timeseries_service import TimeSeriesService
from streamsql.services.quality_service import QualityService


class ServiceContainer:
    def __init__(self):
        self.metadata_service = MetadataService()
        self.cdc_service = CDCService()
        self.query_service = QueryService()
        self.vector_service = VectorService()
        self.lifecycle_service = LifecycleService()
        self.lineage_service = LineageService()
        self.timeseries_service = TimeSeriesService()
        self.quality_service = QualityService()


container = ServiceContainer()


def get_metadata_service() -> MetadataService:
    return container.metadata_service


def get_cdc_service() -> CDCService:
    return container.cdc_service


def get_query_service() -> QueryService:
    return container.query_service


def get_vector_service() -> VectorService:
    return container.vector_service


def get_lifecycle_service() -> LifecycleService:
    return container.lifecycle_service


def get_lineage_service() -> LineageService:
    return container.lineage_service


def get_timeseries_service() -> TimeSeriesService:
    return container.timeseries_service


def get_quality_service() -> QualityService:
    return container.quality_service
