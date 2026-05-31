import asyncio
from typing import Any, Awaitable, Callable, Dict, Generic, Optional, Type, TypeVar

from aiohttp import ClientSession

from .config import get_settings
from .utils import get_logger

from .interfaces.repositories import (
    IMultiSigRepository,
    IEventListenerRepository,
    ICrossChainRepository,
)
from .interfaces.services import (
    ISignatureVerifier,
    IChainExecutor,
    IMessageVerifier,
    IWebhookSender,
    ICallbackHandlerRegistry,
)
from .interfaces.modules import (
    IMultiSigModule,
    IEventListenerModule,
    ICrossChainModule,
)
from .repositories import (
    MultiSigRepository,
    EventListenerRepository,
    CrossChainRepository,
)
from .services import (
    SignatureVerifierService,
    ChainExecutorService,
    MessageVerifierService,
    WebhookSenderService,
    CallbackHandlerRegistry,
)

logger = get_logger(__name__)

T = TypeVar("T")


class DiProvider(Generic[T]):
    def __init__(self, factory: Callable[..., Awaitable[T]], singleton: bool = True):
        self._factory = factory
        self._singleton = singleton
        self._instance: Optional[T] = None

    async def get(self, *args, **kwargs) -> T:
        if self._singleton and self._instance is not None:
            return self._instance
        instance = await self._factory(*args, **kwargs)
        if self._singleton:
            self._instance = instance
        return instance


class DIContainer:
    def __init__(self):
        self._providers: Dict[str, DiProvider] = {}
        self._http_session: Optional[ClientSession] = None
        self._initialized = False
        self._settings = get_settings()

    async def initialize(self) -> None:
        if self._initialized:
            return

        logger.info("Initializing DI container")
        self._http_session = ClientSession()
        self._register_defaults()
        self._initialized = True
        logger.info("DI container initialized")

    async def shutdown(self) -> None:
        if not self._initialized:
            return

        logger.info("Shutting down DI container")
        if self._http_session:
            await self._http_session.close()
            self._http_session = None
        self._providers.clear()
        self._initialized = False
        logger.info("DI container shutdown complete")

    def _register_defaults(self) -> None:
        self.register_factory(
            "http_session",
            lambda: asyncio.coroutine(lambda: self._http_session)(),
            singleton=True,
        )

        self.register_factory(
            "multisig_repository",
            self._create_multisig_repository,
            singleton=True,
        )
        self.register_factory(
            "event_listener_repository",
            self._create_event_listener_repository,
            singleton=True,
        )
        self.register_factory(
            "cross_chain_repository",
            self._create_cross_chain_repository,
            singleton=True,
        )

        self.register_factory(
            "signature_verifier",
            self._create_signature_verifier,
            singleton=True,
        )
        self.register_factory(
            "chain_executor",
            self._create_chain_executor,
            singleton=True,
        )
        self.register_factory(
            "message_verifier",
            self._create_message_verifier,
            singleton=True,
        )
        self.register_factory(
            "webhook_sender",
            self._create_webhook_sender,
            singleton=True,
        )
        self.register_factory(
            "callback_handler_registry",
            self._create_callback_handler_registry,
            singleton=True,
        )

    async def _create_multisig_repository(self) -> IMultiSigRepository:
        return MultiSigRepository()

    async def _create_event_listener_repository(self) -> IEventListenerRepository:
        return EventListenerRepository()

    async def _create_cross_chain_repository(self) -> ICrossChainRepository:
        return CrossChainRepository()

    async def _create_signature_verifier(self) -> ISignatureVerifier:
        return SignatureVerifierService()

    async def _create_chain_executor(self) -> IChainExecutor:
        return ChainExecutorService()

    async def _create_message_verifier(self) -> IMessageVerifier:
        return MessageVerifierService(
            min_signatures=self._settings.crosschain.min_signatures
        )

    async def _create_webhook_sender(self) -> IWebhookSender:
        return WebhookSenderService(
            session=self._http_session,
            timeout=self._settings.events.callback_timeout,
        )

    async def _create_callback_handler_registry(self) -> ICallbackHandlerRegistry:
        return CallbackHandlerRegistry()

    def register_factory(
        self,
        key: str,
        factory: Callable[..., Awaitable[Any]],
        singleton: bool = True,
    ) -> None:
        self._providers[key] = DiProvider(factory, singleton=singleton)
        logger.debug(f"Registered provider: {key}")

    async def get(self, key: str, *args, **kwargs) -> Any:
        if key not in self._providers:
            raise ValueError(f"No provider registered for key: {key}")
        return await self._providers[key].get(*args, **kwargs)

    async def get_multisig_repository(self) -> IMultiSigRepository:
        return await self.get("multisig_repository")

    async def get_event_listener_repository(self) -> IEventListenerRepository:
        return await self.get("event_listener_repository")

    async def get_cross_chain_repository(self) -> ICrossChainRepository:
        return await self.get("cross_chain_repository")

    async def get_signature_verifier(self) -> ISignatureVerifier:
        return await self.get("signature_verifier")

    async def get_chain_executor(self) -> IChainExecutor:
        return await self.get("chain_executor")

    async def get_message_verifier(self) -> IMessageVerifier:
        return await self.get("message_verifier")

    async def get_webhook_sender(self) -> IWebhookSender:
        return await self.get("webhook_sender")

    async def get_callback_handler_registry(self) -> ICallbackHandlerRegistry:
        return await self.get("callback_handler_registry")

    async def get_http_session(self) -> ClientSession:
        return await self.get("http_session")


_container: Optional[DIContainer] = None


def get_container() -> DIContainer:
    global _container
    if _container is None:
        _container = DIContainer()
    return _container
