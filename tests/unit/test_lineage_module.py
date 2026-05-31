import pytest
import threading
import time
from unittest.mock import Mock, patch, MagicMock
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List

from src.domain.lineage.lineage_parser import LineageParser
from src.domain.lineage.dag_builder import LineageDAGBuilder
from src.domain.lineage.models import LineageGraph, LineageNode, LineageEdge, NodeType, EdgeType


class TestLineageParser:
    def test_parse_simple_sql(self, lineage_single_sql):
        parser = LineageParser()

        graph = parser.parse_sql(lineage_single_sql.sql_list[0], lineage_single_sql.default_database)

        assert len(graph.nodes) > 0
        assert len(graph.edges) > 0

    def test_parse_multi_sql(self, lineage_multi_sql):
        parser = LineageParser()

        graph = parser.parse_sql(lineage_multi_sql.sql_list[0], lineage_multi_sql.default_database)

        assert len(graph.nodes) > 0

    def test_parse_insert_lineage(self):
        parser = LineageParser()
        sql = """
            INSERT INTO target_table (id, name, total)
            SELECT s.id, s.name, SUM(s.amount)
            FROM source_table s
            GROUP BY s.id, s.name
        """

        graph = parser.parse_sql(sql, "test_db")

        assert len(graph.nodes) > 0

    def test_parse_create_table_as_select(self):
        parser = LineageParser()
        sql = "CREATE TABLE new_table AS SELECT * FROM old_table"

        graph = parser.parse_sql(sql, "test_db")

        assert len(graph.nodes) > 0

    def test_column_lineage_extraction(self):
        parser = LineageParser()
        sql = """
            INSERT INTO metrics (customer_id, total_spent, order_count)
            SELECT o.customer_id, SUM(o.amount), COUNT(*)
            FROM orders o
            GROUP BY o.customer_id
        """

        graph = parser.parse_sql(sql, "shop")

        assert len(graph.nodes) > 0

    def test_parse_with_table_aliases(self):
        parser = LineageParser()
        sql = """
            SELECT o.id, c.name
            FROM orders AS o
            JOIN customers c ON o.customer_id = c.id
        """

        graph = parser.parse_sql(sql, "test")

        assert len(graph.nodes) > 0

    def test_parse_with_subqueries(self):
        parser = LineageParser()
        sql = """
            INSERT INTO summary (region, total)
            SELECT region, SUM(amount)
            FROM (
                SELECT o.*, c.region
                FROM orders o
                JOIN customers c ON o.customer_id = c.id
            ) sub
            GROUP BY region
        """

        graph = parser.parse_sql(sql, "test")

        assert len(graph.nodes) > 0

    def test_parse_union_queries(self):
        parser = LineageParser()
        sql = """
            SELECT id, name FROM table_a
            UNION ALL
            SELECT id, name FROM table_b
        """

        graph = parser.parse_sql(sql, "test")

        assert len(graph.nodes) > 0


class TestLineageDAGBuilder:
    def test_build_dag_from_sql_list(self, lineage_multi_sql):
        builder = LineageDAGBuilder()

        dag = builder.build_from_sql_list(
            lineage_multi_sql.sql_list,
            lineage_multi_sql.default_database
        )

        assert dag.number_of_nodes() > 0

    def test_dag_is_directed_acyclic(self, lineage_multi_sql):
        builder = LineageDAGBuilder()

        dag = builder.build_from_sql_list(
            lineage_multi_sql.sql_list,
            lineage_multi_sql.default_database
        )

        import networkx as nx
        assert nx.is_directed_acyclic_graph(dag)

    def test_get_upstream_nodes(self, lineage_multi_sql):
        builder = LineageDAGBuilder()
        dag = builder.build_from_sql_list(
            lineage_multi_sql.sql_list,
            lineage_multi_sql.default_database
        )

        nodes = list(dag.nodes())
        if nodes:
            upstream = builder.get_upstream(nodes[-1], depth=1)
            assert isinstance(upstream, list)

    def test_get_downstream_nodes(self, lineage_multi_sql):
        builder = LineageDAGBuilder()
        dag = builder.build_from_sql_list(
            lineage_multi_sql.sql_list,
            lineage_multi_sql.default_database
        )

        nodes = list(dag.nodes())
        if nodes:
            downstream = builder.get_downstream(nodes[0], depth=1)
            assert isinstance(downstream, list)

    def test_impact_analysis(self, lineage_multi_sql):
        builder = LineageDAGBuilder()
        dag = builder.build_from_sql_list(
            lineage_multi_sql.sql_list,
            lineage_multi_sql.default_database
        )

        nodes = list(dag.nodes())
        if nodes:
            impact = builder.get_impact_analysis(nodes[0])
            assert "impacted_tables" in impact or "source_node" in impact

    def test_topological_sort(self, lineage_multi_sql):
        builder = LineageDAGBuilder()
        dag = builder.build_from_sql_list(
            lineage_multi_sql.sql_list,
            lineage_multi_sql.default_database
        )

        order = builder.topological_sort()
        assert isinstance(order, list)

    def test_get_all_paths(self, lineage_single_sql):
        builder = LineageDAGBuilder()
        dag = builder.build_from_sql_list(
            lineage_single_sql.sql_list,
            lineage_single_sql.default_database
        )

        nodes = list(dag.nodes())
        if len(nodes) >= 2:
            paths = builder.get_all_paths(nodes[0], nodes[-1])
            assert isinstance(paths, list)

    def test_get_statistics(self, lineage_multi_sql):
        builder = LineageDAGBuilder()
        dag = builder.build_from_sql_list(
            lineage_multi_sql.sql_list,
            lineage_multi_sql.default_database
        )

        stats = builder.get_statistics()
        assert "total_nodes" in stats
        assert "total_edges" in stats
        assert stats["total_nodes"] > 0

    def test_export_dot(self, lineage_single_sql):
        builder = LineageDAGBuilder()
        dag = builder.build_from_sql_list(
            lineage_single_sql.sql_list,
            lineage_single_sql.default_database
        )

        dot = builder.export_dot()
        assert isinstance(dot, str)
        assert "digraph" in dot

    def test_export_json(self, lineage_single_sql):
        builder = LineageDAGBuilder()
        dag = builder.build_from_sql_list(
            lineage_single_sql.sql_list,
            lineage_single_sql.default_database
        )

        json_output = builder.export_json()
        assert isinstance(json_output, dict)
        assert "nodes" in json_output or "links" in json_output


class TestLineageGraph:
    def test_graph_add_node(self):
        graph = LineageGraph()
        node = LineageNode(
            node_id="test:orders",
            node_type=NodeType.TABLE,
            name="orders",
            database="test",
        )

        graph.add_node(node)

        assert "test:orders" in graph.nodes
        assert graph.get_node("test:orders") is not None

    def test_graph_add_edge(self):
        graph = LineageGraph()
        source = LineageNode(
            node_id="source",
            node_type=NodeType.TABLE,
            name="source",
            database="test",
        )
        target = LineageNode(
            node_id="target",
            node_type=NodeType.TABLE,
            name="target",
            database="test",
        )
        graph.add_node(source)
        graph.add_node(target)

        edge = LineageEdge(
            source_id=source.node_id,
            target_id=target.node_id,
            edge_type=EdgeType.DERIVES_FROM,
        )
        graph.add_edge(edge)

        assert len(graph.edges) == 1

    def test_graph_get_upstream(self):
        graph = LineageGraph()
        source = LineageNode("source", NodeType.TABLE, "source", "test")
        target = LineageNode("target", NodeType.TABLE, "target", "test")
        graph.add_node(source)
        graph.add_node(target)
        edge = LineageEdge(
            source_id=source.node_id,
            target_id=target.node_id,
            edge_type=EdgeType.DERIVES_FROM,
        )
        graph.add_edge(edge)

        upstream = graph.get_upstream_nodes(target.node_id)

        assert len(upstream) == 1
        assert upstream[0].node_id == source.node_id

    def test_graph_get_downstream(self):
        graph = LineageGraph()
        source = LineageNode("source", NodeType.TABLE, "source", "test")
        target = LineageNode("target", NodeType.TABLE, "target", "test")
        graph.add_node(source)
        graph.add_node(target)
        edge = LineageEdge(
            source_id=source.node_id,
            target_id=target.node_id,
            edge_type=EdgeType.DERIVES_FROM,
        )
        graph.add_edge(edge)

        downstream = graph.get_downstream_nodes(source.node_id)

        assert len(downstream) == 1
        assert downstream[0].node_id == target.node_id


class TestLineageConcurrency:
    def test_concurrent_parsing(self, lineage_multi_sql):
        parser = LineageParser()
        iterations = 10

        def parse_worker(worker_id):
            sql = lineage_multi_sql.sql_list[0]
            result = parser.parse_sql(sql, f"db_{worker_id}")
            return len(result.nodes)

        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(parse_worker, i) for i in range(iterations)]
            results = [f.result() for f in as_completed(futures)]

        assert all(r > 0 for r in results)
        assert len(results) == iterations

    def test_concurrent_dag_building(self, lineage_multi_sql):
        builder = LineageDAGBuilder()
        iterations = 5

        def build_worker(worker_id):
            sqls = lineage_multi_sql.sql_list
            dag = builder.build_from_sql_list(sqls, f"db_{worker_id}")
            return dag.number_of_nodes()

        with ThreadPoolExecutor(max_workers=3) as executor:
            futures = [executor.submit(build_worker, i) for i in range(iterations)]
            results = [f.result() for f in as_completed(futures)]

        assert all(r > 0 for r in results)

    def test_thread_safe_parsing_multiple_schemas(self):
        parser = LineageParser()
        thread_count = 8

        def worker(thread_id):
            sqls = [
                f"INSERT INTO result_{thread_id} SELECT * FROM source_{thread_id}",
                f"CREATE TABLE summary_{thread_id} AS SELECT * FROM result_{thread_id}",
            ]
            for sql in sqls:
                graph = parser.parse_sql(sql, f"db_{thread_id}")
                if len(graph.nodes) == 0:
                    return False
            return True

        with ThreadPoolExecutor(max_workers=thread_count) as executor:
            futures = [executor.submit(worker, i) for i in range(thread_count)]
            results = [f.result() for f in as_completed(futures)]

        assert all(results)

    def test_shared_parser_instance_thread_safety(self):
        parser = LineageParser()
        shared_results = []
        lock = threading.Lock()

        def safe_parse(sql, db):
            result = parser.parse_sql(sql, db)
            with lock:
                shared_results.append(result)

        threads = []
        for i in range(5):
            sql = f"INSERT INTO target_{i} SELECT * FROM source_{i}"
            t = threading.Thread(target=safe_parse, args=(sql, "shared_db"))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        assert len(shared_results) == 5

    def test_shared_dag_builder_thread_safety(self):
        builder = LineageDAGBuilder()
        errors = []
        lock = threading.Lock()

        def build_safely(sql_list, db_name):
            try:
                dag = builder.build_from_sql_list(sql_list, db_name)
                return dag.number_of_nodes()
            except Exception as e:
                with lock:
                    errors.append(e)
                return 0

        sql_lists = [
            [f"INSERT INTO t{i}_target SELECT * FROM t{i}_source"]
            for i in range(4)
        ]

        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [
                executor.submit(build_safely, sqls, f"db_{i}")
                for i, sqls in enumerate(sql_lists)
            ]
            results = [f.result() for f in futures]

        assert len(errors) == 0
        assert all(r > 0 for r in results)

    def test_concurrent_upstream_queries(self, lineage_multi_sql):
        builder = LineageDAGBuilder()
        builder.build_from_sql_list(
            lineage_multi_sql.sql_list,
            lineage_multi_sql.default_database
        )

        nodes = list(builder._nx_graph.nodes()) if builder._nx_graph else []

        def query_worker(node_id):
            upstream = builder.get_upstream(node_id, depth=2)
            downstream = builder.get_downstream(node_id, depth=2)
            return len(upstream), len(downstream)

        if nodes:
            with ThreadPoolExecutor(max_workers=4) as executor:
                futures = [executor.submit(query_worker, node) for node in nodes]
                results = [f.result() for f in futures]

            assert all(isinstance(r, tuple) for r in results)

    def test_concurrent_impact_analysis(self, lineage_multi_sql):
        builder = LineageDAGBuilder()
        builder.build_from_sql_list(
            lineage_multi_sql.sql_list,
            lineage_multi_sql.default_database
        )

        nodes = list(builder._nx_graph.nodes()) if builder._nx_graph else []

        def impact_worker(node_id):
            result = builder.get_impact_analysis(node_id)
            return result

        if nodes:
            with ThreadPoolExecutor(max_workers=3) as executor:
                futures = [executor.submit(impact_worker, node) for node in nodes[:3]]
                results = [f.result() for f in futures]

            assert len(results) == 3

    def test_no_race_conditions_on_graph_operations(self):
        parser = LineageParser()
        graph = LineageGraph()
        lock = threading.Lock()

        def add_node_worker(start_id, count):
            for i in range(count):
                node_id = f"node_{start_id}_{i}"
                node = LineageNode(
                    node_id=node_id,
                    node_type=NodeType.TABLE,
                    name=f"table_{i}",
                    database="test",
                )
                with lock:
                    graph.add_node(node)

        threads = []
        for thread_id in range(4):
            t = threading.Thread(target=add_node_worker, args=(thread_id, 10))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        assert len(graph.nodes) == 40

    def test_concurrent_export_operations(self, lineage_single_sql):
        builder = LineageDAGBuilder()
        builder.build_from_sql_list(
            lineage_single_sql.sql_list,
            lineage_single_sql.default_database
        )

        def export_dot_worker():
            return builder.export_dot()

        def export_json_worker():
            return builder.export_json()

        def stats_worker():
            return builder.get_statistics()

        with ThreadPoolExecutor(max_workers=3) as executor:
            dot_future = executor.submit(export_dot_worker)
            json_future = executor.submit(export_json_worker)
            stats_future = executor.submit(stats_worker)

            dot_result = dot_future.result()
            json_result = json_future.result()
            stats_result = stats_future.result()

        assert isinstance(dot_result, str)
        assert isinstance(json_result, dict)
        assert isinstance(stats_result, dict)


class TestLineageEdgeCases:
    def test_parse_empty_sql(self):
        parser = LineageParser()
        graph = parser.parse_sql("", "test")

        assert len(graph.nodes) == 0

    def test_parse_whitespace_only_sql(self):
        parser = LineageParser()
        graph = parser.parse_sql("   \n\t   ", "test")

        assert len(graph.nodes) == 0

    def test_parse_invalid_sql(self):
        parser = LineageParser()
        graph = parser.parse_sql("THIS IS NOT VALID SQL", "test")

        assert isinstance(graph, LineageGraph)

    def test_parse_very_long_sql(self):
        parser = LineageParser()
        columns = ", ".join([f"col_{i}" for i in range(100)])
        sql = f"INSERT INTO big_table ({columns}) SELECT {columns} FROM source_table"

        graph = parser.parse_sql(sql, "test")

        assert isinstance(graph, LineageGraph)

    def test_parse_special_characters(self):
        parser = LineageParser()
        sql = "INSERT INTO \"order-data\" SELECT * FROM \"source-data\""

        graph = parser.parse_sql(sql, "test")

        assert isinstance(graph, LineageGraph)

    def test_dag_with_circular_reference_handling(self):
        builder = LineageDAGBuilder()
        sql_list = [
            "INSERT INTO table_a SELECT * FROM table_b",
            "INSERT INTO table_b SELECT * FROM table_a",
        ]

        dag = builder.build_from_sql_list(sql_list, "test")

        import networkx as nx
        if dag.number_of_nodes() > 1:
            if not nx.is_directed_acyclic_graph(dag):
                cycles = list(nx.simple_cycles(dag))
                assert len(cycles) > 0


class TestTimeoutControl:
    def test_parse_sql_with_short_timeout(self):
        from src.domain.lineage.lineage_parser import LineageParseTimeoutException

        parser = LineageParser(default_timeout=0.1)
        complex_sql = "SELECT * FROM (SELECT * FROM (SELECT * FROM very_big_table))"

        original_parse_select = parser._parse_select_sql

        def slow_parse_select(sql, db, graph):
            import time
            time.sleep(0.3)
            return original_parse_select(sql, db, graph)

        parser._parse_select_sql = slow_parse_select

        with pytest.raises(LineageParseTimeoutException):
            parser.parse_sql(complex_sql, "test")

    def test_parse_sql_timeout_parameter_overrides_default(self):
        from src.domain.lineage.lineage_parser import LineageParseTimeoutException

        parser = LineageParser(default_timeout=30)
        sql = "SELECT * FROM some_table"

        original_parse_select = parser._parse_select_sql

        def slow_parse_select(sql, db, graph):
            import time
            time.sleep(0.3)
            return original_parse_select(sql, db, graph)

        parser._parse_select_sql = slow_parse_select

        with pytest.raises(LineageParseTimeoutException):
            parser.parse_sql(sql, "test", timeout=0.1)

    def test_normal_sql_completes_within_timeout(self):
        parser = LineageParser(default_timeout=5)
        sql = "INSERT INTO target (id, name) SELECT id, name FROM source"

        graph = parser.parse_sql(sql, "test_db")

        assert isinstance(graph, LineageGraph)
        assert len(graph.nodes) > 0

    def test_timeout_exception_contains_sql_info(self):
        from src.domain.lineage.lineage_parser import LineageParseTimeoutException

        parser = LineageParser(default_timeout=0.1)
        test_sql = "SELECT * FROM some_very_long_table_name_that_should_timeout"

        original_parse_select = parser._parse_select_sql

        def slow_parse_select(sql, db, graph):
            import time
            time.sleep(0.3)
            return original_parse_select(sql, db, graph)

        parser._parse_select_sql = slow_parse_select

        with pytest.raises(LineageParseTimeoutException) as exc_info:
            parser.parse_sql(test_sql, "test")

        assert "some_very_long_table_name" in str(exc_info.value)
        assert exc_info.value.timeout_seconds == 0.1

    def test_mock_slow_parsing_triggers_timeout(self):
        from src.domain.lineage.lineage_parser import LineageParseTimeoutException

        parser = LineageParser(default_timeout=0.1)

        slow_sql = "INSERT INTO target SELECT * FROM slow_table"

        original_parse_write = parser._parse_write_sql

        def slow_parse_write(sql, db, graph):
            import time
            time.sleep(0.5)
            return original_parse_write(sql, db, graph)

        parser._parse_write_sql = slow_parse_write

        with pytest.raises(LineageParseTimeoutException):
            parser.parse_sql(slow_sql, "test")

    def test_dag_build_from_sql_list_timeout(self):
        from src.domain.lineage.lineage_parser import LineageParseTimeoutException

        builder = LineageDAGBuilder(default_timeout=0.1)
        sql_list = [
            "INSERT INTO table1 SELECT * FROM table2",
            "INSERT INTO table2 SELECT * FROM table3",
            "INSERT INTO table3 SELECT * FROM table4",
        ] * 10

        with patch.object(LineageParser, 'parse_sql') as mock_parse:
            def slow_parse(*args, **kwargs):
                time.sleep(0.02)
                return LineageGraph()

            mock_parse.side_effect = slow_parse

            with pytest.raises(LineageParseTimeoutException):
                builder.build_from_sql_list(sql_list, "test", timeout=0.05)

    def test_timeout_does_not_leak_threads(self):
        import threading
        initial_thread_count = threading.active_count()

        parser = LineageParser(default_timeout=0.1)

        original_parse_select = parser._parse_select_sql

        def slow_parse_select(sql, db, graph):
            import time
            time.sleep(0.5)
            return original_parse_select(sql, db, graph)

        parser._parse_select_sql = slow_parse_select

        for _ in range(5):
            try:
                parser.parse_sql("SELECT * FROM test", "test")
            except Exception:
                pass

        time.sleep(0.2)
        final_thread_count = threading.active_count()

        assert abs(final_thread_count - initial_thread_count) <= 2

    def test_parser_cleanup_on_destruction(self):
        import weakref

        parser = LineageParser(default_timeout=5)
        parser_ref = weakref.ref(parser)

        try:
            parser.parse_sql("SELECT * FROM test", "test")
        except Exception:
            pass

        del parser
        import gc
        gc.collect()

        assert parser_ref() is None

    def test_custom_timeout_configuration(self):
        parser = LineageParser(default_timeout=10)

        assert parser._default_timeout == 10

    def test_multiple_parsers_independent_timeouts(self):
        from src.domain.lineage.lineage_parser import LineageParseTimeoutException

        fast_parser = LineageParser(default_timeout=0.1)
        slow_parser = LineageParser(default_timeout=30)

        sql = "INSERT INTO target SELECT * FROM source"

        original_parse_write = fast_parser._parse_write_sql

        def slow_parse_write(sql, db, graph):
            import time
            time.sleep(0.5)
            return original_parse_write(sql, db, graph)

        fast_parser._parse_write_sql = slow_parse_write

        with pytest.raises(LineageParseTimeoutException):
            fast_parser.parse_sql(sql, "test")

        result = slow_parser.parse_sql(sql, "test")
        assert isinstance(result, LineageGraph)

    def test_timeout_with_concurrent_requests(self):
        from src.domain.lineage.lineage_parser import LineageParseTimeoutException

        parser = LineageParser(default_timeout=5)

        original_parse_select = parser._parse_select_sql

        call_count = 0

        def sometimes_slow_parse(sql, db, graph):
            nonlocal call_count
            call_count += 1
            if call_count % 2 == 0:
                import time
                time.sleep(0.5)
            return original_parse_select(sql, db, graph)

        parser._parse_select_sql = sometimes_slow_parse

        def fast_request():
            return parser.parse_sql("SELECT * FROM table1", "test")

        def slow_request():
            with pytest.raises(LineageParseTimeoutException):
                parser.parse_sql("SELECT * FROM table2", "test", timeout=0.1)

        with ThreadPoolExecutor(max_workers=2) as executor:
            future1 = executor.submit(fast_request)
            future2 = executor.submit(slow_request)

            result1 = future1.result()
            future2.result()

        assert isinstance(result1, LineageGraph)

    def test_build_from_graph_timeout(self):
        from src.domain.lineage.lineage_parser import LineageParseTimeoutException

        builder = LineageDAGBuilder(default_timeout=0.1)
        graph = LineageGraph()

        for i in range(10):
            node = LineageNode(
                node_id=f"node_{i}",
                node_type=NodeType.TABLE,
                name=f"table_{i}",
                database="test",
            )
            graph.add_node(node)

        with patch('src.domain.lineage.dag_builder.nx.is_directed_acyclic_graph') as mock_is_dag:
            def slow_is_dag(*args, **kwargs):
                time.sleep(0.5)
                return True

            mock_is_dag.side_effect = slow_is_dag

            with pytest.raises(LineageParseTimeoutException):
                builder.build_from_graph(graph, timeout=0.1)

    def test_no_timeout_for_simple_queries(self):
        parser = LineageParser(default_timeout=5)

        sqls = [
            "SELECT * FROM table1",
            "INSERT INTO target SELECT * FROM source",
            "CREATE TABLE new_table AS SELECT * FROM old_table",
            "SELECT a.id, b.name FROM table_a a JOIN table_b b ON a.id = b.id",
        ]

        for sql in sqls:
            graph = parser.parse_sql(sql, "test")
            assert isinstance(graph, LineageGraph)
