from datetime import timedelta
import logging
import random
from typing import Optional, Any
from sqlalchemy import and_, func, select, or_
from sqlalchemy.orm import Session, selectinload

from app.models.stocktake import (
    StocktakePlan,
    StocktakeTask,
    StocktakeResult,
    StocktakeAdjustment,
    StocktakePlanStatus,
    StocktakeTaskStatus,
    StocktakeResultStatus,
    AdjustmentType,
    AdjustmentStatus,
)
from app.models.inventory import Inventory
from app.models.inventory_document import (
    InventoryDocument,
    DocumentType,
    DocumentStatus,
    DocumentItem,
)
from app.models.sku import SKU
from app.models.product import Product
from app.models.serial_number import SerialNumber
from app.schemas.stocktake import (
    StocktakePlanCreate,
    StocktakePlanUpdate,
    CountResultRequest,
    AdjustmentReviewRequest,
    StocktakeGenerateRequest,
    StocktakeGenerateResponse,
    StocktakeDifferenceResponse,
    StocktakePlanListFilter,
    StocktakeTaskListFilter,
    StocktakeResultListFilter,
    StocktakePlanStatisticsResponse,
    ABCCategoryAnalysis,
    StocktakeSyncRequest,
    StocktakeSyncResponse,
)
from app.utils.exceptions import (
    InventoryException,
    InventoryNotFoundException,
    InvalidTransactionException,
)
from app.utils.helpers import (
    get_current_utc_time,
    generate_code,
    calculate_total_value,
)
from app.core.cache import cache
from app.core.audit import AuditLogger
from app.services.inventory_service import InventoryService
from app.services.warehouse_service import WarehouseService
from app.services.approval_service import create_approval_service

logger = logging.getLogger(__name__)

PLAN_CODE_PREFIX = "STP"
STOCKTAKE_LOCK_TIMEOUT = 300

ABC_THRESHOLD_A = 0.7
ABC_THRESHOLD_B = 0.9


class StocktakeService:
    def __init__(self, db: Session, user_id: Optional[int] = None):
        self.db = db
        self.user_id = user_id
        self.audit_logger = AuditLogger(db)
        self.inventory_service = InventoryService(db)
        self.warehouse_service = WarehouseService(db)
        self.approval_service = create_approval_service(db)

    def _get_lock_key(self, plan_id: int) -> str:
        return f"stocktake:lock:plan:{plan_id}"

    def _acquire_lock(self, plan_id: int) -> bool:
        lock_key = self._get_lock_key(plan_id)
        return cache.get_client().set(
            lock_key, "1", ex=STOCKTAKE_LOCK_TIMEOUT, nx=True
        )

    def _release_lock(self, plan_id: int) -> None:
        lock_key = self._get_lock_key(plan_id)
        cache.delete(lock_key)

    def _generate_plan_no(self) -> str:
        return generate_code(PLAN_CODE_PREFIX, 8)

    def _get_plan_data(self, plan: StocktakePlan) -> dict[str, Any]:
        return {
            "id": plan.id,
            "plan_no": plan.plan_no,
            "warehouse_id": plan.warehouse_id,
            "plan_type": plan.plan_type.value,
            "status": plan.status.value,
            "scheduled_date": plan.scheduled_date.isoformat(),
        }

    def _calculate_abc_classification(
        self, warehouse_id: int
    ) -> list[ABCCategoryAnalysis]:
        inventories = (
            self.db.query(Inventory)
            .filter(
                and_(
                    Inventory.warehouse_id == warehouse_id,
                    Inventory.quantity > 0,
                )
            )
            .all()
        )

        if not inventories:
            return []

        total_value = sum(float(inv.total_value) for inv in inventories)
        if total_value == 0:
            return []

        inventory_values = [
            {
                "sku_id": inv.sku_id,
                "value": float(inv.total_value),
                "quantity": inv.quantity,
            }
            for inv in inventories
        ]
        inventory_values.sort(key=lambda x: x["value"], reverse=True)

        cumulative_value = 0
        categories = {"A": [], "B": [], "C": []}

        for item in inventory_values:
            cumulative_value += item["value"]
            percentage = cumulative_value / total_value

            if percentage <= ABC_THRESHOLD_A:
                categories["A"].append(item)
            elif percentage <= ABC_THRESHOLD_B:
                categories["B"].append(item)
            else:
                categories["C"].append(item)

        result = []
        total_skus = len(inventory_values)

        for cat, items in categories.items():
            if items:
                cat_value = sum(x["value"] for x in items)
                result.append(
                    ABCCategoryAnalysis(
                        category=cat,
                        sku_count=len(items),
                        sku_percentage=len(items) / total_skus,
                        total_value=cat_value,
                        value_percentage=cat_value / total_value,
                        stocktake_frequency={"A": 12, "B": 6, "C": 2}.get(cat, 4),
                    )
                )

        return result

    def _select_skus_by_strategy(
        self,
        warehouse_id: int,
        generate_strategy: str,
        abc_categories: Optional[list[str]] = None,
        cycle_days: Optional[int] = None,
        random_count: Optional[int] = None,
        zone_ids: Optional[list[int]] = None,
        category_ids: Optional[list[int]] = None,
    ) -> list[int]:
        query = (
            self.db.query(Inventory.sku_id)
            .filter(
                and_(
                    Inventory.warehouse_id == warehouse_id,
                    Inventory.quantity > 0,
                )
            )
            .distinct()
        )

        if zone_ids:
            query = query.filter(Inventory.zone_id.in_(zone_ids))

        if category_ids:
            sku_subquery = (
                self.db.query(SKU.id)
                .join(Product, SKU.product_id == Product.id)
                .filter(Product.category_id.in_(category_ids))
                .subquery()
            )
            query = query.filter(Inventory.sku_id.in_(select(sku_subquery)))

        if generate_strategy == "ABC":
            abc_analysis = self._calculate_abc_classification(warehouse_id)
            target_cats = abc_categories or ["A", "B"]
            sku_ids_in_category = set()

            for inv in self.db.query(Inventory).filter(
                Inventory.warehouse_id == warehouse_id
            ).all():
                sku_value = float(inv.total_value)
                total_value = sum(
                    float(i.total_value)
                    for i in self.db.query(Inventory).filter(
                        Inventory.warehouse_id == warehouse_id
                    ).all()
                )
                if total_value > 0:
                    percentage = sku_value / total_value
                    if percentage <= 0.7 and "A" in target_cats:
                        sku_ids_in_category.add(inv.sku_id)
                    elif 0.7 < percentage <= 0.9 and "B" in target_cats:
                        sku_ids_in_category.add(inv.sku_id)
                    elif "C" in target_cats:
                        sku_ids_in_category.add(inv.sku_id)

            if sku_ids_in_category:
                query = query.filter(Inventory.sku_id.in_(list(sku_ids_in_category)))

        elif generate_strategy == "CYCLE":
            days = cycle_days or 30
            cutoff_date = get_current_utc_time() - timedelta(days=days)
            query = query.filter(
                or_(
                    Inventory.last_counted_at.is_(None),
                    Inventory.last_counted_at <= cutoff_date,
                )
            )

        elif generate_strategy == "RANDOM":
            all_skus = [row[0] for row in query.all()]
            count = min(random_count or 50, len(all_skus))
            if count > 0:
                return random.sample(all_skus, count)
            return []

        return [row[0] for row in query.all()]

    def _group_skus_into_tasks(
        self,
        sku_ids: list[int],
        zone_ids: list[int],
        items_per_task: int = 20,
    ) -> list[tuple[list[int], list[int]]]:
        if not sku_ids:
            return []

        sku_chunks = [
            sku_ids[i : i + items_per_task]
            for i in range(0, len(sku_ids), items_per_task)
        ]

        tasks = []
        for chunk in sku_chunks:
            tasks.append((chunk, zone_ids or [1]))

        return tasks

    def get_plan(self, plan_id: int, lock: bool = False) -> StocktakePlan:
        if lock and not self._acquire_lock(plan_id):
            raise InventoryException(
                f"Stocktake plan {plan_id} is locked by another operation",
                code=409,
            )

        plan = (
            self.db.query(StocktakePlan)
            .filter(StocktakePlan.id == plan_id)
            .first()
        )

        if not plan:
            if lock:
                self._release_lock(plan_id)
            raise InventoryException(
                f"Stocktake plan {plan_id} not found", code=404
            )

        return plan

    def list_plans(
        self,
        filters: StocktakePlanListFilter,
        skip: int = 0,
        limit: int = 20,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
    ) -> list[StocktakePlan]:
        query = self.db.query(StocktakePlan)

        if filters.warehouse_id:
            query = query.filter(
                StocktakePlan.warehouse_id == filters.warehouse_id
            )
        if filters.plan_type:
            query = query.filter(
                StocktakePlan.plan_type == filters.plan_type
            )
        if filters.status:
            query = query.filter(StocktakePlan.status == filters.status)
        if filters.created_by:
            query = query.filter(StocktakePlan.created_by == filters.created_by)
        if filters.start_date:
            query = query.filter(
                StocktakePlan.scheduled_date >= filters.start_date
            )
        if filters.end_date:
            query = query.filter(
                StocktakePlan.scheduled_date <= filters.end_date
            )
        if filters.plan_no:
            query = query.filter(
                StocktakePlan.plan_no.like(f"%{filters.plan_no}%")
            )

        if sort_by and hasattr(StocktakePlan, sort_by):
            sort_column = getattr(StocktakePlan, sort_by)
            query = (
                query.order_by(sort_column.desc())
                if sort_order == "desc"
                else query.order_by(sort_column.asc())
            )
        else:
            query = query.order_by(StocktakePlan.id.desc())

        return query.offset(skip).limit(limit).all()

    def count_plans(self, filters: StocktakePlanListFilter) -> int:
        query = self.db.query(func.count(StocktakePlan.id))

        if filters.warehouse_id:
            query = query.filter(
                StocktakePlan.warehouse_id == filters.warehouse_id
            )
        if filters.plan_type:
            query = query.filter(
                StocktakePlan.plan_type == filters.plan_type
            )
        if filters.status:
            query = query.filter(StocktakePlan.status == filters.status)
        if filters.created_by:
            query = query.filter(StocktakePlan.created_by == filters.created_by)
        if filters.start_date:
            query = query.filter(
                StocktakePlan.scheduled_date >= filters.start_date
            )
        if filters.end_date:
            query = query.filter(
                StocktakePlan.scheduled_date <= filters.end_date
            )
        if filters.plan_no:
            query = query.filter(
                StocktakePlan.plan_no.like(f"%{filters.plan_no}%")
            )

        return query.scalar() or 0

    def create_plan(self, plan_in: StocktakePlanCreate) -> StocktakePlan:
        self.warehouse_service.get_warehouse(plan_in.warehouse_id)

        plan_no = plan_in.plan_no or self._generate_plan_no()

        plan = StocktakePlan(
            plan_no=plan_no,
            warehouse_id=plan_in.warehouse_id,
            plan_type=plan_in.plan_type,
            status=StocktakePlanStatus.DRAFT,
            scheduled_date=plan_in.scheduled_date,
            description=plan_in.description,
            created_by=self.user_id or 0,
            created_at=get_current_utc_time(),
            updated_at=get_current_utc_time(),
        )

        self.db.add(plan)
        self.db.flush()
        self.db.refresh(plan)

        sku_ids = plan_in.sku_ids or []
        if not sku_ids and (plan_in.zone_ids or plan_in.category_ids):
            sku_ids = self._select_skus_by_strategy(
                warehouse_id=plan_in.warehouse_id,
                generate_strategy="ABC",
                zone_ids=plan_in.zone_ids,
                category_ids=plan_in.category_ids,
            )

        if sku_ids:
            tasks = self._group_skus_into_tasks(
                sku_ids, plan_in.zone_ids or [1]
            )
            for task_skus, task_zones in tasks:
                task = StocktakeTask(
                    plan_id=plan.id,
                    sku_ids=task_skus,
                    zone_ids=task_zones,
                    status=StocktakeTaskStatus.PENDING,
                    created_at=get_current_utc_time(),
                )
                self.db.add(task)

        self.db.flush()
        self.db.refresh(plan)

        self.audit_logger.log(
            user_id=self.user_id,
            action="create",
            resource_type="stocktake_plan",
            resource_id=plan.id,
            new_value=self._get_plan_data(plan),
        )

        cache.delete_pattern("stocktake:plan:list:*")

        logger.info(
            f"Stocktake plan created: id={plan.id}, no={plan.plan_no}, "
            f"type={plan.plan_type}, created_by={self.user_id}"
        )

        return plan

    def generate_plan(
        self, request: StocktakeGenerateRequest
    ) -> StocktakeGenerateResponse:
        self.warehouse_service.get_warehouse(request.warehouse_id)

        sku_ids = self._select_skus_by_strategy(
            warehouse_id=request.warehouse_id,
            generate_strategy=request.generate_strategy,
            abc_categories=request.abc_category,
            cycle_days=request.cycle_days,
            random_count=request.random_count,
            zone_ids=request.zone_ids,
            category_ids=request.category_ids,
        )

        if not sku_ids:
            raise InventoryException(
                "No SKUs found for stocktake with the given criteria",
                code=400,
            )

        plan_in = StocktakePlanCreate(
            warehouse_id=request.warehouse_id,
            plan_type=request.plan_type,
            scheduled_date=request.scheduled_date or get_current_utc_time(),
            description=request.description
            or f"Auto-generated {request.generate_strategy} stocktake",
            sku_ids=sku_ids,
            zone_ids=request.zone_ids,
            category_ids=request.category_ids,
        )

        plan = self.create_plan(plan_in)

        task_count = (
            self.db.query(func.count(StocktakeTask.id))
            .filter(StocktakeTask.plan_id == plan.id)
            .scalar()
            or 0
        )

        return StocktakeGenerateResponse(
            plan_id=plan.id,
            plan_no=plan.plan_no,
            task_count=task_count,
            sku_count=len(sku_ids),
            message=f"Generated {task_count} tasks for {len(sku_ids)} SKUs",
        )

    def update_plan(
        self, plan_id: int, plan_in: StocktakePlanUpdate
    ) -> StocktakePlan:
        plan = self.get_plan(plan_id, lock=True)
        try:
            if plan.status not in [
                StocktakePlanStatus.DRAFT,
                StocktakePlanStatus.PLANNED,
            ]:
                raise InvalidTransactionException(
                    "STOCKTAKE_PLAN_UPDATE",
                    f"Cannot update plan in status {plan.status}",
                )

            old_data = self._get_plan_data(plan)

            if plan_in.warehouse_id:
                self.warehouse_service.get_warehouse(plan_in.warehouse_id)
                plan.warehouse_id = plan_in.warehouse_id

            if plan_in.plan_type:
                plan.plan_type = plan_in.plan_type
            if plan_in.scheduled_date:
                plan.scheduled_date = plan_in.scheduled_date
            if plan_in.description is not None:
                plan.description = plan_in.description
            if plan_in.status:
                plan.status = plan_in.status

            plan.updated_at = get_current_utc_time()

            self.db.flush()
            self.db.refresh(plan)

            self.audit_logger.log(
                user_id=self.user_id,
                action="update",
                resource_type="stocktake_plan",
                resource_id=plan.id,
                old_value=old_data,
                new_value=self._get_plan_data(plan),
            )

            cache.delete(f"stocktake:plan:{plan_id}")
            cache.delete_pattern("stocktake:plan:list:*")

            logger.info(
                f"Stocktake plan updated: id={plan.id}, status={plan.status}"
            )

            return plan
        finally:
            self._release_lock(plan_id)

    def start_plan(self, plan_id: int) -> StocktakePlan:
        plan = self.get_plan(plan_id, lock=True)
        try:
            if plan.status not in [
                StocktakePlanStatus.DRAFT,
                StocktakePlanStatus.PLANNED,
            ]:
                raise InvalidTransactionException(
                    "STOCKTAKE_PLAN_START",
                    f"Cannot start plan in status {plan.status}",
                )

            if not plan.tasks:
                raise InventoryException(
                    "Plan has no tasks, cannot start", code=400
                )

            old_data = self._get_plan_data(plan)

            plan.status = StocktakePlanStatus.IN_PROGRESS
            plan.actual_start_date = get_current_utc_time()
            plan.updated_at = get_current_utc_time()

            for task in plan.tasks:
                if task.status == StocktakeTaskStatus.PENDING:
                    task.status = StocktakeTaskStatus.IN_PROGRESS
                    task.started_at = get_current_utc_time()

            self.db.flush()
            self.db.refresh(plan)

            self.audit_logger.log(
                user_id=self.user_id,
                action="start",
                resource_type="stocktake_plan",
                resource_id=plan.id,
                old_value=old_data,
                new_value=self._get_plan_data(plan),
            )

            cache.delete(f"stocktake:plan:{plan_id}")
            cache.delete_pattern("stocktake:plan:list:*")

            logger.info(
                f"Stocktake plan started: id={plan.id}, no={plan.plan_no}"
            )

            return plan
        finally:
            self._release_lock(plan_id)

    def complete_plan(self, plan_id: int) -> StocktakePlan:
        plan = self.get_plan(plan_id, lock=True)
        try:
            if plan.status != StocktakePlanStatus.IN_PROGRESS:
                raise InvalidTransactionException(
                    "STOCKTAKE_PLAN_COMPLETE",
                    f"Cannot complete plan in status {plan.status}",
                )

            pending_tasks = [
                t
                for t in plan.tasks
                if t.status != StocktakeTaskStatus.COMPLETED
            ]
            if pending_tasks:
                raise InventoryException(
                    f"There are {len(pending_tasks)} incomplete tasks",
                    code=400,
                )

            old_data = self._get_plan_data(plan)

            plan.status = StocktakePlanStatus.COMPLETED
            plan.actual_end_date = get_current_utc_time()
            plan.updated_at = get_current_utc_time()

            self.db.flush()

            self._generate_adjustments_for_plan(plan)

            self.db.flush()
            self.db.refresh(plan)

            self.audit_logger.log(
                user_id=self.user_id,
                action="complete",
                resource_type="stocktake_plan",
                resource_id=plan.id,
                old_value=old_data,
                new_value=self._get_plan_data(plan),
            )

            cache.delete(f"stocktake:plan:{plan_id}")
            cache.delete_pattern("stocktake:plan:list:*")

            logger.info(
                f"Stocktake plan completed: id={plan.id}, no={plan.plan_no}"
            )

            return plan
        finally:
            self._release_lock(plan_id)

    def _generate_adjustments_for_plan(self, plan: StocktakePlan) -> None:
        for task in plan.tasks:
            for result in task.results:
                if result.difference_quantity != 0 and not result.adjustment:
                    self._create_adjustment_from_result(result)

    def _create_adjustment_from_result(
        self, result: StocktakeResult
    ) -> StocktakeAdjustment:
        inventory = self.inventory_service.get_inventory_by_key(
            sku_id=result.sku_id,
            warehouse_id=result.task.plan.warehouse_id,
            zone_id=1,
        )

        unit_cost = float(inventory.unit_cost) if inventory else 0.0
        adj_type = (
            AdjustmentType.GAIN
            if result.difference_quantity > 0
            else AdjustmentType.LOSS
        )

        adjustment = StocktakeAdjustment(
            result_id=result.id,
            adjustment_type=adj_type,
            quantity=abs(result.difference_quantity),
            unit_cost=unit_cost,
            total_cost=calculate_total_value(
                abs(result.difference_quantity), unit_cost
            ),
            status=AdjustmentStatus.DRAFT,
            created_by=self.user_id or 0,
            created_at=get_current_utc_time(),
        )

        self.db.add(adjustment)
        self.db.flush()

        result.status = StocktakeResultStatus.CHECKED

        return adjustment

    def list_tasks(
        self,
        filters: StocktakeTaskListFilter,
        skip: int = 0,
        limit: int = 20,
    ) -> list[StocktakeTask]:
        query = self.db.query(StocktakeTask)

        if filters.plan_id:
            query = query.filter(StocktakeTask.plan_id == filters.plan_id)
        if filters.assignee_id:
            query = query.filter(StocktakeTask.assignee_id == filters.assignee_id)
        if filters.status:
            query = query.filter(StocktakeTask.status == filters.status)
        if filters.warehouse_id:
            query = query.join(StocktakePlan).filter(
                StocktakePlan.warehouse_id == filters.warehouse_id
            )

        return query.order_by(StocktakeTask.id.desc()).offset(skip).limit(limit).all()

    def count_tasks(self, filters: StocktakeTaskListFilter) -> int:
        query = self.db.query(func.count(StocktakeTask.id))

        if filters.plan_id:
            query = query.filter(StocktakeTask.plan_id == filters.plan_id)
        if filters.assignee_id:
            query = query.filter(StocktakeTask.assignee_id == filters.assignee_id)
        if filters.status:
            query = query.filter(StocktakeTask.status == filters.status)
        if filters.warehouse_id:
            query = query.join(StocktakePlan).filter(
                StocktakePlan.warehouse_id == filters.warehouse_id
            )

        return query.scalar() or 0

    def assign_task(self, task_id: int, assignee_id: int) -> StocktakeTask:
        task = (
            self.db.query(StocktakeTask)
            .filter(StocktakeTask.id == task_id)
            .first()
        )

        if not task:
            raise InventoryException(
                f"Stocktake task {task_id} not found", code=404
            )

        if task.status != StocktakeTaskStatus.PENDING:
            raise InvalidTransactionException(
                "TASK_ASSIGN",
                f"Cannot assign task in status {task.status}",
            )

        task.assignee_id = assignee_id
        task.assigned_at = get_current_utc_time()

        self.db.flush()
        self.db.refresh(task)

        self.audit_logger.log(
            user_id=self.user_id,
            action="assign",
            resource_type="stocktake_task",
            resource_id=task.id,
            new_value={
                "task_id": task.id,
                "assignee_id": assignee_id,
            },
        )

        logger.info(
            f"Stocktake task assigned: id={task.id}, assignee={assignee_id}"
        )

        return task

    def start_task(self, task_id: int) -> StocktakeTask:
        task = (
            self.db.query(StocktakeTask)
            .filter(StocktakeTask.id == task_id)
            .first()
        )

        if not task:
            raise InventoryException(
                f"Stocktake task {task_id} not found", code=404
            )

        if task.status not in [
            StocktakeTaskStatus.PENDING,
            StocktakeTaskStatus.IN_PROGRESS,
        ]:
            raise InvalidTransactionException(
                "TASK_START",
                f"Cannot start task in status {task.status}",
            )

        task.status = StocktakeTaskStatus.IN_PROGRESS
        task.started_at = get_current_utc_time()

        self.db.flush()
        self.db.refresh(task)

        self.audit_logger.log(
            user_id=self.user_id,
            action="start",
            resource_type="stocktake_task",
            resource_id=task.id,
            new_value={"task_id": task.id, "status": task.status.value},
        )

        logger.info(f"Stocktake task started: id={task.id}")

        return task

    def complete_task(self, task_id: int) -> StocktakeTask:
        task = (
            self.db.query(StocktakeTask)
            .options(selectinload(StocktakeTask.results))
            .filter(StocktakeTask.id == task_id)
            .first()
        )

        if not task:
            raise InventoryException(
                f"Stocktake task {task_id} not found", code=404
            )

        if task.status != StocktakeTaskStatus.IN_PROGRESS:
            raise InvalidTransactionException(
                "TASK_COMPLETE",
                f"Cannot complete task in status {task.status}",
            )

        for sku_id in task.sku_ids:
            existing_result = next(
                (r for r in task.results if r.sku_id == sku_id), None
            )
            if not existing_result:
                inventory = self.inventory_service.get_inventory_by_key(
                    sku_id=sku_id,
                    warehouse_id=task.plan.warehouse_id,
                    zone_id=task.zone_ids[0] if task.zone_ids else 1,
                )
                expected_qty = inventory.quantity if inventory else 0
                result = StocktakeResult(
                    task_id=task.id,
                    sku_id=sku_id,
                    expected_quantity=expected_qty,
                    counted_quantity=0,
                    difference_quantity=-expected_qty,
                    status=StocktakeResultStatus.UNCHECKED,
                    created_at=get_current_utc_time(),
                )
                self.db.add(result)

        task.status = StocktakeTaskStatus.COMPLETED
        task.completed_at = get_current_utc_time()

        self.db.flush()
        self.db.refresh(task)

        self.audit_logger.log(
            user_id=self.user_id,
            action="complete",
            resource_type="stocktake_task",
            resource_id=task.id,
            new_value={"task_id": task.id, "status": task.status.value},
        )

        logger.info(f"Stocktake task completed: id={task.id}")

        return task

    def count_result(
        self, task_id: int, request: CountResultRequest
    ) -> StocktakeResult:
        task = (
            self.db.query(StocktakeTask)
            .filter(StocktakeTask.id == task_id)
            .first()
        )

        if not task:
            raise InventoryException(
                f"Stocktake task {task_id} not found", code=404
            )

        if task.status != StocktakeTaskStatus.IN_PROGRESS:
            raise InvalidTransactionException(
                "COUNT_RESULT",
                f"Cannot record count for task in status {task.status}",
            )

        if request.sku_id not in task.sku_ids:
            raise InventoryException(
                f"SKU {request.sku_id} not in task {task_id}", code=400
            )

        inventory = self.inventory_service.get_inventory_by_key(
            sku_id=request.sku_id,
            warehouse_id=task.plan.warehouse_id,
            zone_id=task.zone_ids[0] if task.zone_ids else 1,
        )
        expected_qty = inventory.quantity if inventory else 0

        existing_result = (
            self.db.query(StocktakeResult)
            .filter(
                and_(
                    StocktakeResult.task_id == task_id,
                    StocktakeResult.sku_id == request.sku_id,
                    StocktakeResult.batch_id == request.batch_id,
                )
            )
            .first()
        )

        if existing_result:
            existing_result.counted_quantity = request.counted_quantity
            existing_result.difference_quantity = (
                request.counted_quantity - existing_result.expected_quantity
            )
            existing_result.status = StocktakeResultStatus.UNCHECKED
            result = existing_result
        else:
            result = StocktakeResult(
                task_id=task_id,
                sku_id=request.sku_id,
                batch_id=request.batch_id,
                expected_quantity=expected_qty,
                counted_quantity=request.counted_quantity,
                difference_quantity=request.counted_quantity - expected_qty,
                variance_reason=request.remark,
                status=StocktakeResultStatus.UNCHECKED,
                created_at=get_current_utc_time(),
            )
            self.db.add(result)

        self.db.flush()
        self.db.refresh(result)

        if request.serial_numbers:
            for sn in request.serial_numbers:
                serial = (
                    self.db.query(SerialNumber)
                    .filter(SerialNumber.serial_code == sn)
                    .first()
                )
                if serial:
                    serial.status = (
                        "INSTOCK"
                        if request.counted_quantity > 0
                        else "SCRAPPED"
                    )
                    serial.updated_at = get_current_utc_time()

        self.audit_logger.log(
            user_id=self.user_id,
            action="count",
            resource_type="stocktake_result",
            resource_id=result.id,
            new_value={
                "task_id": task_id,
                "sku_id": request.sku_id,
                "counted": request.counted_quantity,
                "expected": expected_qty,
            },
        )

        logger.info(
            f"Stocktake count recorded: task={task_id}, sku={request.sku_id}, "
            f"counted={request.counted_quantity}, expected={expected_qty}"
        )

        return result

    def list_results(
        self,
        filters: StocktakeResultListFilter,
        skip: int = 0,
        limit: int = 20,
    ) -> list[StocktakeResult]:
        query = self.db.query(StocktakeResult)

        if filters.plan_id:
            query = query.join(StocktakeTask).filter(
                StocktakeTask.plan_id == filters.plan_id
            )
        if filters.task_id:
            query = query.filter(StocktakeResult.task_id == filters.task_id)
        if filters.sku_id:
            query = query.filter(StocktakeResult.sku_id == filters.sku_id)
        if filters.status:
            query = query.filter(StocktakeResult.status == filters.status)
        if filters.has_difference is not None:
            if filters.has_difference:
                query = query.filter(StocktakeResult.difference_quantity != 0)
            else:
                query = query.filter(StocktakeResult.difference_quantity == 0)

        return query.order_by(StocktakeResult.id.desc()).offset(skip).limit(limit).all()

    def count_results(self, filters: StocktakeResultListFilter) -> int:
        query = self.db.query(func.count(StocktakeResult.id))

        if filters.plan_id:
            query = query.join(StocktakeTask).filter(
                StocktakeTask.plan_id == filters.plan_id
            )
        if filters.task_id:
            query = query.filter(StocktakeResult.task_id == filters.task_id)
        if filters.sku_id:
            query = query.filter(StocktakeResult.sku_id == filters.sku_id)
        if filters.status:
            query = query.filter(StocktakeResult.status == filters.status)
        if filters.has_difference is not None:
            if filters.has_difference:
                query = query.filter(StocktakeResult.difference_quantity != 0)
            else:
                query = query.filter(StocktakeResult.difference_quantity == 0)

        return query.scalar() or 0

    def list_differences(
        self, plan_id: int
    ) -> list[StocktakeDifferenceResponse]:
        results = (
            self.db.query(StocktakeResult)
            .options(
                selectinload(StocktakeResult.adjustment),
                selectinload(StocktakeResult.task),
            )
            .join(StocktakeTask)
            .filter(
                and_(
                    StocktakeTask.plan_id == plan_id,
                    StocktakeResult.difference_quantity != 0,
                )
            )
            .all()
        )

        differences = []
        for result in results:
            sku = self.db.query(SKU).filter(SKU.id == result.sku_id).first()
            product = (
                self.db.query(Product).filter(Product.id == sku.product_id).first()
                if sku
                else None
            )
            inventory = self.inventory_service.get_inventory_by_key(
                sku_id=result.sku_id,
                warehouse_id=result.task.plan.warehouse_id,
                zone_id=1,
            )
            unit_cost = float(inventory.unit_cost) if inventory else 0.0

            differences.append(
                StocktakeDifferenceResponse(
                    result_id=result.id,
                    sku_id=result.sku_id,
                    sku_code=sku.sku_code if sku else f"SKU-{result.sku_id}",
                    sku_name=product.name if product else "Unknown",
                    batch_id=result.batch_id,
                    expected_quantity=result.expected_quantity,
                    counted_quantity=result.counted_quantity,
                    difference_quantity=result.difference_quantity,
                    difference_type=(
                        "GAIN" if result.difference_quantity > 0 else "LOSS"
                    ),
                    unit_cost=unit_cost,
                    difference_value=abs(result.difference_quantity) * unit_cost,
                    variance_reason=result.variance_reason,
                    status=result.status,
                    has_adjustment=result.adjustment is not None,
                )
            )

        return differences

    def approve_adjustment(
        self, adjustment_id: int, request: AdjustmentReviewRequest
    ) -> StocktakeAdjustment:
        adjustment = (
            self.db.query(StocktakeAdjustment)
            .options(selectinload(StocktakeAdjustment.result))
            .filter(StocktakeAdjustment.id == adjustment_id)
            .first()
        )

        if not adjustment:
            raise InventoryException(
                f"Stocktake adjustment {adjustment_id} not found", code=404
            )

        if adjustment.status != AdjustmentStatus.DRAFT:
            raise InvalidTransactionException(
                "ADJUSTMENT_APPROVE",
                f"Cannot approve adjustment in status {adjustment.status}",
            )

        adjustment.status = request.status
        adjustment.approved_by = self.user_id
        adjustment.approved_at = get_current_utc_time()

        if adjustment.result:
            adjustment.result.status = StocktakeResultStatus.ADJUSTED

        self.db.flush()
        self.db.refresh(adjustment)

        self.audit_logger.log(
            user_id=self.user_id,
            action="approve",
            resource_type="stocktake_adjustment",
            resource_id=adjustment.id,
            new_value={
                "adjustment_id": adjustment_id,
                "status": request.status.value,
                "remark": request.remark,
            },
        )

        logger.info(
            f"Stocktake adjustment approved: id={adjustment_id}, "
            f"status={request.status}"
        )

        return adjustment

    def execute_adjustment(self, adjustment_id: int) -> StocktakeAdjustment:
        adjustment = (
            self.db.query(StocktakeAdjustment)
            .options(
                selectinload(StocktakeAdjustment.result),
                selectinload(StocktakeAdjustment.result).selectinload(
                    StocktakeResult.task
                ),
            )
            .filter(StocktakeAdjustment.id == adjustment_id)
            .first()
        )

        if not adjustment:
            raise InventoryException(
                f"Stocktake adjustment {adjustment_id} not found", code=404
            )

        if adjustment.status != AdjustmentStatus.APPROVED:
            raise InvalidTransactionException(
                "ADJUSTMENT_EXECUTE",
                f"Cannot execute adjustment in status {adjustment.status}",
            )

        result = adjustment.result
        task = result.task if result else None
        plan = task.plan if task else None

        if not plan:
            raise InventoryException("Invalid adjustment hierarchy", code=400)

        qty_change = (
            adjustment.quantity
            if adjustment.adjustment_type == AdjustmentType.GAIN
            else -adjustment.quantity
        )

        from app.schemas.warehouse import InventoryAdjustRequest

        adjust_request = InventoryAdjustRequest(
            inventory_id=0,
            quantity=qty_change,
            reason=f"Stocktake adjustment: {adjustment_id}",
            unit_cost=float(adjustment.unit_cost),
        )

        inventory = self.inventory_service.get_inventory_by_key(
            sku_id=result.sku_id,
            warehouse_id=plan.warehouse_id,
            zone_id=1,
            lock=True,
        )

        if not inventory:
            if qty_change > 0:
                self.inventory_service.process_inbound(
                    sku_id=result.sku_id,
                    warehouse_id=plan.warehouse_id,
                    zone_id=1,
                    quantity=adjustment.quantity,
                    unit_cost=float(adjustment.unit_cost),
                    reason=f"Stocktake gain: {adjustment_id}",
                    created_by=self.user_id,
                )
            else:
                raise InventoryNotFoundException(
                    message=f"No inventory found for SKU {result.sku_id}"
                )
        else:
            adjust_request.inventory_id = inventory.id
            self.inventory_service.adjust_inventory(
                adjust_request, created_by=self.user_id
            )

        doc = InventoryDocument(
            document_no=generate_code("ADJ", 8),
            document_type=DocumentType.STOCKTAKE,
            warehouse_id=plan.warehouse_id,
            status=DocumentStatus.COMPLETED,
            total_quantity=abs(qty_change),
            total_amount=float(adjustment.total_cost),
            remark=f"Stocktake adjustment for plan {plan.plan_no}",
            reference_type="STOCKTAKE_PLAN",
            reference_id=plan.id,
            created_by=self.user_id or 0,
            created_at=get_current_utc_time(),
            updated_at=get_current_utc_time(),
            confirmed_by=self.user_id,
            confirmed_at=get_current_utc_time(),
            completed_by=self.user_id,
            completed_at=get_current_utc_time(),
        )
        self.db.add(doc)
        self.db.flush()

        doc_item = DocumentItem(
            document_id=doc.id,
            sku_id=result.sku_id,
            quantity=abs(qty_change),
            actual_quantity=abs(qty_change),
            unit_cost=float(adjustment.unit_cost),
            total_cost=float(adjustment.total_cost),
            remark="Stocktake adjustment",
            created_at=get_current_utc_time(),
        )
        self.db.add(doc_item)

        adjustment.status = AdjustmentStatus.COMPLETED

        self.db.flush()
        self.db.refresh(adjustment)

        self.audit_logger.log(
            user_id=self.user_id,
            action="execute",
            resource_type="stocktake_adjustment",
            resource_id=adjustment.id,
            new_value={
                "adjustment_id": adjustment_id,
                "type": adjustment.adjustment_type.value,
                "quantity": adjustment.quantity,
                "value": float(adjustment.total_cost),
            },
        )

        logger.info(
            f"Stocktake adjustment executed: id={adjustment_id}, "
            f"type={adjustment.adjustment_type}, qty={adjustment.quantity}"
        )

        return adjustment

    def get_statistics(
        self, filters: Optional[StocktakePlanListFilter] = None
    ) -> StocktakePlanStatisticsResponse:
        if filters is None:
            filters = StocktakePlanListFilter()

        base_query = self.db.query(StocktakePlan)

        if filters.warehouse_id:
            base_query = base_query.filter(
                StocktakePlan.warehouse_id == filters.warehouse_id
            )
        if filters.start_date:
            base_query = base_query.filter(
                StocktakePlan.scheduled_date >= filters.start_date
            )
        if filters.end_date:
            base_query = base_query.filter(
                StocktakePlan.scheduled_date <= filters.end_date
            )

        total_plans = base_query.count()
        planned_count = base_query.filter(
            StocktakePlan.status == StocktakePlanStatus.PLANNED
        ).count()
        in_progress_count = base_query.filter(
            StocktakePlan.status == StocktakePlanStatus.IN_PROGRESS
        ).count()
        completed_count = base_query.filter(
            StocktakePlan.status == StocktakePlanStatus.COMPLETED
        ).count()
        cancelled_count = base_query.filter(
            StocktakePlan.status == StocktakePlanStatus.CANCELLED
        ).count()

        results_query = (
            self.db.query(StocktakeResult)
            .join(StocktakeTask)
            .join(StocktakePlan)
        )
        if filters.warehouse_id:
            results_query = results_query.filter(
                StocktakePlan.warehouse_id == filters.warehouse_id
            )

        total_sku_count = results_query.count()
        total_difference_count = results_query.filter(
            StocktakeResult.difference_quantity != 0
        ).count()
        total_difference_value = (
            self.db.query(
                func.coalesce(
                    func.sum(
                        func.abs(StocktakeResult.difference_quantity)
                        * Inventory.unit_cost
                    ),
                    0.0,
                )
            )
            .select_from(StocktakeResult)
            .join(StocktakeTask)
            .join(StocktakePlan)
            .join(
                Inventory,
                and_(
                    StocktakeResult.sku_id == Inventory.sku_id,
                    StocktakePlan.warehouse_id == Inventory.warehouse_id,
                ),
            )
            .scalar()
            or 0.0
        )

        accuracy_rate = (
            (total_sku_count - total_difference_count) / total_sku_count
            if total_sku_count > 0
            else 1.0
        )

        return StocktakePlanStatisticsResponse(
            total_plans=total_plans,
            planned_count=planned_count,
            in_progress_count=in_progress_count,
            completed_count=completed_count,
            cancelled_count=cancelled_count,
            total_sku_count=total_sku_count,
            total_difference_count=total_difference_count,
            total_difference_value=float(total_difference_value),
            accuracy_rate=round(accuracy_rate, 4),
        )

    def get_abc_analysis(
        self, warehouse_id: int
    ) -> list[ABCCategoryAnalysis]:
        return self._calculate_abc_classification(warehouse_id)

    def sync_data(
        self, request: StocktakeSyncRequest
    ) -> StocktakeSyncResponse:
        query = self.db.query(StocktakeTask).filter(
            StocktakeTask.status != StocktakeTaskStatus.COMPLETED
        )

        if request.task_ids:
            query = query.filter(StocktakeTask.id.in_(request.task_ids))

        if request.last_sync_at:
            query = query.filter(
                StocktakeTask.updated_at >= request.last_sync_at
            )

        tasks = query.order_by(StocktakeTask.id.desc()).limit(100).all()
        has_more = len(tasks) == 100

        results = []
        if request.include_results:
            task_ids = [t.id for t in tasks]
            results = (
                self.db.query(StocktakeResult)
                .filter(StocktakeResult.task_id.in_(task_ids))
                .all()
            )

        return StocktakeSyncResponse(
            tasks=tasks,
            results=results,
            sync_timestamp=get_current_utc_time(),
            has_more=has_more,
        )


def create_stocktake_service(
    db: Session, user_id: Optional[int] = None
) -> StocktakeService:
    return StocktakeService(db, user_id)
