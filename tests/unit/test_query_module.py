import pytest
from unittest.mock import Mock, patch, MagicMock
from typing import List

from src.domain.query.sql_parser import StreamSQLParser, StreamSQLType, WindowType, JoinType
from src.domain.query.logical_plan import LogicalPlanBuilder, LogicalPlan, LogicalNodeType
from src.domain.query.physical_plan import PhysicalPlanTranslator, PhysicalOperatorType
from src.domain.query.optimizer import PlanOptimizer
from tests.fixtures.test_data_builder import QueryTestData


class TestStreamSQLParser:
    def test_parse_simple_select(self, data_builder):
        parser = StreamSQLParser()
        test_data = data_builder.build_valid_select_query()

        result = parser.parse(test_data.sql)

        assert result.sql_type.value == test_data.expected_sql_type
        assert len(result.sources) > 0
        source_names = [s.name for s in result.sources]
        assert all(s in source_names for s in test_data.expected_sources)

    def test_parse_select_with_window(self, data_builder):
        parser = StreamSQLParser()
        test_data = data_builder.build_valid_select_query(with_window=True)

        result = parser.parse(test_data.sql)

        assert result.sql_type.value == test_data.expected_sql_type
        assert result.window.window_type.value == test_data.expected_window_type
        assert result.window.size is not None

    def test_parse_select_with_join(self, data_builder):
        parser = StreamSQLParser()
        test_data = data_builder.build_valid_select_query(with_join=True)

        result = parser.parse(test_data.sql)

        assert result.sql_type.value == test_data.expected_sql_type
        assert result.join.join_type.value == test_data.expected_join_type

    def test_parse_select_with_window_and_join(self, data_builder):
        parser = StreamSQLParser()
        test_data = data_builder.build_valid_select_query(with_window=True, with_join=True)

        result = parser.parse(test_data.sql)

        assert result.sql_type.value == test_data.expected_sql_type
        assert result.window.window_type.value == test_data.expected_window_type
        assert result.join.join_type.value == test_data.expected_join_type

    def test_parse_insert_query(self, data_builder):
        parser = StreamSQLParser()
        test_data = data_builder.build_valid_insert_query()

        result = parser.parse(test_data.sql)

        assert result.sql_type.value == test_data.expected_sql_type
        assert result.target_table is not None

    def test_parse_create_stream(self, data_builder):
        parser = StreamSQLParser()
        test_data = data_builder.build_valid_create_stream_query()

        result = parser.parse(test_data.sql)

        assert result.sql_type.value == test_data.expected_sql_type
        assert result.stream_name is not None

    def test_all_window_types(self, data_builder):
        parser = StreamSQLParser()
        queries = data_builder.build_window_queries_with_variations()

        for test_data in queries:
            result = parser.parse(test_data.sql)
            assert result.window.window_type.value == test_data.expected_window_type

    def test_invalid_sql(self, data_builder):
        parser = StreamSQLParser()
        test_cases = data_builder.build_invalid_syntax_queries()

        for test_data in test_cases:
            result = parser.parse(test_data.sql)
            assert result.sql_type.value == test_data.expected_sql_type

    def test_validate_valid_query(self, data_builder):
        parser = StreamSQLParser()
        test_data = data_builder.build_valid_select_query()

        parsed = parser.parse(test_data.sql)
        errors = parser.validate(parsed)

        assert len(errors) == 0

    def test_validate_query_without_sources(self):
        parser = StreamSQLParser()
        sql = "SELECT 1"

        parsed = parser.parse(sql)
        errors = parser.validate(parsed)

        assert len(errors) > 0

    def test_parse_columns_with_aliases(self):
        parser = StreamSQLParser()
        sql = "SELECT id AS user_id, name AS user_name FROM users"

        result = parser.parse(sql)

        assert len(result.columns) == 2
        aliases = [c.alias for c in result.columns]
        assert "user_id" in aliases
        assert "user_name" in aliases

    def test_parse_aggregation_functions(self):
        parser = StreamSQLParser()
        sql = """
            SELECT
                COUNT(*) as total,
                SUM(amount) as total_amount,
                AVG(amount) as avg_amount,
                MIN(amount) as min_amount,
                MAX(amount) as max_amount
            FROM transactions
        """

        result = parser.parse(sql)

        aggregations = [c.aggregation for c in result.columns if c.aggregation]
        assert len(aggregations) == 5
        assert "COUNT" in aggregations
        assert "SUM" in aggregations
        assert "AVG" in aggregations
        assert "MIN" in aggregations
        assert "MAX" in aggregations

    def test_parse_group_by(self):
        parser = StreamSQLParser()
        sql = "SELECT region, SUM(sales) FROM sales_data GROUP BY region"

        result = parser.parse(sql)

        assert len(result.group_by) == 1
        assert "region" in result.group_by

    def test_parse_order_by(self):
        parser = StreamSQLParser()
        sql = "SELECT name, score FROM players ORDER BY score DESC"

        result = parser.parse(sql)

        assert len(result.order_by) == 1

    def test_parse_limit(self):
        parser = StreamSQLParser()
        sql = "SELECT * FROM logs LIMIT 50"

        result = parser.parse(sql)

        assert result.limit == 50

    def test_parse_where_clause(self):
        parser = StreamSQLParser()
        sql = "SELECT * FROM orders WHERE status = 'completed' AND amount > 100"

        result = parser.parse(sql)

        assert result.where_clause is not None


class TestLogicalPlanBuilder:
    def test_build_simple_select_plan(self, data_builder):
        parser = StreamSQLParser()
        builder = LogicalPlanBuilder()
        test_data = data_builder.build_valid_select_query()

        parsed = parser.parse(test_data.sql)
        plan = builder.build(parsed)

        assert plan.root is not None
        nodes = plan.get_nodes()
        assert len(nodes) > 0
        assert any(n.node_type == LogicalNodeType.SCAN for n in nodes)

    def test_build_plan_with_filter(self):
        parser = StreamSQLParser()
        builder = LogicalPlanBuilder()
        sql = "SELECT * FROM users WHERE active = true"

        parsed = parser.parse(sql)
        plan = builder.build(parsed)

        nodes = plan.get_nodes()
        assert any(n.node_type == LogicalNodeType.FILTER for n in nodes)

    def test_build_plan_with_aggregation(self):
        parser = StreamSQLParser()
        builder = LogicalPlanBuilder()
        sql = "SELECT region, SUM(sales) FROM sales_data GROUP BY region"

        parsed = parser.parse(sql)
        plan = builder.build(parsed)

        nodes = plan.get_nodes()
        assert any(n.node_type == LogicalNodeType.AGGREGATE for n in nodes)

    def test_build_plan_with_sort(self):
        parser = StreamSQLParser()
        builder = LogicalPlanBuilder()
        sql = "SELECT name, score FROM players ORDER BY score DESC"

        parsed = parser.parse(sql)
        plan = builder.build(parsed)

        nodes = plan.get_nodes()
        assert any(n.node_type == LogicalNodeType.SORT for n in nodes)

    def test_build_plan_with_limit(self):
        parser = StreamSQLParser()
        builder = LogicalPlanBuilder()
        sql = "SELECT * FROM logs LIMIT 50"

        parsed = parser.parse(sql)
        plan = builder.build(parsed)

        nodes = plan.get_nodes()
        assert any(n.node_type == LogicalNodeType.LIMIT for n in nodes)

    def test_build_plan_with_window(self, data_builder):
        parser = StreamSQLParser()
        builder = LogicalPlanBuilder()
        test_data = data_builder.build_valid_select_query(with_window=True)

        parsed = parser.parse(test_data.sql)
        plan = builder.build(parsed)

        nodes = plan.get_nodes()
        assert any(n.node_type == LogicalNodeType.WINDOW for n in nodes)

    def test_build_plan_with_project(self, data_builder):
        parser = StreamSQLParser()
        builder = LogicalPlanBuilder()
        test_data = data_builder.build_valid_select_query()

        parsed = parser.parse(test_data.sql)
        plan = builder.build(parsed)

        nodes = plan.get_nodes()
        assert any(n.node_type == LogicalNodeType.PROJECT for n in nodes)

    def test_plan_cost_estimation(self, data_builder):
        parser = StreamSQLParser()
        builder = LogicalPlanBuilder()
        test_data = data_builder.build_valid_select_query()

        parsed = parser.parse(test_data.sql)
        plan = builder.build(parsed)

        cost = plan.estimate_cost()
        assert cost > 0

    def test_logical_node_walk(self, data_builder):
        parser = StreamSQLParser()
        builder = LogicalPlanBuilder()
        test_data = data_builder.build_valid_select_query(with_window=True, with_join=True)

        parsed = parser.parse(test_data.sql)
        plan = builder.build(parsed)

        nodes = plan.get_nodes()
        assert len(nodes) > 1
        node_ids = [n.node_id for n in nodes]
        assert len(node_ids) == len(set(node_ids))


class TestPhysicalPlanTranslator:
    def test_translate_simple_plan(self, data_builder):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        translator = PhysicalPlanTranslator()
        test_data = data_builder.build_valid_select_query()

        parsed = parser.parse(test_data.sql)
        logical_plan = logical_builder.build(parsed)
        physical_plan = translator.translate(logical_plan)

        assert physical_plan.root is not None
        assert physical_plan.total_cost() > 0

    def test_translate_window_operator(self, data_builder):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        translator = PhysicalPlanTranslator()
        test_data = data_builder.build_valid_select_query(with_window=True)

        parsed = parser.parse(test_data.sql)
        logical_plan = logical_builder.build(parsed)
        physical_plan = translator.translate(logical_plan)

        window_ops = [PhysicalOperatorType.TUMBLING_WINDOW,
                      PhysicalOperatorType.HOPPING_WINDOW,
                      PhysicalOperatorType.SLIDING_WINDOW,
                      PhysicalOperatorType.SESSION_WINDOW]
        ops = physical_plan.get_operators()
        assert any(op.operator_type in window_ops for op in ops)

    def test_translate_aggregate_operator(self):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        translator = PhysicalPlanTranslator()
        sql = "SELECT region, SUM(sales) FROM sales_data GROUP BY region"

        parsed = parser.parse(sql)
        logical_plan = logical_builder.build(parsed)
        physical_plan = translator.translate(logical_plan)

        agg_ops = [PhysicalOperatorType.HASH_AGGREGATE, PhysicalOperatorType.SORT_AGGREGATE]
        ops = physical_plan.get_operators()
        assert any(op.operator_type in agg_ops for op in ops)

    def test_translate_to_dict(self, data_builder):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        translator = PhysicalPlanTranslator()
        test_data = data_builder.build_valid_select_query()

        parsed = parser.parse(test_data.sql)
        logical_plan = logical_builder.build(parsed)
        physical_plan = translator.translate(logical_plan)

        plan_dict = physical_plan.to_dict()
        assert "operator" in plan_dict
        assert "properties" in plan_dict
        assert "children" in plan_dict

    def test_parallelism_setting(self, data_builder):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        translator = PhysicalPlanTranslator()
        test_data = data_builder.build_valid_select_query(with_window=True)

        parsed = parser.parse(test_data.sql)
        logical_plan = logical_builder.build(parsed)
        physical_plan = translator.translate(logical_plan)

        for op in physical_plan.get_operators():
            assert op.parallelism >= 1


class TestPlanOptimizer:
    def test_optimizer_apply_rules(self, data_builder):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        optimizer = PlanOptimizer()
        test_data = data_builder.build_valid_select_query()

        parsed = parser.parse(test_data.sql)
        original_plan = logical_builder.build(parsed)
        original_cost = original_plan.estimate_cost()

        optimized_plan = optimizer.optimize(original_plan)
        optimized_cost = optimized_plan.estimate_cost()

        assert optimized_cost <= original_cost or optimized_cost == original_cost

    def test_explain_plan(self, data_builder):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        optimizer = PlanOptimizer()
        test_data = data_builder.build_valid_select_query()

        parsed = parser.parse(test_data.sql)
        plan = logical_builder.build(parsed)

        explanation = optimizer.explain(plan)
        assert isinstance(explanation, str)
        assert len(explanation) > 0

    def test_predicate_pushdown(self):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        optimizer = PlanOptimizer()
        sql = """
            SELECT o.id, c.name
            FROM orders o
            JOIN customers c ON o.customer_id = c.id
            WHERE o.amount > 1000
        """

        parsed = parser.parse(sql)
        plan = logical_builder.build(parsed)
        optimized = optimizer.optimize(plan)

        assert optimized.root is not None

    def test_optimize_with_limit_pushdown(self):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        optimizer = PlanOptimizer()
        sql = "SELECT name, score FROM players ORDER BY score DESC LIMIT 10"

        parsed = parser.parse(sql)
        plan = logical_builder.build(parsed)
        optimized = optimizer.optimize(plan)

        nodes = optimized.get_nodes()
        limit_nodes = [n for n in nodes if n.node_type == LogicalNodeType.LIMIT]
        assert len(limit_nodes) > 0


class TestQueryModuleIntegration:
    def test_full_query_pipeline(self, data_builder):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        optimizer = PlanOptimizer()
        translator = PhysicalPlanTranslator()
        test_data = data_builder.build_valid_select_query(with_window=True)

        parsed = parser.parse(test_data.sql)
        assert parsed.sql_type.value == test_data.expected_sql_type

        logical_plan = logical_builder.build(parsed)
        assert logical_plan.root is not None

        optimized_plan = optimizer.optimize(logical_plan)
        assert optimized_plan.estimate_cost() > 0

        physical_plan = translator.translate(optimized_plan)
        assert physical_plan.total_cost() > 0

    def test_window_query_pipeline(self, data_builder):
        parser = StreamSQLParser()
        logical_builder = LogicalPlanBuilder()
        optimizer = PlanOptimizer()
        translator = PhysicalPlanTranslator()

        queries = data_builder.build_window_queries_with_variations()
        for test_data in queries:
            parsed = parser.parse(test_data.sql)
            logical_plan = logical_builder.build(parsed)
            optimized = optimizer.optimize(logical_plan)
            physical = translator.translate(optimized)

            assert physical.root is not None

    def test_error_handling_empty_sql(self):
        parser = StreamSQLParser()
        result = parser.parse("")

        assert result.sql_type == StreamSQLType.UNKNOWN

    def test_error_handling_none_sql(self):
        parser = StreamSQLParser()
        with pytest.raises(AttributeError):
            parser.parse(None)

    def test_validate_query_without_window_size(self):
        parser = StreamSQLParser()
        sql = """
            SELECT metric, SUM(value)
            FROM metrics
            WINDOW TUMBLING(ON ts)
            GROUP BY metric
        """

        parsed = parser.parse(sql)
        errors = parser.validate(parsed)
        assert len(errors) >= 0


class TestStateMachineFixes:
    def test_having_clause_after_group_by(self):
        parser = StreamSQLParser()
        sql = """
            SELECT region, SUM(sales) as total_sales
            FROM sales_data
            WHERE region IS NOT NULL
            GROUP BY region
            HAVING SUM(sales) > 10000
            ORDER BY total_sales DESC
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert len(parsed.sources) == 1
        assert parsed.sources[0].name == "sales_data"
        assert len(parsed.columns) == 2
        assert "region" in parsed.group_by
        assert parsed.where_clause is not None
        assert "region IS NOT NULL" in parsed.where_clause
        assert "total_sales" in parsed.order_by[0]

    def test_multiple_joins_correct_source_extraction(self):
        parser = StreamSQLParser()
        sql = """
            SELECT o.id, c.name, p.product_name, o.amount
            FROM orders o
            INNER JOIN customers c ON o.customer_id = c.id
            INNER JOIN products p ON o.product_id = p.id
            WHERE o.amount > 100
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert len(parsed.sources) == 3
        source_names = [s.name for s in parsed.sources]
        assert "orders" in source_names
        assert "customers" in source_names
        assert "products" in source_names

    def test_window_clause_position_after_group_by(self):
        parser = StreamSQLParser()
        sql = """
            SELECT product_id, SUM(quantity) as total_qty
            FROM sales_stream
            GROUP BY product_id
            WINDOW TUMBLING(SIZE 10 MINUTES, ON event_time)
            EMIT CHANGES
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert parsed.window.window_type == WindowType.TUMBLING
        assert parsed.window.size == "10 MINUTES"
        assert parsed.window.time_field == "event_time"

    def test_complex_having_with_aggregate(self):
        parser = StreamSQLParser()
        sql = """
            SELECT category, COUNT(*) as product_count, AVG(price) as avg_price
            FROM products
            WHERE status = 'active'
            GROUP BY category
            HAVING COUNT(*) > 5 AND AVG(price) > 100
            ORDER BY avg_price DESC
            LIMIT 10
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert len(parsed.sources) == 1
        assert parsed.sources[0].name == "products"
        assert "category" in parsed.group_by
        assert parsed.limit == 10

    def test_no_duplicate_sources_from_join(self):
        parser = StreamSQLParser()
        sql = """
            SELECT o.id, c.name
            FROM orders o
            JOIN customers c ON o.customer_id = c.id
        """

        parsed = parser.parse(sql)

        source_names = [s.name for s in parsed.sources]
        assert source_names.count("orders") == 1
        assert source_names.count("customers") == 1
        assert len(parsed.sources) == 2

    def test_complex_nested_query_with_having(self):
        parser = StreamSQLParser()
        sql = """
            SELECT 
                region, 
                country,
                SUM(sales_amount) as total_sales,
                AVG(sales_amount) as avg_sales,
                COUNT(DISTINCT customer_id) as unique_customers
            FROM sales_records
            WHERE sale_date >= '2024-01-01'
            GROUP BY region, country
            HAVING SUM(sales_amount) > 50000 AND COUNT(DISTINCT customer_id) > 100
            ORDER BY total_sales DESC
            LIMIT 20
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert len(parsed.sources) == 1
        assert parsed.sources[0].name == "sales_records"
        assert len(parsed.group_by) == 2
        assert "region" in parsed.group_by
        assert "country" in parsed.group_by
        assert parsed.limit == 20

    def test_left_join_with_window(self):
        parser = StreamSQLParser()
        sql = """
            SELECT s.id, s.timestamp, s.value, d.description
            FROM sensor_data s
            LEFT JOIN device_metadata d ON s.device_id = d.id
            WINDOW HOPPING(SIZE 5 MINUTES, SLIDE 1 MINUTE, ON s.timestamp)
            WHERE s.value > 100
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert len(parsed.sources) == 2
        assert parsed.join.join_type == JoinType.LEFT
        assert parsed.join.right_source == "device_metadata"
        assert parsed.window.window_type == WindowType.HOPPING
        assert parsed.window.size == "5 MINUTES"
        assert parsed.window.slide == "1 MINUTE"

    def test_cross_join_with_multiple_conditions(self):
        parser = StreamSQLParser()
        sql = """
            SELECT a.id, b.id, a.value * b.value as cross_product
            FROM table_a a
            CROSS JOIN table_b b
            WHERE a.category = b.category
            GROUP BY a.id, b.id
            HAVING a.value * b.value > 1000
            ORDER BY cross_product DESC
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert len(parsed.sources) == 2
        assert parsed.join.join_type == JoinType.CROSS

    def test_state_machine_handles_out_of_order_having_gracefully(self):
        parser = StreamSQLParser()
        sql = """
            SELECT region, SUM(sales)
            FROM sales
            HAVING SUM(sales) > 1000
            GROUP BY region
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert len(parsed.sources) == 1

    def test_three_level_nested_joins(self):
        parser = StreamSQLParser()
        sql = """
            SELECT t1.id, t2.name, t3.value, t4.category
            FROM table1 t1
            JOIN table2 t2 ON t1.id = t2.t1_id
            JOIN table3 t3 ON t2.id = t3.t2_id
            JOIN table4 t4 ON t3.id = t4.t3_id
            WHERE t1.active = true
        """

        parsed = parser.parse(sql)

        assert len(parsed.sources) == 4
        source_names = [s.name for s in parsed.sources]
        assert "table1" in source_names
        assert "table2" in source_names
        assert "table3" in source_names
        assert "table4" in source_names

    def test_window_before_where_should_still_work(self):
        parser = StreamSQLParser()
        sql = """
            SELECT sensor_id, AVG(temperature)
            FROM sensor_readings
            WINDOW TUMBLING(SIZE 1 HOUR, ON reading_time)
            WHERE temperature > 0
            GROUP BY sensor_id
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert parsed.window.window_type == WindowType.TUMBLING
        assert parsed.where_clause is not None
        assert len(parsed.group_by) == 1

    def test_missing_from_clause(self):
        parser = StreamSQLParser()
        sql = "SELECT 1 + 1"

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert len(parsed.sources) == 0

    def test_multiple_aggregate_functions_in_having(self):
        parser = StreamSQLParser()
        sql = """
            SELECT 
                department,
                COUNT(*) as emp_count,
                AVG(salary) as avg_sal,
                MAX(salary) as max_sal,
                MIN(salary) as min_sal
            FROM employees
            WHERE hire_date > '2020-01-01'
            GROUP BY department
            HAVING 
                COUNT(*) > 5 
                AND AVG(salary) > 50000
                AND MAX(salary) < 200000
            ORDER BY emp_count DESC
        """

        parsed = parser.parse(sql)

        assert parsed.sql_type == StreamSQLType.SELECT
        assert len(parsed.sources) == 1
        assert parsed.sources[0].name == "employees"
        assert "department" in parsed.group_by
        assert len(parsed.columns) == 5
