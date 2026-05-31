import pytest
from streamsql.modules.streaming_query.parser import StreamingQueryParser
from streamsql.modules.streaming_query.logical_plan import LogicalPlanner, NodeType
from streamsql.modules.streaming_query.optimizer import LogicalOptimizer
from streamsql.modules.streaming_query.physical_plan import PhysicalPlanTranslator, ExecutionMode


def test_query_parser_parse_simple_select():
    parser = StreamingQueryParser()
    parsed = parser.parse("SELECT id, name FROM users WHERE age > 18")
    assert parsed["tables"] == ["users"]
    assert "id" in parsed["columns"]
    assert "name" in parsed["columns"]
    assert parsed["where"] is not None


def test_query_parser_parse_with_window():
    parser = StreamingQueryParser()
    parsed = parser.parse(
        "SELECT user_id, COUNT(*) OVER (TUMBLE(INTERVAL '1' HOUR)) as cnt "
        "FROM events GROUP BY user_id"
    )
    assert parsed["tables"] == ["events"]
    assert "window" in parsed


def test_query_parser_extract_tables():
    parser = StreamingQueryParser()
    sql = """
    SELECT u.name, o.total
    FROM users u
    JOIN orders o ON u.id = o.user_id
    WHERE u.age > 18
    """
    tables = parser.extract_tables(sql)
    assert "users" in tables
    assert "orders" in tables


def test_logical_planner_build_plan():
    planner = LogicalPlanner()
    parsed_query = {
        "columns": ["id", "name"],
        "tables": ["users"],
        "where": "age > 18",
    }
    plan = planner.build_plan(parsed_query)
    assert plan["type"] == NodeType.PROJECT.value
    assert plan["columns"] == ["id", "name"]
    assert plan["child"]["type"] == NodeType.FILTER.value
    assert plan["child"]["child"]["type"] == NodeType.SCAN.value


def test_logical_planner_build_join_plan():
    planner = LogicalPlanner()
    parsed_query = {
        "columns": ["u.name", "o.total"],
        "tables": ["users", "orders"],
        "joins": [{"left": "users", "right": "orders", "condition": "u.id = o.user_id"}],
    }
    plan = planner.build_plan(parsed_query)
    assert plan["type"] == NodeType.PROJECT.value
    assert plan["child"]["type"] == NodeType.JOIN.value


def test_optimizer_predicate_pushdown():
    optimizer = LogicalOptimizer()
    plan = {
        "type": NodeType.PROJECT.value,
        "columns": ["id", "name"],
        "child": {
            "type": NodeType.FILTER.value,
            "condition": "age > 18",
            "child": {
                "type": NodeType.SCAN.value,
                "table": "users",
            },
        },
    }
    optimized = optimizer.optimize(plan)
    assert optimized["child"]["type"] == NodeType.FILTER.value
    assert "age > 18" in optimized["child"]["condition"]


def test_optimizer_projection_pushdown():
    optimizer = LogicalOptimizer()
    plan = {
        "type": NodeType.PROJECT.value,
        "columns": ["id", "name"],
        "child": {
            "type": NodeType.SCAN.value,
            "table": "users",
            "columns": ["id", "name", "age", "email"],
        },
    }
    optimized = optimizer.optimize(plan)
    assert optimized["child"]["columns"] == ["id", "name"]


def test_physical_plan_translator_translate():
    translator = PhysicalPlanTranslator(mode=ExecutionMode.STREAMING)
    logical_plan = {
        "type": NodeType.PROJECT.value,
        "columns": ["id", "name"],
        "child": {
            "type": NodeType.SCAN.value,
            "table": "users",
        },
    }
    physical_plan = translator.translate(logical_plan)
    assert "operators" in physical_plan
    assert physical_plan["execution_mode"] == ExecutionMode.STREAMING.value
    assert len(physical_plan["operators"]) == 2


def test_physical_plan_validate():
    translator = PhysicalPlanTranslator(mode=ExecutionMode.BATCH)
    valid_plan = {
        "operators": [
            {"type": "SCAN", "table": "users"},
            {"type": "PROJECT", "columns": ["id", "name"]},
        ],
        "execution_mode": "BATCH",
    }
    assert translator.validate(valid_plan) is True

    invalid_plan = {
        "operators": [
            {"type": "PROJECT", "columns": ["id"]},
        ],
        "execution_mode": "BATCH",
    }
    assert translator.validate(invalid_plan) is False
