pub mod model;
pub mod service;
pub mod handler;

pub use model::{
    FirmwarePackage, UpgradeTask, DeviceUpgradeStatus, GrayStrategy, RollbackPolicy,
    UpgradePhase, GrayStrategyType, RollbackTrigger, DeltaPackage,
    UploadFirmwareRequest, CreateUpgradeTaskRequest, ApproveUpgradeRequest,
    DeviceStatusUpdateRequest, FirmwareResponse, UpgradeTaskResponse,
    DeviceStatusResponse, GenerateDeltaRequest, DeltaResponse, RollbackRequest,
    UpgradeStatistics,
};

pub use service::OtaUpgradeService;
pub use handler::{routes, OtaUpgradeHandler};
