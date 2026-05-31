from __future__ import annotations

import asyncio
from typing import Any, Dict, List, Optional
from uuid import uuid4

from eth_account import Account
from eth_account.datastructures import SignedTransaction as EthSignedTransaction
from eth_utils import to_checksum_address

from src.core.ports.chain_interaction_port import IChainInteractionPort
from src.core.ports.gas_estimator_port import IGasEstimatorPort
from src.core.ports.transaction_builder_port import (
    IGasOptimizerPort,
    IMultiSigPort,
    ITransactionBuilderPort,
)
from src.shared.errors import (
    InvalidTransactionError,
    MultiSigError,
    SigningError,
    TransactionBuilderError,
)
from src.shared.logger import get_logger
from src.shared.types import (
    Address,
    ChainId,
    GasAmount,
    Hash,
    HexString,
    SignedTransaction,
    WeiAmount,
)
from src.shared.utils import get_abi_element, encode_function_call, decode_function_input

logger = get_logger(__name__)

try:
    from web3 import Web3
    WEB3_AVAILABLE = True
except ImportError:
    WEB3_AVAILABLE = False


class TransactionBuilderService(ITransactionBuilderPort):
    def __init__(
        self,
        chain_adapter: IChainInteractionPort,
        gas_estimator: Optional[IGasEstimatorPort] = None,
        gas_optimizer: Optional[IGasOptimizerPort] = None,
    ):
        if not WEB3_AVAILABLE:
            raise TransactionBuilderError("web3 package not installed")

        self._chain = chain_adapter
        self._gas_estimator = gas_estimator
        self._gas_optimizer = gas_optimizer or GasOptimizerService(gas_estimator)
        self._w3 = chain_adapter._w3

    async def build_transaction(
        self,
        to: Optional[Address] = None,
        from_address: Optional[Address] = None,
        value: WeiAmount = 0,
        data: HexString = "0x",
        gas_limit: Optional[GasAmount] = None,
        gas_price: Optional[WeiAmount] = None,
        max_fee_per_gas: Optional[WeiAmount] = None,
        max_priority_fee_per_gas: Optional[WeiAmount] = None,
        nonce: Optional[int] = None,
        chain_id: Optional[ChainId] = None,
    ) -> Dict[str, Any]:
        tx: Dict[str, Any] = {
            "to": to_checksum_address(to) if to else None,
            "value": value,
            "data": data,
            "chainId": chain_id or self._chain.chain_id,
        }

        if from_address:
            tx["from"] = to_checksum_address(from_address)
            if nonce is None:
                try:
                    tx["nonce"] = await self._chain.get_transaction_count(from_address, "pending")
                except Exception as e:
                    logger.warning(f"Failed to get nonce: {e}")
                    tx["nonce"] = 0
            else:
                tx["nonce"] = nonce
        else:
            tx["nonce"] = nonce or 0

        if max_fee_per_gas is not None and max_priority_fee_per_gas is not None:
            tx["maxFeePerGas"] = max_fee_per_gas
            tx["maxPriorityFeePerGas"] = max_priority_fee_per_gas
            tx["type"] = 2
        elif gas_price is not None:
            tx["gasPrice"] = gas_price
            tx["type"] = 0
        else:
            try:
                eip1559_fees = await self._gas_estimator.estimate_eip1559_fees("average") if self._gas_estimator else None
                if eip1559_fees:
                    tx["maxFeePerGas"] = eip1559_fees["max_fee_per_gas"]
                    tx["maxPriorityFeePerGas"] = eip1559_fees["max_priority_fee_per_gas"]
                    tx["type"] = 2
                else:
                    gas_price = await self._chain.get_gas_price()
                    tx["gasPrice"] = gas_price
                    tx["type"] = 0
            except Exception as e:
                logger.warning(f"Failed to auto-estimate gas fees: {e}")
                tx["gasPrice"] = 0
                tx["type"] = 0

        if gas_limit is None:
            try:
                tx["gas"] = await self._chain.estimate_gas(
                    to=to,
                    from_address=from_address,
                    value=value,
                    data=data,
                )
                tx["gas"] = int(tx["gas"] * 1.1)
            except Exception as e:
                logger.warning(f"Failed to estimate gas limit: {e}")
                tx["gas"] = 21000
        else:
            tx["gas"] = gas_limit

        if not self._validate_transaction(tx):
            raise InvalidTransactionError("Transaction validation failed")

        return tx

    def _validate_transaction(self, tx: Dict[str, Any]) -> bool:
        if tx.get("to") is None and (tx.get("data") == "0x" or tx.get("data") is None):
            raise InvalidTransactionError("Contract deployment requires bytecode data")

        if tx.get("gas", 0) < 21000:
            raise InvalidTransactionError("Gas limit too low")

        if tx.get("value", 0) < 0:
            raise InvalidTransactionError("Value cannot be negative")

        if tx.get("nonce", 0) < 0:
            raise InvalidTransactionError("Nonce cannot be negative")

        return True

    async def build_contract_deployment(
        self,
        bytecode: HexString,
        constructor_args: Optional[List[Any]] = None,
        abi: Optional[List[Dict[str, Any]]] = None,
        from_address: Optional[Address] = None,
        value: WeiAmount = 0,
        gas_limit: Optional[GasAmount] = None,
        gas_price: Optional[WeiAmount] = None,
        nonce: Optional[int] = None,
    ) -> Dict[str, Any]:
        data = bytecode
        if constructor_args and abi:
            try:
                constructor_abi = get_abi_element(abi, "constructor", None)
                data = encode_function_call(constructor_abi, constructor_args or [])
                if isinstance(data, bytes):
                    data = "0x" + data.hex()
            except Exception as e:
                logger.warning(f"Failed to encode constructor args: {e}")

        return await self.build_transaction(
            to=None,
            from_address=from_address,
            value=value,
            data=data,
            gas_limit=gas_limit,
            gas_price=gas_price,
            nonce=nonce,
        )

    async def build_contract_call(
        self,
        contract_address: Address,
        function_name: str,
        function_args: Optional[List[Any]] = None,
        abi: List[Dict[str, Any]] = None,
        from_address: Optional[Address] = None,
        value: WeiAmount = 0,
        gas_limit: Optional[GasAmount] = None,
        gas_price: Optional[WeiAmount] = None,
        nonce: Optional[int] = None,
    ) -> Dict[str, Any]:
        data = await self.encode_function_call(function_name, function_args, abi)
        return await self.build_transaction(
            to=contract_address,
            from_address=from_address,
            value=value,
            data=data,
            gas_limit=gas_limit,
            gas_price=gas_price,
            nonce=nonce,
        )

    async def sign_transaction(
        self,
        transaction: Dict[str, Any],
        private_key: HexString,
    ) -> SignedTransaction:
        try:
            tx_for_signing = transaction.copy()

            if "from" in tx_for_signing:
                del tx_for_signing["from"]

            if "type" in tx_for_signing:
                del tx_for_signing["type"]

            for key in list(tx_for_signing.keys()):
                if tx_for_signing[key] is None:
                    del tx_for_signing[key]

            signed: EthSignedTransaction = await asyncio.to_thread(
                Account.sign_transaction,
                tx_for_signing,
                private_key,
            )

            from_address = Account.from_key(private_key).address

            return SignedTransaction(
                raw_transaction=signed.raw_transaction.hex(),
                hash=signed.hash.hex(),
                from_address=from_address,
                to_address=transaction.get("to"),
                value=transaction.get("value", 0),
                gas=transaction.get("gas", 0),
                gas_price=transaction.get("gasPrice") or transaction.get("maxFeePerGas", 0),
                nonce=transaction.get("nonce", 0),
                chain_id=transaction.get("chainId", self._chain.chain_id),
                signers=[from_address],
            )
        except Exception as e:
            raise SigningError(f"Failed to sign transaction: {e}")

    async def sign_message(
        self,
        message: str | HexString,
        private_key: HexString,
        sign_type: str = "ecdsa",
    ) -> HexString:
        try:
            from eth_account.messages import encode_defunct

            if isinstance(message, str) and message.startswith("0x"):
                msg_bytes = bytes.fromhex(message[2:])
                signable = encode_defunct(primitive=msg_bytes)
            else:
                signable = encode_defunct(text=str(message))

            signed = await asyncio.to_thread(
                Account.sign_message,
                signable,
                private_key,
            )
            return signed.signature.hex()
        except Exception as e:
            raise SigningError(f"Failed to sign message: {e}")

    async def verify_transaction(self, signed_tx: SignedTransaction) -> bool:
        try:
            recovered = await asyncio.to_thread(
                Account.recover_transaction,
                signed_tx.raw_transaction,
            )
            return recovered.lower() == signed_tx.from_address.lower()
        except Exception as e:
            logger.warning(f"Transaction verification failed: {e}")
            return False

    async def encode_function_call(
        self,
        function_name: str,
        function_args: Optional[List[Any]] = None,
        abi: List[Dict[str, Any]] = None,
    ) -> HexString:
        if not abi:
            raise InvalidTransactionError("ABI is required for function encoding")

        try:
            func_abi = get_abi_element(abi, "function", function_name)
            encoded = encode_function_call(func_abi, function_args or [])
            if isinstance(encoded, bytes):
                return "0x" + encoded.hex()
            return encoded
        except Exception as e:
            raise InvalidTransactionError(f"Failed to encode function call: {e}")

    async def decode_function_input(
        self,
        data: HexString,
        abi: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        if not abi:
            raise InvalidTransactionError("ABI is required for function decoding")

        try:
            func_abi, args = decode_function_input(
                self._w3.eth.contract(abi=abi),
                data,
            )
            return {
                "function_name": func_abi["name"],
                "arguments": dict(args),
            }
        except Exception as e:
            raise InvalidTransactionError(f"Failed to decode function input: {e}")


class GasOptimizerService(IGasOptimizerPort):
    def __init__(self, gas_estimator: Optional[IGasEstimatorPort] = None):
        self._gas_estimator = gas_estimator

    async def optimize_gas_price(
        self,
        transaction: Dict[str, Any],
        speed: str = "average",
    ) -> Dict[str, Any]:
        if not self._gas_estimator:
            return transaction

        optimized = transaction.copy()

        try:
            eip1559_fees = await self._gas_estimator.estimate_eip1559_fees(speed)
            optimized["maxFeePerGas"] = eip1559_fees["max_fee_per_gas"]
            optimized["maxPriorityFeePerGas"] = eip1559_fees["max_priority_fee_per_gas"]
            optimized["type"] = 2

            if "gasPrice" in optimized:
                del optimized["gasPrice"]

            logger.info(f"Optimized gas price for speed: {speed}")
        except Exception as e:
            logger.warning(f"Failed to optimize gas price: {e}")

        return optimized

    async def optimize_gas_limit(
        self,
        transaction: Dict[str, Any],
        buffer_percent: float = 10.0,
    ) -> Dict[str, Any]:
        optimized = transaction.copy()
        current_gas = optimized.get("gas", 0)

        if current_gas > 0:
            buffer = int(current_gas * (buffer_percent / 100))
            optimized["gas"] = current_gas + buffer
            logger.info(f"Optimized gas limit with {buffer_percent}% buffer: {optimized['gas']}")

        return optimized

    async def suggest_gas_savings(
        self,
        transaction: Dict[str, Any],
    ) -> List[Dict[str, Any]]:
        suggestions: List[Dict[str, Any]] = []

        if transaction.get("type") != 2 and transaction.get("maxFeePerGas") is None:
            suggestions.append(
                {
                    "type": "eip1559",
                    "description": "Use EIP-1559 fee market for better gas price optimization",
                    "potential_savings_pct": 15.0,
                }
            )

        current_gas = transaction.get("gas", 0)
        if current_gas > 100000:
            suggestions.append(
                {
                    "type": "gas_limit",
                    "description": "Consider reducing gas limit if transaction is simple",
                    "potential_savings_pct": 5.0,
                }
            )

        if transaction.get("data") and len(transaction["data"]) > 1000:
            suggestions.append(
                {
                    "type": "calldata",
                    "description": "Large calldata increases gas cost. Consider off-chain storage.",
                    "potential_savings_pct": 10.0,
                }
            )

        return suggestions

    async def batch_transactions(
        self,
        transactions: List[Dict[str, Any]],
        from_address: Address,
    ) -> Dict[str, Any]:
        if not transactions:
            raise InvalidTransactionError("No transactions to batch")

        total_value = sum(tx.get("value", 0) for tx in transactions)
        total_gas = sum(tx.get("gas", 21000) for tx in transactions)

        avg_gas_price = 0
        for tx in transactions:
            avg_gas_price += tx.get("gasPrice") or tx.get("maxFeePerGas", 0)
        avg_gas_price = avg_gas_price // len(transactions) if transactions else 0

        batched = {
            "to": from_address,
            "from": from_address,
            "value": total_value,
            "gas": total_gas + 50000,
            "gasPrice": avg_gas_price,
            "nonce": transactions[0].get("nonce", 0),
            "chainId": transactions[0].get("chainId", 1),
            "data": "0x",
            "batch": True,
            "transactions": transactions,
        }

        logger.info(f"Created batch of {len(transactions)} transactions")
        return batched


class MultiSigService(IMultiSigPort):
    def __init__(self, chain_adapter: Optional[IChainInteractionPort] = None):
        self._chain = chain_adapter
        self._wallets: Dict[str, Dict[str, Any]] = {}
        self._transactions: Dict[str, Dict[str, Any]] = {}

    async def create_multisig_wallet(
        self,
        owners: List[Address],
        threshold: int,
        chain_id: ChainId,
    ) -> Address:
        if threshold <= 0 or threshold > len(owners):
            raise MultiSigError("Invalid threshold for multi-sig wallet")

        if len(owners) < 1:
            raise MultiSigError("At least one owner is required")

        wallet_id = hashlib.sha256(
            "".join(sorted(owners) + [str(threshold), str(chain_id)]).encode()
        ).hexdigest()
        wallet_address = "0x" + wallet_id[:40]

        self._wallets[wallet_address.lower()] = {
            "owners": [to_checksum_address(o) for o in owners],
            "threshold": threshold,
            "chain_id": chain_id,
            "created_at": __import__("datetime").datetime.utcnow(),
        }

        logger.info(
            f"Created multi-sig wallet: {wallet_address}",
            owners=len(owners),
            threshold=threshold,
        )

        return to_checksum_address(wallet_address)

    async def propose_transaction(
        self,
        multisig_address: Address,
        to: Address,
        value: WeiAmount,
        data: HexString,
        proposer: Address,
    ) -> str:
        wallet = self._wallets.get(multisig_address.lower())
        if not wallet:
            raise MultiSigError(f"Multi-sig wallet {multisig_address} not found")

        if proposer.lower() not in [o.lower() for o in wallet["owners"]]:
            raise MultiSigError("Proposer is not an owner of this wallet")

        tx_id = f"tx_{uuid4().hex[:16]}"
        self._transactions[tx_id] = {
            "wallet_address": multisig_address,
            "to": to,
            "value": value,
            "data": data,
            "proposer": proposer,
            "approvals": [proposer],
            "status": "pending",
            "created_at": __import__("datetime").datetime.utcnow(),
        }

        logger.info(
            f"Proposed transaction: {tx_id}",
            multisig=multisig_address,
            to=to,
            value=value,
        )

        return tx_id

    async def approve_transaction(
        self,
        multisig_address: Address,
        tx_id: str,
        approver: Address,
    ) -> bool:
        tx = self._transactions.get(tx_id)
        if not tx:
            raise MultiSigError(f"Transaction {tx_id} not found")

        wallet = self._wallets.get(multisig_address.lower())
        if not wallet:
            raise MultiSigError(f"Multi-sig wallet {multisig_address} not found")

        if approver.lower() not in [o.lower() for o in wallet["owners"]]:
            raise MultiSigError("Approver is not an owner of this wallet")

        if approver.lower() in [a.lower() for a in tx["approvals"]]:
            logger.warning(f"Approver {approver} already approved transaction {tx_id}")
            return True

        tx["approvals"].append(approver)

        if len(tx["approvals"]) >= wallet["threshold"]:
            tx["status"] = "approved"
            logger.info(f"Transaction {tx_id} reached approval threshold")

        logger.info(f"Transaction {tx_id} approved by {approver}")
        return True

    async def execute_transaction(
        self,
        multisig_address: Address,
        tx_id: str,
    ) -> Optional[Hash]:
        tx = self._transactions.get(tx_id)
        if not tx:
            raise MultiSigError(f"Transaction {tx_id} not found")

        wallet = self._wallets.get(multisig_address.lower())
        if not wallet:
            raise MultiSigError(f"Multi-sig wallet {multisig_address} not found")

        if len(tx["approvals"]) < wallet["threshold"]:
            raise MultiSigError("Insufficient approvals to execute transaction")

        if tx["status"] == "executed":
            return tx.get("transaction_hash")

        tx["status"] = "executed"
        tx["executed_at"] = __import__("datetime").datetime.utcnow()
        tx["transaction_hash"] = f"0x{uuid4().hex}{uuid4().hex}"

        logger.info(f"Transaction {tx_id} executed", tx_hash=tx["transaction_hash"])
        return tx["transaction_hash"]

    async def get_transaction(
        self,
        multisig_address: Address,
        tx_id: str,
    ) -> Optional[Dict[str, Any]]:
        tx = self._transactions.get(tx_id)
        if not tx or tx["wallet_address"].lower() != multisig_address.lower():
            return None
        return tx.copy()

    async def list_transactions(
        self,
        multisig_address: Address,
        status: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        results = []
        for tx in self._transactions.values():
            if tx["wallet_address"].lower() != multisig_address.lower():
                continue
            if status and tx["status"] != status:
                continue
            results.append(tx.copy())

        results.sort(key=lambda t: t["created_at"], reverse=True)
        return results

    async def get_owners(self, multisig_address: Address) -> List[Address]:
        wallet = self._wallets.get(multisig_address.lower())
        if not wallet:
            raise MultiSigError(f"Multi-sig wallet {multisig_address} not found")
        return wallet["owners"].copy()

    async def get_threshold(self, multisig_address: Address) -> int:
        wallet = self._wallets.get(multisig_address.lower())
        if not wallet:
            raise MultiSigError(f"Multi-sig wallet {multisig_address} not found")
        return wallet["threshold"]


import hashlib
