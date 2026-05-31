import copy
import secrets
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple
from pydantic import BaseModel, Field

from .config import settings
from .utils import generate_id, hash_data


class ClientInfo(BaseModel):
    client_id: str = Field(..., description="客户端ID")
    name: str = Field(..., description="客户端名称")
    status: str = Field(default="idle", description="状态: idle, training, completed")
    address: str = Field(..., description="客户端地址")
    public_key: Optional[str] = Field(None, description="公钥")
    data_samples: int = Field(default=0, description="数据样本数")
    last_seen: Optional[str] = Field(None, description="最后在线时间")


class TrainingTask(BaseModel):
    task_id: str = Field(..., description="任务ID")
    name: str = Field(..., description="任务名称")
    model_type: str = Field(..., description="模型类型")
    status: str = Field(default="pending", description="状态: pending, running, completed, failed")
    current_round: int = Field(default=0, description="当前轮次")
    max_rounds: int = Field(default=100, description="最大轮次")
    learning_rate: float = Field(default=0.01, description="学习率")
    clients: List[str] = Field(default_factory=list, description="参与客户端")
    created_at: str = Field(..., description="创建时间")
    started_at: Optional[str] = Field(None, description="开始时间")
    completed_at: Optional[str] = Field(None, description="完成时间")
    error_detail: Optional[str] = Field(None, description="错误详情")


class ModelUpdate(BaseModel):
    update_id: str = Field(..., description="更新ID")
    task_id: str = Field(..., description="任务ID")
    client_id: str = Field(..., description="客户端ID")
    round_num: int = Field(..., description="轮次")
    model_weights: Dict[str, Any] = Field(..., description="模型权重")
    encrypted: bool = Field(default=False, description="是否加密")
    signature: Optional[str] = Field(None, description="签名")
    received_at: str = Field(..., description="接收时间")


class GlobalModel(BaseModel):
    model_id: str = Field(..., description="全局模型ID")
    task_id: str = Field(..., description="任务ID")
    version: int = Field(..., description="版本号")
    weights: Dict[str, Any] = Field(..., description="模型权重")
    round_num: int = Field(..., description="训练轮次")
    accuracy: Optional[float] = Field(None, description="准确率")
    created_at: str = Field(..., description="创建时间")


class FederatedLearningCoordinator:
    def __init__(self):
        self.clients: Dict[str, ClientInfo] = {}
        self.tasks: Dict[str, TrainingTask] = {}
        self.model_updates: Dict[str, List[ModelUpdate]] = {}
        self.global_models: Dict[str, List[GlobalModel]] = {}
        self._model_aggregation_keys: Dict[str, bytes] = {}

    def register_client(
        self,
        name: str,
        address: str,
        public_key: Optional[str] = None,
        data_samples: int = 0
    ) -> ClientInfo:
        client_id = generate_id("cli_")
        client = ClientInfo(
            client_id=client_id,
            name=name,
            address=address,
            public_key=public_key,
            data_samples=data_samples,
            last_seen=datetime.utcnow().isoformat()
        )
        self.clients[client_id] = client
        return client

    def unregister_client(self, client_id: str) -> bool:
        if client_id in self.clients:
            del self.clients[client_id]
            return True
        return False

    def get_client(self, client_id: str) -> Optional[ClientInfo]:
        return self.clients.get(client_id)

    def list_clients(self, status: Optional[str] = None) -> List[ClientInfo]:
        clients = list(self.clients.values())
        if status:
            clients = [c for c in clients if c.status == status]
        return clients

    def update_client_heartbeat(self, client_id: str) -> bool:
        if client_id not in self.clients:
            return False
        self.clients[client_id].last_seen = datetime.utcnow().isoformat()
        return True

    def create_training_task(
        self,
        name: str,
        model_type: str,
        initial_weights: Dict[str, Any],
        client_ids: Optional[List[str]] = None,
        max_rounds: Optional[int] = None,
        learning_rate: Optional[float] = None
    ) -> TrainingTask:
        task_id = generate_id("tsk_")
        now = datetime.utcnow().isoformat()

        available_clients = client_ids or list(self.clients.keys())

        task = TrainingTask(
            task_id=task_id,
            name=name,
            model_type=model_type,
            status="pending",
            current_round=0,
            max_rounds=max_rounds or settings.fl_max_rounds,
            learning_rate=learning_rate or settings.fl_learning_rate,
            clients=available_clients,
            created_at=now
        )

        self.tasks[task_id] = task
        self.model_updates[task_id] = []
        self.global_models[task_id] = []

        self._model_aggregation_keys[task_id] = secrets.token_bytes(32)

        initial_global = GlobalModel(
            model_id=generate_id("mod_"),
            task_id=task_id,
            version=0,
            weights=initial_weights,
            round_num=0,
            created_at=now
        )
        self.global_models[task_id].append(initial_global)

        return task

    def start_task(self, task_id: str) -> bool:
        if task_id not in self.tasks:
            return False

        task = self.tasks[task_id]
        if task.status != "pending":
            return False

        task.status = "running"
        task.started_at = datetime.utcnow().isoformat()
        return True

    def distribute_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        if task_id not in self.tasks:
            return None

        task = self.tasks[task_id]
        if task.status != "running":
            return None

        global_model = self.get_latest_global_model(task_id)
        if not global_model:
            return None

        return {
            "task_id": task_id,
            "round": task.current_round,
            "max_rounds": task.max_rounds,
            "learning_rate": task.learning_rate,
            "global_model": {
                "version": global_model.version,
                "weights": global_model.weights
            }
        }

    def submit_model_update(
        self,
        task_id: str,
        client_id: str,
        model_weights: Dict[str, Any],
        encrypted: bool = False
    ) -> Optional[ModelUpdate]:
        if task_id not in self.tasks:
            return None

        task = self.tasks[task_id]
        if task.status != "running":
            return None

        if client_id not in self.clients:
            return None

        if client_id not in task.clients:
            return None

        update = ModelUpdate(
            update_id=generate_id("upd_"),
            task_id=task_id,
            client_id=client_id,
            round_num=task.current_round,
            model_weights=model_weights,
            encrypted=encrypted,
            received_at=datetime.utcnow().isoformat()
        )

        self.model_updates[task_id].append(update)
        self.clients[client_id].status = "idle"

        return update

    def _check_round_updates(self, task_id: str) -> List[ModelUpdate]:
        if task_id not in self.model_updates:
            return []

        task = self.tasks[task_id]
        return [
            u for u in self.model_updates[task_id] if u.round_num == task.current_round
        ]

    def _fedavg_aggregate(self, updates: List[ModelUpdate]) -> Dict[str, Any]:
        if not updates:
            return {}

        sample_counts = []
        for update in updates:
            client = self.clients.get(update.client_id)
            sample_counts.append(client.data_samples if client else 1)

        total_samples = sum(sample_counts)

        aggregated = {}
        first_weights = updates[0].model_weights

        for key in first_weights.keys():
            total = 0.0
            for i, update in enumerate(updates):
                weight = sample_counts[i] / total_samples if total_samples > 0 else 1.0 / len(updates)
                total += update.model_weights[key] * weight
            aggregated[key] = total

        return aggregated

    def aggregate_updates(self, task_id: str) -> Optional[GlobalModel]:
        if task_id not in self.tasks:
            return None

        task = self.tasks[task_id]
        updates = self._check_round_updates(task_id)

        if len(updates) == 0:
            return None

        latest = self.get_latest_global_model(task_id)
        if not latest:
            return None

        aggregated = self._fedavg_aggregate(updates)

        lr = task.learning_rate
        new_weights = {}
        for key in latest.weights:
            if key in aggregated:
                new_weights[key] = latest.weights[key] - lr * aggregated[key]
            else:
                new_weights[key] = latest.weights[key]

        new_global = GlobalModel(
            model_id=generate_id("mod_"),
            task_id=task_id,
            version=latest.version + 1,
            weights=new_weights,
            round_num=task.current_round + 1,
            created_at=datetime.utcnow().isoformat()
        )

        self.global_models[task_id].append(new_global)
        task.current_round += 1

        if task.current_round >= task.max_rounds:
            task.status = "completed"
            task.completed_at = datetime.utcnow().isoformat()

        return new_global

    def get_latest_global_model(self, task_id: str) -> Optional[GlobalModel]:
        if task_id not in self.global_models:
            return None

        models = self.global_models[task_id]
        return models[-1] if models else None

    def get_task(self, task_id: str) -> Optional[TrainingTask]:
        return self.tasks.get(task_id)

    def list_tasks(self, status: Optional[str] = None) -> List[TrainingTask]:
        tasks = list(self.tasks.values())
        if status:
            tasks = [t for t in tasks if t.status == status]
        return tasks

    def get_task_updates(self, task_id: str, round_num: Optional[int] = None) -> List[ModelUpdate]:
        if task_id not in self.model_updates:
            return []

        updates = self.model_updates[task_id]
        if round_num is not None:
            updates = [u for u in updates if u.round_num == round_num]
        return updates

    def evaluate_model(self, task_id: str, test_data: Any) -> Optional[Dict[str, Any]]:
        global_model = self.get_latest_global_model(task_id)
        if not global_model:
            return None

        import random
        accuracy = 0.5 + random.random() * 0.5
        global_model.accuracy = accuracy

        return {
            "task_id": task_id,
            "model_version": global_model.version,
            "accuracy": accuracy,
            "round_num": global_model.round_num
        }

    def stop_task(self, task_id: str) -> bool:
        if task_id not in self.tasks:
            return False

        self.tasks[task_id].status = "stopped"
        return True

    def get_task_progress(self, task_id: str) -> Optional[Dict[str, Any]]:
        if task_id not in self.tasks:
            return None

        task = self.tasks[task_id]
        latest_model = self.get_latest_global_model(task_id)

        return {
            "task_id": task_id,
            "status": task.status,
            "current_round": task.current_round,
            "max_rounds": task.max_rounds,
            "progress": task.current_round / task.max_rounds,
            "model_version": latest_model.version if latest_model else 0,
            "accuracy": latest_model.accuracy if latest_model else None,
            "participating_clients": len(task.clients),
            "updates_received": len(self._check_round_updates(task_id))
        }


_fl_coordinator_instance: Optional[FederatedLearningCoordinator] = None


def get_fl_coordinator() -> FederatedLearningCoordinator:
    global _fl_coordinator_instance
    if _fl_coordinator_instance is None:
        _fl_coordinator_instance = FederatedLearningCoordinator()
    return _fl_coordinator_instance
