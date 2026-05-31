from typing import Any, Dict, Optional


class NFTIndexerError(Exception):
    code: int = 500
    message: str = "Internal server error"

    def __init__(
        self,
        message: Optional[str] = None,
        code: Optional[int] = None,
        details: Optional[Dict[str, Any]] = None,
    ):
        self.message = message or self.message
        self.code = code or self.code
        self.details = details or {}
        super().__init__(self.message)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "code": self.code,
            "message": self.message,
            "details": self.details,
        }


class ValidationError(NFTIndexerError):
    code = 422
    message = "Validation failed"


class ConfigurationError(NFTIndexerError):
    code = 500
    message = "Configuration error"


class ChainInteractionError(NFTIndexerError):
    code = 502
    message = "Chain interaction failed"


class NotFoundError(NFTIndexerError):
    code = 404
    message = "Resource not found"


class ConflictError(NFTIndexerError):
    code = 409
    message = "Resource conflict"


class PermissionDeniedError(NFTIndexerError):
    code = 403
    message = "Permission denied"


class TimeoutError(NFTIndexerError):
    code = 504
    message = "Operation timed out"


class SignatureError(NFTIndexerError):
    code = 400
    message = "Invalid signature"


class EventListenerError(NFTIndexerError):
    code = 500
    message = "Event listener error"


class CrossChainError(NFTIndexerError):
    code = 500
    message = "Cross-chain operation failed"


class ZKPVerificationError(NFTIndexerError):
    code = 400
    message = "ZKP verification failed"


class StorageError(NFTIndexerError):
    code = 502
    message = "Storage operation failed"


class IndexingError(NFTIndexerError):
    code = 500
    message = "Indexing error"


class IndexerError(NFTIndexerError):
    code = 500
    message = "Indexer error"


class WalletError(NFTIndexerError):
    code = 500
    message = "Wallet operation failed"


class GasEstimationError(NFTIndexerError):
    code = 500
    message = "Gas estimation failed"
