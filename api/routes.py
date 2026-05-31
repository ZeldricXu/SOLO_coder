from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from ..core.audit_chain import get_audit_chain, AuditLogEntry
from ..core.data_masking import get_masking_engine, MaskingRule, UserRole
from ..core.shamir import get_shard_manager, Share
from ..core.tee_manager import get_tee_manager
from ..core.federated_learning import get_fl_coordinator
from ..core.data_classification import get_classification_engine
from ..core.differential_privacy import get_dp_engine
from ..core.mpc_coordinator import get_mpc_coordinator

router = APIRouter()


class AuditLogRequest(BaseModel):
    action: str
    actor: str
    resource: str
    details: Optional[Dict[str, Any]] = None


class MaskingRequest(BaseModel):
    data: Any
    role: str = "guest"


class KeySplitRequest(BaseModel):
    key_length: int = 32
    threshold: int = 3
    total: int = 5
    holders: Optional[List[str]] = None


class KeyReconstructRequest(BaseModel):
    shares: List[Dict[str, Any]]


class EnclaveCreateRequest(BaseModel):
    name: str
    enclave_type: str = "simulated"
    memory_size: int = 128
    attributes: Optional[Dict[str, Any]] = None


class FLRegisterClientRequest(BaseModel):
    name: str
    address: str
    public_key: Optional[str] = None
    data_samples: int = 0


class FLCreateTaskRequest(BaseModel):
    name: str
    model_type: str
    initial_weights: Dict[str, Any]
    client_ids: Optional[List[str]] = None
    max_rounds: int = 100
    learning_rate: float = 0.01


class FLSubmitUpdateRequest(BaseModel):
    task_id: str
    client_id: str
    model_weights: Dict[str, Any]
    encrypted: bool = False


class ClassificationRequest(BaseModel):
    data: Any
    policy_id: Optional[str] = None


class DPNoiseRequest(BaseModel):
    value: float
    epsilon: float
    sensitivity: float = 1.0
    mechanism: str = "laplace"
    budget_id: Optional[str] = None


class DPCreateBudgetRequest(BaseModel):
    user_id: str
    total_epsilon: float = 10.0
    total_delta: float = 1e-5


class MPCRegisterPartyRequest(BaseModel):
    name: str
    address: str
    public_key: Optional[str] = None


class MPCCreateSessionRequest(BaseModel):
    name: str
    protocol: str
    operation: str
    party_ids: List[str]
    threshold: Optional[int] = None


class MPCSubmitInputRequest(BaseModel):
    session_id: str
    party_id: str
    encrypted_value: str
    commitments: Optional[List[str]] = None


@router.get("/health")
async def health_check():
    return {"status": "healthy", "service": "APIShield"}


@router.post("/audit/logs")
async def add_audit_log(request: AuditLogRequest):
    chain = get_audit_chain()
    entry = chain.add_entry(request.action, request.actor, request.resource, request.details)
    return {"code": 200, "data": entry.dict()}


@router.get("/audit/logs")
async def get_audit_logs(
    action: Optional[str] = None,
    sequence: Optional[int] = None
):
    chain = get_audit_chain()
    if sequence is not None:
        entry = chain.get_entry_by_sequence(sequence)
        if not entry:
            raise HTTPException(status_code=404, detail="Log entry not found")
        return {"code": 200, "data": entry.dict()}
    if action:
        entries = chain.get_entries_by_action(action)
        return {"code": 200, "data": [e.dict() for e in entries]}
    return {"code": 200, "data": chain.to_dict()}


@router.get("/audit/verify")
async def verify_audit_chain():
    chain = get_audit_chain()
    result = chain.verify_integrity()
    return {"code": 200, "data": result}


@router.get("/audit/detect-tampering")
async def detect_tampering(
    start: int = Query(0, ge=0),
    end: Optional[int] = None
):
    chain = get_audit_chain()
    result = chain.detect_tampering(start, end)
    return {"code": 200, "data": result}


@router.post("/masking/mask")
async def mask_data(request: MaskingRequest):
    engine = get_masking_engine()
    result = engine.mask_data(request.data, request.role)
    return {"code": 200, "data": result, "role": request.role}


@router.post("/masking/auto-mask")
async def auto_mask_data(request: MaskingRequest):
    engine = get_masking_engine()
    result = engine.auto_detect_and_mask(request.data, request.role)
    return {"code": 200, "data": result, "role": request.role}


@router.get("/masking/roles")
async def get_masking_roles():
    engine = get_masking_engine()
    roles = engine.get_available_roles()
    return {"code": 200, "data": roles}


@router.get("/masking/roles/{role_name}")
async def get_role_rules(role_name: str):
    engine = get_masking_engine()
    rules = engine.get_role_rules(role_name)
    if not rules:
        raise HTTPException(status_code=404, detail="Role not found")
    return {"code": 200, "data": rules}


@router.post("/shamir/split")
async def split_key(request: KeySplitRequest):
    manager = get_shard_manager()
    try:
        metadata, shares, _ = manager.generate_and_split_key(
            request.key_length, request.threshold, request.total, request.holders
        )
        return {
            "code": 200,
            "data": {
                "metadata": metadata.dict(),
                "shares": [s.dict() for s in shares]
            }
        }
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/shamir/reconstruct")
async def reconstruct_key(request: KeyReconstructRequest):
    manager = get_shard_manager()
    try:
        share_objects = [Share(**s) for s in request.shares]
        key = manager.reconstruct_key(share_objects)
        return {"code": 200, "data": {"key": key.hex()}}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/shamir/keys")
async def list_keys():
    manager = get_shard_manager()
    keys = manager.list_keys()
    return {"code": 200, "data": [k.dict() for k in keys]}


@router.get("/shamir/keys/{key_id}")
async def get_key_metadata(key_id: str):
    manager = get_shard_manager()
    metadata = manager.get_key_metadata(key_id)
    if not metadata:
        raise HTTPException(status_code=404, detail="Key not found")
    return {"code": 200, "data": metadata.dict()}


@router.get("/shamir/keys/{key_id}/shares")
async def get_key_shares(key_id: str):
    manager = get_shard_manager()
    shares = manager.get_shares_by_key(key_id)
    return {"code": 200, "data": [s.dict() for s in shares]}


@router.delete("/shamir/keys/{key_id}")
async def delete_key(key_id: str):
    manager = get_shard_manager()
    if manager.delete_key(key_id):
        return {"code": 200, "message": "Key deleted"}
    raise HTTPException(status_code=404, detail="Key not found")


@router.post("/tee/enclaves")
async def create_enclave(request: EnclaveCreateRequest):
    manager = get_tee_manager()
    try:
        enclave = manager.create_enclave(
            request.name, request.enclave_type, request.memory_size, request.attributes
        )
        return {"code": 200, "data": enclave.dict()}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/tee/enclaves")
async def list_enclaves(status: Optional[str] = None):
    manager = get_tee_manager()
    enclaves = manager.list_enclaves(status)
    return {"code": 200, "data": [e.dict() for e in enclaves]}


@router.get("/tee/enclaves/{enclave_id}")
async def get_enclave(enclave_id: str):
    manager = get_tee_manager()
    enclave = manager.get_enclave(enclave_id)
    if not enclave:
        raise HTTPException(status_code=404, detail="Enclave not found")
    return {"code": 200, "data": enclave.dict()}


@router.post("/tee/enclaves/{enclave_id}/start")
async def start_enclave(enclave_id: str):
    manager = get_tee_manager()
    if manager.start_enclave(enclave_id):
        return {"code": 200, "message": "Enclave started"}
    raise HTTPException(status_code=400, detail="Failed to start enclave")


@router.post("/tee/enclaves/{enclave_id}/pause")
async def pause_enclave(enclave_id: str):
    manager = get_tee_manager()
    if manager.pause_enclave(enclave_id):
        return {"code": 200, "message": "Enclave paused"}
    raise HTTPException(status_code=400, detail="Failed to pause enclave")


@router.post("/tee/enclaves/{enclave_id}/resume")
async def resume_enclave(enclave_id: str):
    manager = get_tee_manager()
    if manager.resume_enclave(enclave_id):
        return {"code": 200, "message": "Enclave resumed"}
    raise HTTPException(status_code=400, detail="Failed to resume enclave")


@router.delete("/tee/enclaves/{enclave_id}")
async def destroy_enclave(enclave_id: str):
    manager = get_tee_manager()
    if manager.destroy_enclave(enclave_id):
        return {"code": 200, "message": "Enclave destroyed"}
    raise HTTPException(status_code=404, detail="Enclave not found")


@router.post("/tee/enclaves/{enclave_id}/attest")
async def generate_attestation(enclave_id: str, nonce: Optional[str] = None):
    manager = get_tee_manager()
    report = manager.generate_attestation(enclave_id, nonce)
    if not report:
        raise HTTPException(status_code=400, detail="Failed to generate attestation")
    return {"code": 200, "data": report.dict()}


@router.get("/tee/enclaves/{enclave_id}/health")
async def get_enclave_health(enclave_id: str):
    manager = get_tee_manager()
    health = manager.get_enclave_health(enclave_id)
    if not health:
        raise HTTPException(status_code=404, detail="Enclave not found")
    return {"code": 200, "data": health}


@router.post("/fl/clients")
async def register_client(request: FLRegisterClientRequest):
    coordinator = get_fl_coordinator()
    client = coordinator.register_client(
        request.name, request.address, request.public_key, request.data_samples
    )
    return {"code": 200, "data": client.dict()}


@router.get("/fl/clients")
async def list_clients(status: Optional[str] = None):
    coordinator = get_fl_coordinator()
    clients = coordinator.list_clients(status)
    return {"code": 200, "data": [c.dict() for c in clients]}


@router.delete("/fl/clients/{client_id}")
async def unregister_client(client_id: str):
    coordinator = get_fl_coordinator()
    if coordinator.unregister_client(client_id):
        return {"code": 200, "message": "Client unregistered"}
    raise HTTPException(status_code=404, detail="Client not found")


@router.post("/fl/tasks")
async def create_fl_task(request: FLCreateTaskRequest):
    coordinator = get_fl_coordinator()
    task = coordinator.create_training_task(
        request.name, request.model_type, request.initial_weights,
        request.client_ids, request.max_rounds, request.learning_rate
    )
    return {"code": 200, "data": task.dict()}


@router.get("/fl/tasks")
async def list_fl_tasks(status: Optional[str] = None):
    coordinator = get_fl_coordinator()
    tasks = coordinator.list_tasks(status)
    return {"code": 200, "data": [t.dict() for t in tasks]}


@router.get("/fl/tasks/{task_id}")
async def get_fl_task(task_id: str):
    coordinator = get_fl_coordinator()
    task = coordinator.get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return {"code": 200, "data": task.dict()}


@router.post("/fl/tasks/{task_id}/start")
async def start_fl_task(task_id: str):
    coordinator = get_fl_coordinator()
    if coordinator.start_task(task_id):
        return {"code": 200, "message": "Task started"}
    raise HTTPException(status_code=400, detail="Failed to start task")


@router.get("/fl/tasks/{task_id}/distribute")
async def distribute_task(task_id: str):
    coordinator = get_fl_coordinator()
    task_data = coordinator.distribute_task(task_id)
    if not task_data:
        raise HTTPException(status_code=400, detail="Task not ready for distribution")
    return {"code": 200, "data": task_data}


@router.post("/fl/updates")
async def submit_model_update(request: FLSubmitUpdateRequest):
    coordinator = get_fl_coordinator()
    update = coordinator.submit_model_update(
        request.task_id, request.client_id, request.model_weights, request.encrypted
    )
    if not update:
        raise HTTPException(status_code=400, detail="Failed to submit update")
    return {"code": 200, "data": update.dict()}


@router.post("/fl/tasks/{task_id}/aggregate")
async def aggregate_updates(task_id: str):
    coordinator = get_fl_coordinator()
    global_model = coordinator.aggregate_updates(task_id)
    if not global_model:
        raise HTTPException(status_code=400, detail="No updates to aggregate")
    return {"code": 200, "data": global_model.dict()}


@router.get("/fl/tasks/{task_id}/model")
async def get_global_model(task_id: str):
    coordinator = get_fl_coordinator()
    model = coordinator.get_latest_global_model(task_id)
    if not model:
        raise HTTPException(status_code=404, detail="Model not found")
    return {"code": 200, "data": model.dict()}


@router.get("/fl/tasks/{task_id}/progress")
async def get_task_progress(task_id: str):
    coordinator = get_fl_coordinator()
    progress = coordinator.get_task_progress(task_id)
    if not progress:
        raise HTTPException(status_code=404, detail="Task not found")
    return {"code": 200, "data": progress}


@router.post("/classification/scan")
async def classify_data(request: ClassificationRequest):
    engine = get_classification_engine()
    report = engine.scan_data(request.data, request.policy_id)
    return {"code": 200, "data": report.dict()}


@router.post("/classification/apply-policy/{policy_id}")
async def apply_policy(policy_id: str, data: Any):
    engine = get_classification_engine()
    result = engine.apply_policy(data, policy_id)
    if "error" in result:
        raise HTTPException(status_code=404, detail=result["error"])
    return {"code": 200, "data": result}


@router.get("/classification/levels")
async def get_classification_levels():
    engine = get_classification_engine()
    return {"code": 200, "data": [v.dict() for v in engine.levels.values()]}


@router.get("/classification/patterns")
async def get_patterns(category: Optional[str] = None):
    engine = get_classification_engine()
    patterns = engine.get_sensitive_patterns(category)
    return {"code": 200, "data": [p.dict() for p in patterns]}


@router.get("/classification/policies")
async def get_policies():
    engine = get_classification_engine()
    return {"code": 200, "data": [v.dict() for v in engine.policies.values()]}


@router.get("/classification/stats")
async def get_classification_stats():
    engine = get_classification_engine()
    return {"code": 200, "data": engine.get_statistics()}


@router.post("/dp/budgets")
async def create_budget(request: DPCreateBudgetRequest):
    engine = get_dp_engine()
    budget = engine.create_budget(request.user_id, request.total_epsilon, request.total_delta)
    return {"code": 200, "data": budget.dict()}


@router.get("/dp/budgets")
async def list_budgets(user_id: Optional[str] = None):
    engine = get_dp_engine()
    if user_id:
        budgets = engine.get_budgets_by_user(user_id)
    else:
        budgets = list(engine.budgets.values())
    return {"code": 200, "data": [b.dict() for b in budgets]}


@router.get("/dp/budgets/{budget_id}")
async def get_budget(budget_id: str):
    engine = get_dp_engine()
    budget = engine.get_budget(budget_id)
    if not budget:
        raise HTTPException(status_code=404, detail="Budget not found")
    return {"code": 200, "data": budget.dict()}


@router.post("/dp/budgets/{budget_id}/reset")
async def reset_budget(budget_id: str):
    engine = get_dp_engine()
    if engine.reset_budget(budget_id):
        return {"code": 200, "message": "Budget reset"}
    raise HTTPException(status_code=404, detail="Budget not found")


@router.delete("/dp/budgets/{budget_id}")
async def delete_budget(budget_id: str):
    engine = get_dp_engine()
    if engine.delete_budget(budget_id):
        return {"code": 200, "message": "Budget deleted"}
    raise HTTPException(status_code=404, detail="Budget not found")


@router.post("/dp/noise")
async def add_noise(request: DPNoiseRequest):
    engine = get_dp_engine()
    try:
        result = engine.add_noise_to_numeric(
            request.value, request.epsilon, request.sensitivity,
            request.mechanism, budget_id=request.budget_id
        )
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/dp/count")
async def private_count(data: List[Any], epsilon: float = 1.0, budget_id: Optional[str] = None):
    engine = get_dp_engine()
    try:
        result = engine.private_count(data, epsilon, budget_id)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/dp/sum")
async def private_sum(
    values: List[float],
    epsilon: float = 1.0,
    lower_bound: float = 0.0,
    upper_bound: float = 100.0,
    budget_id: Optional[str] = None
):
    engine = get_dp_engine()
    try:
        result = engine.private_sum(values, epsilon, lower_bound, upper_bound, budget_id)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/dp/average")
async def private_average(
    values: List[float],
    epsilon: float = 1.0,
    lower_bound: float = 0.0,
    upper_bound: float = 100.0,
    budget_id: Optional[str] = None
):
    engine = get_dp_engine()
    try:
        result = engine.private_average(values, epsilon, lower_bound, upper_bound, budget_id)
        return {"code": 200, "data": result}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/dp/stats")
async def get_dp_stats():
    engine = get_dp_engine()
    return {"code": 200, "data": engine.get_statistics()}


@router.post("/mpc/parties")
async def register_party(request: MPCRegisterPartyRequest):
    coordinator = get_mpc_coordinator()
    party = coordinator.register_party(request.name, request.address, request.public_key)
    return {"code": 200, "data": party.dict()}


@router.get("/mpc/parties")
async def list_mpc_parties(status: Optional[str] = None):
    coordinator = get_mpc_coordinator()
    parties = coordinator.list_parties(status)
    return {"code": 200, "data": [p.dict() for p in parties]}


@router.delete("/mpc/parties/{party_id}")
async def unregister_party(party_id: str):
    coordinator = get_mpc_coordinator()
    if coordinator.unregister_party(party_id):
        return {"code": 200, "message": "Party unregistered"}
    raise HTTPException(status_code=404, detail="Party not found")


@router.post("/mpc/sessions")
async def create_mpc_session(request: MPCCreateSessionRequest):
    coordinator = get_mpc_coordinator()
    session = coordinator.create_session(
        request.name, request.protocol, request.operation, request.party_ids, request.threshold
    )
    if not session:
        raise HTTPException(status_code=400, detail="Failed to create session")
    return {"code": 200, "data": session.dict()}


@router.get("/mpc/sessions")
async def list_mpc_sessions(status: Optional[str] = None):
    coordinator = get_mpc_coordinator()
    sessions = coordinator.list_sessions(status)
    return {"code": 200, "data": [s.dict() for s in sessions]}


@router.get("/mpc/sessions/{session_id}")
async def get_mpc_session(session_id: str):
    coordinator = get_mpc_coordinator()
    session = coordinator.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    return {"code": 200, "data": session.dict()}


@router.post("/mpc/sessions/{session_id}/start")
async def start_mpc_session(session_id: str):
    coordinator = get_mpc_coordinator()
    if coordinator.start_session(session_id):
        return {"code": 200, "message": "Session started"}
    raise HTTPException(status_code=400, detail="Failed to start session")


@router.post("/mpc/inputs")
async def submit_mpc_input(request: MPCSubmitInputRequest):
    coordinator = get_mpc_coordinator()
    input_data = coordinator.submit_encrypted_input(
        request.session_id, request.party_id, request.encrypted_value, request.commitments
    )
    if not input_data:
        raise HTTPException(status_code=400, detail="Failed to submit input")
    return {"code": 200, "data": input_data.dict()}


@router.post("/mpc/sessions/{session_id}/compute")
async def execute_mpc_computation(session_id: str):
    coordinator = get_mpc_coordinator()
    result = coordinator.execute_computation(session_id)
    if not result:
        raise HTTPException(status_code=400, detail="Failed to execute computation")
    return {"code": 200, "data": result}


@router.get("/mpc/sessions/{session_id}/result")
async def get_mpc_result(session_id: str, party_id: Optional[str] = None):
    coordinator = get_mpc_coordinator()
    result = coordinator.get_session_result(session_id, party_id)
    if not result:
        raise HTTPException(status_code=404, detail="Result not found")
    return {"code": 200, "data": result}


@router.get("/mpc/sessions/{session_id}/progress")
async def get_mpc_progress(session_id: str):
    coordinator = get_mpc_coordinator()
    progress = coordinator.get_session_progress(session_id)
    if not progress:
        raise HTTPException(status_code=404, detail="Session not found")
    return {"code": 200, "data": progress}


@router.get("/mpc/protocols")
async def list_mpc_protocols():
    coordinator = get_mpc_coordinator()
    protocols = coordinator.list_protocols()
    return {"code": 200, "data": [p.dict() for p in protocols]}


@router.get("/mpc/stats")
async def get_mpc_stats():
    coordinator = get_mpc_coordinator()
    return {"code": 200, "data": coordinator.get_statistics()}
