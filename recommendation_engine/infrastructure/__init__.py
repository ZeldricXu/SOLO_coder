from .redis_client import RedisClient, get_redis_client
from .postgres_client import PostgresClient, get_postgres_client

try:
    from .kafka_client import KafkaProducerClient, KafkaConsumerClient
except ImportError:
    KafkaProducerClient = None
    KafkaConsumerClient = None

__all__ = [
    "RedisClient",
    "get_redis_client",
    "PostgresClient",
    "get_postgres_client",
    "KafkaProducerClient",
    "KafkaConsumerClient",
]
