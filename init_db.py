#!/usr/bin/env python3

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.database import Base, engine, SessionLocal
from app.models import (
    User, Service, HealthCheck, AlertRule, AlertHistory,
    SlowSQL, SQLExplain, Asset, AssetChangeLog, DutySchedule,
    HandoverReport, Preference, PinnedComponent, LogTemplate,
)
from datetime import date, timedelta


def init_db():
    print("Creating database tables...")
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    print("Tables created successfully!")

    db = SessionLocal()

    print("Inserting initial data...")

    users = [
        User(username="admin", real_name="系统管理员", role="admin", email="admin@example.com", phone="13800138000"),
        User(username="operator1", real_name="张三", role="operator", email="zhangsan@example.com", phone="13800138001"),
        User(username="operator2", real_name="李四", role="operator", email="lisi@example.com", phone="13800138002"),
        User(username="viewer1", real_name="王五", role="viewer", email="wangwu@example.com", phone="13800138003"),
    ]
    db.add_all(users)
    db.flush()

    services = [
        Service(name="订单服务", service_type="microservice", health_endpoint="http://order-service:8080/health", check_interval=30, status="healthy"),
        Service(name="用户服务", service_type="microservice", health_endpoint="http://user-service:8080/health", check_interval=30, status="healthy"),
        Service(name="支付服务", service_type="microservice", health_endpoint="http://pay-service:8080/health", check_interval=30, status="warning"),
        Service(name="MySQL主库", service_type="database", health_endpoint="http://db-proxy:9090/health", check_interval=60, status="healthy"),
        Service(name="Redis集群", service_type="cache", health_endpoint="http://redis-proxy:9090/health", check_interval=60, status="healthy"),
        Service(name="RabbitMQ", service_type="mq", health_endpoint="http://mq-proxy:15672/health", check_interval=60, status="critical"),
    ]
    db.add_all(services)
    db.flush()

    health_checks = [
        HealthCheck(service_id=1, status="healthy", response_time_ms=45, details='{"cpu": 35, "memory": 60, "connections": 120}'),
        HealthCheck(service_id=2, status="healthy", response_time_ms=38, details='{"cpu": 28, "memory": 55, "connections": 95}'),
        HealthCheck(service_id=3, status="warning", response_time_ms=280, details='{"cpu": 78, "memory": 85, "connections": 250, "error_rate": 2.5}'),
        HealthCheck(service_id=4, status="healthy", response_time_ms=12, details='{"connections": 80, "slow_queries": 3, "replication_delay": 0}'),
        HealthCheck(service_id=5, status="healthy", response_time_ms=8, details='{"connected_clients": 45, "used_memory_human": "2.3G", "hit_rate": 99.5}'),
        HealthCheck(service_id=6, status="critical", response_time_ms=None, details='{"error": "Connection refused", "messages_ready": 15230, "consumers": 0}'),
    ]
    db.add_all(health_checks)

    alert_rules = [
        AlertRule(name="5分钟错误率超3%且请求量>100", level="P1", condition_expr="error_rate > 3 AND qps > 100", window_seconds=300, threshold=3.0, notification_channels="dingtalk,webhook", enabled=True),
        AlertRule(name="CPU使用率超90%", level="P2", condition_expr="cpu_usage > 90", window_seconds=300, threshold=90.0, notification_channels="dingtalk", enabled=True),
        AlertRule(name="内存使用率超95%", level="P0", condition_expr="memory_usage > 95", window_seconds=60, threshold=95.0, notification_channels="phone,dingtalk", enabled=True),
        AlertRule(name="磁盘使用率超85%", level="P3", condition_expr="disk_usage > 85", window_seconds=3600, threshold=85.0, notification_channels="dingtalk", enabled=True),
        AlertRule(name="慢查询>100ms超过10次/分钟", level="P2", condition_expr="slow_sql_count > 10", window_seconds=60, threshold=10.0, notification_channels="dingtalk", enabled=True),
    ]
    db.add_all(alert_rules)
    db.flush()

    alert_histories = [
        AlertHistory(rule_id=3, service_id=6, level="P0", message="RabbitMQ服务不可用，消息积压严重，请立即处理！", status="firing"),
        AlertHistory(rule_id=1, service_id=3, level="P1", message="支付服务错误率升高，当前2.5%", status="firing", ack_user_id=2),
        AlertHistory(rule_id=2, service_id=3, level="P2", message="支付服务CPU使用率78%", status="resolved"),
    ]
    db.add_all(alert_histories)

    slow_sqls = [
        SlowSQL(fingerprint="a1b2c3d4e5f6", table_name="orders", sql_text="SELECT * FROM orders WHERE user_id = ? AND status = ? ORDER BY created_at DESC", avg_duration_ms=456, exec_count=1250),
        SlowSQL(fingerprint="b2c3d4e5f6a1", table_name="users", sql_text="SELECT * FROM users WHERE phone = ?", avg_duration_ms=1234, exec_count=890),
        SlowSQL(fingerprint="c3d4e5f6a1b2", table_name="payments", sql_text="SELECT COUNT(*) FROM payments WHERE status = 'pending' AND created_at > ?", avg_duration_ms=890, exec_count=450),
        SlowSQL(fingerprint="d4e5f6a1b2c3", table_name="order_items", sql_text="SELECT oi.*, p.name FROM order_items oi JOIN products p ON oi.product_id = p.id WHERE oi.order_id IN (?)", avg_duration_ms=678, exec_count=2340),
        SlowSQL(fingerprint="e5f6a1b2c3d4", table_name="products", sql_text="SELECT * FROM products WHERE category_id = ? AND stock > 0 ORDER BY sold_count DESC LIMIT ?, ?", avg_duration_ms=345, exec_count=5670),
    ]
    db.add_all(slow_sqls)
    db.flush()

    sql_explains = [
        SQLExplain(slow_sql_id=1, plan_json='{"type": "ALL", "rows": 125000, "key": null, "extra": "Using filesort"}', analysis="缺少user_id+status+created_at联合索引，导致全表扫描和文件排序"),
        SQLExplain(slow_sql_id=2, plan_json='{"type": "ALL", "rows": 890000, "key": null, "extra": null}', analysis="phone字段无索引，全表扫描。建议在phone字段添加唯一索引"),
        SQLExplain(slow_sql_id=3, plan_json='{"type": "range", "rows": 12000, "key": "idx_status_created", "extra": "Using where"}', analysis="索引使用正常，但数据量较大。考虑按时间分区或增加status过滤条件"),
    ]
    db.add_all(sql_explains)

    assets = [
        Asset(name="web-01", category="server", ip="192.168.1.10", port=22, version="CentOS 7.9", owner="张三", status="normal"),
        Asset(name="web-02", category="server", ip="192.168.1.11", port=22, version="CentOS 7.9", owner="张三", status="normal"),
        Asset(name="db-master", category="database", ip="192.168.1.20", port=3306, version="MySQL 8.0", owner="李四", status="normal"),
        Asset(name="db-slave", category="database", ip="192.168.1.21", port=3306, version="MySQL 8.0", owner="李四", status="normal"),
        Asset(name="redis-01", category="cache", ip="192.168.1.30", port=6379, version="Redis 7.0", owner="王五", status="normal"),
        Asset(name="mq-01", category="mq", ip="192.168.1.40", port=5672, version="RabbitMQ 3.12", owner="王五", status="warning"),
    ]
    db.add_all(assets)
    db.flush()

    asset_change_logs = [
        AssetChangeLog(asset_id=1, field_name="version", old_value="CentOS 7.8", new_value="CentOS 7.9", operator_id=1),
        AssetChangeLog(asset_id=4, field_name="owner", old_value="赵六", new_value="李四", operator_id=1),
        AssetChangeLog(asset_id=6, field_name="status", old_value="normal", new_value="warning", operator_id=2),
    ]
    db.add_all(asset_change_logs)

    today = date.today()
    duty_schedules = []
    users_for_duty = [2, 3]
    shift_types = ["day", "night"]
    for i in range(14):
        duty_date = today + timedelta(days=i - 7)
        user_idx = i % 2
        shift_idx = (i // 2) % 2
        duty_schedules.append(
            DutySchedule(user_id=users_for_duty[user_idx], duty_date=duty_date, shift_type=shift_types[shift_idx])
        )
    db.add_all(duty_schedules)
    db.flush()

    handover_reports = [
        HandoverReport(schedule_id=1, from_user_id=2, to_user_id=3, content="当班期间系统运行正常。RabbitMQ服务于14:30出现异常，已联系相关人员处理，目前仍在恢复中。支付服务CPU使用率较高，建议关注。"),
    ]
    db.add_all(handover_reports)

    preferences = [
        Preference(user_id=2, layout_config='{"grid_cols": 3, "theme": "dark"}'),
        Preference(user_id=3, layout_config='{"grid_cols": 2, "theme": "dark"}'),
    ]
    db.add_all(preferences)
    db.flush()

    pinned_components = [
        PinnedComponent(preference_id=1, component_type="health_summary", component_key="all", position=0),
        PinnedComponent(preference_id=1, component_type="alert_list", component_key="firing", position=1),
        PinnedComponent(preference_id=1, component_type="metric_chart", component_key="cpu_usage", position=2),
        PinnedComponent(preference_id=1, component_type="slow_sql_top", component_key="top10", position=3),
        PinnedComponent(preference_id=2, component_type="health_summary", component_key="all", position=0),
        PinnedComponent(preference_id=2, component_type="alert_list", component_key="firing", position=1),
    ]
    db.add_all(pinned_components)

    log_templates = [
        LogTemplate(user_id=2, name="支付错误日志", query_config='{"keyword": "error", "service_name": "pay-service", "level": "ERROR"}'),
        LogTemplate(user_id=2, name="订单慢查询", query_config='{"keyword": "slow query", "service_name": "order-service"}'),
    ]
    db.add_all(log_templates)

    db.commit()
    db.close()

    print("Initial data inserted successfully!")
    print("Database initialization complete!")


if __name__ == "__main__":
    init_db()
