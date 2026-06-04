import json
from datetime import datetime, timedelta
from unittest.mock import patch, MagicMock

import pytest
from sqlalchemy.orm import Session

from app.models import SlowSQL, SQLExplain
from app.services.slow_sql_service import SlowSQLService
from app.schemas.slow_sql import SlowSQLRecord


class TestSQLFingerprintGeneration:

    def test_fingerprint_consistency(self, db_session: Session):
        service = SlowSQLService(db_session)

        sql1 = "SELECT * FROM orders WHERE user_id = 123 AND status = 'paid'"
        sql2 = "SELECT * FROM orders WHERE user_id = 456 AND status = 'pending'"

        fp1 = service.generate_fingerprint(sql1)
        fp2 = service.generate_fingerprint(sql2)

        assert fp1 == fp2
        assert len(fp1) == 12

    def test_fingerprint_different_tables(self, db_session: Session):
        service = SlowSQLService(db_session)

        sql1 = "SELECT * FROM orders WHERE user_id = 123"
        sql2 = "SELECT * FROM users WHERE user_id = 123"

        fp1 = service.generate_fingerprint(sql1)
        fp2 = service.generate_fingerprint(sql2)

        assert fp1 != fp2

    def test_fingerprint_case_insensitive(self, db_session: Session):
        service = SlowSQLService(db_session)

        sql1 = "select * from orders where user_id = 123"
        sql2 = "SELECT * FROM ORDERS WHERE USER_ID = 123"

        fp1 = service.generate_fingerprint(sql1)
        fp2 = service.generate_fingerprint(sql2)

        assert fp1 == fp2

    def test_fingerprint_whitespace_normalization(self, db_session: Session):
        service = SlowSQLService(db_session)

        sql1 = "SELECT * FROM orders WHERE user_id = 123"
        sql2 = "SELECT   *   FROM   orders   WHERE   user_id   =   123"

        fp1 = service.generate_fingerprint(sql1)
        fp2 = service.generate_fingerprint(sql2)

        assert fp1 == fp2

    def test_fingerprint_string_value_normalization(self, db_session: Session):
        service = SlowSQLService(db_session)

        sql1 = "SELECT * FROM users WHERE name = 'Alice'"
        sql2 = "SELECT * FROM users WHERE name = 'Bob'"

        fp1 = service.generate_fingerprint(sql1)
        fp2 = service.generate_fingerprint(sql2)

        assert fp1 == fp2

    def test_fingerprint_multiple_conditions(self, db_session: Session):
        service = SlowSQLService(db_session)

        sql1 = "SELECT * FROM orders WHERE user_id = 1 AND status = 'a' AND amount > 100"
        sql2 = "SELECT * FROM orders WHERE user_id = 2 AND status = 'b' AND amount > 200"

        fp1 = service.generate_fingerprint(sql1)
        fp2 = service.generate_fingerprint(sql2)

        assert fp1 == fp2

    def test_fingerprint_different_queries(self, db_session: Session):
        service = SlowSQLService(db_session)

        sql1 = "SELECT * FROM orders WHERE user_id = 123"
        sql2 = "SELECT COUNT(*) FROM orders WHERE user_id = 123"

        fp1 = service.generate_fingerprint(sql1)
        fp2 = service.generate_fingerprint(sql2)

        assert fp1 != fp2


class TestSQLExplainAnalysis:

    def test_generate_explain_full_table_scan(self, db_session: Session):
        slow_sql = SlowSQL(
            fingerprint="abc123",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE status = 'pending'",
            avg_duration_ms=2500,
            exec_count=1500,
        )
        db_session.add(slow_sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        explain = service.generate_explain(slow_sql.id)

        assert explain is not None
        assert explain.slow_sql_id == slow_sql.id
        assert "执行时间过长" in explain.analysis or "全表扫描" in explain.analysis or "扫描行数过多" in explain.analysis

    def test_generate_explain_missing_index(self, db_session: Session):
        slow_sql = SlowSQL(
            fingerprint="def456",
            table_name="users",
            sql_text="SELECT * FROM users WHERE phone = '13800138000'",
            avg_duration_ms=1800,
            exec_count=800,
        )
        db_session.add(slow_sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        explain = service.generate_explain(slow_sql.id)

        assert explain is not None
        assert "索引" in explain.analysis or "index" in explain.analysis.lower()

    def test_generate_explain_order_by_optimization(self, db_session: Session):
        slow_sql = SlowSQL(
            fingerprint="ghi789",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE user_id = 123 ORDER BY created_at DESC",
            avg_duration_ms=3200,
            exec_count=2000,
        )
        db_session.add(slow_sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        explain = service.generate_explain(slow_sql.id)

        assert explain is not None
        assert explain.plan_json is not None

    def test_analyze_plan_filesort(self, db_session: Session):
        service = SlowSQLService(db_session)

        slow_sql = SlowSQL(
            fingerprint="test_fp",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE status = 'pending' ORDER BY created_at DESC",
            avg_duration_ms=600,
            exec_count=100,
        )
        db_session.add(slow_sql)
        db_session.commit()

        plan = {
            "type": "ALL",
            "key": None,
            "rows": 100000,
            "Extra": "Using filesort",
        }

        analysis = service._analyze_plan(slow_sql, plan)

        assert "文件排序" in analysis or "排序" in analysis

    def test_analyze_plan_temporary_table(self, db_session: Session):
        service = SlowSQLService(db_session)

        slow_sql = SlowSQL(
            fingerprint="test_fp2",
            table_name="orders",
            sql_text="SELECT status, COUNT(*) FROM orders GROUP BY status",
            avg_duration_ms=600,
            exec_count=100,
        )
        db_session.add(slow_sql)
        db_session.commit()

        plan = {
            "type": "ALL",
            "key": None,
            "rows": 50000,
            "Extra": "Using temporary",
        }

        analysis = service._analyze_plan(slow_sql, plan)

        assert "临时表" in analysis

    def test_analyze_plan_full_table_scan(self, db_session: Session):
        service = SlowSQLService(db_session)

        slow_sql = SlowSQL(
            fingerprint="test_fp3",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE status = 'pending'",
            avg_duration_ms=600,
            exec_count=100,
        )
        db_session.add(slow_sql)
        db_session.commit()

        plan = {
            "type": "ALL",
            "key": None,
            "rows": 1000000,
            "Extra": "Using where",
        }

        analysis = service._analyze_plan(slow_sql, plan)

        assert "全表扫描" in analysis or "full table" in analysis.lower()
        assert "索引" in analysis or "index" in analysis.lower()

    def test_analyze_plan_good_index(self, db_session: Session):
        service = SlowSQLService(db_session)

        slow_sql = SlowSQL(
            fingerprint="test_fp4",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE user_id = 123",
            avg_duration_ms=50,
            exec_count=100,
        )
        db_session.add(slow_sql)
        db_session.commit()

        plan = {
            "type": "ref",
            "key": "idx_user_id",
            "rows": 100,
            "Extra": None,
        }

        analysis = service._analyze_plan(slow_sql, plan)

        assert "正常" in analysis or "good" in analysis.lower() or "正常" in analysis or "✅" in analysis


class TestSlowSQLAggregation:

    def test_aggregate_by_fingerprint(self, db_session: Session):
        for i in range(5):
            sql = SlowSQL(
                fingerprint=f"fp_{i}",
                table_name="orders",
                sql_text=f"SELECT * FROM orders WHERE id = {i}",
                avg_duration_ms=100 + i * 50,
                exec_count=100 + i,
            )
            db_session.add(sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        stats = service.get_statistics()

        assert stats["total_slow_sqls"] >= 5
        assert stats["total_executions"] >= 500

    def test_aggregate_by_table_name(self, db_session: Session):
        tables = ["orders", "users", "payments"]
        for table in tables:
            for i in range(3):
                sql = SlowSQL(
                    fingerprint=f"{table}_fp_{i}",
                    table_name=table,
                    sql_text=f"SELECT * FROM {table} WHERE id = {i}",
                    avg_duration_ms=200 + i * 30,
                    exec_count=50 + i * 10,
                )
                db_session.add(sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        stats = service.get_statistics()

        assert len(stats["by_table"]) == 3

    def test_top_slow_queries(self, db_session: Session):
        durations = [100, 500, 1000, 2000, 5000]
        for i, duration in enumerate(durations):
            sql = SlowSQL(
                fingerprint=f"fp_{i}",
                table_name="orders",
                sql_text=f"SELECT * FROM orders WHERE id = {i}",
                avg_duration_ms=duration,
                exec_count=100,
                first_seen=datetime.now() - timedelta(days=i),
                last_seen=datetime.now() - timedelta(hours=i),
            )
            db_session.add(sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        result = service.get_slow_sql_list(sort_by="duration", limit=3)

        assert len(result) == 3
        assert result[0].avg_duration_ms >= result[1].avg_duration_ms
        assert result[1].avg_duration_ms >= result[2].avg_duration_ms

    def test_filter_by_table_name(self, db_session: Session):
        tables = ["orders", "users", "payments"]
        for table in tables:
            sql = SlowSQL(
                fingerprint=f"{table}_fp",
                table_name=table,
                sql_text=f"SELECT * FROM {table} WHERE id = 1",
                avg_duration_ms=500,
                exec_count=100,
            )
            db_session.add(sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        result = service.get_slow_sql_list(table_name="orders")

        assert len(result) == 1
        assert result[0].table_name == "orders"

    def test_exec_count_aggregation(self, db_session: Session):
        exec_counts = [10, 100, 500, 1000, 2500]
        for i, count in enumerate(exec_counts):
            sql = SlowSQL(
                fingerprint=f"fp_{i}",
                table_name="orders",
                sql_text=f"SELECT * FROM orders WHERE id = {i}",
                avg_duration_ms=500,
                exec_count=count,
            )
            db_session.add(sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        result = service.get_slow_sql_list(sort_by="exec_count", limit=3)

        assert len(result) == 3
        assert result[0].exec_count == 2500
        assert result[1].exec_count == 1000
        assert result[2].exec_count == 500


class TestSlowSQLReporting:

    def test_get_slow_sql_by_id(self, db_session: Session):
        sql = SlowSQL(
            fingerprint="test_fp",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE id = 1",
            avg_duration_ms=500,
            exec_count=100,
            first_seen=datetime.now() - timedelta(days=7),
            last_seen=datetime.now() - timedelta(hours=1),
        )
        db_session.add(sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        result = service.get_slow_sql_by_id(sql.id)

        assert result is not None
        assert result.fingerprint == "test_fp"
        assert result.table_name == "orders"

    def test_get_explain_by_slow_sql_id(self, db_session: Session):
        slow_sql = SlowSQL(
            fingerprint="test_fp",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE id = 1",
            avg_duration_ms=500,
            exec_count=100,
        )
        db_session.add(slow_sql)
        db_session.flush()

        explain = SQLExplain(
            slow_sql_id=slow_sql.id,
            plan_json=json.dumps({"type": "ALL", "key": None, "rows": 1000}),
            analysis="全表扫描，性能较差",
        )
        db_session.add(explain)
        db_session.commit()

        service = SlowSQLService(db_session)
        result = service.get_explain(slow_sql.id)

        assert result is not None
        assert result.slow_sql_id == slow_sql.id
        assert "全表扫描" in result.analysis

    def test_get_nonexistent_explain(self, db_session: Session):
        slow_sql = SlowSQL(
            fingerprint="test_fp",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE id = 1",
            avg_duration_ms=500,
            exec_count=100,
        )
        db_session.add(slow_sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        result = service.get_explain(slow_sql.id)

        assert result is None

    def test_first_seen_last_seen_tracking(self, db_session: Session):
        first_seen = datetime.now() - timedelta(days=30)
        last_seen = datetime.now() - timedelta(hours=2)

        sql = SlowSQL(
            fingerprint="test_fp",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE id = 1",
            avg_duration_ms=500,
            exec_count=100,
            first_seen=first_seen,
            last_seen=last_seen,
        )
        db_session.add(sql)
        db_session.commit()

        service = SlowSQLService(db_session)
        result = service.get_slow_sql_by_id(sql.id)

        assert result.first_seen == first_seen
        assert result.last_seen == last_seen


class TestSlowSQLAgentIntegration:

    def test_agent_report_slow_sql(self, db_session: Session):
        service = SlowSQLService(db_session)

        report_data = SlowSQLRecord(
            fingerprint="",
            sql_text="SELECT * FROM orders WHERE user_id = 123 AND status = 'pending'",
            table_name=None,
            duration_ms=1250,
        )

        result = service.record_slow_sql(report_data)

        assert result is not None
        assert result.fingerprint is not None
        assert result.table_name == "orders"
        assert result.avg_duration_ms >= 1250
        assert result.exec_count >= 1

    def test_agent_report_same_fingerprint(self, db_session: Session):
        service = SlowSQLService(db_session)

        for i in range(5):
            report_data = SlowSQLRecord(
                fingerprint="",
                sql_text=f"SELECT * FROM orders WHERE user_id = {i} AND status = 'pending'",
                table_name=None,
                duration_ms=1000 + i * 100,
            )
            result = service.record_slow_sql(report_data)

        stats = service.get_statistics()
        assert stats["total_slow_sqls"] == 1

    def test_agent_report_multiple_tables(self, db_session: Session):
        service = SlowSQLService(db_session)

        tables = ["orders", "users", "payments"]
        for table in tables:
            report_data = SlowSQLRecord(
                fingerprint="",
                sql_text=f"SELECT * FROM {table} WHERE id = 1",
                table_name=None,
                duration_ms=500,
            )
            service.record_slow_sql(report_data)

        stats = service.get_statistics()
        assert len(stats["by_table"]) == 3