from __future__ import annotations
from datetime import datetime
from typing import Optional, List, Dict, Any, Set
import hashlib

from sqlalchemy import and_
from sqlalchemy.orm import Session, joinedload, selectinload

from app.core.cache import cache
from app.models.serial_number import SerialNumber, SerialNumberTrace, TraceAction
from app.models.batch import Batch
from app.schemas.serial import (
    TraceResponse,
    TraceNode,
    TraceEdge,
    TraceDirectionEnum,
    SerialTraceQuery,
)
from app.utils.exceptions import InventoryException

logger = logging.getLogger(__name__)

TRACE_CACHE_PREFIX = "trace:cache:"
TRACE_CACHE_TTL = 300
TRACE_BATCH_SIZE = 100


class TraceEngine:
    def __init__(self, db: Session):
        self.db = db
        self.max_depth = 10
        self.enable_cache = True

    def _get_cache_key(self, serial_code: str, direction: TraceDirectionEnum, max_depth: int) -> str:
        raw = f"{serial_code}:{direction.value}:{max_depth}"
        hash_val = hashlib.md5(raw.encode()).hexdigest()
        return f"{TRACE_CACHE_PREFIX}{hash_val}"

    def _get_cached_trace(self, cache_key: str) -> Optional[Dict[str, Any]]:
        if not self.enable_cache:
            return None
        try:
            return cache.get(cache_key)
        except Exception as e:
            logger.warning(f"Failed to get cached trace: {e}")
            return None

    def _set_cached_trace(self, cache_key: str, data: Dict[str, Any]) -> None:
        if not self.enable_cache:
            return
        try:
            cache.set(cache_key, data, ttl=TRACE_CACHE_TTL)
        except Exception as e:
            logger.warning(f"Failed to cache trace: {e}")

    def _invalidate_cache(self, serial_code: str) -> None:
        try:
            pattern = f"{TRACE_CACHE_PREFIX}*"
            cache.delete_pattern(pattern)
        except Exception as e:
            logger.warning(f"Failed to invalidate trace cache: {e}")

    def _build_node_id(self, prefix: str, identifier: Any) -> str:
        return f"{prefix}_{identifier}"

    def _create_node(
        self,
        node_id: str,
        node_type: str,
        label: str,
        timestamp: datetime,
        description: Optional[str] = None,
        location: Optional[str] = None,
        operator: Optional[str] = None,
        reference_type: Optional[str] = None,
        reference_id: Optional[int] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> TraceNode:
        return TraceNode(
            id=node_id,
            type=node_type,
            label=label,
            description=description,
            timestamp=timestamp,
            location=location,
            operator=operator,
            reference_type=reference_type,
            reference_id=reference_id,
            metadata=metadata or {},
        )

    def _create_edge(
        self,
        source: str,
        target: str,
        action: str,
        timestamp: datetime,
        label: Optional[str] = None,
    ) -> TraceEdge:
        return TraceEdge(
            source=source,
            target=target,
            label=label,
            action=action,
            timestamp=timestamp,
        )

    def _load_serial_with_relations(self, serial_id: int) -> SerialNumber:
        return (
            self.db.query(SerialNumber)
            .options(
                joinedload(SerialNumber.sku),
                joinedload(SerialNumber.batch),
                joinedload(SerialNumber.warehouse),
                selectinload(SerialNumber.traces).joinedload(SerialNumberTrace.serial_number),
            )
            .filter(SerialNumber.id == serial_id)
            .first()
        )

    def _load_traces_batch(self, serial_ids: List[int]) -> Dict[int, List[SerialNumberTrace]]:
        if not serial_ids:
            return {}

        traces = (
            self.db.query(SerialNumberTrace)
            .options(joinedload(SerialNumberTrace.serial_number))
            .filter(SerialNumberTrace.serial_number_id.in_(serial_ids))
            .order_by(SerialNumberTrace.operated_at.asc())
            .all()
        )

        result: Dict[int, List[SerialNumberTrace]] = {}
        for trace in traces:
            result.setdefault(trace.serial_number_id, []).append(trace)

        return result

    def _get_related_serials(
        self,
        batch_id: Optional[int],
        sku_id: int,
        exclude_serial_ids: Set[int],
        action: TraceAction,
    ) -> List[int]:
        if not batch_id:
            return []

        query = (
            self.db.query(SerialNumber.id)
            .filter(
                and_(
                    SerialNumber.batch_id == batch_id,
                    SerialNumber.sku_id == sku_id,
                    SerialNumber.id.notin_(list(exclude_serial_ids)),
                )
            )
            .limit(TRACE_BATCH_SIZE)
        )

        return [row[0] for row in query.all()]

    def trace_forward(
        self,
        serial_code: str,
        max_depth: Optional[int] = None,
        use_cache: bool = True,
    ) -> TraceResponse:
        return self._trace(
            serial_code=serial_code,
            direction=TraceDirectionEnum.FORWARD,
            max_depth=max_depth,
            use_cache=use_cache,
        )

    def trace_backward(
        self,
        serial_code: str,
        max_depth: Optional[int] = None,
        use_cache: bool = True,
    ) -> TraceResponse:
        return self._trace(
            serial_code=serial_code,
            direction=TraceDirectionEnum.BACKWARD,
            max_depth=max_depth,
            use_cache=use_cache,
        )

    def trace_full(
        self,
        serial_code: str,
        max_depth: Optional[int] = None,
        use_cache: bool = True,
    ) -> TraceResponse:
        return self._trace(
            serial_code=serial_code,
            direction=TraceDirectionEnum.FULL,
            max_depth=max_depth,
            use_cache=use_cache,
        )

    def _trace(
        self,
        serial_code: str,
        direction: TraceDirectionEnum,
        max_depth: Optional[int] = None,
        use_cache: bool = True,
    ) -> TraceResponse:
        actual_max_depth = max_depth or self.max_depth
        cache_key = self._get_cache_key(serial_code, direction, actual_max_depth)

        if use_cache:
            cached = self._get_cached_trace(cache_key)
            if cached:
                return TraceResponse(**cached)

        serial = (
            self.db.query(SerialNumber)
            .filter(SerialNumber.serial_code == serial_code)
            .first()
        )

        if not serial:
            raise InventoryException(
                f"序列号 {serial_code} 不存在",
                code=404,
                details={"serial_code": serial_code}
            )

        nodes: List[TraceNode] = []
        edges: List[TraceEdge] = []
        visited: Set[str] = set()

        start_node = self._build_start_node(serial)
        nodes.append(start_node)
        visited.add(start_node.id)

        if direction in [TraceDirectionEnum.FORWARD, TraceDirectionEnum.FULL]:
            self._bfs_forward(
                start_serial=serial,
                start_node_id=start_node.id,
                max_depth=actual_max_depth,
                nodes=nodes,
                edges=edges,
                visited=visited,
            )

        if direction in [TraceDirectionEnum.BACKWARD, TraceDirectionEnum.FULL]:
            self._bfs_backward(
                start_serial=serial,
                start_node_id=start_node.id,
                max_depth=actual_max_depth,
                nodes=nodes,
                edges=edges,
                visited=visited,
            )

        depth = self._calculate_depth(nodes, edges)

        response = TraceResponse(
            serial_code=serial_code,
            direction=direction,
            nodes=nodes,
            edges=edges,
            depth=depth,
            total_nodes=len(nodes),
            total_edges=len(edges),
        )

        if use_cache:
            self._set_cached_trace(cache_key, response.model_dump())

        return response

    def _build_start_node(self, serial: SerialNumber) -> TraceNode:
        sku_name = serial.sku.name if serial.sku else f"SKU_{serial.sku_id}"
        batch_no = serial.batch.batch_no if serial.batch else "N/A"
        warehouse_name = serial.warehouse.name if serial.warehouse else f"WH_{serial.warehouse_id}"

        return self._create_node(
            node_id=self._build_node_id("serial", serial.id),
            node_type="current",
            label=f"当前: {serial.serial_code}",
            timestamp=serial.created_at,
            description=f"{sku_name} | 批次: {batch_no} | 仓库: {warehouse_name}",
            location=serial.current_location or warehouse_name,
            metadata={
                "serial_code": serial.serial_code,
                "sku_id": serial.sku_id,
                "sku_name": sku_name,
                "batch_id": serial.batch_id,
                "batch_no": batch_no,
                "warehouse_id": serial.warehouse_id,
                "warehouse_name": warehouse_name,
                "status": serial.status.value,
            },
        )

    def _bfs_forward(
        self,
        start_serial: SerialNumber,
        start_node_id: str,
        max_depth: int,
        nodes: List[TraceNode],
        edges: List[TraceEdge],
        visited: Set[str],
    ) -> None:
        queue: Deque[tuple[int, str, int]] = deque()
        queue.append((start_serial.id, start_node_id, 0))

        while queue:
            serial_id, parent_node_id, current_depth = queue.popleft()

            if current_depth >= max_depth:
                continue

            traces = self._load_traces_batch([serial_id]).get(serial_id, [])

            for trace in traces:
                if trace.action in [TraceAction.SHIP, TraceAction.ALLOCATE, TraceAction.TRANSFER, TraceAction.SCRAP]:
                    node_id = self._build_node_id("trace", trace.id)
                    if node_id in visited:
                        continue

                    trace_node = self._create_trace_node(trace, is_forward=True)
                    nodes.append(trace_node)
                    visited.add(node_id)

                    edge = self._create_edge(
                        source=parent_node_id,
                        target=node_id,
                        action=trace.action.value,
                        timestamp=trace.operated_at,
                        label=self._get_action_label(trace.action),
                    )
                    edges.append(edge)

                    if trace.action == TraceAction.SHIP:
                        customer_node = self._create_customer_node(trace)
                        customer_node_id = self._build_node_id("customer", f"{trace.id}_customer")
                        if customer_node_id not in visited:
                            nodes.append(customer_node)
                            visited.add(customer_node_id)

                            customer_edge = self._create_edge(
                                source=node_id,
                                target=customer_node_id,
                                action="DELIVER",
                                timestamp=trace.operated_at,
                                label="配送至客户",
                            )
                            edges.append(customer_edge)

                    next_serials = self._get_related_serials(
                        batch_id=None,
                        sku_id=start_serial.sku_id,
                        exclude_serial_ids=processed_serials,
                        action=trace.action,
                    )

                    for next_serial_id in next_serials:
                        if next_serial_id not in processed_serials:
                            processed_serials.add(next_serial_id)
                            queue.append((next_serial_id, node_id, current_depth + 1))

    def _bfs_backward(
        self,
        start_serial: SerialNumber,
        start_node_id: str,
        max_depth: int,
        nodes: List[TraceNode],
        edges: List[TraceEdge],
        visited: Set[str],
    ) -> None:
        queue: Deque[tuple[int, str, int]] = deque()
        queue.append((start_serial.id, start_node_id, 0))

        while queue:
            serial_id, child_node_id, current_depth = queue.popleft()

            if current_depth >= max_depth:
                continue

            traces = self._load_traces_batch([serial_id]).get(serial_id, [])

            for trace in reversed(traces):
                if trace.action in [TraceAction.RECEIVE, TraceAction.PUTAWAY, TraceAction.RETURN]:
                    node_id = self._build_node_id("trace_rev", trace.id)
                    if node_id in visited:
                        continue

                    trace_node = self._create_trace_node(trace, is_forward=False)
                    nodes.append(trace_node)
                    visited.add(node_id)

                    edge = self._create_edge(
                        source=node_id,
                        target=child_node_id,
                        action=trace.action.value,
                        timestamp=trace.operated_at,
                        label=self._get_action_label(trace.action),
                    )
                    edges.append(edge)

                    if trace.action == TraceAction.RECEIVE:
                        supplier_node = self._create_supplier_node(start_serial, trace)
                        supplier_node_id = self._build_node_id("supplier", f"{trace.id}_supplier")
                        if supplier_node_id not in visited:
                            nodes.append(supplier_node)
                            visited.add(supplier_node_id)

                            supplier_edge = self._create_edge(
                                source=supplier_node_id,
                                target=node_id,
                                action="SUPPLY",
                                timestamp=trace.operated_at,
                                label="供应商发货",
                            )
                            edges.append(supplier_edge)

                        if start_serial.batch_id:
                            batch_node = self._create_batch_node(start_serial.batch)
                            batch_node_id = self._build_node_id("batch", start_serial.batch_id)
                            if batch_node_id not in visited:
                                nodes.append(batch_node)
                                visited.add(batch_node_id)

                                batch_edge = self._create_edge(
                                    source=batch_node_id,
                                    target=node_id,
                                    action="BATCH_RECEIVE",
                                    timestamp=trace.operated_at,
                                    label="批次入库",
                                )
                                edges.append(batch_edge)

    def _create_trace_node(self, trace: SerialNumberTrace, is_forward: bool) -> TraceNode:
        action_label = self._get_action_label(trace.action)
        node_type = trace.action.value.lower()

        return self._create_node(
            node_id=self._build_node_id("trace" if is_forward else "trace_rev", trace.id),
            node_type=node_type,
            label=action_label,
            timestamp=trace.operated_at,
            description=f"从 {trace.from_location or '未知'} 到 {trace.to_location or '未知'}",
            location=trace.to_location if is_forward else trace.from_location,
            operator=str(trace.operated_by) if trace.operated_by else None,
            reference_type=trace.reference_type,
            reference_id=trace.reference_id,
            metadata={
                "trace_id": trace.id,
                "action": trace.action.value,
                "from_location": trace.from_location,
                "to_location": trace.to_location,
                "reference_type": trace.reference_type,
                "reference_id": trace.reference_id,
                "operated_by": trace.operated_by,
            },
        )

    def _create_customer_node(self, trace: SerialNumberTrace) -> TraceNode:
        return self._create_node(
            node_id=self._build_node_id("customer", f"{trace.id}_customer"),
            node_type="customer",
            label="客户",
            timestamp=trace.operated_at,
            description=f"最终去向: {trace.to_location or '已出库'}",
            location=trace.to_location,
            metadata={
                "reference_type": trace.reference_type,
                "reference_id": trace.reference_id,
            },
        )

    def _create_supplier_node(self, serial: SerialNumber, trace: SerialNumberTrace) -> TraceNode:
        supplier_name = "未知供应商"
        if serial.batch and serial.batch.supplier:
            supplier_name = serial.batch.supplier.name

        return self._create_node(
            node_id=self._build_node_id("supplier", f"{trace.id}_supplier"),
            node_type="supplier",
            label=f"供应商: {supplier_name}",
            timestamp=trace.operated_at,
            description=f"来源: {trace.from_location or supplier_name}",
            location=trace.from_location,
            metadata={
                "supplier_id": serial.batch.supplier_id if serial.batch else None,
                "supplier_name": supplier_name,
            },
        )

    def _create_batch_node(self, batch: Batch) -> TraceNode:
        return self._create_node(
            node_id=self._build_node_id("batch", batch.id),
            node_type="batch",
            label=f"批次: {batch.batch_no}",
            timestamp=batch.received_date or batch.created_at,
            description=f"批次数量: {batch.quantity} | 剩余: {batch.remaining_quantity}",
            location=batch.warehouse.name if batch.warehouse else None,
            metadata={
                "batch_id": batch.id,
                "batch_no": batch.batch_no,
                "quantity": batch.quantity,
                "remaining_quantity": batch.remaining_quantity,
                "production_date": batch.production_date.isoformat() if batch.production_date else None,
                "expiration_date": batch.expiration_date.isoformat() if batch.expiration_date else None,
                "inspection_status": batch.inspection_status.value,
            },
        )

    def _get_action_label(self, action: TraceAction) -> str:
        labels = {
            TraceAction.RECEIVE: "入库接收",
            TraceAction.PUTAWAY: "上架",
            TraceAction.TRANSFER: "调拨",
            TraceAction.ALLOCATE: "分配",
            TraceAction.SHIP: "出库",
            TraceAction.RETURN: "退货",
            TraceAction.SCRAP: "报废",
        }
        return labels.get(action, action.value)

    def _calculate_depth(self, nodes: List[TraceNode], edges: List[TraceEdge]) -> int:
        if not edges:
            return 0

        node_depths: Dict[str, int] = {}
        for node in nodes:
            node_depths[node.id] = 0

        changed = True
        while changed:
            changed = False
            for edge in edges:
                new_depth = node_depths[edge.source] + 1
                if new_depth > node_depths[edge.target]:
                    node_depths[edge.target] = new_depth
                    changed = True

        return max(node_depths.values()) if node_depths else 0

    def get_trace_graph_data(
        self,
        serial_code: str,
        direction: TraceDirectionEnum = TraceDirectionEnum.FULL,
    ) -> Dict[str, Any]:
        trace_response = self._trace(serial_code, direction)

        node_data = [
            {
                "id": node.id,
                "label": node.label,
                "group": node.type,
                "data": {
                    "timestamp": node.timestamp.isoformat(),
                    "location": node.location,
                    "description": node.description,
                    "metadata": node.metadata,
                },
            }
            for node in trace_response.nodes
        ]

        edge_data = [
            {
                "id": f"edge_{i}",
                "source": edge.source,
                "target": edge.target,
                "label": edge.label or edge.action,
                "data": {
                    "action": edge.action,
                    "timestamp": edge.timestamp.isoformat(),
                },
            }
            for i, edge in enumerate(trace_response.edges)
        ]

        return {
            "serial_code": serial_code,
            "direction": direction.value,
            "nodes": node_data,
            "edges": edge_data,
            "stats": {
                "total_nodes": trace_response.total_nodes,
                "total_edges": trace_response.total_edges,
                "max_depth": trace_response.depth,
            },
        }

    def batch_trace(
        self,
        query: SerialTraceQuery,
    ) -> List[TraceResponse]:
        if query.serial_codes:
            serial_codes = query.serial_codes
        elif query.batch_id:
            serial_codes = [
                s[0] for s in
                self.db.query(SerialNumber.serial_code)
                .filter(SerialNumber.batch_id == query.batch_id)
                .limit(100)
                .all()
            ]
        elif query.sku_id:
            serial_codes = [
                s[0] for s in
                self.db.query(SerialNumber.serial_code)
                .filter(SerialNumber.sku_id == query.sku_id)
                .limit(100)
                .all()
            ]
        else:
            raise InventoryException(
                "请指定序列号列表、批次ID或SKU ID进行批量追溯",
                code=400
            )

        results: List[TraceResponse] = []
        for serial_code in serial_codes:
            try:
                trace = self._trace(
                    serial_code=serial_code,
                    direction=query.direction,
                    max_depth=query.max_depth,
                    use_cache=True,
                )
                results.append(trace)
            except Exception as e:
                logger.error(f"Failed to trace serial {serial_code}: {e}")

        return results

    def invalidate_serial_trace_cache(self, serial_code: str) -> None:
        self._invalidate_cache(serial_code)


def create_trace_engine(db: Session) -> TraceEngine:
    return TraceEngine(db)
