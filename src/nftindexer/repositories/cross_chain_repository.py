from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import select, func, or_
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload

from ..db import async_session
from ..db.models import CrossChainTransaction, CrossChainMessage
from ..interfaces.repositories import ICrossChainRepository
from ..utils import get_logger, to_checksum_address

logger = get_logger(__name__)


class CrossChainRepository(ICrossChainRepository):
    def __init__(self, session: Optional[AsyncSession] = None):
        self._session = session

    async def _get_session(self) -> AsyncSession:
        if self._session:
            return self._session
        return async_session()

    async def create_transaction(self, tx: CrossChainTransaction) -> CrossChainTransaction:
        session = await self._get_session()
        session.add(tx)
        await session.commit()
        await session.refresh(tx)
        return tx

    async def create_message(self, message: CrossChainMessage) -> CrossChainMessage:
        session = await self._get_session()
        session.add(message)
        await session.commit()
        await session.refresh(message)
        return message

    async def get_transaction(self, tx_id: str) -> Optional[CrossChainTransaction]:
        session = await self._get_session()
        return await session.get(CrossChainTransaction, {"tx_id": tx_id})

    async def get_transaction_with_messages(self, tx_id: str) -> Optional[CrossChainTransaction]:
        session = await self._get_session()
        query = (
            select(CrossChainTransaction)
            .options(joinedload(CrossChainTransaction.messages))
            .where(CrossChainTransaction.tx_id == tx_id)
        )
        result = await session.execute(query)
        return result.scalars().first()

    async def list_transactions(
        self,
        source_chain: Optional[int] = None,
        target_chain: Optional[int] = None,
        status: Optional[str] = None,
        address: Optional[str] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Tuple[List[CrossChainTransaction], int]:
        session = await self._get_session()
        query = select(CrossChainTransaction)

        if source_chain:
            query = query.where(CrossChainTransaction.source_chain_id == source_chain)
        if target_chain:
            query = query.where(CrossChainTransaction.target_chain_id == target_chain)
        if status:
            query = query.where(CrossChainTransaction.status == status)
        if address:
            checksum_addr = to_checksum_address(address)
            query = query.where(
                or_(
                    CrossChainTransaction.sender == checksum_addr,
                    CrossChainTransaction.recipient == checksum_addr,
                )
            )

        count_query = select(func.count()).select_from(query.subquery())
        total = await session.scalar(count_query) or 0

        query = query.order_by(CrossChainTransaction.created_at.desc()).offset(offset).limit(limit)
        result = await session.execute(query)
        transactions = result.scalars().all()

        return list(transactions), total

    async def update_transaction_status(self, tx_id: str, status: str, **kwargs) -> None:
        from datetime import datetime, timezone
        session = await self._get_session()
        tx = await session.get(CrossChainTransaction, {"tx_id": tx_id})
        if tx:
            tx.status = status
            if status == "completed":
                tx.completed_at = datetime.now(timezone.utc)
            for key, value in kwargs.items():
                setattr(tx, key, value)
            await session.commit()

    async def update_source_confirmations(self, tx_id: str, confirmations: int) -> None:
        session = await self._get_session()
        tx = await session.get(CrossChainTransaction, {"tx_id": tx_id})
        if tx:
            tx.confirmations_source = confirmations
            await session.commit()

    async def update_target_confirmations(self, tx_id: str, confirmations: int) -> None:
        session = await self._get_session()
        tx = await session.get(CrossChainTransaction, {"tx_id": tx_id})
        if tx:
            tx.confirmations_target = confirmations
            await session.commit()
