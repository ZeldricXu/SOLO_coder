from fastapi import APIRouter, HTTPException, Depends
from typing import List
from sqlalchemy.ext.asyncio import AsyncSession

from wallethub.api.models.transaction_models import (
    TransactionCreateRequest,
    TransactionResponse,
    MultiSigProposalRequest,
    MultiSigProposalResponse,
    SignRequest,
    SignResponse,
)
from wallethub.core import TransactionError, SigningError
from wallethub.utils import generate_id
from wallethub.db import get_db_session

router = APIRouter(prefix="/transactions", tags=["Transactions"])


@router.post("", response_model=TransactionResponse, status_code=201)
async def create_transaction(
    request: TransactionCreateRequest,
    db: AsyncSession = Depends(get_db_session),
):
    try:
        from wallethub.modules.transaction import TransactionBuilder

        builder = TransactionBuilder(chain=request.chain)

        if request.eip1559:
            tx = builder.build_eip1559(
                to_address=request.to_address,
                value=request.value,
                data=request.data,
                from_address=request.from_address,
                nonce=request.nonce,
                gas_limit=request.gas_limit or 21000,
                max_fee_per_gas=request.max_fee_per_gas,
                max_priority_fee_per_gas=request.max_priority_fee_per_gas,
            )
        else:
            tx = builder.build_legacy(
                to_address=request.to_address,
                value=request.value,
                data=request.data,
                from_address=request.from_address,
                nonce=request.nonce,
                gas_limit=request.gas_limit or 21000,
                gas_price=request.gas_price,
            )

        from datetime import datetime, timezone

        return TransactionResponse(
            tx_id=tx.tx_id,
            chain=tx.chain,
            from_address=tx.from_address,
            to_address=tx.to_address,
            value=tx.value,
            data=tx.data,
            nonce=tx.nonce,
            gas_limit=tx.gas_limit,
            gas_price=tx.gas_price if hasattr(tx, "gas_price") else None,
            max_fee_per_gas=tx.max_fee_per_gas if hasattr(tx, "max_fee_per_gas") else None,
            max_priority_fee_per_gas=tx.max_priority_fee_per_gas if hasattr(tx, "max_priority_fee_per_gas") else None,
            tx_hash=None,
            status="pending",
            block_number=None,
            created_at=datetime.now(timezone.utc),
            updated_at=datetime.now(timezone.utc),
        )
    except TransactionError as e:
        raise HTTPException(status_code=400, detail=e.message)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{tx_id}", response_model=TransactionResponse)
async def get_transaction(tx_id: str):
    raise HTTPException(status_code=404, detail="Transaction not found")


@router.post("/sign", response_model=SignResponse)
async def sign_transaction(request: SignRequest):
    try:
        from wallethub.modules.transaction import SigningService

        service = SigningService()

        if request.private_key:
            from wallethub.modules.transaction import TransactionSigner

            signer = TransactionSigner(request.private_key)
            if request.typed_data:
                signature = signer.sign_typed_data(request.typed_data)
            elif request.message:
                signature = signer.sign_message(request.message)
            else:
                raise SigningError("Either message or typed_data must be provided")

            return SignResponse(
                signature=signature,
                signer_address=signer.address,
            )

        raise SigningError("No signing method provided")
    except SigningError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.post("/multisig/proposals", response_model=MultiSigProposalResponse, status_code=201)
async def create_multisig_proposal(request: MultiSigProposalRequest):
    try:
        from wallethub.modules.transaction import MultiSigManager

        manager = MultiSigManager()
        proposal = manager.create_proposal(
            wallet_id=request.wallet_id,
            to_address=request.to_address,
            value=request.value,
            data=request.data,
        )

        from datetime import datetime, timezone

        return MultiSigProposalResponse(
            proposal_id=proposal.proposal_id,
            wallet_id=proposal.wallet_id,
            to_address=proposal.to_address,
            value=proposal.value,
            data=proposal.data,
            nonce=proposal.nonce,
            signers=list(proposal.signatures.keys()),
            threshold=1,
            status=proposal.status.value,
            created_at=datetime.now(timezone.utc),
        )
    except SigningError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.get("/multisig/proposals/{proposal_id}", response_model=MultiSigProposalResponse)
async def get_multisig_proposal(proposal_id: str):
    raise HTTPException(status_code=404, detail="Proposal not found")
