import os
import json
import base64
from pathlib import Path
from typing import Any, Dict, Optional

import yaml
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

try:
    import keyring
    KEYRING_AVAILABLE = True
except ImportError:
    KEYRING_AVAILABLE = False


KEYRING_SERVICE = "devkit-cli"
KEYRING_MASTER_KEY = "master-key"
SENSITIVE_MARKER = "!encrypted"


def derive_key_from_passphrase(passphrase: str, salt: bytes = b'devkit_salt') -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        iterations=100000,
    )
    return base64.urlsafe_b64encode(kdf.derive(passphrase.encode()))


def get_master_key() -> Optional[bytes]:
    env_key = os.environ.get("DEVKIT_MASTER_KEY")
    if env_key:
        if len(env_key) == 44:
            return env_key.encode()
        return derive_key_from_passphrase(env_key)
    
    if KEYRING_AVAILABLE:
        try:
            stored = keyring.get_password(KEYRING_SERVICE, KEYRING_MASTER_KEY)
            if stored:
                return stored.encode()
        except Exception:
            pass
    
    return None


def generate_master_key() -> bytes:
    return Fernet.generate_key()


def store_master_key(key: bytes) -> bool:
    if KEYRING_AVAILABLE:
        try:
            keyring.set_password(KEYRING_SERVICE, KEYRING_MASTER_KEY, key.decode())
            return True
        except Exception:
            pass
    return False


def encrypt_value(value: str, key: bytes) -> str:
    fernet = Fernet(key)
    encrypted = fernet.encrypt(value.encode())
    return f"{SENSITIVE_MARKER}:{base64.b64encode(encrypted).decode()}"


def decrypt_value(encrypted: str, key: bytes) -> str:
    if not encrypted.startswith(f"{SENSITIVE_MARKER}:"):
        return encrypted
    
    encrypted_data = base64.b64decode(encrypted[len(SENSITIVE_MARKER) + 1:])
    fernet = Fernet(key)
    return fernet.decrypt(encrypted_data).decode()


SENSITIVE_PATHS = {
    "db_connections.*.password",
    "db_connections.*.user",
    "api_tokens.*",
    "api_variables.*token*",
    "api_variables.*secret*",
    "crypto.master_key",
    "servers.*.password",
    "servers.*.key",
}


def is_sensitive_path(path: str) -> bool:
    path_parts = path.split('.')
    for pattern in SENSITIVE_PATHS:
        pattern_parts = pattern.split('.')
        if len(pattern_parts) != len(path_parts):
            continue
        match = True
        for p, pat in zip(path_parts, pattern_parts):
            if pat != '*' and pat != p and not (pat.endswith('*') and p.startswith(pat[:-1])):
                match = False
                break
        if match:
            return True
    return False


class Config:
    def __init__(self, config_path=None):
        self.config_path = Path(config_path) if config_path else self._default_config_path()
        self._master_key = get_master_key()
        self.config = self._load()
        self._dirty = False

    @staticmethod
    def _default_config_path():
        home = Path.home()
        return home / '.config' / 'devkit' / 'config.yml'

    def _load(self):
        if not self.config_path.exists():
            return self._default_config()
        try:
            with open(self.config_path, 'r', encoding='utf-8') as f:
                loaded = yaml.safe_load(f) or {}
                return self._merge_defaults(loaded)
        except Exception:
            return self._default_config()

    @staticmethod
    def _default_config():
        return {
            'general': {
                'default_editor': 'vim',
                'theme': 'default',
                'timezone': 'Asia/Shanghai',
            },
            'json': {
                'indent': 2,
                'sort_keys': False,
            },
            'crypto': {
                'default_algo': 'aes-256-cbc',
            },
            'net': {
                'timeout': 30,
                'dns_server': '8.8.8.8',
            },
            'db': {
                'default_type': 'mysql',
                'timeout': 30,
            },
            'api': {
                'timeout': 30,
                'verify_ssl': True,
            },
            'sysmon': {
                'refresh_interval': 1,
            },
            'codegen': {
                'default_indent': 4,
            },
            'servers': [],
            'api_tokens': {},
            'api_collections': {},
            'api_variables': {},
            'db_connections': {},
        }

    def _merge_defaults(self, loaded: Dict[str, Any]) -> Dict[str, Any]:
        defaults = self._default_config()
        for section, section_defaults in defaults.items():
            if section not in loaded:
                loaded[section] = section_defaults
            elif isinstance(section_defaults, dict) and isinstance(loaded[section], dict):
                for key, value in section_defaults.items():
                    if key not in loaded[section]:
                        loaded[section][key] = value
        return loaded

    def save(self):
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        config_to_save = self._encrypt_sensitive(self.config)
        with open(self.config_path, 'w', encoding='utf-8') as f:
            yaml.dump(config_to_save, f, default_flow_style=False, allow_unicode=True)
        self._dirty = False

    def _encrypt_sensitive(self, data: Any, path: str = '') -> Any:
        if isinstance(data, dict):
            result = {}
            for key, value in data.items():
                new_path = f"{path}.{key}" if path else key
                result[key] = self._encrypt_sensitive(value, new_path)
            return result
        elif isinstance(data, list):
            return [self._encrypt_sensitive(item, f"{path}[{i}]") for i, item in enumerate(data)]
        elif isinstance(data, str) and self._master_key and is_sensitive_path(path):
            return encrypt_value(data, self._master_key)
        return data

    def _decrypt_sensitive(self, data: Any, path: str = '') -> Any:
        if isinstance(data, dict):
            result = {}
            for key, value in data.items():
                new_path = f"{path}.{key}" if path else key
                result[key] = self._decrypt_sensitive(value, new_path)
            return result
        elif isinstance(data, list):
            return [self._decrypt_sensitive(item, f"{path}[{i}]") for i, item in enumerate(data)]
        elif isinstance(data, str) and data.startswith(f"{SENSITIVE_MARKER}:"):
            if self._master_key:
                try:
                    return decrypt_value(data, self._master_key)
                except Exception:
                    return data
        return data

    def get(self, key, default=None):
        keys = key.split('.')
        value = self.config
        for k in keys:
            if isinstance(value, dict) and k in value:
                value = value[k]
            else:
                return default
        return self._decrypt_sensitive(value, key)

    def set(self, key, value):
        keys = key.split('.')
        config = self.config
        for k in keys[:-1]:
            if k not in config:
                config[k] = {}
            config = config[k]
        
        if is_sensitive_path(key) and self._master_key:
            if isinstance(value, str):
                value = encrypt_value(value, self._master_key)
        
        config[keys[-1]] = value
        self.save()

    def delete(self, key):
        keys = key.split('.')
        config = self.config
        for k in keys[:-1]:
            if k in config:
                config = config[k]
            else:
                return
        if keys[-1] in config:
            del config[keys[-1]]
            self.save()

    def has_master_key(self) -> bool:
        return self._master_key is not None

    def set_master_key(self, key: bytes, persist: bool = False) -> None:
        self._master_key = key
        if persist and KEYRING_AVAILABLE:
            store_master_key(key)
        self._dirty = True

    def reload(self) -> None:
        self.config = self._load()

    def get_all(self) -> Dict[str, Any]:
        return self._decrypt_sensitive(self.config)

    def add_server(self, name, host, port=22, user=None, password=None):
        servers = self.config.setdefault('servers', [])
        srv = next((s for s in servers if s.get('name') == name), None)
        server_data = {'name': name, 'host': host, 'port': port, 'user': user}
        if password:
            server_data['password'] = password
        if srv:
            srv.update(server_data)
        else:
            servers.append(server_data)
        self.save()

    def set_api_token(self, service, token):
        tokens = self.config.setdefault('api_tokens', {})
        if self._master_key:
            tokens[service] = encrypt_value(token, self._master_key)
        else:
            tokens[service] = token
        self.save()

    def get_api_token(self, service):
        tokens = self.config.get('api_tokens', {})
        value = tokens.get(service)
        if value and self._master_key:
            try:
                return decrypt_value(value, self._master_key)
            except Exception:
                pass
        return value

    def add_db_connection(self, name, db_type, host, port, database, user=None, password=None):
        connections = self.config.setdefault('db_connections', {})
        conn_data = {
            'type': db_type,
            'host': host,
            'port': port,
            'database': database,
        }
        if user:
            conn_data['user'] = user
        if password:
            conn_data['password'] = password
        connections[name] = conn_data
        self.save()

    def get_db_connection(self, name):
        connections = self.config.get('db_connections', {})
        conn = connections.get(name)
        if not conn:
            return None
        return self._decrypt_sensitive(dict(conn), f"db_connections.{name}")


_config_instance = None


def get_config():
    global _config_instance
    if _config_instance is None:
        _config_instance = Config()
    return _config_instance


def reset_config():
    global _config_instance
    _config_instance = None
