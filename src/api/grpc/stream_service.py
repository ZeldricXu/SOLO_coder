import logging
import json
from concurrent import futures
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)


class StreamSQLServicer:
    def __init__(self):
        from src.service.query_service import QueryService
        from src.service.cdc_service import CDCService
        self._query_service = QueryService()
        self._cdc_service = None

    def _get_cdc_service(self) -> Any:
        if self._cdc_service is None:
            from src.service.cdc_service import CDCService
            self._cdc_service = CDCService()
        return self._cdc_service

    def ExecuteQuery(self, request: Dict[str, Any]) -> Dict[str, Any]:
        sql = request.get("sql", "")
        optimize = request.get("optimize", True)
        try:
            result = self._query_service.execute_query(sql, optimize)
            return {"status": "ok", "result": json.dumps(result, default=str)}
        except Exception as e:
            return {"status": "error", "error": str(e)}

    def ValidateQuery(self, request: Dict[str, Any]) -> Dict[str, Any]:
        sql = request.get("sql", "")
        result = self._query_service.validate_sql(sql)
        return result

    def ExplainQuery(self, request: Dict[str, Any]) -> Dict[str, Any]:
        sql = request.get("sql", "")
        try:
            explanation = self._query_service.explain_query(sql)
            return {"status": "ok", "explanation": explanation}
        except Exception as e:
            return {"status": "error", "error": str(e)}

    def StreamCDCEvents(self, request: Dict[str, Any]):
        source_type = request.get("source_type", "mysql")
        yield {"status": "started", "source_type": source_type}

    def HealthCheck(self, request: Dict[str, Any]) -> Dict[str, Any]:
        return {"status": "healthy", "service": "StreamSQL", "version": "1.0.0"}

    @staticmethod
    def serve(host: str = "0.0.0.0", port: int = 50051):
        logger.info(f"gRPC server would start on {host}:{port}")
        logger.info("Note: Full gRPC support requires protobuf definitions and generated stubs")
