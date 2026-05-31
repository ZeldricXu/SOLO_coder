"""
示例6: 服务目录与发现模块
"""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from src.core import ServiceMetadata
from src.modules.service_discovery import (
    ServiceRegistry,
    ServiceCatalog,
    DependencyAnalyzer,
)


def main():
    print("=== 服务目录与发现示例 ===\n")

    registry = ServiceRegistry()
    catalog = ServiceCatalog(registry)
    analyzer = DependencyAnalyzer(registry)

    print("1. 注册服务:")

    user_svc = ServiceMetadata(
        name="user-service",
        type="service",
        description="用户管理服务",
        version="1.0.0",
        language="python",
        owner="team-alpha",
        repository="git@github.com:example/user-service.git",
        endpoints=[{"path": "/api/users", "method": "GET"}],
        dependencies=[],
        tags=["users", "auth", "python"],
    )
    registry.register(user_svc)
    print(f"   - {user_svc.name} ({user_svc.type})")

    order_svc = ServiceMetadata(
        name="order-service",
        type="service",
        description="订单管理服务",
        version="2.1.0",
        language="java",
        owner="team-beta",
        repository="git@github.com:example/order-service.git",
        endpoints=[{"path": "/api/orders", "method": "POST"}],
        dependencies=[user_svc.id],
        tags=["orders", "java", "payment"],
    )
    registry.register(order_svc)
    print(f"   - {order_svc.name} ({order_svc.type})")

    payment_svc = ServiceMetadata(
        name="payment-service",
        type="service",
        description="支付处理服务",
        version="1.5.0",
        language="javascript",
        owner="team-gamma",
        repository="git@github.com:example/payment-service.git",
        endpoints=[{"path": "/api/payments", "method": "POST"}],
        dependencies=[user_svc.id, order_svc.id],
        tags=["payment", "javascript"],
    )
    registry.register(payment_svc)
    print(f"   - {payment_svc.name} ({payment_svc.type})")

    common_lib = ServiceMetadata(
        name="common-utils",
        type="library",
        description="通用工具库",
        version="3.2.0",
        language="python",
        owner="team-platform",
        repository="git@github.com:example/common-utils.git",
        dependencies=[],
        tags=["library", "python", "utils"],
    )
    registry.register(common_lib)
    print(f"   - {common_lib.name} ({common_lib.type})")

    print("\n2. 搜索服务:")
    results = catalog.search(query="service", language="python")
    print(f"   搜索 'service' 且语言为 Python:")
    for svc in results:
        print(f"   - {svc.name} (owner: {svc.owner})")

    results = catalog.search(tags=["payment"])
    print(f"\n   标签 'payment':")
    for svc in results:
        print(f"   - {svc.name}")

    print("\n3. 统计信息:")
    stats = catalog.get_statistics()
    print(f"   总服务数: {stats['total_services']}")
    print(f"   按类型: {dict(stats['by_type'])}")
    print(f"   按语言: {dict(stats['by_language'])}")
    print(f"   所有标签: {stats['all_tags']}")

    print("\n4. 依赖关系分析:")
    print(f"   payment-service 的直接依赖:")
    deps = analyzer.get_dependencies(payment_svc.id)
    for dep in deps:
        print(f"   - {dep.name}")

    print(f"\n   payment-service 的所有传递依赖:")
    all_deps = analyzer.get_all_dependencies(payment_svc.id)
    for dep in all_deps:
        print(f"   - {dep.name}")

    print(f"\n   依赖 user-service 的服务:")
    dependents = analyzer.get_dependents(user_svc.id)
    for dep in dependents:
        print(f"   - {dep.name}")

    print(f"\n   payment-service 到 user-service 的依赖链:")
    chain = analyzer.get_dependency_chain(payment_svc.id, user_svc.id)
    for i, svc in enumerate(chain):
        prefix = "  -> " if i > 0 else "   "
        print(f"{prefix}{svc.name}")

    print("\n5. Mermaid 依赖图:")
    diagram = analyzer.generate_mermaid_diagram()
    print(diagram)


if __name__ == "__main__":
    main()
