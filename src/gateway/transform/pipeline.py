from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlencode, parse_qs
import json
import re

from gateway.logger import get_logger

logger = get_logger("transform")


class TransformRule(ABC):
    def __init__(self, config: Dict[str, Any]):
        self.config = config
        self.enabled = config.get("enabled", True)
        self.path_pattern = config.get("path_pattern")
        self.priority = config.get("priority", 0)

    def matches_path(self, path: str) -> bool:
        if not self.path_pattern:
            return True
        return path.startswith(self.path_pattern)

    @abstractmethod
    async def apply(self, data: Any, context: Dict[str, Any]) -> Any:
        pass


class RequestHeaderTransform(TransformRule):
    async def apply(self, headers: Dict[str, str], context: Dict[str, Any]) -> Dict[str, str]:
        if not self.enabled:
            return headers

        add_headers = self.config.get("add", {})
        set_headers = self.config.get("set", {})
        remove_headers = self.config.get("remove", [])

        for key, value in add_headers.items():
            if key not in headers:
                headers[key] = str(value)

        for key, value in set_headers.items():
            headers[key] = str(value)

        for key in remove_headers:
            headers.pop(key, None)
            headers.pop(key.lower(), None)

        return headers


class RequestQueryTransform(TransformRule):
    async def apply(self, query_string: str, context: Dict[str, Any]) -> str:
        if not self.enabled:
            return query_string

        params = parse_qs(query_string, keep_blank_values=True)

        add_params = self.config.get("add", {})
        set_params = self.config.get("set", {})
        remove_params = self.config.get("remove", [])
        rename_params = self.config.get("rename", {})

        for old_name, new_name in rename_params.items():
            if old_name in params:
                params[new_name] = params.pop(old_name)

        for key, value in add_params.items():
            if key not in params:
                params[key] = [str(value)]

        for key, value in set_params.items():
            params[key] = [str(value)]

        for key in remove_params:
            params.pop(key, None)

        return urlencode(params, doseq=True)


class RequestBodyTransform(TransformRule):
    async def apply(self, body: bytes, context: Dict[str, Any]) -> bytes:
        if not self.enabled or not body:
            return body

        content_type = context.get("content_type", "")
        if "application/json" not in content_type:
            return body

        try:
            data = json.loads(body.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return body

        data = await self._transform_json(data)
        return json.dumps(data).encode("utf-8")

    async def _transform_json(self, data: Any) -> Any:
        mask_fields = self.config.get("mask", [])
        remove_fields = self.config.get("remove", [])
        add_fields = self.config.get("add", {})
        rename_fields = self.config.get("rename", {})

        if isinstance(data, dict):
            for old_key, new_key in rename_fields.items():
                if old_key in data:
                    data[new_key] = data.pop(old_key)

            for field in mask_fields:
                if field in data:
                    data[field] = self._mask_value(data[field])

            for field in remove_fields:
                data.pop(field, None)

            for key, value in add_fields.items():
                data[key] = value

            for key, value in list(data.items()):
                data[key] = await self._transform_json(value)

        elif isinstance(data, list):
            data = [await self._transform_json(item) for item in data]

        return data

    def _mask_value(self, value: Any) -> Any:
        if isinstance(value, str):
            if len(value) <= 4:
                return "*" * len(value)
            return value[:2] + "*" * (len(value) - 4) + value[-2:]
        elif isinstance(value, (int, float)):
            return "***"
        return value


class ResponseHeaderTransform(TransformRule):
    async def apply(self, headers: Dict[str, str], context: Dict[str, Any]) -> Dict[str, str]:
        if not self.enabled:
            return headers

        add_headers = self.config.get("add", {})
        set_headers = self.config.get("set", {})
        remove_headers = self.config.get("remove", [])

        for key, value in add_headers.items():
            if key not in headers:
                headers[key] = str(value)

        for key, value in set_headers.items():
            headers[key] = str(value)

        for key in remove_headers:
            headers.pop(key, None)

        return headers


class ResponseBodyTransform(TransformRule):
    async def apply(self, body: bytes, context: Dict[str, Any]) -> bytes:
        if not self.enabled or not body:
            return body

        content_type = context.get("content_type", "")
        if "application/json" not in content_type:
            return body

        try:
            data = json.loads(body.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return body

        data = await self._transform_json(data)
        return json.dumps(data).encode("utf-8")

    async def _transform_json(self, data: Any) -> Any:
        include_fields = self.config.get("include")
        exclude_fields = self.config.get("exclude", [])
        mask_fields = self.config.get("mask", [])
        add_fields = self.config.get("add", {})
        rename_fields = self.config.get("rename", {})

        if isinstance(data, dict):
            for old_key, new_key in rename_fields.items():
                if old_key in data:
                    data[new_key] = data.pop(old_key)

            if include_fields:
                data = {k: v for k, v in data.items() if k in include_fields}

            for field in exclude_fields:
                data.pop(field, None)

            for field in mask_fields:
                if field in data:
                    data[field] = self._mask_value(data[field])

            for key, value in add_fields.items():
                data[key] = value

            for key, value in list(data.items()):
                data[key] = await self._transform_json(value)

        elif isinstance(data, list):
            data = [await self._transform_json(item) for item in data]

        return data

    def _mask_value(self, value: Any) -> Any:
        if isinstance(value, str):
            if len(value) <= 4:
                return "*" * len(value)
            return value[:2] + "*" * (len(value) - 4) + value[-2:]
        return "***"


class ResponseStatusTransform(TransformRule):
    async def apply(self, status_code: int, context: Dict[str, Any]) -> int:
        if not self.enabled:
            return status_code

        mappings = self.config.get("mappings", {})
        return int(mappings.get(str(status_code), status_code))


RULE_REGISTRY: Dict[str, type] = {
    "request_header": RequestHeaderTransform,
    "request_query": RequestQueryTransform,
    "request_body": RequestBodyTransform,
    "response_header": ResponseHeaderTransform,
    "response_body": ResponseBodyTransform,
    "response_status": ResponseStatusTransform,
}


class TransformPipeline:
    def __init__(self):
        self._request_header_rules: List[RequestHeaderTransform] = []
        self._request_query_rules: List[RequestQueryTransform] = []
        self._request_body_rules: List[RequestBodyTransform] = []
        self._response_header_rules: List[ResponseHeaderTransform] = []
        self._response_body_rules: List[ResponseBodyTransform] = []
        self._response_status_rules: List[ResponseStatusTransform] = []

    def load_rules(self, rules_config: List[Dict[str, Any]]) -> None:
        self._request_header_rules.clear()
        self._request_query_rules.clear()
        self._request_body_rules.clear()
        self._response_header_rules.clear()
        self._response_body_rules.clear()
        self._response_status_rules.clear()

        for rule_config in rules_config:
            rule_type = rule_config.get("type")
            rule_class = RULE_REGISTRY.get(rule_type)
            if not rule_class:
                logger.warning("Unknown transform rule type", type=rule_type)
                continue

            rule = rule_class(rule_config)
            self._add_rule(rule_type, rule)

        self._sort_rules()
        logger.info("Transform rules loaded",
                    request_header_count=len(self._request_header_rules),
                    request_query_count=len(self._request_query_rules),
                    request_body_count=len(self._request_body_rules),
                    response_header_count=len(self._response_header_rules),
                    response_body_count=len(self._response_body_rules),
                    response_status_count=len(self._response_status_rules))

    def load_route_rules(self, route_transform: Optional[Dict[str, Any]]) -> None:
        if not route_transform:
            return

        request_rules = route_transform.get("request", [])
        response_rules = route_transform.get("response", [])

        for rule_config in request_rules:
            rule_type = rule_config.get("type")
            rule_class = RULE_REGISTRY.get(rule_type)
            if rule_class:
                rule = rule_class(rule_config)
                self._add_rule(rule_type, rule)

        for rule_config in response_rules:
            rule_type = rule_config.get("type")
            rule_class = RULE_REGISTRY.get(rule_type)
            if rule_class:
                rule = rule_class(rule_config)
                self._add_rule(rule_type, rule)

        self._sort_rules()

    def _add_rule(self, rule_type: str, rule: TransformRule) -> None:
        rule_list = {
            "request_header": self._request_header_rules,
            "request_query": self._request_query_rules,
            "request_body": self._request_body_rules,
            "response_header": self._response_header_rules,
            "response_body": self._response_body_rules,
            "response_status": self._response_status_rules,
        }.get(rule_type)

        if rule_list is not None:
            rule_list.append(rule)

    def _sort_rules(self) -> None:
        for rules in [
            self._request_header_rules,
            self._request_query_rules,
            self._request_body_rules,
            self._response_header_rules,
            self._response_body_rules,
            self._response_status_rules,
        ]:
            rules.sort(key=lambda r: r.priority, reverse=True)

    async def transform_request_headers(self, headers: Dict[str, str], path: str,
                                        context: Dict[str, Any]) -> Dict[str, str]:
        for rule in self._request_header_rules:
            if rule.matches_path(path):
                headers = await rule.apply(headers, context)
        return headers

    async def transform_request_query(self, query_string: str, path: str,
                                      context: Dict[str, Any]) -> str:
        for rule in self._request_query_rules:
            if rule.matches_path(path):
                query_string = await rule.apply(query_string, context)
        return query_string

    async def transform_request_body(self, body: bytes, path: str,
                                     context: Dict[str, Any]) -> bytes:
        for rule in self._request_body_rules:
            if rule.matches_path(path):
                body = await rule.apply(body, context)
        return body

    async def transform_response_headers(self, headers: Dict[str, str], path: str,
                                         context: Dict[str, Any]) -> Dict[str, str]:
        for rule in self._response_header_rules:
            if rule.matches_path(path):
                headers = await rule.apply(headers, context)
        return headers

    async def transform_response_body(self, body: bytes, path: str,
                                      context: Dict[str, Any]) -> bytes:
        for rule in self._response_body_rules:
            if rule.matches_path(path):
                body = await rule.apply(body, context)
        return body

    async def transform_response_status(self, status_code: int, path: str,
                                        context: Dict[str, Any]) -> int:
        for rule in self._response_status_rules:
            if rule.matches_path(path):
                status_code = await rule.apply(status_code, context)
        return status_code

    async def transform_error_response(self, status_code: int, body: Dict[str, Any],
                                       path: str) -> Tuple[int, Dict[str, Any]]:
        error_mappings = {
            400: {"code": "BAD_REQUEST", "message": "Bad Request"},
            401: {"code": "UNAUTHORIZED", "message": "Unauthorized"},
            403: {"code": "FORBIDDEN", "message": "Forbidden"},
            404: {"code": "NOT_FOUND", "message": "Not Found"},
            405: {"code": "METHOD_NOT_ALLOWED", "message": "Method Not Allowed"},
            429: {"code": "TOO_MANY_REQUESTS", "message": "Too Many Requests"},
            500: {"code": "INTERNAL_ERROR", "message": "Internal Server Error"},
            502: {"code": "BAD_GATEWAY", "message": "Bad Gateway"},
            503: {"code": "SERVICE_UNAVAILABLE", "message": "Service Unavailable"},
            504: {"code": "GATEWAY_TIMEOUT", "message": "Gateway Timeout"},
        }

        mapping = error_mappings.get(status_code, {"code": f"ERROR_{status_code}", "message": "Error"})

        standardized = {
            "error": {
                "code": mapping["code"],
                "status": status_code,
                "message": mapping["message"],
                "detail": body.get("error", {}).get("detail", body.get("detail", "")) if isinstance(body, dict) else str(body),
                "request_id": context.get("request_id", ""),
                "timestamp": context.get("timestamp", ""),
            }
        }

        return status_code, standardized


_pipeline_instance: Optional[TransformPipeline] = None


def get_transform_pipeline() -> TransformPipeline:
    global _pipeline_instance
    if _pipeline_instance is None:
        _pipeline_instance = TransformPipeline()
    return _pipeline_instance
