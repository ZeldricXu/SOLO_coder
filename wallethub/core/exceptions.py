from typing import Any, Dict, Optional


class WalletHubError(Exception):
    code: int = 500
    message: str = "Internal server error"

    def __init__(
        self,
        message: Optional[str] = None,
        code: Optional[int] = None,
        details: Optional[Dict[str, Any]] = None
    ):
        self.message = message or self.message
        self.code = code or self.code
        self.details = details or {}
        super().__init__(self.message)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "code": self.code,
            "message": self.message,
            "details": self.details
        }


class ValidationError(WalletHubError):
    code = 422
    message = "Validation error"


class ConfigurationError(WalletHubError):
    code = 500
    message = "Configuration error"


class ChainInteractionError(WalletHubError):
    code = 502
    message = "Chain interaction error"


class TransactionError(WalletHubError):
    code = 400
    message = "Transaction error"


class SigningError(WalletHubError):
    code = 400
    message = "Signing error"


class CrossChainError(WalletHubError):
    code = 500
    message = "Cross-chain operation error"


class StorageError(WalletHubError):
    code = 502
    message = "Decentralized storage error"


class EventListenerError(WalletHubError):
    code = 500
    message = "Event listener error"


class GasEstimationError(WalletHubError):
    code = 500
    message = "Gas estimation error"


class AddressError(WalletHubError):
    code = 400
    message = "Address error"


class IndexerError(WalletHubError):
    code = 500
    message = "Indexer error"


class ResourceNotFoundError(WalletHubError):
    code = 404
    message = "Resource not found"


class UnauthorizedError(WalletHubError):
    code = 401
    message = "Unauthorized"
