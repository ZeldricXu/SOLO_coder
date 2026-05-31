from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Set, Tuple

from sqlalchemy import select, and_
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ValidationError, NotFoundError, ConflictError
from core.utils import validate_params, utc_now
from .models import (
    WorkflowDefinition,
    WorkflowCreate,
    WorkflowUpdate,
    WorkflowResponse,
    WorkflowInstance,
    WorkflowInstanceCreate,
    WorkflowInstanceResponse,
    WorkflowNodeExecution,
    NodeExecutionResponse,
    NodeType,
    EdgeType,
    WorkflowStatus,
    InstanceStatus,
    NodeStatus,
    ValidationError as WorkflowValidationError,
)


class WorkflowValidationService:
    def __init__(self):
        pass

    def validate_node(self, node: Dict[str, Any]) -> List[WorkflowValidationError]:
        errors = []
        node_id = node.get("id")
        node_type = node.get("type")

        if not node_id:
            errors.append(
                WorkflowValidationError(
                    node_id=None,
                    edge_id=None,
                    error_type="missing_id",
                    message="节点缺少唯一ID",
                )
            )
            return errors

        if not node_type:
            errors.append(
                WorkflowValidationError(
                    node_id=node_id,
                    edge_id=None,
                    error_type="missing_type",
                    message=f"节点 {node_id} 缺少类型",
                )
            )
            return errors

        try:
            NodeType(node_type)
        except ValueError:
            errors.append(
                WorkflowValidationError(
                    node_id=node_id,
                    edge_id=None,
                    error_type="invalid_type",
                    message=f"节点 {node_id} 类型 {node_type} 无效",
                )
            )

        config = node.get("config", {})

        if node_type == NodeType.CONDITION:
            if "expression" not in config:
                errors.append(
                    WorkflowValidationError(
                        node_id=node_id,
                        edge_id=None,
                        error_type="missing_config",
                        message=f"条件节点 {node_id} 缺少表达式配置",
                    )
                )

        if node_type in [NodeType.TASK, NodeType.APPROVAL, NodeType.WEBHOOK]:
            if not config.get("handler"):
                errors.append(
                    WorkflowValidationError(
                        node_id=node_id,
                        edge_id=None,
                        error_type="missing_handler",
                        message=f"节点 {node_id} 缺少处理程序配置",
                    )
                )

        return errors

    def validate_edge(
        self, edge: Dict[str, Any], node_ids: Set[str]
    ) -> List[WorkflowValidationError]:
        errors = []
        edge_id = edge.get("id")
        source = edge.get("source")
        target = edge.get("target")

        if not edge_id:
            errors.append(
                WorkflowValidationError(
                    node_id=None,
                    edge_id=None,
                    error_type="missing_id",
                    message="连线缺少唯一ID",
                )
            )
            return errors

        if not source or source not in node_ids:
            errors.append(
                WorkflowValidationError(
                    node_id=source,
                    edge_id=edge_id,
                    error_type="invalid_source",
                    message=f"连线 {edge_id} 的源节点 {source} 不存在",
                )
            )

        if not target or target not in node_ids:
            errors.append(
                WorkflowValidationError(
                    node_id=target,
                    edge_id=edge_id,
                    error_type="invalid_target",
                    message=f"连线 {edge_id} 的目标节点 {target} 不存在",
                )
            )

        edge_type = edge.get("type", EdgeType.SEQUENCE)
        try:
            EdgeType(edge_type)
        except ValueError:
            errors.append(
                WorkflowValidationError(
                    node_id=None,
                    edge_id=edge_id,
                    error_type="invalid_type",
                    message=f"连线 {edge_id} 类型 {edge_type} 无效",
                )
            )

        return errors

    def validate_workflow_structure(
        self, nodes: List[Dict[str, Any]], edges: List[Dict[str, Any]]
    ) -> List[WorkflowValidationError]:
        errors = []

        node_ids = {node.get("id") for node in nodes if node.get("id")}

        start_nodes = [n for n in nodes if n.get("type") == NodeType.START]
        end_nodes = [n for n in nodes if n.get("type") == NodeType.END]

        if len(start_nodes) == 0:
            errors.append(
                WorkflowValidationError(
                    node_id=None,
                    edge_id=None,
                    error_type="missing_start",
                    message="流程必须包含至少一个开始节点",
                )
            )
        elif len(start_nodes) > 1:
            errors.append(
                WorkflowValidationError(
                    node_id=None,
                    edge_id=None,
                    error_type="multiple_starts",
                    message="流程只能有一个开始节点",
                )
            )

        if len(end_nodes) == 0:
            errors.append(
                WorkflowValidationError(
                    node_id=None,
                    edge_id=None,
                    error_type="missing_end",
                    message="流程必须包含至少一个结束节点",
                )
            )

        for node in nodes:
            errors.extend(self.validate_node(node))

        for edge in edges:
            errors.extend(self.validate_edge(edge, node_ids))

        errors.extend(self._validate_connectivity(nodes, edges, node_ids))

        return errors

    def _validate_connectivity(
        self,
        nodes: List[Dict[str, Any]],
        edges: List[Dict[str, Any]],
        node_ids: Set[str],
    ) -> List[WorkflowValidationError]:
        errors = []

        outgoing_edges: Dict[str, List[Dict[str, Any]]] = {nid: [] for nid in node_ids}
        incoming_edges: Dict[str, List[Dict[str, Any]]] = {nid: [] for nid in node_ids}

        for edge in edges:
            source = edge.get("source")
            target = edge.get("target")
            if source in outgoing_edges:
                outgoing_edges[source].append(edge)
            if target in incoming_edges:
                incoming_edges[target].append(edge)

        for node in nodes:
            node_id = node.get("id")
            node_type = node.get("type")

            if node_type == NodeType.START and incoming_edges.get(node_id, []):
                errors.append(
                    WorkflowValidationError(
                        node_id=node_id,
                        edge_id=None,
                        error_type="invalid_incoming",
                        message=f"开始节点 {node_id} 不能有入边",
                    )
                )

            if node_type == NodeType.END and outgoing_edges.get(node_id, []):
                errors.append(
                    WorkflowValidationError(
                        node_id=node_id,
                        edge_id=None,
                        error_type="invalid_outgoing",
                        message=f"结束节点 {node_id} 不能有出边",
                    )
                )

            if node_type not in [NodeType.START, NodeType.END]:
                if not incoming_edges.get(node_id, []):
                    errors.append(
                        WorkflowValidationError(
                            node_id=node_id,
                            edge_id=None,
                            error_type="disconnected",
                            message=f"节点 {node_id} 没有入边，无法到达",
                        )
                    )
                if not outgoing_edges.get(node_id, []):
                    errors.append(
                        WorkflowValidationError(
                            node_id=node_id,
                            edge_id=None,
                            error_type="dead_end",
                            message=f"节点 {node_id} 没有出边，形成死路",
                        )
                    )

            if node_type == NodeType.CONDITION:
                out_edges = outgoing_edges.get(node_id, [])
                conditional_edges = [
                    e for e in out_edges if e.get("type") == EdgeType.CONDITIONAL
                ]
                default_edges = [
                    e for e in out_edges if e.get("type") == EdgeType.DEFAULT
                ]

                if len(conditional_edges) == 0:
                    errors.append(
                        WorkflowValidationError(
                            node_id=node_id,
                            edge_id=None,
                            error_type="missing_conditional",
                            message=f"条件节点 {node_id} 至少需要一个条件分支",
                        )
                    )
                if len(default_edges) > 1:
                    errors.append(
                        WorkflowValidationError(
                            node_id=node_id,
                            edge_id=None,
                            error_type="multiple_defaults",
                            message=f"条件节点 {node_id} 只能有一个默认分支",
                        )
                    )

        return errors


class WorkflowDesignerService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.validation_service = WorkflowValidationService()

    async def create_workflow(self, workflow_data: WorkflowCreate) -> WorkflowResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
        }
        validate_params(workflow_data.model_dump(), validation_rules)

        validation_errors = self.validation_service.validate_workflow_structure(
            workflow_data.nodes, workflow_data.edges
        )

        workflow = WorkflowDefinition(**workflow_data.model_dump())
        self.db.add(workflow)
        await self.db.flush()

        return WorkflowResponse(
            **workflow.__dict__,
            is_valid=len(validation_errors) == 0,
            validation_errors=[e.model_dump() for e in validation_errors],
        )

    async def get_workflow(
        self, workflow_id: str, tenant_id: Optional[str] = None
    ) -> WorkflowResponse:
        query = select(WorkflowDefinition).where(
            WorkflowDefinition.workflow_id == workflow_id
        )
        if tenant_id:
            query = query.where(WorkflowDefinition.tenant_id == tenant_id)

        result = await self.db.execute(query)
        workflow = result.scalar_one_or_none()

        if not workflow:
            raise NotFoundError(f"流程 {workflow_id} 不存在")

        validation_errors = self.validation_service.validate_workflow_structure(
            workflow.nodes, workflow.edges
        )

        return WorkflowResponse(
            **workflow.__dict__,
            is_valid=len(validation_errors) == 0,
            validation_errors=[e.model_dump() for e in validation_errors],
        )

    async def update_workflow(
        self, workflow_id: str, update_data: WorkflowUpdate, tenant_id: Optional[str] = None
    ) -> WorkflowResponse:
        query = select(WorkflowDefinition).where(
            WorkflowDefinition.workflow_id == workflow_id
        )
        if tenant_id:
            query = query.where(WorkflowDefinition.tenant_id == tenant_id)

        result = await self.db.execute(query)
        workflow = result.scalar_one_or_none()

        if not workflow:
            raise NotFoundError(f"流程 {workflow_id} 不存在")

        update_dict = update_data.model_dump(exclude_unset=True)
        for key, value in update_dict.items():
            setattr(workflow, key, value)

        workflow.version += 1

        validation_errors = self.validation_service.validate_workflow_structure(
            workflow.nodes, workflow.edges
        )

        self.db.add(workflow)
        await self.db.flush()

        return WorkflowResponse(
            **workflow.__dict__,
            is_valid=len(validation_errors) == 0,
            validation_errors=[e.model_dump() for e in validation_errors],
        )

    async def validate_workflow(
        self, workflow_id: str, tenant_id: Optional[str] = None
    ) -> Dict[str, Any]:
        workflow = await self.get_workflow(workflow_id, tenant_id)

        return {
            "workflow_id": workflow_id,
            "is_valid": workflow.is_valid,
            "errors": workflow.validation_errors,
            "node_count": len(workflow.nodes),
            "edge_count": len(workflow.edges),
        }

    async def list_workflows(
        self,
        tenant_id: Optional[str] = None,
        status: Optional[WorkflowStatus] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> List[WorkflowResponse]:
        query = select(WorkflowDefinition)
        if tenant_id:
            query = query.where(WorkflowDefinition.tenant_id == tenant_id)
        if status:
            query = query.where(WorkflowDefinition.status == status)

        query = query.order_by(WorkflowDefinition.updated_at.desc()).limit(limit).offset(offset)
        result = await self.db.execute(query)
        workflows = result.scalars().all()

        responses = []
        for wf in workflows:
            errors = self.validation_service.validate_workflow_structure(wf.nodes, wf.edges)
            responses.append(
                WorkflowResponse(
                    **wf.__dict__,
                    is_valid=len(errors) == 0,
                    validation_errors=[e.model_dump() for e in errors],
                )
            )

        return responses


class WorkflowEngineService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.designer_service = WorkflowDesignerService(db)

    async def start_instance(
        self, instance_data: WorkflowInstanceCreate
    ) -> WorkflowInstanceResponse:
        workflow = await self.designer_service.get_workflow(
            instance_data.workflow_id, instance_data.tenant_id
        )

        if not workflow.is_valid:
            raise ValidationError(
                message="流程定义无效，无法启动",
                details={"errors": workflow.validation_errors},
            )

        if workflow.status != WorkflowStatus.ACTIVE:
            raise ConflictError(f"流程状态为 {workflow.status}，无法启动")

        start_node = next(
            (n for n in workflow.nodes if n.get("type") == NodeType.START), None
        )

        instance = WorkflowInstance(
            **instance_data.model_dump(),
            workflow_version=workflow.version,
            current_node_id=start_node.get("id") if start_node else None,
            started_at=utc_now(),
            status=InstanceStatus.RUNNING,
        )
        self.db.add(instance)
        await self.db.flush()

        if start_node:
            await self._execute_node(instance, start_node)

        return WorkflowInstanceResponse.model_validate(instance)

    async def _execute_node(
        self, instance: WorkflowInstance, node: Dict[str, Any]
    ) -> WorkflowNodeExecution:
        node_id = node.get("id")
        node_type = NodeType(node.get("type"))

        execution = WorkflowNodeExecution(
            instance_id=instance.instance_id,
            node_id=node_id,
            node_type=node_type,
            status=NodeStatus.RUNNING,
            input_data={**instance.context, **instance.variables},
            started_at=utc_now(),
            tenant_id=instance.tenant_id,
        )
        self.db.add(execution)
        await self.db.flush()

        output_data = await self._process_node(node, execution.input_data)

        execution.status = NodeStatus.COMPLETED
        execution.output_data = output_data
        execution.completed_at = utc_now()
        execution.duration_seconds = (
            execution.completed_at - execution.started_at
        ).total_seconds()

        instance.variables.update(output_data)
        instance.current_node_id = await self._get_next_node(
            instance, node, output_data
        )

        if instance.current_node_id is None:
            instance.status = InstanceStatus.COMPLETED
            instance.completed_at = utc_now()

        self.db.add(execution)
        self.db.add(instance)
        await self.db.flush()

        return execution

    async def _process_node(
        self, node: Dict[str, Any], input_data: Dict[str, Any]
    ) -> Dict[str, Any]:
        node_type = NodeType(node.get("type"))
        config = node.get("config", {})

        if node_type == NodeType.START:
            return {"started": True}

        elif node_type == NodeType.END:
            return {"completed": True}

        elif node_type == NodeType.CONDITION:
            expression = config.get("expression", "")
            try:
                result = eval(expression, {"__builtins__": {}}, input_data)
                return {"condition_result": bool(result)}
            except Exception as e:
                return {"condition_result": False, "error": str(e)}

        elif node_type == NodeType.DELAY:
            delay_seconds = config.get("delay_seconds", 0)
            return {"delayed": True, "delay_seconds": delay_seconds}

        elif node_type == NodeType.NOTIFICATION:
            return {
                "notification_sent": True,
                "recipient": config.get("recipient"),
                "template": config.get("template"),
            }

        else:
            return {"processed": True, "node_type": node_type}

    async def _get_next_node(
        self, instance: WorkflowInstance, current_node: Dict[str, Any], output_data: Dict[str, Any]
    ) -> Optional[str]:
        workflow = await self.designer_service.get_workflow(
            instance.workflow_id, instance.tenant_id
        )

        current_node_id = current_node.get("id")
        node_type = NodeType(current_node.get("type"))

        if node_type == NodeType.END:
            return None

        outgoing_edges = [
            e for e in workflow.edges if e.get("source") == current_node_id
        ]

        if node_type == NodeType.CONDITION:
            condition_result = output_data.get("condition_result", False)
            for edge in outgoing_edges:
                if edge.get("type") == EdgeType.CONDITIONAL:
                    edge_condition = edge.get("condition", "")
                    try:
                        edge_result = eval(
                            edge_condition, {"__builtins__": {}}, {**instance.context, **output_data}
                        )
                        if edge_result:
                            return edge.get("target")
                    except Exception:
                        continue

            default_edge = next(
                (e for e in outgoing_edges if e.get("type") == EdgeType.DEFAULT), None
            )
            if default_edge:
                return default_edge.get("target")

            if not outgoing_edges:
                return None

            return outgoing_edges[0].get("target")

        if outgoing_edges:
            return outgoing_edges[0].get("target")

        return None

    async def get_instance(
        self, instance_id: str, tenant_id: Optional[str] = None
    ) -> WorkflowInstanceResponse:
        query = select(WorkflowInstance).where(
            WorkflowInstance.instance_id == instance_id
        )
        if tenant_id:
            query = query.where(WorkflowInstance.tenant_id == tenant_id)

        result = await self.db.execute(query)
        instance = result.scalar_one_or_none()

        if not instance:
            raise NotFoundError(f"流程实例 {instance_id} 不存在")

        return WorkflowInstanceResponse.model_validate(instance)

    async def get_instance_executions(
        self, instance_id: str, tenant_id: Optional[str] = None
    ) -> List[NodeExecutionResponse]:
        query = select(WorkflowNodeExecution).where(
            WorkflowNodeExecution.instance_id == instance_id
        )
        if tenant_id:
            query = query.where(WorkflowNodeExecution.tenant_id == tenant_id)

        query = query.order_by(WorkflowNodeExecution.created_at)
        result = await self.db.execute(query)
        executions = result.scalars().all()

        return [NodeExecutionResponse.model_validate(e) for e in executions]
