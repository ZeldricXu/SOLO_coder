from infrastructure.persistence.models.device_model import DeviceModel
from infrastructure.persistence.models.shadow_model import DeviceShadowModel
from infrastructure.persistence.models.telemetry_model import TelemetryDataModel, AggregatedDataModel
from infrastructure.persistence.models.rule_model import RuleModel
from infrastructure.persistence.models.inference_model import AIModelModel, InferenceTaskModel, InferenceResultModel
from infrastructure.persistence.models.ota_model import OTAPackageModel, UpgradeTaskModel
from infrastructure.persistence.models.event_model import EventModel
from infrastructure.persistence.models.offline_cache_model import OfflineCacheModel

all_models = [
    DeviceModel,
    DeviceShadowModel,
    TelemetryDataModel,
    AggregatedDataModel,
    RuleModel,
    AIModelModel,
    InferenceTaskModel,
    InferenceResultModel,
    OTAPackageModel,
    UpgradeTaskModel,
    EventModel,
    OfflineCacheModel,
]

__all__ = [
    "DeviceModel",
    "DeviceShadowModel",
    "TelemetryDataModel",
    "AggregatedDataModel",
    "RuleModel",
    "AIModelModel",
    "InferenceTaskModel",
    "InferenceResultModel",
    "OTAPackageModel",
    "UpgradeTaskModel",
    "EventModel",
    "OfflineCacheModel",
    "all_models",
]
