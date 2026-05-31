import sys
import time
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

print("=" * 60)
print("性能重构验证测试")
print("=" * 60)


def test_pool_event_emitter():
    print("\n[测试1] PoolEventEmitter 性能优化验证")

    from app.data.database import (
        PoolEventEmitter, PoolEvent, PoolEventType, emit_pool_event
    )

    emitter = PoolEventEmitter.get_instance()
    emitter._event_history.clear()
    emitter._type_counts.clear()
    emitter._pool_counts.clear()

    call_count = {"test": 0}

    def listener(event):
        call_count["test"] += 1

    emitter.on(PoolEventType.QUERY_EXECUTED, listener)

    print(f"  - 添加监听器: OK")

    start = time.perf_counter()
    for i in range(10000):
        emit_pool_event(
            PoolEventType.QUERY_EXECUTED,
            "test_pool",
            {"query_index": i}
        )
    elapsed = (time.perf_counter() - start) * 1000

    print(f"  - 10,000次事件发射: {elapsed:.2f}ms")

    stats = emitter.get_event_stats()
    assert stats["total_events"] >= 10000, f"事件统计错误: {stats}"
    assert stats["by_type"]["query.executed"] >= 10000
    print(f"  - 事件统计: OK")

    recent = emitter.get_recent_events(PoolEventType.QUERY_EXECUTED, limit=5)
    assert len(recent) == 5
    print(f"  - 事件历史: OK")

    assert call_count["test"] == 10000, f"监听器调用次数错误: {call_count['test']}"
    print(f"  - 监听器调用: OK (10,000次)")

    print(f"[测试1] 通过 ✓")


def test_read_write_router_performance():
    print("\n[测试2] ReadWriteRouter 性能优化验证")

    from app.data.read_write_router import (
        ReadWriteRouter, RouteStrategy, RoutingDecision, QueryClassifier
    )

    print(f"  - QueryClassifier 前缀匹配测试...")

    test_queries = [
        ("SELECT * FROM users", True),
        ("INSERT INTO users VALUES (1)", False),
        ("UPDATE users SET name = 'test'", False),
        ("SHOW TABLES", True),
        ("BEGIN TRANSACTION", False),
        ("COMMIT", False),
    ]

    for query, expected in test_queries:
        result = QueryClassifier.is_read_only_query(query)
        assert result == expected, f"QueryClassifier 错误: {query} -> {result}"

    print(f"  - QueryClassifier: OK ({len(test_queries)} 个测试用例)")

    router = ReadWriteRouter(
        primary_pool_name="primary",
        strategy=RouteStrategy.AUTO,
        enable_replicas=["replica1", "replica2"]
    )

    print(f"  - 路由决策性能测试...")

    start = time.perf_counter()
    for i in range(10000):
        result = router.decide(query="SELECT * FROM users")
        assert result.decision in (RoutingDecision.PRIMARY, RoutingDecision.REPLICA)
    elapsed = (time.perf_counter() - start) * 1000

    print(f"  - 10,000次路由决策: {elapsed:.2f}ms")

    stats = router.get_stats()
    assert stats["decision_stats"]["primary_count"] + stats["decision_stats"]["replica_count"] == 10000
    print(f"  - 路由统计: OK")

    print(f"[测试2] 通过 ✓")


def test_router_concurrent():
    print("\n[测试3] ReadWriteRouter 并发验证")

    from app.data.read_write_router import ReadWriteRouter, RouteStrategy, QueryClassifier

    router = ReadWriteRouter(
        primary_pool_name="primary",
        strategy=RouteStrategy.AUTO,
        enable_replicas=["replica1", "replica2"]
    )

    results = []

    def worker(start_query, count):
        for i in range(count):
            q = f"SELECT * FROM users WHERE id = {start_query + i}"
            result = router.decide(query=q)
            results.append(result)

    threads = []
    start = time.perf_counter()

    for i in range(10):
        t = threading.Thread(target=worker, args=(i * 1000, 1000))
        threads.append(t)
        t.start()

    for t in threads:
        t.join()

    elapsed = (time.perf_counter() - start) * 1000

    assert len(results) == 10000, f"并发结果数错误: {len(results)}"
    print(f"  - 10线程 × 1000次 = 10,000次并发决策: {elapsed:.2f}ms")

    stats = router.get_stats()
    assert stats["decision_stats"]["primary_count"] + stats["decision_stats"]["replica_count"] == 10000
    print(f"  - 并发统计一致: OK")

    print(f"[测试3] 通过 ✓")


def test_template_engine():
    print("\n[测试4] TemplateEngine 性能优化验证")

    from app.scaffold.generator import (
        TemplateEngine, _snake_case, _camel_case, _pascal_case, _kebab_case
    )

    print(f"  - 字符串转换函数测试...")

    test_cases = [
        ("ProjectName", "project_name", "projectName", "ProjectName", "project-name"),
        ("my-api-service", "my_api_service", "myApiService", "MyApiService", "my-api-service"),
        ("HTTP Client", "http_client", "httpClient", "HttpClient", "http-client"),
    ]

    for input_val, snake, camel, pascal, kebab in test_cases:
        assert _snake_case(input_val) == snake, f"snake_case 错误: {input_val}"
        assert _camel_case(input_val) == camel, f"camel_case 错误: {input_val}"
        assert _pascal_case(input_val) == pascal, f"pascal_case 错误: {input_val}"
        assert _kebab_case(input_val) == kebab, f"kebab_case 错误: {input_val}"

    print(f"  - 字符串转换: OK ({len(test_cases)} 个测试用例)")

    engine = TemplateEngine.get_instance()

    print(f"  - 模板渲染性能测试...")

    template_str = "Hello, {{ name | pascal_case }}! Version: {{ version }}"
    context = {"name": "test project", "version": "1.0.0"}

    start = time.perf_counter()
    for i in range(10000):
        result = engine.render_string(template_str, context)
    elapsed = (time.perf_counter() - start) * 1000

    assert result == "Hello, TestProject! Version: 1.0.0"
    print(f"  - 10,000次模板渲染: {elapsed:.2f}ms")

    template_without_vars = "This is a plain text with no variables"
    start = time.perf_counter()
    for i in range(10000):
        result = engine.render_string(template_without_vars, {})
    elapsed_no_vars = (time.perf_counter() - start) * 1000

    print(f"  - 10,000次无变量文本(短路): {elapsed_no_vars:.2f}ms")

    print(f"[测试4] 通过 ✓")


def test_connection_pool_stats():
    print("\n[测试5] ConnectionPool 统计优化验证")

    from app.data.database import ConnectionPool, PoolStatus, PoolStats

    pool = ConnectionPool(
        name="test",
        dsn="sqlite+aiosqlite:///:memory:"
    )

    assert pool._stats.health_status == PoolStatus.HEALTHY
    assert pool._stats.total_acquired == 0
    assert pool._stats.total_released == 0
    print(f"  - 初始状态: OK")

    stats_obj = pool._stats
    stats_obj.total_acquired = 100
    stats_obj.total_released = 95
    stats_obj.total_errors = 2
    stats_obj.total_timeouts = 3

    stats_dict = pool.get_stats()

    assert stats_dict["total_acquired"] == 100
    assert stats_dict["total_released"] == 95
    assert stats_dict["total_errors"] == 2
    assert stats_dict["total_timeouts"] == 3
    print(f"  - 统计数据读取: OK")

    print(f"[测试5] 通过 ✓")


def test_pattern_compilation():
    print("\n[测试6] 忽略模式预编译验证")

    from app.scaffold.generator import _compile_ignore_patterns

    patterns = ["__pycache__", "*.pyc", ".git"]
    matcher = _compile_ignore_patterns(patterns)

    test_cases = [
        ("__pycache__", True),
        ("test.pyc", True),
        (".git", True),
        ("main.py", False),
        ("__init__.py", False),
        ("test.py", False),
    ]

    for name, expected in test_cases:
        result = matcher(name)
        assert result == expected, f"模式匹配错误: {name} -> {result}"

    print(f"  - 模式编译和匹配: OK ({len(test_cases)} 个测试用例)")

    print(f"[测试6] 通过 ✓")


def test_router_strategy():
    print("\n[测试7] 路由策略验证")

    from app.data.read_write_router import ReadWriteRouter, RouteStrategy, RoutingDecision

    router = ReadWriteRouter(
        primary_pool_name="primary",
        strategy=RouteStrategy.AUTO,
        enable_replicas=["replica1", "replica2"]
    )

    result = router.decide(query="SELECT * FROM users")
    print(f"  - AUTO策略 + SELECT查询: {result.decision.value}")
    assert result.decision in (RoutingDecision.PRIMARY, RoutingDecision.REPLICA)

    router.set_strategy(RouteStrategy.PRIMARY_ONLY)
    result = router.decide(query="SELECT * FROM users")
    assert result.decision == RoutingDecision.PRIMARY
    print(f"  - PRIMARY_ONLY策略: {result.decision.value}")

    router.set_strategy(RouteStrategy.READ_REPLICA_ONLY)
    result = router.decide(query="SELECT * FROM users")
    assert result.decision == RoutingDecision.REPLICA
    print(f"  - READ_REPLICA_ONLY策略: {result.decision.value}")

    router.set_strategy(RouteStrategy.READ_ONLY)
    result = router.decide(query="SELECT * FROM users", force_read_only=True)
    assert result.decision == RoutingDecision.REPLICA
    print(f"  - READ_ONLY + force_read_only: {result.decision.value}")

    result = router.decide(query="SELECT * FROM users", in_transaction=True)
    assert result.decision == RoutingDecision.PRIMARY
    print(f"  - READ_ONLY + in_transaction: {result.decision.value}")

    print(f"[测试7] 通过 ✓")


def test_performance_comparison():
    print("\n[测试8] 性能对比基准测试")

    from app.data.read_write_router import ReadWriteRouter, RouteStrategy, QueryClassifier

    print(f"\n  QueryClassifier 性能对比:")
    queries = [
        "SELECT * FROM users WHERE id = ?",
        "INSERT INTO users VALUES (?, ?, ?)",
        "UPDATE users SET name = ? WHERE id = ?",
        "DELETE FROM users WHERE id = ?",
        "SHOW TABLES",
        "BEGIN TRANSACTION",
    ]

    start = time.perf_counter()
    for _ in range(100000):
        for q in queries:
            QueryClassifier.is_read_only_query(q)
    elapsed = (time.perf_counter() - start) * 1000
    print(f"    600,000次查询分类: {elapsed:.2f}ms")

    print(f"\n  路由决策性能:")
    router = ReadWriteRouter(
        primary_pool_name="primary",
        strategy=RouteStrategy.AUTO,
        enable_replicas=["replica1", "replica2", "replica3"]
    )

    start = time.perf_counter()
    for i in range(50000):
        result = router.decide(query="SELECT * FROM users WHERE id = ?")
    elapsed = (time.perf_counter() - start) * 1000
    print(f"    50,000次路由决策: {elapsed:.2f}ms")

    print(f"\n  事件发射性能:")
    from app.data.database import PoolEventEmitter, PoolEvent, PoolEventType

    emitter = PoolEventEmitter.get_instance()

    start = time.perf_counter()
    for i in range(50000):
        event = PoolEvent(
            event_type=PoolEventType.QUERY_EXECUTED,
            pool_name="test",
            timestamp=__import__('datetime').datetime.utcnow(),
            metadata={"i": i},
            event_id=f"evt_{i}"
        )
        emitter.emit(event)
    elapsed = (time.perf_counter() - start) * 1000
    print(f"    50,000次事件发射: {elapsed:.2f}ms")

    print(f"\n  模板渲染性能:")
    from app.scaffold.generator import TemplateEngine

    engine = TemplateEngine.get_instance()

    start = time.perf_counter()
    for i in range(20000):
        result = engine.render_string(
            "Hello {{ name | pascal_case }}!",
            {"name": "test project"}
        )
    elapsed = (time.perf_counter() - start) * 1000
    print(f"    20,000次模板渲染: {elapsed:.2f}ms")

    print(f"\n[测试8] 通过 ✓")


def main():
    print(f"\nPython 版本: {sys.version.split()[0]}")
    print(f"测试开始时间: {time.strftime('%Y-%m-%d %H:%M:%S')}")

    tests = [
        test_pool_event_emitter,
        test_read_write_router_performance,
        test_router_concurrent,
        test_template_engine,
        test_connection_pool_stats,
        test_pattern_compilation,
        test_router_strategy,
        test_performance_comparison,
    ]

    failed = []
    for test in tests:
        try:
            test()
        except Exception as e:
            print(f"\n  ❌ 失败: {test.__name__}: {e}")
            import traceback
            traceback.print_exc()
            failed.append(test.__name__)

    print("\n" + "=" * 60)
    if failed:
        print(f"测试完成，{len(failed)} 个失败: {failed}")
        return 1
    else:
        print(f"所有 {len(tests)} 个测试通过 ✓")
        print("=" * 60)
        return 0


if __name__ == "__main__":
    sys.exit(main())
