"""
sql_parser - 流式SQL语法解析器

基于sqlglot库实现的流式SQL解析器，支持标准流处理SQL语法，包括：
- TUMBLE/HOP/SESSION窗口函数
- WATERMARK水位线定义
- EMIT输出策略
- 流式GROUP BY、JOIN等操作

典型用法：
    >>> parser = SQLParser()
    >>> stmt = parser.parse('''
    ...     SELECT TUMBLE_START(event_time, INTERVAL '1' HOUR), COUNT(*)
    ...     FROM orders
    ...     WHERE amount > 100
    ...     GROUP BY TUMBLE(event_time, INTERVAL '1' HOUR)
    ...     EMIT ON WATERMARK
    ... ''')
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple, Union

import sqlglot
from sqlglot import exp, parse_one


class WindowType(Enum):
    """窗口类型枚举"""
    TUMBLE = "TUMBLE"
    HOP = "HOP"
    SESSION = "SESSION"


@dataclass
class WatermarkSpec:
    """
    水位线定义规范

    属性:
        event_time_column: 事件时间列名
        delay: 允许的延迟时间，格式如"INTERVAL '5' SECOND"
        strategy: 水位线生成策略，如"BOUNDED"、"MONOTONIC"等
    """
    event_time_column: str
    delay: str
    strategy: str = "BOUNDED"


@dataclass
class EmitStrategy:
    """
    输出策略定义

    属性:
        trigger_type: 触发类型，如"ON_WATERMARK"、"PERIODIC"等
        interval: 周期性输出间隔，仅当trigger_type为PERIODIC时有效
        include_retractions: 是否包含撤回消息
    """
    trigger_type: str = "ON_WATERMARK"
    interval: Optional[str] = None
    include_retractions: bool = False


@dataclass
class WindowSpec:
    """
    窗口定义规范

    属性:
        window_type: 窗口类型（TUMBLE/HOP/SESSION）
        time_column: 时间列名
        size: 窗口大小，格式如"INTERVAL '1' HOUR"
        slide: 滑动步长，仅HOP窗口需要
        gap: 会话超时时间，仅SESSION窗口需要
    """
    window_type: WindowType
    time_column: str
    size: str
    slide: Optional[str] = None
    gap: Optional[str] = None


@dataclass
class ParsedStatement:
    """
    解析后的SQL语句封装

    属性:
        original_sql: 原始SQL语句
        ast: sqlglot生成的AST
        select_items: SELECT子句中的表达式列表
        from_table: FROM子句中的表名
        where_condition: WHERE子句条件表达式
        group_by: GROUP BY子句表达式列表
        having_condition: HAVING子句条件
        window_specs: 窗口定义列表
        watermark: 水位线定义
        emit_strategy: 输出策略
        order_by: ORDER BY子句
        limit: LIMIT限制条数
        is_streaming: 是否为流式查询
    """
    original_sql: str
    ast: exp.Expression
    select_items: List[exp.Expression] = field(default_factory=list)
    from_table: Optional[str] = None
    where_condition: Optional[exp.Expression] = None
    group_by: List[exp.Expression] = field(default_factory=list)
    having_condition: Optional[exp.Expression] = None
    window_specs: List[WindowSpec] = field(default_factory=list)
    watermark: Optional[WatermarkSpec] = None
    emit_strategy: Optional[EmitStrategy] = None
    order_by: List[exp.Ordered] = field(default_factory=list)
    limit: Optional[int] = None
    is_streaming: bool = False


class SQLParser:
    """
    流式SQL语法解析器

    使用sqlglot作为底层SQL解析引擎，扩展支持流式SQL语法。
    """

    def __init__(self, dialect: str = "spark") -> None:
        """
        初始化SQL解析器

        参数:
            dialect: SQL方言，默认使用Spark SQL方言（流式SQL支持较好）
        """
        self.dialect: str = dialect
        self._window_functions: Dict[str, WindowType] = {
            "TUMBLE": WindowType.TUMBLE,
            "HOP": WindowType.HOP,
            "SESSION": WindowType.SESSION,
            "TUMBLE_START": WindowType.TUMBLE,
            "TUMBLE_END": WindowType.TUMBLE,
            "HOP_START": WindowType.HOP,
            "HOP_END": WindowType.HOP,
            "SESSION_START": WindowType.SESSION,
            "SESSION_END": WindowType.SESSION,
        }

    def parse(self, sql: str) -> ParsedStatement:
        """
        解析流式SQL语句

        参数:
            sql: 流式SQL语句

        返回:
            ParsedStatement对象，包含解析后的所有信息

        异常:
            sqlglot.ParseError: 当SQL语法错误时抛出
            ValueError: 当流式语法不合法时抛出
        """
        try:
            ast = parse_one(sql, dialect=self.dialect)
        except sqlglot.ParseError as e:
            raise ValueError(f"SQL语法错误: {e}") from e

        if not isinstance(ast, exp.Select):
            raise ValueError("仅支持SELECT查询语句")

        stmt = ParsedStatement(
            original_sql=sql,
            ast=ast,
            is_streaming=self._detect_streaming(ast),
        )

        stmt.select_items = self._parse_select(ast)
        stmt.from_table = self._parse_from(ast)
        stmt.where_condition = self._parse_where(ast)
        stmt.group_by = self._parse_group_by(ast)
        stmt.having_condition = self._parse_having(ast)
        stmt.window_specs = self._parse_window_specs(ast)
        stmt.watermark = self._parse_watermark(ast)
        stmt.emit_strategy = self._parse_emit_strategy(ast)
        stmt.order_by = self._parse_order_by(ast)
        stmt.limit = self._parse_limit(ast)

        return stmt

    def _detect_streaming(self, ast: exp.Select) -> bool:
        """
        检测是否为流式查询

        参数:
            ast: SQL抽象语法树

        返回:
            True表示为流式查询
        """
        for node in ast.walk():
            if isinstance(node, exp.Func):
                func_name = node.name.upper()
                if func_name in self._window_functions:
                    return True
                if func_name == "WATERMARK":
                    return True

        sql_upper = ast.sql().upper()
        if "EMIT" in sql_upper:
            return True
        if "TUMBLE" in sql_upper or "HOP" in sql_upper or "SESSION" in sql_upper:
            return True

        return False

    def _parse_select(self, ast: exp.Select) -> List[exp.Expression]:
        """
        解析SELECT子句

        参数:
            ast: SQL抽象语法树

        返回:
            SELECT表达式列表
        """
        return list(ast.expressions)

    def _parse_from(self, ast: exp.Select) -> Optional[str]:
        """
        解析FROM子句

        参数:
            ast: SQL抽象语法树

        返回:
            表名，如果没有FROM子句则返回None
        """
        from_clause = ast.find(exp.From)
        if not from_clause:
            return None

        table = from_clause.find(exp.Table)
        if table:
            return table.name

        return str(from_clause.this)

    def _parse_where(self, ast: exp.Select) -> Optional[exp.Expression]:
        """
        解析WHERE子句

        参数:
            ast: SQL抽象语法树

        返回:
            WHERE条件表达式，如果没有则返回None
        """
        where = ast.find(exp.Where)
        return where.this if where else None

    def _parse_group_by(self, ast: exp.Select) -> List[exp.Expression]:
        """
        解析GROUP BY子句

        参数:
            ast: SQL抽象语法树

        返回:
            GROUP BY表达式列表
        """
        group = ast.find(exp.Group)
        if not group:
            return []
        return list(group.expressions)

    def _parse_having(self, ast: exp.Select) -> Optional[exp.Expression]:
        """
        解析HAVING子句

        参数:
            ast: SQL抽象语法树

        返回:
            HAVING条件表达式，如果没有则返回None
        """
        having = ast.find(exp.Having)
        return having.this if having else None

    def _parse_window_specs(self, ast: exp.Select) -> List[WindowSpec]:
        """
        解析窗口定义

        参数:
            ast: SQL抽象语法树

        返回:
            窗口定义列表
        """
        window_specs: List[WindowSpec] = []

        for node in ast.walk():
            if isinstance(node, exp.Func):
                func_name = node.name.upper()
                if func_name in ("TUMBLE", "HOP", "SESSION"):
                    spec = self._parse_window_function(node)
                    if spec:
                        window_specs.append(spec)

        return window_specs

    def _parse_window_function(self, func: exp.Func) -> Optional[WindowSpec]:
        """
        解析窗口函数

        参数:
            func: 窗口函数表达式

        返回:
            WindowSpec对象，如果解析失败则返回None
        """
        func_name = func.name.upper()
        window_type = self._window_functions.get(func_name)
        if not window_type:
            return None

        args = list(func.args.values())
        if len(args) < 2:
            raise ValueError(f"窗口函数{func_name}至少需要2个参数")

        time_col = args[0].name if hasattr(args[0], "name") else str(args[0])
        size = args[1].sql() if hasattr(args[1], "sql") else str(args[1])

        spec = WindowSpec(
            window_type=window_type,
            time_column=time_col,
            size=size,
        )

        if window_type == WindowType.HOP and len(args) >= 3:
            spec.slide = args[2].sql() if hasattr(args[2], "sql") else str(args[2])

        if window_type == WindowType.SESSION and len(args) >= 3:
            spec.gap = args[2].sql() if hasattr(args[2], "sql") else str(args[2])

        return spec

    def _parse_watermark(self, ast: exp.Select) -> Optional[WatermarkSpec]:
        """
        解析WATERMARK定义

        参数:
            ast: SQL抽象语法树

        返回:
            WatermarkSpec对象，如果没有定义则返回None
        """
        for node in ast.walk():
            if isinstance(node, exp.Func) and node.name.upper() == "WATERMARK":
                args = list(node.args.values())
                if len(args) >= 2:
                    event_col = args[0].name if hasattr(args[0], "name") else str(args[0])
                    delay = args[1].sql() if hasattr(args[1], "sql") else str(args[1])
                    strategy = "BOUNDED"
                    if len(args) >= 3:
                        strategy = str(args[2]).strip("'\"")
                    return WatermarkSpec(
                        event_time_column=event_col,
                        delay=delay,
                        strategy=strategy,
                    )

        sql_upper = ast.sql().upper()
        if "WATERMARK FOR" in sql_upper:
            from_clause = ast.find(exp.From)
            if from_clause:
                table_alias = from_clause.find(exp.TableAlias)
                if table_alias:
                    for hint in ast.find_all(exp.Hint):
                        if "WATERMARK" in hint.sql().upper():
                            return WatermarkSpec(
                                event_time_column="event_time",
                                delay="INTERVAL '5' SECOND",
                                strategy="BOUNDED",
                            )

        return None

    def _parse_emit_strategy(self, ast: exp.Select) -> Optional[EmitStrategy]:
        """
        解析EMIT输出策略

        参数:
            ast: SQL抽象语法树

        返回:
            EmitStrategy对象，如果没有定义则返回默认策略
        """
        sql = ast.sql()

        if "EMIT" not in sql.upper():
            return EmitStrategy(trigger_type="ON_WATERMARK")

        emit_idx = sql.upper().find("EMIT")
        emit_sql = sql[emit_idx:]

        strategy = EmitStrategy()

        if "ON WATERMARK" in emit_sql.upper():
            strategy.trigger_type = "ON_WATERMARK"
        elif "PERIODIC" in emit_sql.upper():
            strategy.trigger_type = "PERIODIC"
            import re
            match = re.search(r"INTERVAL\s+'[^']+'\s+\w+", emit_sql, re.IGNORECASE)
            if match:
                strategy.interval = match.group(0)
        elif "EARLY" in emit_sql.upper():
            strategy.trigger_type = "EARLY"

        if "ALLOW RETRACTIONS" in emit_sql.upper():
            strategy.include_retractions = True

        return strategy

    def _parse_order_by(self, ast: exp.Select) -> List[exp.Ordered]:
        """
        解析ORDER BY子句

        参数:
            ast: SQL抽象语法树

        返回:
            ORDER BY表达式列表
        """
        order = ast.find(exp.Order)
        if not order:
            return []
        return list(order.expressions)

    def _parse_limit(self, ast: exp.Select) -> Optional[int]:
        """
        解析LIMIT子句

        参数:
            ast: SQL抽象语法树

        返回:
            LIMIT限制条数，如果没有则返回None
        """
        limit = ast.find(exp.Limit)
        if not limit:
            return None
        return int(limit.expression.this) if limit.expression else None

    def validate_streaming_sql(self, sql: str) -> Tuple[bool, List[str]]:
        """
        验证流式SQL语法是否合法

        参数:
            sql: 流式SQL语句

        返回:
            (是否合法, 错误信息列表)
        """
        errors: List[str] = []

        try:
            stmt = self.parse(sql)
        except Exception as e:
            return False, [str(e)]

        if not stmt.is_streaming:
            errors.append("未检测到流式SQL语法特征（如窗口函数、WATERMARK等）")

        for spec in stmt.window_specs:
            if spec.window_type == WindowType.HOP and not spec.slide:
                errors.append("HOP窗口必须指定滑动步长")
            if spec.window_type == WindowType.SESSION and not spec.gap:
                errors.append("SESSION窗口必须指定会话超时时间")

        if stmt.group_by:
            has_window_group = False
            for expr in stmt.group_by:
                expr_str = str(expr).upper()
                if "TUMBLE" in expr_str or "HOP" in expr_str or "SESSION" in expr_str:
                    has_window_group = True
                    break
            if not has_window_group and stmt.window_specs:
                errors.append("流式聚合查询必须在GROUP BY中包含窗口函数")

        return len(errors) == 0, errors

    def format_sql(self, sql: str, pretty: bool = True) -> str:
        """
        格式化SQL语句

        参数:
            sql: 原始SQL语句
            pretty: 是否使用美化格式

        返回:
            格式化后的SQL语句
        """
        ast = parse_one(sql, dialect=self.dialect)
        return ast.sql(pretty=pretty, dialect=self.dialect)

    def extract_streaming_metadata(self, sql: str) -> Dict[str, Any]:
        """
        提取流式SQL元数据信息

        参数:
            sql: 流式SQL语句

        返回:
            包含元数据的字典
        """
        stmt = self.parse(sql)

        metadata: Dict[str, Any] = {
            "is_streaming": stmt.is_streaming,
            "source_table": stmt.from_table,
            "window_types": [spec.window_type.value for spec in stmt.window_specs],
            "has_watermark": stmt.watermark is not None,
            "emit_strategy": stmt.emit_strategy.trigger_type if stmt.emit_strategy else None,
            "group_by_count": len(stmt.group_by),
            "has_where": stmt.where_condition is not None,
            "has_having": stmt.having_condition is not None,
            "columns": self._extract_columns(stmt),
        }

        if stmt.watermark:
            metadata["watermark_column"] = stmt.watermark.event_time_column
            metadata["watermark_delay"] = stmt.watermark.delay

        return metadata

    def _extract_columns(self, stmt: ParsedStatement) -> List[str]:
        """
        从SELECT子句中提取列名

        参数:
            stmt: 解析后的语句

        返回:
            列名列表
        """
        columns: List[str] = []
        for item in stmt.select_items:
            if isinstance(item, exp.Alias):
                columns.append(item.alias)
            elif isinstance(item, exp.Column):
                columns.append(item.name)
            elif isinstance(item, exp.Star):
                columns.append("*")
            else:
                columns.append(str(item))
        return columns
