from typing import AsyncGenerator, Optional, Annotated
from fastapi import Depends, Request, Header, HTTPException, status

from ..config import get_settings
from ..modules import (
    get_chain_adapter,
    get_multisig_module,
    get_event_listener_module,
    get_cross_chain_module,
    get_hd_wallet_module,
    get_zkp_verifier_module,
    get_gas_estimator_module,
    get_storage_module,
    get_indexer_module,
    init_multisig_module,
    init_event_listener_module,
    init_cross_chain_module,
)
from ..container import get_container
from ..interfaces.modules import (
    IMultiSigModule,
    IEventListenerModule,
    ICrossChainModule,
)
from ..utils import get_logger

logger = get_logger(__name__)
settings = get_settings()


async def get_request_trace_id(
    x_trace_id: Optional[str] = Header(None, alias="X-Trace-Id")
) -> str:
    return x_trace_id or "default-trace-id"


async def verify_api_key(
    x_api_key: Optional[str] = Header(None, alias="X-API-Key")
) -> None:
    if settings.api.require_api_key:
        if not x_api_key or x_api_key not in settings.api.api_keys:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or missing API key",
            )


async def get_di_container():
    container = get_container()
    if not container._initialized:
        await container.initialize()
    return container


async def get_chain_adapter_dep():
    adapter = get_chain_adapter()
    if not adapter._initialized:
        await adapter.initialize()
    return adapter


async def get_multisig_module_dep() -> IMultiSigModule:
    container = await get_di_container()
    chain_adapter = await get_chain_adapter_dep()
    module = get_multisig_module()
    if not module._initialized:
        module = await init_multisig_module(container)
    return module


async def get_event_listener_module_dep() -> IEventListenerModule:
    container = await get_di_container()
    chain_adapter = await get_chain_adapter_dep()
    module = get_event_listener_module()
    if not module._initialized:
        module = await init_event_listener_module(chain_adapter, container)
    return module


async def get_cross_chain_module_dep() -> ICrossChainModule:
    container = await get_di_container()
    chain_adapter = await get_chain_adapter_dep()
    module = get_cross_chain_module()
    if not module._initialized:
        module = await init_cross_chain_module(chain_adapter, container)
    return module


async def get_hd_wallet_module_dep():
    module = get_hd_wallet_module()
    if not module._initialized:
        await module.initialize()
    return module


async def get_zkp_verifier_module_dep():
    module = get_zkp_verifier_module()
    if not module._initialized:
        await module.initialize()
    return module


async def get_gas_estimator_module_dep():
    module = get_gas_estimator_module()
    if not module._initialized:
        await module.initialize()
    return module


async def get_storage_module_dep():
    module = get_storage_module()
    if not module._initialized:
        await module.initialize()
    return module


async def get_indexer_module_dep():
    module = get_indexer_module()
    if not module._initialized:
        await module.initialize()
    return module


TraceIdDep = Annotated[str, Depends(get_request_trace_id)]
ApiKeyDep = Annotated[None, Depends(verify_api_key)]
ChainAdapterDep = Annotated[object, Depends(get_chain_adapter_dep)]
MultiSigModuleDep = Annotated[IMultiSigModule, Depends(get_multisig_module_dep)]
EventListenerModuleDep = Annotated[IEventListenerModule, Depends(get_event_listener_module_dep)]
CrossChainModuleDep = Annotated[ICrossChainModule, Depends(get_cross_chain_module_dep)]
HDWalletModuleDep = Annotated[object, Depends(get_hd_wallet_module_dep)]
ZKPVerifierModuleDep = Annotated[object, Depends(get_zkp_verifier_module_dep)]
GasEstimatorModuleDep = Annotated[object, Depends(get_gas_estimator_module_dep)]
StorageModuleDep = Annotated[object, Depends(get_storage_module_dep)]
IndexerModuleDep = Annotated[object, Depends(get_indexer_module_dep)]
