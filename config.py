import os
from typing import Dict, Any


class Config:
    PROJECT_NAME = "ModelServe"
    VERSION = "1.1.0"

    SECRET_KEY = os.environ.get('SECRET_KEY', 'modelserve-secret-key-2024')

    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    DATA_DIR = os.environ.get('DATA_DIR', os.path.join(BASE_DIR, 'data'))
    MODELS_DIR = os.environ.get('MODELS_DIR', os.path.join(BASE_DIR, 'models'))

    FLASK_DEBUG = os.environ.get('FLASK_DEBUG', 'True').lower() == 'true'
    FLASK_HOST = os.environ.get('FLASK_HOST', '0.0.0.0')
    FLASK_PORT = int(os.environ.get('FLASK_PORT', 5000))

    MAX_CONTENT_LENGTH = 100 * 1024 * 1024

    INFERENCE_TIMEOUT = int(os.environ.get('INFERENCE_TIMEOUT', 60))
    MAX_BATCH_SIZE = int(os.environ.get('MAX_BATCH_SIZE', 32))

    LOG_LEVEL = os.environ.get('LOG_LEVEL', 'INFO')
    LOG_FORMAT = '%(asctime)s - %(name)s - %(levelname)s - %(message)s'

    SUPPORTED_FRAMEWORKS = ['tensorflow', 'pytorch', 'onnx', 'sklearn', 'mock']

    MODEL_TYPES = ['classification', 'regression', 'detection', 'segmentation', 'text', 'other']

    STORAGE_CONFIG: Dict[str, Any] = {
        'type': 'local',
        'data_dir': DATA_DIR,
        'models_dir': MODELS_DIR
    }

    MONITORING_CONFIG: Dict[str, Any] = {
        'enabled': True,
        'stats_collection': 'stats',
        'inference_collection': 'inferences',
        'use_redis': os.environ.get('MONITORING_USE_REDIS', 'False').lower() == 'true',
        'flush_interval_ms': int(os.environ.get('MONITORING_FLUSH_INTERVAL_MS', 1000)),
        'batch_size': int(os.environ.get('MONITORING_BATCH_SIZE', 100)),
        'max_queue_size': int(os.environ.get('MONITORING_MAX_QUEUE_SIZE', 10000))
    }

    REDIS_CONFIG: Dict[str, Any] = {
        'host': os.environ.get('REDIS_HOST', 'localhost'),
        'port': int(os.environ.get('REDIS_PORT', 6379)),
        'db': int(os.environ.get('REDIS_DB', 0)),
        'password': os.environ.get('REDIS_PASSWORD', ''),
        'socket_timeout': int(os.environ.get('REDIS_SOCKET_TIMEOUT', 5)),
        'socket_connect_timeout': int(os.environ.get('REDIS_SOCKET_CONNECT_TIMEOUT', 2)),
        'retry_on_timeout': os.environ.get('REDIS_RETRY_ON_TIMEOUT', 'True').lower() == 'true',
        'queue_key': os.environ.get('REDIS_QUEUE_KEY', 'modelserve:monitoring:queue'),
        'pending_key_prefix': os.environ.get('REDIS_PENDING_KEY_PREFIX', 'modelserve:monitoring:pending')
    }

    BATCHING_CONFIG: Dict[str, Any] = {
        'default_enable_batching': True,
        'default_batch_timeout_ms': float(os.environ.get('DEFAULT_BATCH_TIMEOUT_MS', 100.0)),
        'default_max_batch_size': int(os.environ.get('DEFAULT_MAX_BATCH_SIZE', 32)),
        'default_max_queue_size': int(os.environ.get('DEFAULT_MAX_QUEUE_SIZE', 10000))
    }

    HEALTH_CHECK_CONFIG: Dict[str, Any] = {
        'enable_health_check': True,
        'health_check_timeout_ms': float(os.environ.get('HEALTH_CHECK_TIMEOUT_MS', 30000.0)),
        'health_check_retry_count': int(os.environ.get('HEALTH_CHECK_RETRY_COUNT', 3)),
        'expected_latency_threshold_ms': float(os.environ.get('EXPECTED_LATENCY_THRESHOLD_MS', 500.0)),
        'max_acceptable_latency_ms': float(os.environ.get('MAX_ACCEPTABLE_LATENCY_MS', 2000.0)),
        'enable_auto_rollback': True
    }

    @classmethod
    def get(cls, key: str, default: Any = None) -> Any:
        return getattr(cls, key, default)


class DevelopmentConfig(Config):
    FLASK_DEBUG = True
    LOG_LEVEL = 'DEBUG'

    MONITORING_CONFIG = {
        **Config.MONITORING_CONFIG,
        'use_redis': os.environ.get('MONITORING_USE_REDIS', 'False').lower() == 'true',
    }


class ProductionConfig(Config):
    FLASK_DEBUG = False
    LOG_LEVEL = 'WARNING'

    MONITORING_CONFIG = {
        **Config.MONITORING_CONFIG,
        'use_redis': os.environ.get('MONITORING_USE_REDIS', 'True').lower() == 'true',
    }

    @classmethod
    def get(cls, key: str, default: Any = None) -> Any:
        env_value = os.environ.get(key)
        if env_value is not None:
            if key in ['FLASK_PORT', 'INFERENCE_TIMEOUT', 'MAX_BATCH_SIZE', 'MAX_CONTENT_LENGTH']:
                return int(env_value)
            elif key in ['FLASK_DEBUG']:
                return env_value.lower() == 'true'
            return env_value
        return getattr(cls, key, default)


config = {
    'development': DevelopmentConfig,
    'production': ProductionConfig,
    'default': DevelopmentConfig
}

get_config = lambda: config[os.environ.get('FLASK_ENV', 'default')]
