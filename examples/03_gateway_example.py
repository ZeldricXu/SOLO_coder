"""
示例3: API网关 - 请求日志与链路追踪
"""

import asyncio
import sys
import os
import json

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from src.core import LogLevel
from src.infrastructure.logging import (
    StructuredLogger,
    ConsoleHandler,
    TextFormatter,
)
from src.modules.api_gateway import (
    ApiGateway,
    SimpleRequest,
    SimpleResponse,
    RateLimitMiddleware,
    AuthMiddleware,
)


async def echo_handler(request):
    return SimpleResponse(
        status_code=200,
        body=json.dumps({"echo": request.path, "params": request.query_params}).encode(),
        headers={"Content-Type": "application/json"},
    )


async def user_handler(request):
    return SimpleResponse(
        status_code=200,
        body=json.dumps({"user_id": "123", "name": "Alice"}).encode(),
        headers={"Content-Type": "application/json"},
    )


async def main():
    print("=== API网关示例 ===\n")

    console_handler = ConsoleHandler(level=LogLevel.INFO)
    console_handler.set_formatter(TextFormatter())
    logger = StructuredLogger(service_name="api-gateway", handlers=[console_handler])

    gateway = ApiGateway(logger=logger)

    gateway.add_middleware(AuthMiddleware(
        api_keys={"valid-key-123": "user-alice", "test-key-456": "user-bob"},
        logger=logger,
    ))
    gateway.add_middleware(RateLimitMiddleware(
        max_requests=5,
        window_seconds=60,
        logger=logger,
    ))

    gateway.register_handler("/api/echo", echo_handler)
    gateway.register_handler("/api/users/me", user_handler)

    print("1. 成功的请求 (带认证):")
    req1 = SimpleRequest(
        method="GET",
        path="/api/echo",
        query_params={"message": "hello"},
        headers={"Authorization": "Bearer valid-key-123"},
    )
    resp1 = await gateway.process_request(req1)
    print(f"   状态码: {resp1.status_code}")
    print(f"   Trace-ID: {resp1.headers.get('X-Trace-Id')}")
    print(f"   响应: {resp1.body.decode()}")

    trace_id = resp1.headers.get('X-Trace-Id')
    print(f"\n2. 查看链路追踪信息 (trace_id={trace_id}):")
    trace_info = gateway.get_trace_info(trace_id)
    print(json.dumps(trace_info, indent=2, ensure_ascii=False))

    print("\n3. 未授权的请求:")
    req2 = SimpleRequest(
        method="GET",
        path="/api/users/me",
        headers={},
    )
    resp2 = await gateway.process_request(req2)
    print(f"   状态码: {resp2.status_code}")
    print(f"   响应: {resp2.body.decode()}")

    print("\n4. 无效API Key:")
    req3 = SimpleRequest(
        method="GET",
        path="/api/users/me",
        headers={"Authorization": "Bearer invalid-key"},
    )
    resp3 = await gateway.process_request(req3)
    print(f"   状态码: {resp3.status_code}")
    print(f"   响应: {resp3.body.decode()}")

    print("\n5. 不存在的路径:")
    req4 = SimpleRequest(
        method="GET",
        path="/api/nonexistent",
        headers={"Authorization": "Bearer valid-key-123"},
    )
    resp4 = await gateway.process_request(req4)
    print(f"   状态码: {resp4.status_code}")

    print("\n6. 测试限流 (发送6个请求):")
    for i in range(6):
        req = SimpleRequest(
            method="GET",
            path="/api/echo",
            query_params={"i": str(i)},
            headers={"Authorization": "Bearer test-key-456", "X-Forwarded-For": "192.168.1.100"},
        )
        resp = await gateway.process_request(req)
        status = "限流" if resp.status_code == 429 else "成功"
        print(f"   请求 {i+1}: {status} (状态码 {resp.status_code})")


if __name__ == "__main__":
    asyncio.run(main())
