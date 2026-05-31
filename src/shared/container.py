from __future__ import annotations

from typing import Dict, Optional

from src.core.ports.chain_interaction_port import IChainInteractionPort
from src.core.ports.cross_chain_port import ICrossChainBridgePort
from src.core.ports.event_listener_port import IEventListenerPort
from src.core.ports.gas_estimator_port import IGasEstimatorPort
from src.core.ports.indexer_port import IDataIndexerPort
from src.core.ports.storage_port import IDecentralizedStoragePort
from src.core.ports.transaction_builder_port import ITransactionBuilderPort
from src.core.ports.wallet_port import IAddressBookPort, IHDWalletPort
from src.core.ports.zkp_verifier_port import IZKPVerifierPort
from src.core.usecases.address_manager_usecase import (
    AddressBookService,
    HDWalletService,
)
from src.core.usecases.cross_chain_bridge_usecase import CrossChainBridgeService
from src.core.usecases.data_indexer_usecase import DataIndexerService
from src.core.usecases.decentralized_storage_usecase import (
    ArweaveStorageAdapter,
    IPFSStorageAdapter,
    StorageService,
)
from src.core.usecases.event_listener_usecase import EventListenerService
from src.core.usecases.gas_estimator_usecase import GasEstimatorService
from src.core.usecases.transaction_builder_usecase import (
    GasOptimizerService,
    MultiSigService,
    TransactionBuilderService,
)
from src.core.usecases.zkp_verifier_usecase import ZKPVerifierService
from src.infrastructure.adapters.chain_interaction_adapter import (
    ChainInteractionAdapter,
)
from src.shared.config import settings
from src.shared.errors import NotFoundError
from src.shared.types import Chain


class Container:
    def __init__(self):
        self._chain_adapters: Dict[Chain, IChainInteractionPort] = {}
        self._event_listeners: Dict[Chain, IEventListenerPort] = {}
        self._gas_estimators: Dict[Chain, IGasEstimatorPort] = {}
        self._indexers: Dict[Chain, IDataIndexerPort] = {}
        self._transaction_builders: Dict[Chain, ITransactionBuilderPort] = {}
        self._cross_chain_bridges: Dict[Chain, ICrossChainBridgePort] = {}

        self._hd_wallet: Optional[IHDWalletPort] = None
        self._address_book: Optional[IAddressBookPort] = None
        self._zkp_verifier: Optional[IZKPVerifierPort] = None
        self._storage_service: Optional[StorageService] = None
        self._multi_sig_service: Optional[MultiSigService] = None
        self._gas_optimizer: Optional[GasOptimizerService] = None

        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return

        for chain_key, chain_config in settings.chains.items():
            try:
                chain = Chain(chain_key)
                adapter = ChainInteractionAdapter(chain)
                self._chain_adapters[chain] = adapter

                self._event_listeners[chain] = EventListenerService(adapter)
                self._gas_estimators[chain] = GasEstimatorService(adapter)
                self._indexers[chain] = DataIndexerService(adapter)
                self._transaction_builders[chain] = TransactionBuilderService(
                    adapter, self._gas_estimators[chain]
                )

                logger.info(f"Initialized chain adapter: {chain_key}")
            except Exception as e:
                logger.warning(f"Failed to initialize chain {chain_key}: {e}")

        self._hd_wallet = HDWalletService()
        self._address_book = AddressBookService()
        self._zkp_verifier = ZKPVerifierService()
        self._multi_sig_service = MultiSigService()
        self._gas_optimizer = GasOptimizerService()

        self._storage_service = StorageService()
        try:
            ipfs_adapter = IPFSStorageAdapter()
            self._storage_service.register_adapter("ipfs", ipfs_adapter)
        except Exception as e:
            logger.warning(f"Failed to initialize IPFS adapter: {e}")

        try:
            arweave_adapter = ArweaveStorageAdapter()
            self._storage_service.register_adapter("arweave", arweave_adapter)
        except Exception as e:
            logger.warning(f"Failed to initialize Arweave adapter: {e}")

        if self._chain_adapters:
            first_chain = next(iter(self._chain_adapters.keys()))
            self._cross_chain_bridges[first_chain] = CrossChainBridgeService(
                self._chain_adapters
            )

        self._initialized = True
        logger.info("Container initialized successfully")

    def get_chain_adapter(self, chain: Chain) -> IChainInteractionPort:
        if chain not in self._chain_adapters:
            raise NotFoundError(f"Chain adapter not found for {chain}")
        return self._chain_adapters[chain]

    def get_event_listener(self, chain: Chain) -> IEventListenerPort:
        if chain not in self._event_listeners:
            raise NotFoundError(f"Event listener not found for {chain}")
        return self._event_listeners[chain]

    def get_gas_estimator(self, chain: Chain) -> IGasEstimatorPort:
        if chain not in self._gas_estimators:
            raise NotFoundError(f"Gas estimator not found for {chain}")
        return self._gas_estimators[chain]

    def get_indexer(self, chain: Chain) -> IDataIndexerPort:
        if chain not in self._indexers:
            raise NotFoundError(f"Indexer not found for {chain}")
        return self._indexers[chain]

    def get_transaction_builder(self, chain: Chain) -> ITransactionBuilderPort:
        if chain not in self._transaction_builders:
            raise NotFoundError(f"Transaction builder not found for {chain}")
        return self._transaction_builders[chain]

    def get_cross_chain_bridge(self, chain: Chain) -> ICrossChainBridgePort:
        if chain not in self._cross_chain_bridges:
            raise NotFoundError(f"Cross-chain bridge not found for {chain}")
        return self._cross_chain_bridges[chain]

    @property
    def hd_wallet(self) -> IHDWalletPort:
        if not self._hd_wallet:
            raise NotFoundError("HD Wallet service not initialized")
        return self._hd_wallet

    @property
    def address_book(self) -> IAddressBookPort:
        if not self._address_book:
            raise NotFoundError("Address book service not initialized")
        return self._address_book

    @property
    def zkp_verifier(self) -> IZKPVerifierPort:
        if not self._zkp_verifier:
            raise NotFoundError("ZKP verifier service not initialized")
        return self._zkp_verifier

    @property
    def storage_service(self) -> StorageService:
        if not self._storage_service:
            raise NotFoundError("Storage service not initialized")
        return self._storage_service

    @property
    def multi_sig_service(self) -> MultiSigService:
        if not self._multi_sig_service:
            raise NotFoundError("Multi-sig service not initialized")
        return self._multi_sig_service

    @property
    def gas_optimizer(self) -> GasOptimizerService:
        if not self._gas_optimizer:
            raise NotFoundError("Gas optimizer service not initialized")
        return self._gas_optimizer

    def list_available_chains(self) -> list[Chain]:
        return list(self._chain_adapters.keys())


from src.shared.logger import get_logger

logger = get_logger(__name__)

container = Container()
