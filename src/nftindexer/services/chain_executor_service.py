from typing import Any, Dict, List, Optional

from ..interfaces.services import IChainExecutor
from ..utils import get_logger, generate_id

logger = get_logger(__name__)


class ChainExecutorService(IChainExecutor):
    def __init__(self, chain_adapter: Optional[Any] = None):
        self._chain_adapter = chain_adapter

    async def execute_transaction(
        self,
        chain_id: int,
        wallet_address: str,
        to: str,
        value: int,
        data: str,
        operation: int,
        signatures: str,
        nonce: int,
    ) -> str:
        logger.info(
            f"Would execute on chain {chain_id}: wallet={wallet_address}, to={to}, value={value}, nonce={nonce}"
        )
        return generate_id("tx")
