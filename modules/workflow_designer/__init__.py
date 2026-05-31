from .models import (
    WorkflowDefinition,
    WorkflowInstance,
    WorkflowNodeExecution,
    NodeType,
    EdgeType,
    WorkflowStatus,
    InstanceStatus,
    NodeStatus,
    WorkflowCreate,
    WorkflowUpdate,
    WorkflowResponse,
    WorkflowInstanceCreate,
    WorkflowInstanceResponse,
    NodeExecutionResponse,
    ValidationError,
)
from .service import (
    WorkflowValidationService,
    WorkflowDesignerService,
    WorkflowEngineService,
)
from .router import router

__all__ = [
    "WorkflowDefinition",
    "WorkflowInstance",
    "WorkflowNodeExecution",
    "NodeType",
    "EdgeType",
    "WorkflowStatus",
    "InstanceStatus",
    "NodeStatus",
    "WorkflowCreate",
    "WorkflowUpdate",
    "WorkflowResponse",
    "WorkflowInstanceCreate",
    "WorkflowInstanceResponse",
    "NodeExecutionResponse",
    "ValidationError",
    "WorkflowValidationService",
    "WorkflowDesignerService",
    "WorkflowEngineService",
    "router",
]


class WorkflowDesignerModule:
    name = "workflow_designer"
    description = "拖拽式流程设计器，节点配置与连线规则校验模块"
    router = router

    def __init__(self):
        pass
