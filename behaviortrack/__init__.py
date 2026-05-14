from .config import settings
from .storage import MongoStorage
from .modules import (
    BehaviorCollector,
    EventAnalyzer,
    TrajectoryAnalyzer,
    UserProfiler,
    StatisticsModule,
    QueryModule,
    ExportModule,
    VisualizationModule
)
from .api import create_app

__version__ = "1.0.0"
__all__ = [
    "settings",
    "MongoStorage",
    "BehaviorCollector",
    "EventAnalyzer",
    "TrajectoryAnalyzer",
    "UserProfiler",
    "StatisticsModule",
    "QueryModule",
    "ExportModule",
    "VisualizationModule",
    "create_app"
]
