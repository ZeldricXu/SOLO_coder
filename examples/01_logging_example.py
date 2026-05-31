"""
示例1: 结构化日志模块使用
展示如何使用 StructuredLogger 进行日志输出和链路追踪
"""

import sys
import os
import json

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from src.core import LogLevel
from src.infrastructure.logging import (
    StructuredLogger,
    ConsoleHandler,
    FileHandler,
    JsonFormatter,
    TextFormatter,
)


def main():
    print("=== 结构化日志示例 ===\n")

    print("1. 文本格式日志 (控制台输出):")
    console_handler = ConsoleHandler(level=LogLevel.INFO)
    console_handler.set_formatter(TextFormatter())

    logger = StructuredLogger(
        service_name="order-service",
        handlers=[console_handler],
    )

    logger.debug("这条消息不会显示，因为级别是 DEBUG")
    logger.info("订单创建成功", order_id="ORD-12345", user_id="USR-67890", amount=299.99)
    logger.warning("库存不足", product_id="PROD-456", remaining=5)
    logger.error("支付失败", order_id="ORD-12345", error_code="INSUFFICIENT_FUNDS")

    print("\n2. JSON 格式日志 (控制台输出):")
    json_console = ConsoleHandler(level=LogLevel.DEBUG)
    json_console.set_formatter(JsonFormatter())

    json_logger = StructuredLogger(
        service_name="payment-service",
        handlers=[json_console],
    )

    json_logger.info("支付处理中", order_id="ORD-12345", amount=299.99)
    json_logger.debug("支付网关响应", gateway="stripe", status="success")

    print("\n3. 带链路追踪的日志:")
    trace_logger = logger.with_trace(
        trace_id="trace-abc-123",
        span_id="span-xyz-789",
        parent_span_id="span-parent-456",
    )

    trace_logger.info("开始处理请求", path="/api/orders", method="POST")
    trace_logger.info("验证用户身份", user_id="USR-67890")
    trace_logger.info("扣减库存成功", product_id="PROD-456", quantity=1)

    print("\n4. 日志文件输出:")
    log_file = os.path.join(os.path.dirname(__file__), "output", "app.log")
    os.makedirs(os.path.dirname(log_file), exist_ok=True)

    file_handler = FileHandler(
        level=LogLevel.INFO,
        file_path=log_file,
    )
    file_handler.set_formatter(JsonFormatter())

    file_logger = StructuredLogger(
        service_name="file-log-demo",
        handlers=[file_handler, console_handler],
    )

    file_logger.info("这条日志会同时输出到文件和控制台")
    file_logger.info("操作完成", duration_ms=156, status="success")

    print(f"   日志文件: {log_file}")
    with open(log_file, "r", encoding="utf-8") as f:
        lines = f.readlines()
        print(f"   文件行数: {len(lines)}")
        if lines:
            print(f"   最后一行: {lines[-1][:100]}...")

    print("\n5. 结构化上下文字段:")
    user_logger = logger.with_context(
        user_id="USR-67890",
        session_id="sess-abc123",
        request_id="req-xyz789",
    )

    user_logger.info("用户登录")
    user_logger.info("查看个人资料")
    user_logger.info("修改密码", ip_address="192.168.1.100")

    print("\n6. 不同日志级别的演示:")
    levels = [
        (LogLevel.DEBUG, "调试信息"),
        (LogLevel.INFO, "普通信息"),
        (LogLevel.WARNING, "警告信息"),
        (LogLevel.ERROR, "错误信息"),
        (LogLevel.CRITICAL, "严重错误"),
    ]

    test_logger = StructuredLogger(
        service_name="level-demo",
        handlers=[ConsoleHandler(level=LogLevel.DEBUG, formatter=TextFormatter())],
    )

    for level, message in levels:
        test_logger.log(level, message, code=level.value)


if __name__ == "__main__":
    main()
