from datetime import datetime, timedelta
from typing import List, Optional, Dict, Any
import json
import hashlib
import re
import random

from sqlalchemy.orm import Session

from app.models import SlowSQL, SQLExplain
from app.schemas import SlowSQLRecord, SQLExplainRequest


class SlowSQLService:
    """慢SQL采集分析器，负责慢SQL的指纹生成、按指纹聚合和执行计划分析。

    主要职责：
    - SQL指纹生成：将参数化的SQL归一化为12位MD5指纹
    - 按指纹聚合：相同指纹的SQL合并统计执行次数和平均耗时
    - 执行计划分析：生成 EXPLAIN 结果并标记性能问题

    对外接口：
    - record_slow_sql(data): 记录一条慢SQL（新指纹创建，已有指纹合并统计）
    - generate_fingerprint(sql): 生成SQL指纹
    - generate_explain(slow_sql_id): 生成执行计划分析

    依赖的外部服务：
    - 数据库（SlowSQL, SQLExplain 模型）
    """

    def __init__(self, db: Session):
        self.db = db

    def generate_fingerprint(self, sql: str) -> str:
        """生成SQL指纹，将参数化的SQL归一化为12位MD5哈希。

        归一化规则：去除注释、替换字符串和数字为占位符?、压缩空白、转小写。
        相同逻辑结构（仅参数值不同）的SQL会产生相同指纹。

        :param sql: 原始SQL语句
        :return: 12位十六进制指纹字符串
        """
        normalized = self._normalize_sql(sql)
        return hashlib.md5(normalized.encode("utf-8")).hexdigest()[:12]

    def _normalize_sql(self, sql: str) -> str:
        normalized = re.sub(r'--.*$', '', sql, flags=re.MULTILINE)
        normalized = re.sub(r'/\*.*?\*/', '', normalized, flags=re.DOTALL)
        normalized = re.sub(r"'[^']*'", '?', normalized)
        normalized = re.sub(r'"[^"]*"', '?', normalized)
        normalized = re.sub(r'\b\d+\b', '?', normalized)
        normalized = re.sub(r'\s+', ' ', normalized).strip()
        normalized = normalized.lower()
        return normalized

    def extract_table_name(self, sql: str) -> Optional[str]:
        match = re.search(r'from\s+([a-zA-Z_][a-zA-Z0-9_]*)', sql, re.IGNORECASE)
        if match:
            return match.group(1)
        match = re.search(r'update\s+([a-zA-Z_][a-zA-Z0-9_]*)', sql, re.IGNORECASE)
        if match:
            return match.group(1)
        match = re.search(r'into\s+([a-zA-Z_][a-zA-Z0-9_]*)', sql, re.IGNORECASE)
        if match:
            return match.group(1)
        return None

    def record_slow_sql(self, data: SlowSQLRecord) -> SlowSQL:
        """记录一条慢SQL。如果指纹已存在，则合并统计（累加执行次数、更新平均耗时）。

        平均耗时计算方式：增量加权平均。
        new_avg = (old_avg * old_count + new_duration) / (old_count + 1)

        :param data: 慢SQL记录数据，包含 sql_text、duration_ms 等字段
        :return: 新创建或更新后的 SlowSQL 对象
        """
        fingerprint = data.fingerprint or self.generate_fingerprint(data.sql_text)
        table_name = data.table_name or self.extract_table_name(data.sql_text)

        existing = self.db.query(SlowSQL).filter(SlowSQL.fingerprint == fingerprint).first()

        if existing:
            existing.exec_count += 1
            existing.avg_duration_ms = (
                (existing.avg_duration_ms * (existing.exec_count - 1) + data.duration_ms)
                / existing.exec_count
            )
            existing.last_seen = datetime.now()
            if not existing.table_name and table_name:
                existing.table_name = table_name
            self.db.commit()
            self.db.refresh(existing)
            return existing
        else:
            slow_sql = SlowSQL(
                fingerprint=fingerprint,
                table_name=table_name,
                sql_text=data.sql_text,
                avg_duration_ms=data.duration_ms,
                exec_count=1,
            )
            self.db.add(slow_sql)
            self.db.commit()
            self.db.refresh(slow_sql)
            return slow_sql

    def batch_record(self, records: List[SlowSQLRecord]) -> List[SlowSQL]:
        return [self.record_slow_sql(r) for r in records]

    def get_slow_sql_list(
        self,
        table_name: Optional[str] = None,
        min_duration: Optional[float] = None,
        sort_by: str = "last_seen",
        limit: int = 100,
    ) -> List[SlowSQL]:
        query = self.db.query(SlowSQL)

        if table_name:
            query = query.filter(SlowSQL.table_name == table_name)
        if min_duration:
            query = query.filter(SlowSQL.avg_duration_ms >= min_duration)

        sort_columns = {
            "last_seen": SlowSQL.last_seen.desc(),
            "duration": SlowSQL.avg_duration_ms.desc(),
            "exec_count": SlowSQL.exec_count.desc(),
            "first_seen": SlowSQL.first_seen.desc(),
        }
        query = query.order_by(sort_columns.get(sort_by, SlowSQL.last_seen.desc()))

        return query.limit(limit).all()

    def get_slow_sql_by_id(self, slow_sql_id: int) -> Optional[SlowSQL]:
        return self.db.query(SlowSQL).filter(SlowSQL.id == slow_sql_id).first()

    def get_explain(self, slow_sql_id: int) -> Optional[SQLExplain]:
        return self.db.query(SQLExplain).filter(
            SQLExplain.slow_sql_id == slow_sql_id
        ).order_by(SQLExplain.created_at.desc()).first()

    def generate_explain(self, slow_sql_id: int) -> SQLExplain:
        slow_sql = self.get_slow_sql_by_id(slow_sql_id)
        if not slow_sql:
            raise ValueError(f"SlowSQL {slow_sql_id} not found")

        plan = self._mock_explain_plan(slow_sql)
        analysis = self._analyze_plan(slow_sql, plan)

        explain = SQLExplain(
            slow_sql_id=slow_sql_id,
            plan_json=json.dumps(plan, ensure_ascii=False),
            analysis=analysis,
        )
        self.db.add(explain)
        self.db.commit()
        self.db.refresh(explain)
        return explain

    def _mock_explain_plan(self, slow_sql: SlowSQL) -> Dict[str, Any]:
        sql_lower = slow_sql.sql_text.lower()
        has_order_by = "order by" in sql_lower
        has_group_by = "group by" in sql_lower
        has_join = "join" in sql_lower
        has_where = "where" in sql_lower

        scan_type = "ALL" if random.random() < 0.7 else "range"
        rows_examined = random.randint(10000, 500000)

        plan = {
            "id": 1,
            "select_type": "SIMPLE",
            "table": slow_sql.table_name or "unknown",
            "type": scan_type,
            "possible_keys": None if scan_type == "ALL" else "idx_some_column",
            "key": None if scan_type == "ALL" else "idx_some_column",
            "key_len": None if scan_type == "ALL" else "5",
            "ref": None if scan_type == "ALL" else "const",
            "rows": rows_examined,
            "filtered": random.uniform(10, 100),
            "Extra": [],
        }

        extras = []
        if scan_type == "ALL":
            extras.append("Using filesort" if has_order_by else "Full table scan")
        if has_group_by:
            extras.append("Using temporary")
        if has_where and scan_type == "ALL":
            extras.append("Using where")
        if has_join:
            extras.append("Using join buffer")

        plan["Extra"] = "; ".join(extras) if extras else None
        return plan

    def _analyze_plan(self, slow_sql: SlowSQL, plan: Dict[str, Any]) -> str:
        analysis = []

        if plan.get("type") == "ALL":
            analysis.append("⚠️ **全表扫描**：查询未使用有效索引，建议检查WHERE条件字段是否添加了合适的索引。")

        if plan.get("Extra") and "filesort" in plan["Extra"]:
            analysis.append("⚠️ **文件排序**：ORDER BY字段未命中索引，导致MySQL在磁盘上排序。建议为ORDER BY字段添加联合索引。")

        if plan.get("Extra") and "temporary" in plan["Extra"]:
            analysis.append("⚠️ **临时表**：GROUP BY操作使用了临时表，性能损耗较大。考虑优化GROUP BY字段或添加覆盖索引。")

        rows = plan.get("rows", 0)
        if rows > 100000:
            analysis.append(f"⚠️ **扫描行数过多**：本次查询扫描了约{rows:,}行数据，建议增加过滤条件或优化索引减少扫描范围。")

        if slow_sql.avg_duration_ms > 500:
            analysis.append(f"⏱️ **执行时间过长**：平均执行时间{slow_sql.avg_duration_ms:.0f}ms，已超过500ms阈值，建议优先优化。")

        if not analysis:
            analysis.append("✅ 查询执行计划正常，无明显性能问题。")

        analysis.append(f"\n📊 **统计信息**：\n- 执行次数：{slow_sql.exec_count:,} 次\n- 平均耗时：{slow_sql.avg_duration_ms:.0f} ms\n- 首次出现：{slow_sql.first_seen}\n- 最近出现：{slow_sql.last_seen}")

        return "\n\n".join(analysis)

    def get_statistics(self, days: int = 7) -> Dict[str, Any]:
        start_date = datetime.now() - timedelta(days=days)

        slow_sqls = self.db.query(SlowSQL).filter(
            SlowSQL.last_seen >= start_date
        ).all()

        total_count = sum(s.exec_count for s in slow_sqls)
        by_table = {}
        for s in slow_sqls:
            table = s.table_name or "unknown"
            if table not in by_table:
                by_table[table] = {"count": 0, "total_duration": 0, "slow_sql_count": 0}
            by_table[table]["count"] += s.exec_count
            by_table[table]["total_duration"] += s.avg_duration_ms * s.exec_count
            by_table[table]["slow_sql_count"] += 1

        for table in by_table:
            data = by_table[table]
            data["avg_duration"] = data["total_duration"] / data["count"] if data["count"] > 0 else 0

        top_10 = sorted(slow_sqls, key=lambda s: s.avg_duration_ms * s.exec_count, reverse=True)[:10]

        return {
            "total_slow_sqls": len(slow_sqls),
            "total_executions": total_count,
            "by_table": by_table,
            "top_10": top_10,
        }

    def get_tables(self) -> List[str]:
        result = self.db.query(SlowSQL.table_name).filter(
            SlowSQL.table_name.isnot(None)
        ).distinct().all()
        return [r[0] for r in result]

    def delete_slow_sql(self, slow_sql_id: int) -> bool:
        slow_sql = self.get_slow_sql_by_id(slow_sql_id)
        if not slow_sql:
            return False
        self.db.delete(slow_sql)
        self.db.commit()
        return True

    def generate_sample_data(self):
        sample_sqls = [
            ("SELECT * FROM orders WHERE user_id = 12345 AND status = 'pending' ORDER BY created_at DESC LIMIT 50", "orders", 456.7),
            ("SELECT * FROM users WHERE phone = '13800138000'", "users", 1234.5),
            ("SELECT COUNT(*) FROM payments WHERE status = 'pending' AND created_at > '2024-01-01'", "payments", 890.2),
            ("SELECT oi.*, p.name FROM order_items oi JOIN products p ON oi.product_id = p.id WHERE oi.order_id IN (1,2,3,4,5)", "order_items", 678.9),
            ("SELECT * FROM products WHERE category_id = 10 AND stock > 0 ORDER BY sold_count DESC LIMIT 0, 20", "products", 345.6),
            ("SELECT * FROM logs WHERE level = 'ERROR' AND created_at BETWEEN '2024-01-01' AND '2024-01-02'", "logs", 567.8),
            ("SELECT u.username, COUNT(o.id) as order_count FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.id HAVING order_count > 10", "users", 987.6),
            ("SELECT * FROM notifications WHERE user_id = 12345 AND is_read = 0 ORDER BY created_at DESC", "notifications", 234.5),
            ("SELECT * FROM coupons WHERE code = 'SAVE10' AND valid_from <= NOW() AND valid_to >= NOW()", "coupons", 432.1),
            ("SELECT AVG(amount) FROM transactions WHERE status = 'success' AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)", "transactions", 765.4),
        ]

        for sql, table, duration in sample_sqls:
            for _ in range(random.randint(100, 2000)):
                pass
            self.record_slow_sql(SlowSQLRecord(
                fingerprint="",
                sql_text=sql,
                table_name=table,
                duration_ms=duration,
            ))
