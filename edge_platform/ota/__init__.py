"""固件OTA升级模块 - 差分升级包生成，分批灰度升级与失败自动回滚"""

from .ota_manager import (
    OTAManager,
    FirmwareVersion,
    DeltaPackage,
    UpgradeBatch,
    UpgradeStatus,
    DeviceUpgradeRecord
)

__all__ = [
    "OTAManager",
    "FirmwareVersion",
    "DeltaPackage",
    "UpgradeBatch",
    "UpgradeStatus",
    "DeviceUpgradeRecord"
]
