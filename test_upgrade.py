#!/usr/bin/env python3
import sys
sys.path.insert(0, '.')

def test_read_write_router():
    print('=' * 60)
    print('验证1: 读写分离路由')
    print('=' * 60)
    from app.data.read_write_router import (
        ReadWriteRouter,
        RouteStrategy,
        RoutingDecision,
        RoutingRule,
        QueryClassifier,
        create_read_write_router,
        get_read_write_router,
        get_router_manager
    )

    assert QueryClassifier.is_read_only_query('SELECT * FROM users') == True
    assert QueryClassifier.is_read_only_query('INSERT INTO users VALUES (1)') == False
    assert QueryClassifier.is_read_only_query('UPDATE users SET name = ?') == False
    assert QueryClassifier.is_read_only_query('SHOW TABLES') == True
    assert QueryClassifier.is_read_only_query('BEGIN') == False
    print('  QueryClassifier: PASS')

    router = create_read_write_router(
        name='test',
        primary_pool='primary',
        strategy='auto',
        replicas=['replica1', 'replica2']
    )
    assert router.strategy == RouteStrategy.AUTO
    print(f'  Router strategy: {router.strategy.value}')

    result = router.decide(query='SELECT * FROM users')
    assert result.decision in (RoutingDecision.REPLICA, RoutingDecision.PRIMARY)
    print(f'  SELECT 路由决策: {result.decision.value} -> {result.pool_name}')

    result2 = router.decide(query='INSERT INTO users VALUES (1)')
    assert result2.decision == RoutingDecision.PRIMARY
    print(f'  INSERT 路由决策: {result2.decision.value} -> {result2.pool_name}')

    router.set_strategy(RouteStrategy.PRIMARY_ONLY)
    result3 = router.decide(query='SELECT * FROM users')
    assert result3.decision == RoutingDecision.PRIMARY
    print(f'  PRIMARY_ONLY 策略: {result3.decision.value}')

    router.set_strategy(RouteStrategy.AUTO)
    router.add_rule_by_pattern(
        pattern='SELECT.*FROM.*users',
        target=RoutingDecision.PRIMARY,
        priority=100,
        description='强制users表查询走主库'
    )
    result4 = router.decide(query='SELECT * FROM users')
    assert result4.decision == RoutingDecision.PRIMARY
    print(f'  路由规则匹配: {result4.decision.value}')

    router.set_replica_health('replica1', False)
    stats = router.get_stats()
    assert stats['replicas']['replica1']['healthy'] == False
    print(f'  副本健康状态设置: PASS')

    print('  读写分离路由: PASS')


def test_event_driven_notification():
    print()
    print('=' * 60)
    print('验证2: 事件驱动通知策略')
    print('=' * 60)
    from app.data.database import (
        PoolEvent,
        PoolEventType,
        PoolEventEmitter,
        emit_pool_event
    )

    events_received = []
    def test_listener(event: PoolEvent):
        events_received.append(event)

    emitter = PoolEventEmitter.get_instance()
    emitter.on(PoolEventType.CONNECTION_ACQUIRED, test_listener)
    emitter.on_all(lambda e: events_received.append(e))

    event = emit_pool_event(
        PoolEventType.CONNECTION_ACQUIRED,
        'test_pool',
        {'connection_id': 'conn_123'}
    )
    assert event.event_type == PoolEventType.CONNECTION_ACQUIRED
    assert event.pool_name == 'test_pool'
    print(f'  事件发射: {event.event_type.value}')

    recent = emitter.get_recent_events(pool_name='test_pool', limit=10)
    assert len(recent) >= 1
    print(f'  事件历史: {len(recent)} 条')

    stats = emitter.get_event_stats()
    assert 'total_events' in stats
    print(f'  事件统计: {stats}')

    print('  事件驱动通知: PASS')


def test_plugin_system():
    print()
    print('=' * 60)
    print('验证3: 监控插件化扩展')
    print('=' * 60)
    from app.monitoring.plugin import (
        BaseMetricsPlugin,
        ConsoleLoggingPlugin,
        StatsFilePlugin,
        ThresholdAlertPlugin,
        PluginManager,
        PluginStatus,
        get_plugin_manager,
        register_plugin,
        unregister_plugin,
        list_plugins
    )
    from datetime import datetime

    class CustomTestPlugin(BaseMetricsPlugin):
        def __init__(self):
            super().__init__(
                name='test_plugin',
                version='1.0.0',
                description='测试插件',
                priority=50
            )
            self.counter_calls = 0
            self.gauge_calls = 0
            self.histogram_calls = 0

        def on_counter(self, name, value, labels, timestamp):
            self.counter_calls += 1

        def on_gauge(self, name, value, labels, timestamp):
            self.gauge_calls += 1

        def on_histogram(self, name, value, labels, timestamp):
            self.histogram_calls += 1

    manager = get_plugin_manager()
    plugin = CustomTestPlugin()
    info = manager.register(plugin)
    assert info.name == 'test_plugin'
    assert info.status == PluginStatus.LOADED
    print(f'  插件注册: {info.name} v{info.version}')

    plugins = manager.list_all()
    assert len(plugins) >= 1
    print(f'  已注册插件数: {len(plugins)}')

    manager.notify_counter('test_counter', 5, {}, datetime.utcnow())
    manager.notify_gauge('test_gauge', 42, {}, datetime.utcnow())
    manager.notify_histogram('test_hist', 100, {}, datetime.utcnow())

    assert plugin.counter_calls == 1
    assert plugin.gauge_calls == 1
    assert plugin.histogram_calls == 1
    print('  插件通知回调: PASS')

    threshold_plugin = ThresholdAlertPlugin()
    threshold_plugin.set_threshold('memory_usage', 80.0)
    threshold_plugin.on_gauge('memory_usage', 90.0, {}, datetime.utcnow())

    alerts = threshold_plugin.get_recent_alerts()
    assert len(alerts) >= 1
    print(f'  阈值告警插件: 触发 {len(alerts)} 条告警')

    manager.disable('test_plugin')
    plugin_info = manager.get('test_plugin')
    assert plugin_info.info.enabled == False
    print('  插件禁用: PASS')

    manager.unregister('test_plugin')
    print('  插件卸载: PASS')

    print('  监控插件化: PASS')


def test_backward_compatibility():
    print()
    print('=' * 60)
    print('验证4: 原有功能兼容性')
    print('=' * 60)
    from app.monitoring.metrics import MetricsCollector, MetricType

    collector = MetricsCollector(use_plugins=True)
    collector.increment_counter('compatible_test', value=10)
    collector.set_gauge('compatible_gauge', 3.14)
    collector.record_histogram('compatible_hist', 50)

    snapshot = collector.snapshot()
    assert 'counters' in snapshot
    assert 'gauges' in snapshot
    assert 'histograms' in snapshot
    print('  MetricsCollector 原有功能: PASS')

    from app.config.manager import get_config_manager
    config = get_config_manager()
    config.set('compatibility', 'test_key', 'test_value')
    assert config.get('compatibility', 'test_key') == 'test_value'
    print('  配置管理原有功能: PASS')

    print()
    print('=' * 60)
    print('所有验证通过!')
    print('=' * 60)


if __name__ == '__main__':
    test_read_write_router()
    test_event_driven_notification()
    test_plugin_system()
    test_backward_compatibility()
