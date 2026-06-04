from datetime import datetime, timedelta
from typing import List, Optional, Dict, Any
import json
import random
import asyncio

from sqlalchemy.orm import Session
import httpx

from app.models import Service, HealthCheck
from app.config import settings
from app.schemas import HealthCheckResult, ServiceCreate, ServiceUpdate


class HealthService:
    """服务健康聚合面板，负责服务健康检查、状态聚合和历史查询。

    主要职责：
    - 定时执行健康检查（HTTP探测或Mock模拟）
    - 聚合所有服务的健康状态（健康/告警/故障）
    - 提供历史检查记录查询（含timeline组件数据）

    对外接口：
    - check_service(service): 检查单个服务健康状态
    - check_all_services(): 检查所有服务并保存结果
    - get_summary(): 获取全量服务状态汇总
    - get_check_history(service_id, hours): 获取指定时间窗口的检查历史

    依赖的外部服务：
    - Prometheus / 目标服务的 health_endpoint（健康检查探针）
    - 数据库（Service, HealthCheck 模型）
    """
    def __init__(self, db: Session):
        self.db = db
        self.use_mock = True

    async def check_service(self, service: Service) -> HealthCheckResult:
        """检查单个服务的健康状态。

        :param service: 被检查的服务对象，需包含 health_endpoint 和 service_type
        :return: 健康检查结果，包含 service_id、status、response_time_ms、details
        """
        if self.use_mock:
            return await self._mock_check(service)
        return await self._real_check(service)

    async def _mock_check(self, service: Service) -> HealthCheckResult:
        await asyncio.sleep(random.uniform(0.01, 0.05))

        status_weights = {
            1: [("healthy", 0.95), ("warning", 0.04), ("critical", 0.01)],
            2: [("healthy", 0.95), ("warning", 0.04), ("critical", 0.01)],
            3: [("healthy", 0.7), ("warning", 0.25), ("critical", 0.05)],
            4: [("healthy", 0.98), ("warning", 0.02), ("critical", 0.0)],
            5: [("healthy", 0.99), ("warning", 0.01), ("critical", 0.0)],
            6: [("healthy", 0.1), ("warning", 0.3), ("critical", 0.6)],
        }

        weights = status_weights.get(service.id, [("healthy", 0.9), ("warning", 0.08), ("critical", 0.02)])
        r = random.random()
        cumulative = 0
        status = "healthy"
        for s, w in weights:
            cumulative += w
            if r <= cumulative:
                status = s
                break

        if status == "healthy":
            response_time = random.randint(15, 100)
        elif status == "warning":
            response_time = random.randint(200, 500)
        else:
            response_time = None

        details = self._generate_mock_details(service, status)

        return HealthCheckResult(
            service_id=service.id,
            status=status,
            response_time_ms=response_time,
            details=json.dumps(details, ensure_ascii=False)
        )

    def _generate_mock_details(self, service: Service, status: str) -> Dict[str, Any]:
        base_details = {
            "checked_at": datetime.now().isoformat(),
            "service_type": service.service_type,
        }

        if service.service_type == "microservice":
            base_details.update({
                "cpu_usage": random.uniform(20, 95) if status != "healthy" else random.uniform(15, 60),
                "memory_usage": random.uniform(30, 95) if status != "healthy" else random.uniform(25, 70),
                "connections": random.randint(50, 300),
                "qps": random.randint(10, 500),
                "error_rate": round(random.uniform(0, 5), 2) if status == "warning" else round(random.uniform(0, 0.5), 2),
            })
        elif service.service_type == "database":
            base_details.update({
                "connections": random.randint(30, 150),
                "max_connections": 200,
                "slow_queries": random.randint(0, 15),
                "replication_delay": random.randint(0, 5) if status == "healthy" else random.randint(5, 30),
                "connection_pool_usage": f"{random.randint(15, 85)}%",
            })
        elif service.service_type == "cache":
            base_details.update({
                "connected_clients": random.randint(20, 100),
                "used_memory_human": f"{round(random.uniform(1.5, 4.0), 1)}G",
                "hit_rate": round(random.uniform(95, 99.9), 2),
                "evicted_keys": random.randint(0, 10),
            })
        elif service.service_type == "mq":
            base_details.update({
                "messages_ready": random.randint(0, 1000) if status == "healthy" else random.randint(5000, 20000),
                "messages_unacknowledged": random.randint(0, 500),
                "consumers": random.randint(3, 10),
                "queue_utilization": f"{random.randint(10, 95)}%",
            })

        if status == "critical":
            base_details["error"] = "Service unavailable"
            base_details["error_message"] = "Connection timeout or refused"

        return base_details

    async def _real_check(self, service: Service) -> HealthCheckResult:
        try:
            async with httpx.AsyncClient(timeout=settings.prometheus_timeout) as client:
                start = datetime.now()
                response = await client.get(service.health_endpoint)
                elapsed = int((datetime.now() - start).total_seconds() * 1000)

                if response.status_code >= 500:
                    status = "critical"
                elif response.status_code >= 400:
                    status = "warning"
                else:
                    try:
                        data = response.json()
                        if data.get("status") in ["down", "critical"]:
                            status = "critical"
                        elif data.get("status") in ["warn", "warning", "degraded"]:
                            status = "warning"
                        else:
                            status = "healthy"
                    except json.JSONDecodeError:
                        status = "healthy" if response.status_code < 400 else "warning"

                details = response.text if response.text else None

                return HealthCheckResult(
                    service_id=service.id,
                    status=status,
                    response_time_ms=elapsed,
                    details=details
                )
        except Exception as e:
            return HealthCheckResult(
                service_id=service.id,
                status="critical",
                response_time_ms=None,
                details=json.dumps({"error": str(e)}, ensure_ascii=False)
            )

    async def check_all_services(self) -> List[HealthCheckResult]:
        """检查所有已注册服务的健康状态，并将结果持久化到数据库。

        :return: 所有服务的检查结果列表
        """
        services = self.db.query(Service).all()
        results = []
        for service in services:
            result = await self.check_service(service)
            results.append(result)
            self._save_check_result(result)
            service.status = result.status
            service.last_check = datetime.now()
        self.db.commit()
        return results

    def _save_check_result(self, result: HealthCheckResult):
        check = HealthCheck(
            service_id=result.service_id,
            status=result.status,
            response_time_ms=result.response_time_ms,
            details=result.details,
        )
        self.db.add(check)
        self.db.commit()

    def get_all_services(self) -> List[Service]:
        return self.db.query(Service).all()

    def get_service_by_id(self, service_id: int) -> Optional[Service]:
        return self.db.query(Service).filter(Service.id == service_id).first()

    def get_service_status(self, service_id: int) -> Optional[Dict[str, Any]]:
        service = self.get_service_by_id(service_id)
        if not service:
            return None

        last_check = self.db.query(HealthCheck).filter(
            HealthCheck.service_id == service_id
        ).order_by(HealthCheck.checked_at.desc()).first()

        details = {}
        if last_check and last_check.details:
            try:
                details = json.loads(last_check.details)
            except (json.JSONDecodeError, TypeError):
                details = {"raw": last_check.details}

        return {
            "service": service,
            "last_check": last_check,
            "details": details,
        }

    def get_all_statuses(self) -> List[Dict[str, Any]]:
        services = self.get_all_services()
        statuses = []
        for service in services:
            status = self.get_service_status(service.id)
            if status:
                statuses.append(status)
        return statuses

    def get_recent_checks(self, service_id: int, limit: int = 20) -> List[HealthCheck]:
        return self.db.query(HealthCheck).filter(
            HealthCheck.service_id == service_id
        ).order_by(HealthCheck.checked_at.desc()).limit(limit).all()

    def get_check_history(self, service_id: int, hours: int = 1) -> List[HealthCheck]:
        """获取指定服务在过去N小时内的健康检查历史记录。

        时间窗口为 [now - hours, now]，左闭右闭。结果按 checked_at 升序排列，
        适用于 timeline 组件渲染。

        :param service_id: 服务ID
        :param hours: 回溯小时数，默认1
        :return: HealthCheck 对象列表，按时间升序排列
        """
        time_threshold = datetime.now() - timedelta(hours=hours)
        return self.db.query(HealthCheck).filter(
            HealthCheck.service_id == service_id,
            HealthCheck.checked_at >= time_threshold
        ).order_by(HealthCheck.checked_at.asc()).all()

    def create_service(self, data: ServiceCreate) -> Service:
        service = Service(
            name=data.name,
            service_type=data.service_type,
            health_endpoint=data.health_endpoint,
            check_interval=data.check_interval,
        )
        self.db.add(service)
        self.db.commit()
        self.db.refresh(service)
        return service

    def update_service(self, service_id: int, data: ServiceUpdate) -> Optional[Service]:
        service = self.get_service_by_id(service_id)
        if not service:
            return None
        for key, value in data.model_dump(exclude_unset=True).items():
            setattr(service, key, value)
        self.db.commit()
        self.db.refresh(service)
        return service

    def delete_service(self, service_id: int) -> bool:
        service = self.get_service_by_id(service_id)
        if not service:
            return False
        self.db.delete(service)
        self.db.commit()
        return True

    def get_summary(self) -> Dict[str, Any]:
        """获取所有服务的健康状态汇总计数。

        :return: 汇总字典，包含 total、healthy、warning、critical、unknown 计数
        """
        services = self.get_all_services()
        total = len(services)
        healthy = sum(1 for s in services if s.status == "healthy")
        warning = sum(1 for s in services if s.status == "warning")
        critical = sum(1 for s in services if s.status == "critical")
        unknown = sum(1 for s in services if s.status == "unknown")
        return {
            "total": total,
            "healthy": healthy,
            "warning": warning,
            "critical": critical,
            "unknown": unknown,
        }
