import random
from datetime import datetime, timedelta

import factory
from factory import Faker, LazyAttribute
from factory.alchemy import SQLAlchemyModelFactory

from app.database import Base, engine, SessionLocal
from app.models import (
    User, Service, HealthCheck, AlertRule, AlertHistory,
    SlowSQL, SQLExplain, Asset, AssetChangeLog, DutySchedule,
    HandoverReport, Preference, PinnedComponent, LogTemplate,
)


Session = SessionLocal()


class BaseFactory(SQLAlchemyModelFactory):
    class Meta:
        abstract = True
        sqlalchemy_session = Session
        sqlalchemy_session_persistence = "commit"


class UserFactory(BaseFactory):
    class Meta:
        model = User

    username = Faker("user_name")
    real_name = Faker("name")
    email = Faker("email")
    phone = Faker("phone_number")
    role = factory.LazyFunction(lambda: random.choice(["admin", "operator", "viewer"]))


class ServiceFactory(BaseFactory):
    class Meta:
        model = Service

    name = factory.Sequence(lambda n: f"服务-{n}")
    service_type = factory.LazyFunction(lambda: random.choice([
        "microservice", "database", "cache", "mq", "load_balancer"
    ]))
    health_endpoint = factory.LazyAttribute(
        lambda o: f"http://{o.name.lower().replace(' ', '-')}:8080/health"
    )
    check_interval = factory.LazyFunction(lambda: random.choice([15, 30, 60, 120]))
    status = factory.LazyFunction(lambda: random.choice([
        "healthy", "healthy", "healthy", "warning", "critical"
    ]))


class HealthCheckFactory(BaseFactory):
    class Meta:
        model = HealthCheck

    service = factory.SubFactory(ServiceFactory)
    service_id = factory.SelfAttribute("service.id")
    status = factory.LazyFunction(lambda: random.choice([
        "healthy", "healthy", "healthy", "warning", "critical"
    ]))
    response_time_ms = factory.LazyAttribute(
        lambda o: random.randint(10, 500) if o.status != "critical" else None
    )
    details = factory.LazyFunction(lambda: {
        "cpu": random.randint(20, 90),
        "memory": random.randint(30, 95),
        "connections": random.randint(10, 300),
    })
    checked_at = factory.LazyFunction(lambda: datetime.now() - timedelta(seconds=random.randint(0, 300)))


class AlertRuleFactory(BaseFactory):
    class Meta:
        model = AlertRule

    name = factory.Sequence(lambda n: f"告警规则-{n}")
    level = factory.LazyFunction(lambda: random.choice(["P0", "P1", "P2", "P3"]))
    condition_expr = factory.LazyFunction(lambda: random.choice([
        "cpu_usage > 90",
        "memory_usage > 95",
        "error_rate > 3 AND qps > 100",
        "disk_usage > 85",
        "response_time > 500",
    ]))
    window_seconds = factory.LazyFunction(lambda: random.choice([60, 300, 600, 1800]))
    threshold = factory.LazyFunction(lambda: random.uniform(1.0, 100.0))
    notification_channels = factory.LazyFunction(
        lambda: ",".join(random.sample(["dingtalk", "wechat", "phone", "webhook"], 2))
    )
    enabled = True


class AlertHistoryFactory(BaseFactory):
    class Meta:
        model = AlertHistory

    rule = factory.SubFactory(AlertRuleFactory)
    rule_id = factory.SelfAttribute("rule.id")
    service = factory.SubFactory(ServiceFactory)
    service_id = factory.SelfAttribute("service.id")
    level = factory.LazyFunction(lambda: random.choice(["P0", "P1", "P2", "P3"]))
    message = Faker("sentence")
    status = factory.LazyFunction(lambda: random.choice([
        "firing", "acknowledged", "resolved"
    ]))
    ack_user_id = factory.Maybe(
        lambda o: o.status in ["acknowledged", "resolved"],
        factory.SubFactory(UserFactory),
        None,
    )
    triggered_at = factory.LazyFunction(lambda: datetime.now() - timedelta(minutes=random.randint(1, 120)))
    acknowledged_at = factory.Maybe(
        lambda o: o.status in ["acknowledged", "resolved"],
        factory.LazyFunction(lambda: datetime.now() - timedelta(minutes=random.randint(1, 60))),
        None,
    )
    resolved_at = factory.Maybe(
        lambda o: o.status == "resolved",
        factory.LazyFunction(lambda: datetime.now() - timedelta(minutes=random.randint(1, 30))),
        None,
    )


class SlowSQLFactory(BaseFactory):
    class Meta:
        model = SlowSQL

    fingerprint = factory.LazyFunction(lambda: "".join([random.choice("0123456789abcdef") for _ in range(32)]))
    table_name = factory.LazyFunction(lambda: random.choice([
        "orders", "users", "payments", "order_items", "products", "logs"
    ]))
    sql_text = factory.LazyFunction(lambda: random.choice([
        "SELECT * FROM orders WHERE user_id = ? AND status = ? ORDER BY created_at DESC",
        "SELECT * FROM users WHERE phone = ?",
        "SELECT COUNT(*) FROM payments WHERE status = ? AND created_at > ?",
        "SELECT * FROM products WHERE category_id = ? AND stock > 0 ORDER BY sold_count DESC",
    ]))
    avg_duration_ms = factory.LazyFunction(lambda: random.randint(100, 5000))
    exec_count = factory.LazyFunction(lambda: random.randint(10, 10000))
    first_seen = factory.LazyFunction(lambda: datetime.now() - timedelta(days=random.randint(1, 30)))
    last_seen = factory.LazyFunction(lambda: datetime.now() - timedelta(minutes=random.randint(1, 1440)))


class SQLExplainFactory(BaseFactory):
    class Meta:
        model = SQLExplain

    slow_sql = factory.SubFactory(SlowSQLFactory)
    slow_sql_id = factory.SelfAttribute("slow_sql.id")
    plan_json = factory.LazyFunction(lambda: {
        "type": random.choice(["ALL", "range", "ref", "eq_ref"]),
        "rows": random.randint(1000, 1000000),
        "key": random.choice([None, "idx_user_id", "idx_status_created", "primary"]),
        "extra": random.choice([None, "Using filesort", "Using temporary", "Using where"]),
    })
    analysis = Faker("paragraph")


class AssetFactory(BaseFactory):
    class Meta:
        model = Asset

    name = factory.Sequence(lambda n: f"server-{n:02d}")
    category = factory.LazyFunction(lambda: random.choice([
        "server", "database", "cache", "mq", "load_balancer", "storage"
    ]))
    ip = Faker("ipv4")
    port = factory.LazyFunction(lambda: random.choice([22, 3306, 6379, 5672, 80, 443]))
    version = factory.LazyFunction(lambda: random.choice([
        "CentOS 7.9", "Ubuntu 22.04", "MySQL 8.0", "Redis 7.0", "RabbitMQ 3.12"
    ]))
    owner = Faker("name")
    status = factory.LazyFunction(lambda: random.choice(["normal", "normal", "warning", "maintenance"]))


class AssetChangeLogFactory(BaseFactory):
    class Meta:
        model = AssetChangeLog

    asset = factory.SubFactory(AssetFactory)
    asset_id = factory.SelfAttribute("asset.id")
    field_name = factory.LazyFunction(lambda: random.choice([
        "version", "owner", "status", "ip", "port"
    ]))
    old_value = Faker("word")
    new_value = Faker("word")
    operator = factory.SubFactory(UserFactory)
    operator_id = factory.SelfAttribute("operator.id")
    changed_at = factory.LazyFunction(lambda: datetime.now() - timedelta(days=random.randint(1, 30)))


class DutyScheduleFactory(BaseFactory):
    class Meta:
        model = DutySchedule

    user = factory.SubFactory(UserFactory)
    user_id = factory.SelfAttribute("user.id")
    duty_date = factory.LazyFunction(lambda: datetime.now().date() + timedelta(days=random.randint(-7, 7)))
    shift_type = factory.LazyFunction(lambda: random.choice(["day", "night"]))


class HandoverReportFactory(BaseFactory):
    class Meta:
        model = HandoverReport

    schedule = factory.SubFactory(DutyScheduleFactory)
    schedule_id = factory.SelfAttribute("schedule.id")
    from_user = factory.SubFactory(UserFactory)
    from_user_id = factory.SelfAttribute("from_user.id")
    to_user = factory.SubFactory(UserFactory)
    to_user_id = factory.SelfAttribute("to_user.id")
    content = Faker("paragraphs", nb=3)
    created_at = factory.LazyFunction(lambda: datetime.now() - timedelta(hours=random.randint(1, 24)))


class PreferenceFactory(BaseFactory):
    class Meta:
        model = Preference

    user = factory.SubFactory(UserFactory)
    user_id = factory.SelfAttribute("user.id")
    layout_config = factory.LazyFunction(lambda: {
        "grid_cols": random.choice([2, 3, 4]),
        "theme": "dark",
        "auto_refresh": True,
        "refresh_interval": 30,
    })


class PinnedComponentFactory(BaseFactory):
    class Meta:
        model = PinnedComponent

    preference = factory.SubFactory(PreferenceFactory)
    preference_id = factory.SelfAttribute("preference.id")
    component_type = factory.LazyFunction(lambda: random.choice([
        "health_summary", "alert_list", "metric_chart", "slow_sql_top", "duty_info", "asset_summary"
    ]))
    component_key = factory.LazyFunction(lambda: random.choice(["all", "firing", "cpu_usage", "top10"]))
    position = factory.Sequence(lambda n: n)


class LogTemplateFactory(BaseFactory):
    class Meta:
        model = LogTemplate

    user = factory.SubFactory(UserFactory)
    user_id = factory.SelfAttribute("user.id")
    name = factory.Sequence(lambda n: f"查询模板-{n}")
    query_config = factory.LazyFunction(lambda: {
        "keyword": random.choice(["error", "warning", "slow query", "timeout"]),
        "service_name": random.choice(["order-service", "user-service", "pay-service"]),
        "level": random.choice(["ERROR", "WARN", "INFO", "DEBUG"]),
    })
