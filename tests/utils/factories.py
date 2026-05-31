from __future__ import annotations

from datetime import datetime
from typing import Any, Dict, List, Optional

from tests.utils.test_utils import TestUtils
from tests.conftest import (
    CHAIN_IDS,
    TRANSFER_STATUSES,
    PROPOSAL_STATUSES,
    PROPOSAL_TYPES,
    DEFAULT_MULTISIG_OWNERS,
    DEFAULT_THRESHOLD,
)


class TestDataFactory:

    def __init__(self):
        self._counter = 0

    def _next_counter(self) -> int:
        self._counter += 1
        return self._counter

    def create_address(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        overrides = overrides or {}
        now = datetime.now()

        base = {
            'id': TestUtils.generate_id('cl'),
            'address': overrides.get('address') or TestUtils.generate_eth_address(),
            'chainId': overrides.get('chainId') or CHAIN_IDS['ETHEREUM'],
            'derivationPath': overrides.get('derivationPath') or "m/44'/60'/0'/0/0",
            'walletType': overrides.get('walletType') or 'hd',
            'label': overrides.get('label') or f'Test Wallet {self._next_counter()}',
            'metadata': overrides.get('metadata') or {'description': 'Test address'},
            'isActive': overrides.get('isActive') if overrides.get('isActive') is not None else True,
            'createdAt': overrides.get('createdAt') or now,
            'updatedAt': overrides.get('updatedAt') or now,
            'tags': overrides.get('tags') or [],
        }

        return base

    def create_address_with_tags(
        self,
        overrides: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None
    ) -> Dict[str, Any]:
        tags = tags or ['test']
        address = self.create_address(overrides)
        address['tags'] = tags
        address['AddressTag'] = [
            {
                'id': TestUtils.generate_id('cl'),
                'addressId': address['id'],
                'tag': tag,
                'createdAt': TestUtils.generate_timestamp(0, i),
            }
            for i, tag in enumerate(tags)
        ]
        return address

    def create_invalid_address(self) -> Dict[str, Any]:
        return {
            'id': TestUtils.generate_id('cl'),
            'address': 'invalid_address',
            'chainId': 0,
            'derivationPath': '',
            'walletType': 'invalid',
            'isActive': False,
            'createdAt': datetime.now(),
            'updatedAt': datetime.now(),
        }

    def create_address_list(
        self,
        count: int,
        overrides: Optional[Dict[str, Any]] = None
    ) -> List[Dict[str, Any]]:
        return [self.create_address(overrides) for _ in range(count)]

    def create_cross_chain_transfer(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        overrides = overrides or {}
        now = datetime.now()

        base = {
            'id': TestUtils.generate_id('cl'),
            'sourceChainId': overrides.get('sourceChainId') or CHAIN_IDS['ETHEREUM'],
            'targetChainId': overrides.get('targetChainId') or CHAIN_IDS['BSC'],
            'sourceAddress': overrides.get('sourceAddress') or TestUtils.generate_eth_address(),
            'targetAddress': overrides.get('targetAddress') or TestUtils.generate_eth_address(),
            'amount': overrides.get('amount') or TestUtils.generate_amount(),
            'tokenAddress': overrides.get('tokenAddress'),
            'status': overrides.get('status') or 'PENDING',
            'sourceTxHash': overrides.get('sourceTxHash'),
            'targetTxHash': overrides.get('targetTxHash'),
            'messageHash': overrides.get('messageHash'),
            'signatures': overrides.get('signatures') or [],
            'createdAt': overrides.get('createdAt') or now,
            'updatedAt': overrides.get('updatedAt') or now,
        }

        return base

    def create_pending_transfer(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        return self.create_cross_chain_transfer({
            **(overrides or {}),
            'status': 'PENDING',
        })

    def create_locked_transfer(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        signatures = [
            {
                'signer': DEFAULT_MULTISIG_OWNERS[i],
                'signature': TestUtils.generate_signature(),
                'timestamp': datetime.now().timestamp() * 1000,
            }
            for i in range(2)
        ]

        return self.create_cross_chain_transfer({
            **(overrides or {}),
            'status': 'LOCKED',
            'sourceTxHash': TestUtils.generate_transaction_hash(),
            'messageHash': TestUtils.generate_transaction_hash(),
            'signatures': signatures,
        })

    def create_validated_transfer(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        return self.create_cross_chain_transfer({
            **(overrides or {}),
            'status': 'VALIDATED',
        })

    def create_minted_transfer(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        return self.create_cross_chain_transfer({
            **(overrides or {}),
            'status': 'MINTED',
            'targetTxHash': TestUtils.generate_transaction_hash(),
        })

    def create_transfer_list(
        self,
        count: int,
        overrides: Optional[Dict[str, Any]] = None
    ) -> List[Dict[str, Any]]:
        return [self.create_cross_chain_transfer(overrides) for _ in range(count)]

    def create_invalid_transfer(self) -> Dict[str, Any]:
        return {
            'id': TestUtils.generate_id('cl'),
            'sourceChainId': 0,
            'targetChainId': 0,
            'sourceAddress': 'invalid_address',
            'targetAddress': '',
            'amount': '-100',
            'status': 'INVALID',
            'createdAt': datetime.now(),
            'updatedAt': datetime.now(),
        }

    def create_multisig_proposal(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        overrides = overrides or {}
        now = datetime.now()

        base = {
            'id': TestUtils.generate_id('cl'),
            'walletId': overrides.get('walletId') or TestUtils.generate_id('wallet'),
            'chainId': overrides.get('chainId') or CHAIN_IDS['ETHEREUM'],
            'nonce': overrides.get('nonce') if overrides.get('nonce') is not None else 0,
            'type': overrides.get('type') or 'TRANSFER',
            'data': overrides.get('data') or {
                'to': TestUtils.generate_eth_address(),
                'value': TestUtils.generate_amount(),
                'data': '0x',
                'operation': 0,
            },
            'threshold': overrides.get('threshold') or DEFAULT_THRESHOLD,
            'requiredSigners': overrides.get('requiredSigners') or len(DEFAULT_MULTISIG_OWNERS),
            'signatures': overrides.get('signatures') or [],
            'status': overrides.get('status') or 'PENDING',
            'executedTxHash': overrides.get('executedTxHash'),
            'createdAt': overrides.get('createdAt') or now,
            'updatedAt': overrides.get('updatedAt') or now,
        }

        return base

    def create_pending_proposal(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        return self.create_multisig_proposal({
            **(overrides or {}),
            'status': 'PENDING',
            'signatures': [],
        })

    def create_partially_signed_proposal(
        self,
        overrides: Optional[Dict[str, Any]] = None,
        signer_count: int = 1
    ) -> Dict[str, Any]:
        signatures = [
            {
                'signer': DEFAULT_MULTISIG_OWNERS[i],
                'signature': TestUtils.generate_signature(),
                'timestamp': datetime.now().timestamp() * 1000,
            }
            for i in range(signer_count)
        ]

        return self.create_multisig_proposal({
            **(overrides or {}),
            'status': 'PENDING',
            'signatures': signatures,
        })

    def create_approved_proposal(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        signatures = [
            {
                'signer': DEFAULT_MULTISIG_OWNERS[i],
                'signature': TestUtils.generate_signature(),
                'timestamp': datetime.now().timestamp() * 1000,
            }
            for i in range(DEFAULT_THRESHOLD)
        ]

        return self.create_multisig_proposal({
            **(overrides or {}),
            'status': 'APPROVED',
            'signatures': signatures,
        })

    def create_executed_proposal(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        return self.create_approved_proposal({
            **(overrides or {}),
            'status': 'EXECUTED',
            'executedTxHash': TestUtils.generate_transaction_hash(),
        })

    def create_rejected_proposal(
        self,
        overrides: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        return self.create_multisig_proposal({
            **(overrides or {}),
            'status': 'REJECTED',
        })

    def create_proposal_list(
        self,
        count: int,
        overrides: Optional[Dict[str, Any]] = None
    ) -> List[Dict[str, Any]]:
        return [self.create_multisig_proposal(overrides) for _ in range(count)]

    def create_invalid_proposal(self) -> Dict[str, Any]:
        return {
            'id': TestUtils.generate_id('cl'),
            'walletId': '',
            'chainId': 0,
            'nonce': -1,
            'type': 'INVALID',
            'data': {'to': 'invalid_address'},
            'threshold': 0,
            'requiredSigners': 0,
            'signatures': [],
            'status': 'INVALID',
            'createdAt': datetime.now(),
            'updatedAt': datetime.now(),
        }

    def create_signature(
        self,
        signer: Optional[str] = None
    ) -> Dict[str, Any]:
        return {
            'signer': signer or DEFAULT_MULTISIG_OWNERS[0],
            'signature': TestUtils.generate_signature(),
            'timestamp': datetime.now().timestamp() * 1000,
        }
