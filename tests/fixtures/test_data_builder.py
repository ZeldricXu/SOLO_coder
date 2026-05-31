from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
import random
import string


@dataclass
class QueryTestData:
    sql: str
    expected_sql_type: str
    expected_sources: List[str] = field(default_factory=list)
    expected_columns: List[str] = field(default_factory=list)
    expected_window_type: Optional[str] = None
    expected_join_type: Optional[str] = None
    should_raise: bool = False
    exception_type: Optional[type] = None


@dataclass
class LineageTestData:
    sql_list: List[str] = field(default_factory=list)
    expected_nodes: List[str] = field(default_factory=list)
    expected_edges: int = 0
    default_database: str = "default"


@dataclass
class LifecycleTestData:
    table_name: str
    database_name: str = "default"
    row_count: int = 1000
    size_mb: float = 1.0
    oldest_record_days: int = 45
    records: List[Dict[str, Any]] = field(default_factory=list)


class DataBuilder:
    def __init__(self, seed: int = 42):
        random.seed(seed)
        self._counter = 0

    def _next_id(self) -> int:
        self._counter += 1
        return self._counter

    def _random_string(self, length: int = 8) -> str:
        return "".join(random.choices(string.ascii_lowercase, k=length))

    def build_valid_select_query(self, with_window: bool = False, with_join: bool = False) -> QueryTestData:
        if with_window and with_join:
            sql = """
                SELECT t1.id, t1.name, COUNT(*) as cnt
                FROM stream_orders t1
                INNER JOIN stream_items t2 ON t1.id = t2.order_id
                WINDOW TUMBLING(SIZE 5 MINUTES, ON event_time)
                GROUP BY t1.id, t1.name
            """
            return QueryTestData(
                sql=sql,
                expected_sql_type="SELECT",
                expected_sources=["stream_orders", "stream_items"],
                expected_columns=["id", "name", "cnt"],
                expected_window_type="TUMBLING",
                expected_join_type="INNER",
            )
        elif with_window:
            sql = """
                SELECT product_id, AVG(price) as avg_price
                FROM stream_transactions
                WINDOW HOPPING(SIZE 10 MINUTES, SLIDE 5 MINUTES)
                GROUP BY product_id
            """
            return QueryTestData(
                sql=sql,
                expected_sql_type="SELECT",
                expected_sources=["stream_transactions"],
                expected_columns=["product_id", "avg_price"],
                expected_window_type="HOPPING",
            )
        elif with_join:
            sql = """
                SELECT o.id, o.amount, c.name
                FROM orders o
                LEFT JOIN customers c ON o.customer_id = c.id
                WHERE o.amount > 100
            """
            return QueryTestData(
                sql=sql,
                expected_sql_type="SELECT",
                expected_sources=["orders", "customers"],
                expected_columns=["id", "amount", "name"],
                expected_join_type="LEFT",
            )
        else:
            sql = "SELECT id, name, email FROM users WHERE status = 'active' LIMIT 100"
            return QueryTestData(
                sql=sql,
                expected_sql_type="SELECT",
                expected_sources=["users"],
                expected_columns=["id", "name", "email"],
            )

    def build_valid_insert_query(self) -> QueryTestData:
        sql = """
            INSERT INTO daily_summary (date, total, count)
            SELECT DATE(event_time), SUM(amount), COUNT(*)
            FROM transactions
            GROUP BY DATE(event_time)
        """
        return QueryTestData(
            sql=sql,
            expected_sql_type="INSERT",
            expected_sources=["transactions"],
        )

    def build_valid_create_stream_query(self) -> QueryTestData:
        sql = """
            CREATE STREAM high_value_transactions AS
            SELECT * FROM transactions WHERE amount > 1000
        """
        return QueryTestData(
            sql=sql,
            expected_sql_type="CREATE_STREAM",
            expected_sources=["transactions"],
        )

    def build_invalid_syntax_queries(self) -> List[QueryTestData]:
        return [
            QueryTestData(
                sql="SELECT * FROM WHERE id = 1",
                expected_sql_type="SELECT",
                should_raise=False,
            ),
            QueryTestData(
                sql="INVALID SQL STATEMENT",
                expected_sql_type="UNKNOWN",
                should_raise=False,
            ),
            QueryTestData(
                sql="",
                expected_sql_type="UNKNOWN",
                should_raise=False,
            ),
        ]

    def build_window_queries_with_variations(self) -> List[QueryTestData]:
        variations = [
            ("TUMBLING", "SIZE 1 HOUR"),
            ("HOPPING", "SIZE 10 MINUTES, SLIDE 2 MINUTES"),
            ("SLIDING", "SIZE 5 MINUTES, GAP 30 SECONDS"),
            ("SESSION", "SIZE 1 HOUR, GAP 10 MINUTES"),
        ]
        queries = []
        for window_type, params in variations:
            sql = f"""
                SELECT metric, SUM(value)
                FROM metrics_stream
                WINDOW {window_type}({params}, ON ts)
                GROUP BY metric
            """
            queries.append(QueryTestData(
                sql=sql,
                expected_sql_type="SELECT",
                expected_sources=["metrics_stream"],
                expected_window_type=window_type,
            ))
        return queries

    def build_lineage_single_sql(self) -> LineageTestData:
        sql = """
            INSERT INTO sales_summary (date, region, total_amount)
            SELECT o.order_date, c.region, SUM(o.amount)
            FROM orders o
            JOIN customers c ON o.customer_id = c.id
            GROUP BY o.order_date, c.region
        """
        return LineageTestData(
            sql_list=[sql],
            expected_nodes=["orders", "customers", "sales_summary"],
            expected_edges=2,
        )

    def build_lineage_multi_sql(self) -> LineageTestData:
        sql_list = [
            "CREATE TABLE staging_orders AS SELECT * FROM raw_orders",
            "CREATE TABLE cleaned_orders AS SELECT * FROM staging_orders WHERE status = 'valid'",
            """
                INSERT INTO daily_orders_summary (date, total)
                SELECT order_date, COUNT(*) FROM cleaned_orders GROUP BY order_date
            """,
        ]
        return LineageTestData(
            sql_list=sql_list,
            expected_nodes=["raw_orders", "staging_orders", "cleaned_orders", "daily_orders_summary"],
            expected_edges=3,
        )

    def build_lineage_with_column_level(self) -> LineageTestData:
        sql_list = [
            """
                INSERT INTO customer_metrics (customer_id, total_spent, order_count)
                SELECT
                    o.customer_id,
                    SUM(o.amount) as total_spent,
                    COUNT(*) as order_count
                FROM orders o
                GROUP BY o.customer_id
            """,
        ]
        return LineageTestData(
            sql_list=sql_list,
            expected_nodes=["orders", "customer_metrics"],
            expected_edges=1,
        )

    def build_lifecycle_hot_data(self) -> LifecycleTestData:
        records = []
        now = datetime.utcnow()
        for i in range(100):
            records.append({
                "id": i,
                "value": random.uniform(0, 1000),
                "event_time": (now - timedelta(days=random.randint(0, 20))).isoformat(),
            })
        return LifecycleTestData(
            table_name=f"hot_data_{self._next_id()}",
            row_count=100,
            size_mb=0.1,
            oldest_record_days=20,
            records=records,
        )

    def build_lifecycle_cold_data(self) -> LifecycleTestData:
        records = []
        now = datetime.utcnow()
        for i in range(500):
            records.append({
                "id": i,
                "value": random.uniform(0, 1000),
                "event_time": (now - timedelta(days=random.randint(35, 100))).isoformat(),
            })
        return LifecycleTestData(
            table_name=f"cold_data_{self._next_id()}",
            row_count=500,
            size_mb=0.5,
            oldest_record_days=100,
            records=records,
        )

    def build_lifecycle_archive_data(self) -> LifecycleTestData:
        records = []
        now = datetime.utcnow()
        for i in range(1000):
            records.append({
                "id": i,
                "value": random.uniform(0, 1000),
                "event_time": (now - timedelta(days=random.randint(200, 400))).isoformat(),
            })
        return LifecycleTestData(
            table_name=f"archive_data_{self._next_id()}",
            row_count=1000,
            size_mb=1.0,
            oldest_record_days=400,
            records=records,
        )

    def build_lifecycle_table_stats(self, age_days: int, size_mb: float = 1.0) -> Dict[str, Any]:
        return {
            "total_rows": random.randint(1000, 10000),
            "size_mb": size_mb,
            "oldest_record_date": (datetime.utcnow() - timedelta(days=age_days)).isoformat(),
        }

    def build_sample_records(self, count: int = 100, with_timestamps: bool = True) -> List[Dict[str, Any]]:
        records = []
        now = datetime.utcnow()
        for i in range(count):
            record = {
                "id": i,
                "name": f"item_{i}",
                "value": random.uniform(0, 1000),
                "category": random.choice(["A", "B", "C", "D"]),
            }
            if with_timestamps:
                record["created_at"] = (now - timedelta(days=random.randint(0, 365))).isoformat()
            records.append(record)
        return records
