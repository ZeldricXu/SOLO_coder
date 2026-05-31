from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Union
from eth_account import Account
from eth_account.datastructures import SignedTransaction
from eth_utils import to_checksum_address
from web3 import Web3
from web3.types import TxData, TxParams

from wallethub.core import (
    Address,
    Hash,
    HexStr,
    Wei,
    TransactionError,
    ChainInteractionError,
)
from wallethub.config import get_settings
from wallethub.utils import generate_id


@dataclass
class BaseTransaction:
    tx_id: str = field(default_factory=lambda: generate_id("tx"))
    chain: str = "ethereum"
    from_address: Optional[str] = None
    to_address: Optional[str] = None
    value: Wei = Wei(0)
    data: Optional[str] = None
    nonce: Optional[int] = None
    gas_limit: int = 21000
    access_list: Optional[list] = None

    def to_dict(self) -> Dict[str, Any]:
        raise NotImplementedError

    def validate(self) -> None:
        if not self.to_address and not self.data:
            raise TransactionError("Either to_address or data must be provided")
        if self.value < 0:
            raise TransactionError("Value cannot be negative")
        if self.gas_limit < 21000:
            raise TransactionError("Gas limit cannot be less than 21000")


@dataclass
class LegacyTransaction(BaseTransaction):
    gas_price: Optional[Wei] = None

    def to_dict(self) -> Dict[str, Any]:
        tx: Dict[str, Any] = {
            "to": to_checksum_address(self.to_address) if self.to_address else None,
            "value": self.value,
            "gas": self.gas_limit,
            "gasPrice": self.gas_price,
            "nonce": self.nonce,
            "chainId": get_settings().chains[self.chain].chain_id,
        }
        if self.data:
            tx["data"] = self.data
        if self.access_list:
            tx["accessList"] = self.access_list
        return tx

    def validate(self) -> None:
        super().validate()
        if self.gas_price is None:
            raise TransactionError("Gas price is required for legacy transactions")
        if self.gas_price < 0:
            raise TransactionError("Gas price cannot be negative")


@dataclass
class EIP1559Transaction(BaseTransaction):
    max_fee_per_gas: Optional[Wei] = None
    max_priority_fee_per_gas: Optional[Wei] = None

    def to_dict(self) -> Dict[str, Any]:
        tx: Dict[str, Any] = {
            "to": to_checksum_address(self.to_address) if self.to_address else None,
            "value": self.value,
            "gas": self.gas_limit,
            "maxFeePerGas": self.max_fee_per_gas,
            "maxPriorityFeePerGas": self.max_priority_fee_per_gas,
            "nonce": self.nonce,
            "chainId": get_settings().chains[self.chain].chain_id,
            "type": 2,
        }
        if self.data:
            tx["data"] = self.data
        if self.access_list:
            tx["accessList"] = self.access_list
        return tx

    def validate(self) -> None:
        super().validate()
        if self.max_fee_per_gas is None:
            raise TransactionError("max_fee_per_gas is required for EIP-1559 transactions")
        if self.max_priority_fee_per_gas is None:
            raise TransactionError("max_priority_fee_per_gas is required for EIP-1559 transactions")
        if self.max_fee_per_gas < self.max_priority_fee_per_gas:
            raise TransactionError("max_fee_per_gas cannot be less than max_priority_fee_per_gas")
        if self.max_fee_per_gas < 0 or self.max_priority_fee_per_gas < 0:
            raise TransactionError("Gas fees cannot be negative")


class TransactionBuilder:
    def __init__(self, chain: str = "ethereum"):
        self.settings = get_settings()
        if chain not in self.settings.chains:
            raise ChainInteractionError(f"Chain {chain} is not configured")
        self.chain = chain
        self.chain_config = self.settings.chains[chain]

    def build_legacy(
        self,
        to_address: str,
        value: int = 0,
        data: Optional[str] = None,
        from_address: Optional[str] = None,
        nonce: Optional[int] = None,
        gas_limit: int = 21000,
        gas_price: Optional[int] = None,
        access_list: Optional[list] = None,
    ) -> LegacyTransaction:
        tx = LegacyTransaction(
            chain=self.chain,
            from_address=from_address,
            to_address=to_address,
            value=Wei(value),
            data=data,
            nonce=nonce,
            gas_limit=gas_limit,
            gas_price=Wei(gas_price) if gas_price else None,
            access_list=access_list,
        )
        return tx

    def build_eip1559(
        self,
        to_address: str,
        value: int = 0,
        data: Optional[str] = None,
        from_address: Optional[str] = None,
        nonce: Optional[int] = None,
        gas_limit: int = 21000,
        max_fee_per_gas: Optional[int] = None,
        max_priority_fee_per_gas: Optional[int] = None,
        access_list: Optional[list] = None,
    ) -> EIP1559Transaction:
        tx = EIP1559Transaction(
            chain=self.chain,
            from_address=from_address,
            to_address=to_address,
            value=Wei(value),
            data=data,
            nonce=nonce,
            gas_limit=gas_limit,
            max_fee_per_gas=Wei(max_fee_per_gas) if max_fee_per_gas else None,
            max_priority_fee_per_gas=Wei(max_priority_fee_per_gas) if max_priority_fee_per_gas else None,
            access_list=access_list,
        )
        return tx

    def build_contract_deployment(
        self,
        bytecode: str,
        constructor_args: Optional[str] = None,
        from_address: Optional[str] = None,
        nonce: Optional[int] = None,
        gas_limit: int = 2000000,
        eip1559: bool = True,
        **kwargs,
    ) -> Union[LegacyTransaction, EIP1559Transaction]:
        data = bytecode
        if constructor_args:
            data += constructor_args

        if eip1559:
            return self.build_eip1559(
                to_address=None,
                value=0,
                data=data,
                from_address=from_address,
                nonce=nonce,
                gas_limit=gas_limit,
                max_fee_per_gas=kwargs.get("max_fee_per_gas"),
                max_priority_fee_per_gas=kwargs.get("max_priority_fee_per_gas"),
            )
        else:
            return self.build_legacy(
                to_address=None,
                value=0,
                data=data,
                from_address=from_address,
                nonce=nonce,
                gas_limit=gas_limit,
                gas_price=kwargs.get("gas_price"),
            )

    def build_contract_call(
        self,
        contract_address: str,
        calldata: str,
        value: int = 0,
        from_address: Optional[str] = None,
        nonce: Optional[int] = None,
        gas_limit: int = 100000,
        eip1559: bool = True,
        **kwargs,
    ) -> Union[LegacyTransaction, EIP1559Transaction]:
        if eip1559:
            return self.build_eip1559(
                to_address=contract_address,
                value=value,
                data=calldata,
                from_address=from_address,
                nonce=nonce,
                gas_limit=gas_limit,
                max_fee_per_gas=kwargs.get("max_fee_per_gas"),
                max_priority_fee_per_gas=kwargs.get("max_priority_fee_per_gas"),
            )
        else:
            return self.build_legacy(
                to_address=contract_address,
                value=value,
                data=calldata,
                from_address=from_address,
                nonce=nonce,
                gas_limit=gas_limit,
                gas_price=kwargs.get("gas_price"),
            )

    @staticmethod
    def sign_transaction(
        tx: Union[LegacyTransaction, EIP1559Transaction],
        private_key: str,
    ) -> SignedTransaction:
        tx.validate()
        tx_dict = tx.to_dict()
        return Account.sign_transaction(tx_dict, private_key)

    @staticmethod
    def estimate_gas_needed(
        w3: Web3,
        tx: Union[LegacyTransaction, EIP1559Transaction],
    ) -> int:
        tx_dict = tx.to_dict()
        return w3.eth.estimate_gas(tx_dict)
