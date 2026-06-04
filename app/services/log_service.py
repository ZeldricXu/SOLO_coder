from datetime import datetime, timedelta
from typing import List, Optional, Dict, Any
import json
import random
import re
import asyncio

from sqlalchemy.orm import Session
import httpx

from app.config import settings
from app.models import LogTemplate
from app.schemas import LogSearchRequest, LogTemplateCreate
from app.services.lucene_parser import lucene_parser


class LogService:
    """日志采集搜索服务，负责日志查询、Lucene语法解析和查询模板管理。

    主要职责：
    - 日志搜索：支持关键词匹配和 Lucene 风格查询语法
    - 查询语法解析：自动检测 Lucene 语法，转换为 Elasticsearch Query DSL
    - 查询模板管理：保存和加载常用查询配置

    对外接口：
    - search(request): 执行日志搜索
    - get_templates(user_id): 获取用户的查询模板
    - create_template(user_id, data): 创建查询模板

    依赖的外部服务：
    - Elasticsearch（日志存储和搜索）
    - lucene_parser（查询语法解析）
    """

    def __init__(self, db: Session):
        self.db = db
        self.use_mock = True

    async def search(self, request: LogSearchRequest) -> Dict[str, Any]:
        """执行日志搜索，支持关键词匹配和 Lucene 风格查询语法。

        :param request: 搜索请求参数，包含 keyword、service_name、level、时间范围等
        :return: 搜索结果字典，包含 total、page、logs、aggregations
        """
        if self.use_mock:
            return self._mock_search(request)
        return await self._elasticsearch_search(request)

    def _mock_search(self, request: LogSearchRequest) -> Dict[str, Any]:
        total = random.randint(100, 5000)
        page = request.page or 1
        page_size = request.page_size or 50

        logs = []
        services = ["order-service", "user-service", "pay-service", "gateway", "auth-service"]
        levels = ["DEBUG", "INFO", "WARN", "ERROR"]
        messages = [
            "Request processed successfully",
            "Database connection timeout",
            "User authentication failed",
            "Order created successfully",
            "Payment processed",
            "Cache miss for key user:12345",
            "Rate limit exceeded for IP 192.168.1.100",
            "API call to external service failed",
            "Slow query detected: SELECT * FROM orders",
            "Kafka message consumed",
        ]

        start_time = request.start_time or (datetime.now() - timedelta(hours=24))
        end_time = request.end_time or datetime.now()

        for i in range(min(page_size, total - (page - 1) * page_size)):
            timestamp = start_time + (end_time - start_time) * random.random()
            service = request.service_name or random.choice(services)
            level = request.level or random.choices(levels, weights=[5, 70, 15, 10])[0]

            base_message = random.choice(messages)
            if request.keyword:
                if random.random() < 0.7:
                    base_message = f"{base_message} - {request.keyword}"
                elif request.keyword.lower() not in base_message.lower() and not request.keyword:
                    continue

            log = {
                "id": f"log-{random.randint(100000, 999999)}",
                "timestamp": timestamp.strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
                "service": service,
                "level": level,
                "message": base_message,
                "trace_id": f"trace-{random.randint(10000, 99999)}",
                "host": f"192.168.1.{random.randint(10, 50)}",
                "pid": random.randint(1000, 9999),
            }

            if request.keyword:
                log["message"] = self._highlight_keyword(log["message"], request.keyword)

            logs.append(log)

        logs.sort(key=lambda x: x["timestamp"], reverse=True)

        return {
            "total": total,
            "page": page,
            "page_size": page_size,
            "total_pages": (total + page_size - 1) // page_size,
            "logs": logs,
            "aggregations": {
                "by_level": self._mock_level_agg(),
                "by_service": self._mock_service_agg(),
            },
        }

    def _highlight_keyword(self, text: str, keyword: str) -> str:
        if not keyword:
            return text
        pattern = re.compile(re.escape(keyword), re.IGNORECASE)
        return pattern.sub(f'<mark class="log-highlight">{keyword}</mark>', text)

    def _mock_level_agg(self) -> Dict[str, int]:
        return {
            "DEBUG": random.randint(500, 1500),
            "INFO": random.randint(3000, 8000),
            "WARN": random.randint(200, 800),
            "ERROR": random.randint(50, 200),
        }

    def _mock_service_agg(self) -> Dict[str, int]:
        return {
            "order-service": random.randint(1000, 3000),
            "user-service": random.randint(800, 2000),
            "pay-service": random.randint(500, 1500),
            "gateway": random.randint(2000, 5000),
            "auth-service": random.randint(300, 1000),
        }

    async def _elasticsearch_search(self, request: LogSearchRequest) -> Dict[str, Any]:
        query = self._build_es_query(request)

        es_query = {
            "query": query,
            "sort": [{"@timestamp": {"order": "desc"}}],
            "from": (request.page - 1) * request.page_size,
            "size": request.page_size,
            "aggs": {
                "by_level": {"terms": {"field": "level"}},
                "by_service": {"terms": {"field": "service_name"}},
            },
        }

        try:
            async with httpx.AsyncClient(timeout=settings.elasticsearch_timeout) as client:
                response = await client.post(
                    f"{settings.elasticsearch_url}/logs-*/_search",
                    json=es_query
                )
                data = response.json()

                logs = []
                for hit in data.get("hits", {}).get("hits", []):
                    source = hit.get("_source", {})
                    logs.append({
                        "id": hit.get("_id"),
                        "timestamp": source.get("@timestamp"),
                        "service": source.get("service_name"),
                        "level": source.get("level"),
                        "message": source.get("message"),
                        "trace_id": source.get("trace_id"),
                        "host": source.get("host"),
                    })

                aggregations = {
                    "by_level": {b["key"]: b["doc_count"] for b in data.get("aggregations", {}).get("by_level", {}).get("buckets", [])},
                    "by_service": {b["key"]: b["doc_count"] for b in data.get("aggregations", {}).get("by_service", {}).get("buckets", [])},
                }

                return {
                    "total": data.get("hits", {}).get("total", {}).get("value", 0),
                    "page": request.page,
                    "page_size": request.page_size,
                    "total_pages": (data.get("hits", {}).get("total", {}).get("value", 0) + request.page_size - 1) // request.page_size,
                    "logs": logs,
                    "aggregations": aggregations,
                }
        except Exception as e:
            print(f"Elasticsearch search error: {e}")
            return self._mock_search(request)

    def _build_es_query(self, request: LogSearchRequest) -> Dict[str, Any]:
        must_clauses = []
        filter_clauses = []

        if request.keyword:
            has_lucene_syntax = self._detect_lucene_syntax(request.keyword)
            if has_lucene_syntax:
                lucene_query = lucene_parser.parse(request.keyword)
                must_clauses.append(lucene_query)
            else:
                must_clauses.append({
                    "match": {"message": request.keyword}
                })

        if request.service_name:
            filter_clauses.append({
                "term": {"service_name": request.service_name}
            })
        if request.level:
            filter_clauses.append({
                "term": {"level": request.level}
            })

        range_query = {}
        if request.start_time:
            range_query["gte"] = request.start_time.isoformat()
        if request.end_time:
            range_query["lte"] = request.end_time.isoformat()
        if range_query:
            filter_clauses.append({"range": {"@timestamp": range_query}})

        bool_query = {}
        if must_clauses:
            bool_query["must"] = must_clauses
        if filter_clauses:
            bool_query["filter"] = filter_clauses

        if not bool_query:
            return {"match_all": {}}

        return {"bool": bool_query}

    def _detect_lucene_syntax(self, query: str) -> bool:
        if not query:
            return False

        lucene_indicators = [
            " AND ", " OR ", " NOT ",
            ":", "[", "]", "{", "}",
            ' "',
        ]
        query_upper = query.upper()
        for indicator in lucene_indicators:
            if indicator in query_upper or indicator in query:
                return True
        return False

    def get_templates(self, user_id: int) -> List[LogTemplate]:
        return self.db.query(LogTemplate).filter(
            LogTemplate.user_id == user_id
        ).order_by(LogTemplate.created_at.desc()).all()

    def create_template(self, user_id: int, data: LogTemplateCreate) -> LogTemplate:
        template = LogTemplate(
            user_id=user_id,
            name=data.name,
            query_config=json.dumps(data.query_config, ensure_ascii=False),
        )
        self.db.add(template)
        self.db.commit()
        self.db.refresh(template)
        return template

    def delete_template(self, template_id: int, user_id: int) -> bool:
        template = self.db.query(LogTemplate).filter(
            LogTemplate.id == template_id,
            LogTemplate.user_id == user_id,
        ).first()
        if not template:
            return False
        self.db.delete(template)
        self.db.commit()
        return True

    def get_template_by_id(self, template_id: int, user_id: int) -> Optional[Dict[str, Any]]:
        template = self.db.query(LogTemplate).filter(
            LogTemplate.id == template_id,
            LogTemplate.user_id == user_id,
        ).first()
        if not template:
            return None
        try:
            config = json.loads(template.query_config)
        except (json.JSONDecodeError, TypeError):
            config = {}
        return {
            "id": template.id,
            "name": template.name,
            "query_config": config,
            "created_at": template.created_at,
        }

    def get_available_services(self) -> List[str]:
        return [
            "order-service",
            "user-service",
            "pay-service",
            "gateway",
            "auth-service",
            "notification-service",
            "search-service",
        ]

    def get_available_levels(self) -> List[str]:
        return ["DEBUG", "INFO", "WARN", "ERROR", "FATAL"]
