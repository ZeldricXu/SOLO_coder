import pytest
from datetime import datetime, timedelta
from streamsql.modules.data_quality.rules import (
    RuleType,
    NullCheckRule,
    RangeCheckRule,
    RegexCheckRule,
    UniquenessCheckRule,
    FormatCheckRule,
    CustomRule,
)
from streamsql.modules.data_quality.executor import ValidationExecutor, ValidationResult
from streamsql.modules.data_quality.scheduler import ValidationScheduler
from streamsql.modules.data_quality.quality_manager import DataQualityManager


def test_null_check_rule():
    rule = NullCheckRule(column="name")
    valid_data = {"name": "Alice", "age": 25}
    invalid_data = {"name": None, "age": 25}

    assert rule.validate(valid_data) is True
    assert rule.validate(invalid_data) is False


def test_range_check_rule():
    rule = RangeCheckRule(column="age", min_value=0, max_value=150)
    assert rule.validate({"age": 25}) is True
    assert rule.validate({"age": -1}) is False
    assert rule.validate({"age": 200}) is False
    assert rule.validate({"age": 0}) is True
    assert rule.validate({"age": 150}) is True


def test_regex_check_rule():
    rule = RegexCheckRule(
        column="email",
        pattern=r"^[^@]+@[^@]+\.[^@]+$",
    )
    assert rule.validate({"email": "test@example.com"}) is True
    assert rule.validate({"email": "invalid-email"}) is False
    assert rule.validate({"email": None}) is False


def test_uniqueness_check_rule():
    rule = UniquenessCheckRule(column="id")
    data = [
        {"id": 1, "name": "Alice"},
        {"id": 2, "name": "Bob"},
        {"id": 3, "name": "Charlie"},
    ]
    assert rule.validate_batch(data) is True

    data_with_duplicates = [
        {"id": 1, "name": "Alice"},
        {"id": 1, "name": "Alice2"},
        {"id": 3, "name": "Charlie"},
    ]
    assert rule.validate_batch(data_with_duplicates) is False


def test_format_check_rule():
    rule = FormatCheckRule(column="date", format_type="date")
    assert rule.validate({"date": "2024-01-01"}) is True
    assert rule.validate({"date": "2024/01/01"}) is True
    assert rule.validate({"date": "invalid-date"}) is False

    email_rule = FormatCheckRule(column="email", format_type="email")
    assert email_rule.validate({"email": "test@example.com"}) is True


def test_custom_rule():
    def check_age(value):
        return value is not None and value >= 18

    rule = CustomRule(
        column="age",
        validation_function=check_age,
    )
    assert rule.validate({"age": 25}) is True
    assert rule.validate({"age": 17}) is False
    assert rule.validate({"age": None}) is False


def test_validation_executor_validate_row():
    executor = ValidationExecutor()
    rules = [
        NullCheckRule(column="name"),
        RangeCheckRule(column="age", min_value=0, max_value=150),
    ]

    valid_row = {"name": "Alice", "age": 25}
    result = executor.validate_row(valid_row, rules)
    assert result["valid"] is True
    assert len(result["errors"]) == 0

    invalid_row = {"name": None, "age": 200}
    result = executor.validate_row(invalid_row, rules)
    assert result["valid"] is False
    assert len(result["errors"]) == 2


def test_validation_executor_validate_batch():
    executor = ValidationExecutor()
    rules = [
        NullCheckRule(column="name"),
        RangeCheckRule(column="age", min_value=0, max_value=150),
    ]

    data = [
        {"name": "Alice", "age": 25},
        {"name": None, "age": 30},
        {"name": "Bob", "age": 200},
        {"name": "Charlie", "age": 35},
    ]

    result = executor.validate_batch(data, rules)
    assert result["total_rows"] == 4
    assert result["valid_rows"] == 2
    assert result["invalid_rows"] == 2
    assert len(result["errors"]) == 2
    assert result["valid"] is False


def test_validation_executor_mark_anomalies():
    executor = ValidationExecutor()
    rules = [NullCheckRule(column="name")]
    data = [
        {"name": "Alice", "age": 25},
        {"name": None, "age": 30},
        {"name": "Bob", "age": 35},
    ]

    marked_data = executor.mark_anomalies(data, rules)
    assert len(marked_data) == 3
    assert marked_data[0]["_anomaly"] is False
    assert marked_data[1]["_anomaly"] is True
    assert "_anomaly_errors" in marked_data[1]


def test_validation_scheduler_create():
    scheduler = ValidationScheduler()
    rule_configs = [{"type": "null_check", "column": "name"}]

    job_id = scheduler.create_validation_job(
        name="daily_check",
        data_source="users",
        rule_configs=rule_configs,
        schedule_type="interval",
        schedule_params={"interval_seconds": 3600},
    )

    assert job_id is not None
    job = scheduler.get_job(job_id)
    assert job is not None
    assert job["name"] == "daily_check"


def test_validation_scheduler_cron_schedule():
    scheduler = ValidationScheduler()
    rule_configs = [{"type": "null_check", "column": "name"}]

    job_id = scheduler.create_validation_job(
        name="cron_check",
        data_source="users",
        rule_configs=rule_configs,
        schedule_type="cron",
        schedule_params={"cron_expression": "0 0 * * *"},
    )

    job = scheduler.get_job(job_id)
    assert job["schedule_type"] == "cron"
    assert job["schedule_params"]["cron_expression"] == "0 0 * * *"


def test_validation_scheduler_list_jobs():
    scheduler = ValidationScheduler()
    rule_configs = [{"type": "null_check", "column": "name"}]

    scheduler.create_validation_job(
        name="job1",
        data_source="users",
        rule_configs=rule_configs,
        schedule_type="interval",
        schedule_params={"interval_seconds": 3600},
    )
    scheduler.create_validation_job(
        name="job2",
        data_source="orders",
        rule_configs=rule_configs,
        schedule_type="interval",
        schedule_params={"interval_seconds": 7200},
    )

    jobs = scheduler.list_jobs()
    assert len(jobs) == 2


def test_validation_scheduler_delete_job():
    scheduler = ValidationScheduler()
    rule_configs = [{"type": "null_check", "column": "name"}]

    job_id = scheduler.create_validation_job(
        name="to_delete",
        data_source="users",
        rule_configs=rule_configs,
        schedule_type="interval",
        schedule_params={"interval_seconds": 3600},
    )

    assert scheduler.get_job(job_id) is not None
    scheduler.delete_job(job_id)
    assert scheduler.get_job(job_id) is None


def test_data_quality_manager_add_rule():
    manager = DataQualityManager()
    rule = NullCheckRule(column="name")
    manager.add_rule("users", rule)

    rules = manager.get_rules("users")
    assert len(rules) == 1


def test_data_quality_manager_validate():
    manager = DataQualityManager()
    manager.add_rule("users", NullCheckRule(column="name"))
    manager.add_rule("users", RangeCheckRule(column="age", min_value=0, max_value=150))

    data = [
        {"name": "Alice", "age": 25},
        {"name": None, "age": 30},
        {"name": "Bob", "age": 200},
    ]

    result = manager.validate("users", data)
    assert result["total_rows"] == 3
    assert result["valid_rows"] == 1
    assert result["invalid_rows"] == 2


def test_data_quality_manager_generate_report():
    manager = DataQualityManager()
    manager.add_rule("users", NullCheckRule(column="name"))

    data = [
        {"name": "Alice", "age": 25},
        {"name": None, "age": 30},
        {"name": "Bob", "age": 35},
    ]

    validation_result = manager.validate("users", data)
    report = manager.generate_report(validation_result)

    assert report["table_name"] == "users"
    assert report["total_rows"] == 3
    assert "error_summary" in report
    assert "null_check" in report["error_summary"]


def test_data_quality_manager_get_quality_score():
    manager = DataQualityManager()
    manager.add_rule("users", NullCheckRule(column="name"))

    data = [
        {"name": "Alice", "age": 25},
        {"name": "Bob", "age": 30},
        {"name": None, "age": 35},
    ]

    validation_result = manager.validate("users", data)
    score = manager.get_quality_score(validation_result)
    assert score == pytest.approx(66.66666666666667)
