from src.api.rest.query_api import router as query_router
from src.api.rest.lineage_api import router as lineage_router
from src.api.rest.lifecycle_api import router as lifecycle_router
from src.api.rest.cdc_api import router as cdc_router
from src.api.rest.metadata_api import router as metadata_router
from src.api.rest.vector_api import router as vector_router
from src.api.rest.timeseries_api import router as timeseries_router
from src.api.rest.quality_api import router as quality_router

__all__ = [
    "query_router",
    "lineage_router",
    "lifecycle_router",
    "cdc_router",
    "metadata_router",
    "vector_router",
    "timeseries_router",
    "quality_router",
]
