from .diff_generator import DeltaGenerator
from .models import (
    DeltaPackage,
    DeviceUpgradeRecord,
    FirmwareVersion,
    OTAUpgradeTask,
)
from .routes import router as ota_router
from .schemas import (
    DeltaGenerationRequest,
    FirmwareVersionCreate,
    FirmwareVersionResponse,
    FirmwareVersionUpdate,
    OTAUpgradeTaskCreate,
    OTAUpgradeTaskResponse,
    OTAUpgradeTaskUpdate,
    RollbackRequest,
    UpgradeProgressUpdate,
)
from .service import FirmwareOTAService
from .upgrade_manager import UpgradeManager, UpgradePhase, UpgradeStrategy, upgrade_manager

__all__ = [
    "DeltaGenerator",
    "FirmwareVersion",
    "DeltaPackage",
    "OTAUpgradeTask",
    "DeviceUpgradeRecord",
    "ota_router",
    "FirmwareVersionCreate",
    "FirmwareVersionResponse",
    "FirmwareVersionUpdate",
    "OTAUpgradeTaskCreate",
    "OTAUpgradeTaskResponse",
    "OTAUpgradeTaskUpdate",
    "DeltaGenerationRequest",
    "UpgradeProgressUpdate",
    "RollbackRequest",
    "FirmwareOTAService",
    "UpgradeManager",
    "UpgradeStrategy",
    "UpgradePhase",
    "upgrade_manager",
]
