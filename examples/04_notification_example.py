"""
示例4: 通知模块 - 优先级与抑制策略
"""

import asyncio
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from src.infrastructure.notification import (
    NotificationManager,
    ConsoleNotification,
    EmailNotification,
    SlackNotification,
    RateLimitSuppression,
    DeduplicationSuppression,
    TimeWindowSuppression,
)


async def main():
    print("=== 通知模块示例 ===\n")

    console = ConsoleNotification()
    email = EmailNotification()
    slack = SlackNotification()

    manager = NotificationManager(channels=[console, email, slack])

    manager.add_suppression_strategy(RateLimitSuppression(max_messages=3, window_seconds=60))
    manager.add_suppression_strategy(DeduplicationSuppression(ttl_seconds=300))

    print("1. 不同优先级的通知渠道:")
    print("   LOW -> console")
    print("   NORMAL -> email")
    print("   HIGH -> slack, email")
    print("   URGENT -> email, slack, console")

    print("\n2. 发送不同优先级的通知:")

    await manager.send(
        recipient="user@example.com",
        title="系统维护通知",
        content="系统将于本周末进行例行维护",
        priority="low",
    )

    await manager.send(
        recipient="dev-team@example.com",
        title="每日构建成功",
        content="项目构建成功，所有测试通过",
        priority="normal",
    )

    await manager.send(
        recipient="#alerts",
        title="CPU使用率告警",
        content="服务器 app-01 CPU使用率达 85%",
        priority="high",
    )

    await manager.send(
        recipient="oncall@example.com",
        title="数据库连接失败",
        content="生产数据库连接失败，需要立即处理",
        priority="urgent",
    )

    print("\n3. 测试去重抑制 (发送相同内容3次):")
    for i in range(3):
        result = await manager.send(
            recipient="user@example.com",
            title="重复通知",
            content="这是重复的通知内容",
            priority="normal",
        )
        status = "被抑制" if result.get("suppressed") else "已发送"
        print(f"   第 {i+1} 次: {status}")

    print("\n4. 测试速率限制 (发送5次通知):")
    manager2 = NotificationManager(
        channels=[console],
        suppression_strategies=[RateLimitSuppression(max_messages=2, window_seconds=60)],
    )
    for i in range(5):
        result = await manager2.send(
            recipient="test@example.com",
            title=f"测试通知 {i+1}",
            content=f"这是第 {i+1} 条测试通知",
            priority="low",
        )
        status = "被抑制" if result.get("suppressed") else "已发送"
        print(f"   通知 {i+1}: {status}")


if __name__ == "__main__":
    asyncio.run(main())
