from .event_bus import Event


class TransactionCreatedEvent(Event):
    event_type: str = "transaction.created"


class TransactionSignedEvent(Event):
    event_type: str = "transaction.signed"


class TransactionBroadcastEvent(Event):
    event_type: str = "transaction.broadcast"


class TransactionConfirmedEvent(Event):
    event_type: str = "transaction.confirmed"


class CrossChainInitiatedEvent(Event):
    event_type: str = "cross_chain.initiated"


class CrossChainLockedEvent(Event):
    event_type: str = "cross_chain.locked"


class CrossChainMintedEvent(Event):
    event_type: str = "cross_chain.minted"


class CrossChainCompletedEvent(Event):
    event_type: str = "cross_chain.completed"


class ContentStoredEvent(Event):
    event_type: str = "storage.content_stored"


class EventLogReceivedEvent(Event):
    event_type: str = "events.log_received"


class ContractEventTriggeredEvent(Event):
    event_type: str = "events.contract_event_triggered"


class BlockIndexedEvent(Event):
    event_type: str = "indexer.block_indexed"


class TransactionIndexedEvent(Event):
    event_type: str = "indexer.transaction_indexed"
