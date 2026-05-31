from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload

from ..db import async_session
from ..db.models import MultiSigWallet, MultiSigProposal, MultiSigSignature
from ..interfaces.repositories import IMultiSigRepository
from ..utils import get_logger

logger = get_logger(__name__)


class MultiSigRepository(IMultiSigRepository):
    def __init__(self, session: Optional[AsyncSession] = None):
        self._session = session

    async def _get_session(self) -> AsyncSession:
        if self._session:
            return self._session
        return async_session()

    async def create_wallet(self, wallet: MultiSigWallet) -> MultiSigWallet:
        session = await self._get_session()
        session.add(wallet)
        await session.commit()
        await session.refresh(wallet)
        return wallet

    async def get_wallet(self, wallet_id: str) -> Optional[MultiSigWallet]:
        session = await self._get_session()
        return await session.get(MultiSigWallet, {"wallet_id": wallet_id})

    async def list_wallets(
        self, chain_id: Optional[int] = None, offset: int = 0, limit: int = 50
    ) -> Tuple[List[MultiSigWallet], int]:
        session = await self._get_session()
        query = select(MultiSigWallet)
        if chain_id:
            query = query.where(MultiSigWallet.chain_id == chain_id)

        count_query = select(func.count()).select_from(query.subquery())
        total = await session.scalar(count_query) or 0

        query = query.order_by(MultiSigWallet.created_at.desc()).offset(offset).limit(limit)
        result = await session.execute(query)
        wallets = result.scalars().all()

        return list(wallets), total

    async def create_proposal(self, proposal: MultiSigProposal) -> MultiSigProposal:
        session = await self._get_session()
        session.add(proposal)
        await session.commit()
        await session.refresh(proposal)
        return proposal

    async def get_proposal(self, proposal_id: str) -> Optional[MultiSigProposal]:
        session = await self._get_session()
        return await session.get(MultiSigProposal, {"proposal_id": proposal_id})

    async def get_proposal_with_relations(self, proposal_id: str) -> Optional[MultiSigProposal]:
        session = await self._get_session()
        query = (
            select(MultiSigProposal)
            .options(joinedload(MultiSigProposal.signatures), joinedload(MultiSigProposal.wallet))
            .where(MultiSigProposal.proposal_id == proposal_id)
        )
        result = await session.execute(query)
        return result.scalars().first()

    async def list_proposals(
        self,
        wallet_id: Optional[str] = None,
        status: Optional[str] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Tuple[List[MultiSigProposal], int]:
        session = await self._get_session()
        query = select(MultiSigProposal)
        if wallet_id:
            query = query.where(MultiSigProposal.wallet_id == wallet_id)
        if status:
            query = query.where(MultiSigProposal.status == status)

        count_query = select(func.count()).select_from(query.subquery())
        total = await session.scalar(count_query) or 0

        query = query.order_by(MultiSigProposal.created_at.desc()).offset(offset).limit(limit)
        result = await session.execute(query)
        proposals = result.scalars().all()

        return list(proposals), total

    async def add_signature(self, signature: MultiSigSignature) -> MultiSigSignature:
        session = await self._get_session()
        session.add(signature)
        await session.commit()
        await session.refresh(signature)
        return signature

    async def get_signature_count(self, proposal_id: str) -> int:
        session = await self._get_session()
        query = (
            select(func.count())
            .select_from(MultiSigSignature)
            .where(MultiSigSignature.proposal_id == proposal_id)
        )
        return await session.scalar(query) or 0

    async def has_signature(self, proposal_id: str, signer: str) -> bool:
        session = await self._get_session()
        query = select(MultiSigSignature).where(
            MultiSigSignature.proposal_id == proposal_id,
            MultiSigSignature.signer == signer,
        )
        result = await session.execute(query)
        return result.scalars().first() is not None

    async def update_proposal_status(self, proposal_id: str, status: str, **kwargs) -> None:
        session = await self._get_session()
        proposal = await session.get(MultiSigProposal, {"proposal_id": proposal_id})
        if proposal:
            proposal.status = status
            for key, value in kwargs.items():
                setattr(proposal, key, value)
            await session.commit()

    async def increment_wallet_nonce(self, wallet_id: str) -> None:
        session = await self._get_session()
        wallet = await session.get(MultiSigWallet, {"wallet_id": wallet_id})
        if wallet:
            wallet.nonce = wallet.nonce + 1
            await session.commit()
