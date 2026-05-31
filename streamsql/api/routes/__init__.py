from streamsql.api.routes.metadata import router as metadata_router
from streamsql.api.routes.cdc import router as cdc_router
from streamsql.api.routes.query import router as query_router
from streamsql.api.routes.vector import router as vector_router
from streamsql.api.routes.lifecycle import router as lifecycle_router
from streamsql.api.routes.lineage import router as lineage_router
from streamsql.api.routes.timeseries import router as timeseries_router
from streamsql.api.routes.quality import router as quality_router
from streamsql.api.routes.resources import router as resources_router

__all__ = [
    "metadata_router",
    "cdc_router",
    "query_router",
    "vector_router",
    "lifecycle_router",
    "lineage_router",
    "timeseries_router",
    "quality_router",
    "resources_router",
]
