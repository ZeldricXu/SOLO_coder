"""Quick import test for the project."""
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "src"))

print("Testing imports...")

try:
    from domain.models.common import EventMessage, ProcessingResult, ProcessingStatus
    print("✓ domain.models.common imported successfully")
except Exception as e:
    print(f"✗ domain.models.common failed: {e}")

try:
    from infrastructure.config.settings import Settings, get_settings
    print("✓ infrastructure.config.settings imported successfully")
except Exception as e:
    print(f"✗ infrastructure.config.settings failed: {e}")

try:
    from modules.storage.storage_module import StorageModule
    print("✓ modules.storage.storage_module imported successfully")
except Exception as e:
    print(f"✗ modules.storage.storage_module failed: {e}")

try:
    from modules.timeseries.timeseries_module import TimeSeriesModule
    print("✓ modules.timeseries.timeseries_module imported successfully")
except Exception as e:
    print(f"✗ modules.timeseries.timeseries_module failed: {e}")

try:
    from modules.gateway.gateway_module import GatewayModule
    print("✓ modules.gateway.gateway_module imported successfully")
except Exception as e:
    print(f"✗ modules.gateway.gateway_module failed: {e}")

try:
    from modules.data_access.data_access_module import DataAccessModule
    print("✓ modules.data_access.data_access_module imported successfully")
except Exception as e:
    print(f"✗ modules.data_access.data_access_module failed: {e}")

try:
    from modules.metadata_crawler.metadata_crawler import MetadataCrawler
    print("✓ modules.metadata_crawler.metadata_crawler imported successfully")
except Exception as e:
    print(f"✗ modules.metadata_crawler.metadata_crawler failed: {e}")

try:
    from modules.scheduler.scheduler_module import SchedulerModule
    print("✓ modules.scheduler.scheduler_module imported successfully")
except Exception as e:
    print(f"✗ modules.scheduler.scheduler_module failed: {e}")

try:
    from modules.logging.logging_module import LoggingModule
    print("✓ modules.logging.logging_module imported successfully")
except Exception as e:
    print(f"✗ modules.logging.logging_module failed: {e}")

try:
    from modules.data_quality.data_quality_module import DataQualityModule
    print("✓ modules.data_quality.data_quality_module imported successfully")
except Exception as e:
    print(f"✗ modules.data_quality.data_quality_module failed: {e}")

try:
    from modules.streaming_query.streaming_query_module import StreamingQueryModule
    print("✓ modules.streaming_query.streaming_query_module imported successfully")
except Exception as e:
    print(f"✗ modules.streaming_query.streaming_query_module failed: {e}")

try:
    from modules.vector_index.vector_index_module import VectorIndexModule
    print("✓ modules.vector_index.vector_index_module imported successfully")
except Exception as e:
    print(f"✗ modules.vector_index.vector_index_module failed: {e}")

print("\nAll imports tested!")
