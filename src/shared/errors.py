from __future__ import annotations

from typing import Any, Dict, Optional


class BlockchainInfraError(Exception):
    code: int = 500
    message: str = "Internal error"
    details: Optional[Dict[str, Any]] = None

    def __init__(
        self,
        message: Optional[str] = None,
        code: Optional[int] = None,
        details: Optional[Dict[str, Any]] = None,
    ):
        self.message = message or self.message
        self.code = code or self.code
        self.details = details
        super().__init__(self.message)


class ValidationError(BlockchainInfraError):
    code = 422
    message = "Validation error"


class ConfigurationError(BlockchainInfraError):
    code = 400
    message = "Configuration error"


class NotFoundError(BlockchainInfraError):
    code = 404
    message = "Resource not found"


class TimeoutError(BlockchainInfraError):
    code = 504
    message = "Operation timeout"


class ChainInteractionError(BlockchainInfraError):
    code = 502
    message = "Blockchain chain interaction error"


class RPCCallError(ChainInteractionError):
    message = "RPC call failed"

    def __init__(
        self,
        method: str,
        rpc_url: str,
        error: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
    ):
        full_details = details or {}
        full_details.update({"method": method, "rpc_url": rpc_url})
        if error:
            full_details["error"] = error
        super().__init__(
            message=f"RPC call {method} failed on {rpc_url}",
            details=full_details,
        )


class EventListenerError(BlockchainInfraError):
    code = 500
    message = "Event listener error"


class EventFilterError(EventListenerError):
    message = "Event filter creation failed"


class GasEstimationError(BlockchainInfraError):
    code = 500
    message = "Gas estimation error"


class InsufficientGasError(GasEstimationError):
    message = "Insufficient gas for transaction"


class ZKPVerificationError(BlockchainInfraError):
    code = 400
    message = "ZKP verification error"


class InvalidProofError(ZKPVerificationError):
    message = "Invalid proof data"


class WalletError(BlockchainInfraError):
    code = 400
    message = "Wallet operation error"


class InvalidMnemonicError(WalletError):
    message = "Invalid mnemonic phrase"


class AddressNotFoundError(WalletError):
    code = 404
    message = "Address not found"


class CrossChainBridgeError(BlockchainInfraError):
    code = 500
    message = "Cross-chain bridge error"


class MessageVerificationError(CrossChainBridgeError):
    message = "Cross-chain message verification failed"


class AtomicityError(CrossChainBridgeError):
    message = "Atomic operation failed"


class IndexingError(BlockchainInfraError):
    code = 500
    message = "Data indexing error"


class BlockParsingError(IndexingError):
    message = "Block data parsing failed"


class StorageError(BlockchainInfraError):
    code = 502
    message = "Storage operation error"


class IPFSError(StorageError):
    message = "IPFS operation failed"


class ArweaveError(StorageError):
    message = "Arweave operation failed"


class TransactionBuilderError(BlockchainInfraError):
    code = 400
    message = "Transaction builder error"


class InvalidTransactionError(TransactionBuilderError):
    message = "Invalid transaction parameters"


class SigningError(TransactionBuilderError):
    message = "Transaction signing failed"


class MultiSigError(TransactionBuilderError):
    message = "Multi-signature operation failed"


class ConcurrencyError(BlockchainInfraError):
    code = 409
    message = "Concurrency conflict"


class RetryExhaustedError(BlockchainInfraError):
    code = 500
    message = "Retry attempts exhausted"

    def __init__(
        self,
        operation: str,
        attempts: int,
        last_error: Optional[str] = None,
    ):
        message = f"Operation '{operation}' failed after {attempts} attempts"
        if last_error:
            message += f": {last_error}"
        details = {"operation": operation, "attempts": attempts, "last_error": last_error}
        super().__init__(message=message, details=details)
