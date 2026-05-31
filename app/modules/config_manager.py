from datetime import datetime
from typing import Any, Dict, List, Optional
from dataclasses import dataclass, field
from enum import Enum
import json
from pathlib import Path
import uuid

from app.core.logger import logger
from app.core.events import event_bus, EventType, build_event
from app.core.config import settings
from app.core.models import ConfigEntity


class ConfigStatus(str, Enum):
    DRAFT = "draft"
    ACTIVE = "active"
    DEPRECATED = "deprecated"
    ARCHIVED = "archived"


@dataclass
class ConfigVersion:
    version_id: str
    version: int
    namespace: str
    parameters: Dict[str, Any]
    status: ConfigStatus
    created_at: datetime
    applied_at: Optional[datetime] = None
    created_by: str = "system"
    description: str = ""
    rollback_from: Optional[int] = None


class VersionedConfigManager:
    def __init__(self, storage_dir: Optional[str] = None):
        self._storage_dir = Path(storage_dir) if storage_dir else Path(settings.storage_path) / "configs"
        self._storage_dir.mkdir(parents=True, exist_ok=True)
        self._configs_file = self._storage_dir / "config_versions.json"
        self._configs: Dict[str, List[ConfigVersion]] = {}
        self._active_versions: Dict[str, int] = {}
        self._load_configs()

    def _load_configs(self):
        if self._configs_file.exists():
            try:
                with open(self._configs_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                for namespace, versions in data.items():
                    self._configs[namespace] = [
                        ConfigVersion(
                            version_id=v["version_id"],
                            version=v["version"],
                            namespace=namespace,
                            parameters=v["parameters"],
                            status=v["status"],
                            created_at=datetime.fromisoformat(v["created_at"]),
                            applied_at=datetime.fromisoformat(v["applied_at"]) if v.get("applied_at") else None,
                            created_by=v.get("created_by", "system"),
                            description=v.get("description", ""),
                            rollback_from=v.get("rollback_from")
                        )
                        for v in versions
                    ]
                    active = [v for v in self._configs[namespace] if v.status == ConfigStatus.ACTIVE]
                    if active:
                        self._active_versions[namespace] = max(v.version for v in active)
            except Exception as e:
                logger.error(f"Failed to load configs: {e}")
                self._configs = {}

    def _save_configs(self):
        try:
            data = {}
            for namespace, versions in self._configs.items():
                data[namespace] = [
                    {
                        "version_id": v.version_id,
                        "version": v.version,
                        "parameters": v.parameters,
                        "status": v.status,
                        "created_at": v.created_at.isoformat(),
                        "applied_at": v.applied_at.isoformat() if v.applied_at else None,
                        "created_by": v.created_by,
                        "description": v.description,
                        "rollback_from": v.rollback_from
                    }
                    for v in versions
                ]
            with open(self._configs_file, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, default=str)
        except Exception as e:
            logger.error(f"Failed to save configs: {e}")
            raise

    def create_config(self, namespace: str, parameters: Dict[str, Any],
                       description: str = "", created_by: str = "system") -> ConfigVersion:
        existing = self._configs.get(namespace, [])
        next_version = max([v.version for v in existing], default=0) + 1

        config = ConfigVersion(
            version_id=f"cfg_{uuid.uuid4().hex[:8]}",
            version=next_version,
            namespace=namespace,
            parameters=parameters.copy(),
            status=ConfigStatus.DRAFT,
            created_at=datetime.utcnow(),
            created_by=created_by,
            description=description
        )

        if namespace not in self._configs:
            self._configs[namespace] = []
        self._configs[namespace].append(config)
        self._save_configs()

        logger.info(f"Created config version {next_version} for namespace {namespace}")
        return config

    def apply_config(self, namespace: str, version: Optional[int] = None) -> Optional[ConfigVersion]:
        versions = self._configs.get(namespace, [])
        if not versions:
            return None

        target_version = version or max(v.version for v in versions)
        config = next((v for v in versions if v.version == target_version), None)

        if not config:
            return None

        for v in versions:
            if v.status == ConfigStatus.ACTIVE:
                v.status = ConfigStatus.DEPRECATED

        config.status = ConfigStatus.ACTIVE
        config.applied_at = datetime.utcnow()
        self._active_versions[namespace] = target_version
        self._save_configs()

        event_bus.emit(build_event(EventType.CONFIG_UPDATED, {
            "namespace": namespace,
            "version": target_version,
            "parameters": config.parameters
        }))

        logger.info(f"Applied config version {target_version} for namespace {namespace}")
        return config

    def get_config(self, namespace: str, version: Optional[int] = None) -> Optional[ConfigVersion]:
        versions = self._configs.get(namespace, [])
        if not versions:
            return None

        if version:
            return next((v for v in versions if v.version == version), None)

        active = [v for v in versions if v.status == ConfigStatus.ACTIVE]
        if active:
            return max(active, key=lambda v: v.version)

        return max(versions, key=lambda v: v.version)

    def get_active_version(self, namespace: str) -> Optional[int]:
        return self._active_versions.get(namespace)

    def list_versions(self, namespace: str) -> List[ConfigVersion]:
        versions = self._configs.get(namespace, [])
        return sorted(versions, key=lambda v: v.version, reverse=True)

    def list_namespaces(self) -> List[str]:
        return list(self._configs.keys())

    def update_config_parameters(self, namespace: str, parameters: Dict[str, Any],
                                  description: str = "",
                                  created_by: str = "system") -> Optional[ConfigVersion]:
        current = self.get_config(namespace)
        new_params = current.parameters.copy() if current else {}
        new_params.update(parameters)

        return self.create_config(namespace, new_params, description, created_by)

    def archive_config(self, namespace: str, version: int) -> bool:
        versions = self._configs.get(namespace, [])
        config = next((v for v in versions if v.version == version), None)
        if config and config.status != ConfigStatus.ACTIVE:
            config.status = ConfigStatus.ARCHIVED
            self._save_configs()
            logger.info(f"Archived config {namespace} version {version}")
            return True
        return False


class ConfigRollbackManager:
    def __init__(self, version_manager: VersionedConfigManager):
        self._version_manager = version_manager
        self._rollback_history: Dict[str, List[Dict[str, Any]]] = {}

    def rollback_to_version(self, namespace: str, target_version: int,
                            reason: str = "rollback") -> Optional[ConfigVersion]:
        current = self._version_manager.get_config(namespace)
        if not current:
            logger.error(f"No active config found for namespace {namespace}")
            return None

        if current.version <= target_version:
            logger.error(f"Cannot rollback to version {target_version}, current is {current.version}")
            return None

        target = self._version_manager.get_config(namespace, target_version)
        if not target:
            logger.error(f"Target version {target_version} not found for {namespace}")
            return None

        new_config = self._version_manager.create_config(
            namespace=namespace,
            parameters=target.parameters.copy(),
            description=f"Rollback from v{current.version} to v{target_version}: {reason}",
            created_by="rollback"
        )
        new_config.rollback_from = current.version

        applied = self._version_manager.apply_config(namespace, new_config.version)

        if namespace not in self._rollback_history:
            self._rollback_history[namespace] = []
        self._rollback_history[namespace].append({
            "timestamp": datetime.utcnow().isoformat(),
            "from_version": current.version,
            "to_version": new_config.version,
            "target_version": target_version,
            "reason": reason
        })

        event_bus.emit(build_event(EventType.CONFIG_ROLLED_BACK, {
            "namespace": namespace,
            "from_version": current.version,
            "to_version": new_config.version,
            "target_version": target_version,
            "reason": reason
        }))

        logger.info(f"Rolled back {namespace} from v{current.version} to v{new_config.version}")
        return applied

    def rollback_to_previous(self, namespace: str, reason: str = "rollback") -> Optional[ConfigVersion]:
        current = self._version_manager.get_config(namespace)
        if not current:
            return None

        previous_version = current.version - 1
        return self.rollback_to_version(namespace, previous_version, reason)

    def get_rollback_history(self, namespace: str) -> List[Dict[str, Any]]:
        return self._rollback_history.get(namespace, [])

    def can_rollback(self, namespace: str) -> bool:
        current = self._version_manager.get_config(namespace)
        if not current:
            return False
        return current.version > 1

    def preview_rollback(self, namespace: str, target_version: int) -> Optional[Dict[str, Any]]:
        current = self._version_manager.get_config(namespace)
        target = self._version_manager.get_config(namespace, target_version)

        if not current or not target:
            return None

        added = set(target.parameters.keys()) - set(current.parameters.keys())
        removed = set(current.parameters.keys()) - set(target.parameters.keys())
        modified = []
        for key in set(current.parameters.keys()) & set(target.parameters.keys()):
            if current.parameters[key] != target.parameters[key]:
                modified.append(key)

        return {
            "namespace": namespace,
            "from_version": current.version,
            "to_version": target_version,
            "added_parameters": list(added),
            "removed_parameters": list(removed),
            "modified_parameters": modified,
            "target_parameters": target.parameters
        }


class ConfigManagementModule:
    def __init__(self):
        self._version_manager = VersionedConfigManager()
        self._rollback_manager = ConfigRollbackManager(self._version_manager)
        self._init_default_configs()
        logger.info("ConfigManagementModule initialized")

    def _init_default_configs(self):
        default_namespaces = ["default", "staging", "production", "dev"]
        for ns in default_namespaces:
            if not self._version_manager.list_versions(ns):
                config = self._version_manager.create_config(
                    namespace=ns,
                    parameters={"timeout": 30, "retries": 3, "poolSize": 10, "rules": {}},
                    description=f"Default configuration for {ns}",
                    created_by="system"
                )
                self._version_manager.apply_config(ns, config.version)

    @property
    def version_manager(self) -> VersionedConfigManager:
        return self._version_manager

    @property
    def rollback_manager(self) -> ConfigRollbackManager:
        return self._rollback_manager

    def get(self, namespace: str = "default") -> Dict[str, Any]:
        config = self._version_manager.get_config(namespace)
        return config.parameters if config else {}

    def set(self, namespace: str, parameters: Dict[str, Any],
            description: str = "") -> ConfigVersion:
        config = self._version_manager.update_config_parameters(
            namespace, parameters, description
        )
        return self._version_manager.apply_config(namespace, config.version)

    def create_and_apply(self, namespace: str, parameters: Dict[str, Any],
                          description: str = "") -> ConfigVersion:
        config = self._version_manager.create_config(namespace, parameters, description)
        return self._version_manager.apply_config(namespace, config.version)

    def get_config_entity(self, namespace: str = "default") -> Optional[ConfigEntity]:
        config = self._version_manager.get_config(namespace)
        if not config:
            return None
        return ConfigEntity(
            config_id=config.version_id,
            namespace=config.namespace,
            version=config.version,
            parameters=config.parameters,
            enabled=config.status == ConfigStatus.ACTIVE,
            applied_at=config.applied_at or config.created_at
        )


config_module = ConfigManagementModule()
