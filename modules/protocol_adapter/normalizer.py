from typing import Any, Dict, List, Optional
from datetime import datetime, timezone


class DataNormalizer:
    @staticmethod
    def normalize(
        data: Any,
        source_format: str,
        target_format: str = "standard",
        options: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        options = options or {}

        normalizers = {
            "modbus": DataNormalizer._normalize_modbus,
            "mqtt": DataNormalizer._normalize_mqtt,
            "opcua": DataNormalizer._normalize_opcua,
            "http": DataNormalizer._normalize_http,
            "simulation": DataNormalizer._normalize_simulation,
        }

        normalizer = normalizers.get(source_format, DataNormalizer._normalize_generic)
        normalized = normalizer(data, options)

        if target_format == "standard":
            return DataNormalizer._to_standard_format(normalized, source_format)
        elif target_format == "timeseries":
            return DataNormalizer._to_timeseries_format(normalized, source_format)

        return normalized

    @staticmethod
    def _normalize_modbus(data: Any, options: Dict[str, Any]) -> Dict[str, Any]:
        if isinstance(data, dict):
            return {
                "value": data.get("value"),
                "address": data.get("address"),
                "register_type": data.get("register_type"),
                "timestamp": data.get("timestamp", datetime.utcnow().isoformat()),
            }
        return {"value": data, "timestamp": datetime.utcnow().isoformat()}

    @staticmethod
    def _normalize_mqtt(data: Any, options: Dict[str, Any]) -> Dict[str, Any]:
        if isinstance(data, dict):
            return {
                "topic": data.get("topic"),
                "payload": data.get("payload"),
                "qos": data.get("qos", 0),
                "retain": data.get("retain", False),
                "timestamp": data.get("timestamp", datetime.utcnow().isoformat()),
            }
        return {"payload": data, "timestamp": datetime.utcnow().isoformat()}

    @staticmethod
    def _normalize_opcua(data: Any, options: Dict[str, Any]) -> Dict[str, Any]:
        if isinstance(data, dict):
            return {
                "node_id": data.get("node_id"),
                "value": data.get("value"),
                "source_timestamp": data.get("source_timestamp"),
                "server_timestamp": data.get("server_timestamp"),
                "timestamp": data.get("source_timestamp") or datetime.utcnow().isoformat(),
            }
        return {"value": data, "timestamp": datetime.utcnow().isoformat()}

    @staticmethod
    def _normalize_http(data: Any, options: Dict[str, Any]) -> Dict[str, Any]:
        if isinstance(data, dict):
            return {
                "url": data.get("url"),
                "method": data.get("method"),
                "status_code": data.get("status_code"),
                "data": data.get("data"),
                "timestamp": data.get("timestamp", datetime.utcnow().isoformat()),
            }
        return {"data": data, "timestamp": datetime.utcnow().isoformat()}

    @staticmethod
    def _normalize_simulation(data: Any, options: Dict[str, Any]) -> Dict[str, Any]:
        if isinstance(data, dict):
            return {
                "tag": data.get("tag"),
                "value": data.get("value"),
                "quality": data.get("quality", "good"),
                "timestamp": data.get("timestamp", datetime.utcnow().isoformat()),
            }
        return {"value": data, "timestamp": datetime.utcnow().isoformat()}

    @staticmethod
    def _normalize_generic(data: Any, options: Dict[str, Any]) -> Dict[str, Any]:
        if isinstance(data, dict):
            result = dict(data)
            if "timestamp" not in result:
                result["timestamp"] = datetime.utcnow().isoformat()
            return result
        return {"value": data, "timestamp": datetime.utcnow().isoformat()}

    @staticmethod
    def _to_standard_format(data: Dict[str, Any], source: str) -> Dict[str, Any]:
        return {
            "data": data,
            "source": source,
            "normalized_at": datetime.utcnow().isoformat(),
            "schema_version": "1.0",
        }

    @staticmethod
    def _to_timeseries_format(data: Dict[str, Any], source: str) -> Dict[str, Any]:
        return {
            "timestamp": data.get("timestamp", datetime.utcnow().isoformat()),
            "value": data.get("value"),
            "tags": {
                "source": source,
                **data.get("tags", {}),
            },
            "fields": {k: v for k, v in data.items() if k not in ["timestamp", "tags"]},
        }

    @staticmethod
    def transform_value(
        value: Any,
        transformation: str,
        params: Optional[Dict[str, Any]] = None,
    ) -> Any:
        params = params or {}

        transformations = {
            "scale": lambda v: v * params.get("factor", 1.0) + params.get("offset", 0.0),
            "offset": lambda v: v + params.get("offset", 0.0),
            "inverse": lambda v: 1.0 / v if v != 0 else 0.0,
            "absolute": lambda v: abs(v),
            "round": lambda v: round(v, params.get("decimals", 2)),
            "to_string": lambda v: str(v),
            "to_int": lambda v: int(v),
            "to_float": lambda v: float(v),
            "to_bool": lambda v: bool(v),
            "lowercase": lambda v: str(v).lower() if isinstance(v, str) else v,
            "uppercase": lambda v: str(v).upper() if isinstance(v, str) else v,
            "trim": lambda v: str(v).strip() if isinstance(v, str) else v,
        }

        if transformation in transformations:
            try:
                return transformations[transformation](value)
            except Exception:
                return value
        return value

    @staticmethod
    def validate_data(data: Dict[str, Any], schema: Dict[str, Any]) -> bool:
        for field, rules in schema.items():
            if rules.get("required") and field not in data:
                return False

            if field in data:
                value = data[field]
                expected_type = rules.get("type")

                if expected_type == "string" and not isinstance(value, str):
                    return False
                elif expected_type == "number" and not isinstance(value, (int, float)):
                    return False
                elif expected_type == "integer" and not isinstance(value, int):
                    return False
                elif expected_type == "boolean" and not isinstance(value, bool):
                    return False
                elif expected_type == "object" and not isinstance(value, dict):
                    return False
                elif expected_type == "array" and not isinstance(value, list):
                    return False

                if "min" in rules and isinstance(value, (int, float)) and value < rules["min"]:
                    return False
                if "max" in rules and isinstance(value, (int, float)) and value > rules["max"]:
                    return False
                if "enum" in rules and value not in rules["enum"]:
                    return False

        return True
