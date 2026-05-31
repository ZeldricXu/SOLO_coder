import pytest
from streamsql.modules.data_lineage.column_lineage import (
    ColumnLineage,
    TableLineage,
    SQLColumnLineageExtractor,
)
from streamsql.modules.data_lineage.dag_builder import LineageDAGBuilder
from streamsql.modules.data_lineage.graph import LineageGraph
from streamsql.modules.data_lineage.extractor import DataLineageExtractor


def test_column_lineage_creation():
    lineage = ColumnLineage(
        source_table="users",
        source_column="name",
        target_table="user_summary",
        target_column="user_name",
        transformation="UPPER(name)",
    )
    assert lineage.source_table == "users"
    assert lineage.source_column == "name"
    assert lineage.target_table == "user_summary"
    assert lineage.target_column == "user_name"


def test_table_lineage_creation():
    lineage = TableLineage(
        source_tables=["users", "orders"],
        target_table="user_orders",
        sql_query="SELECT * FROM users JOIN orders ON users.id = orders.user_id",
    )
    assert len(lineage.source_tables) == 2
    assert lineage.target_table == "user_orders"


def test_sql_column_lineage_extractor_simple():
    extractor = SQLColumnLineageExtractor()
    sql = "SELECT id, name, email FROM users WHERE age > 18"
    lineages = extractor.extract_column_lineage(sql)
    assert len(lineages) == 3
    tables = extractor.extract_tables(sql)
    assert "users" in tables


def test_sql_column_lineage_extractor_with_alias():
    extractor = SQLColumnLineageExtractor()
    sql = "SELECT u.id AS user_id, u.name AS user_name FROM users u"
    lineages = extractor.extract_column_lineage(sql)
    assert len(lineages) == 2
    target_columns = [l.target_column for l in lineages]
    assert "user_id" in target_columns
    assert "user_name" in target_columns


def test_sql_column_lineage_extractor_with_join():
    extractor = SQLColumnLineageExtractor()
    sql = """
    SELECT u.name, o.total, o.created_at
    FROM users u
    JOIN orders o ON u.id = o.user_id
    """
    lineages = extractor.extract_column_lineage(sql)
    assert len(lineages) == 3
    tables = extractor.extract_tables(sql)
    assert "users" in tables
    assert "orders" in tables


def test_dag_builder_build():
    builder = LineageDAGBuilder()
    lineages = [
        TableLineage(source_tables=["users"], target_table="user_summary"),
        TableLineage(source_tables=["user_summary"], target_table="user_report"),
        TableLineage(source_tables=["orders"], target_table="order_summary"),
    ]
    dag = builder.build(lineages)
    assert "users" in dag
    assert "user_summary" in dag
    assert "user_report" in dag
    assert "orders" in dag


def test_dag_builder_add_lineage():
    builder = LineageDAGBuilder()
    builder.add_lineage(
        TableLineage(source_tables=["users"], target_table="user_summary")
    )
    builder.add_lineage(
        TableLineage(source_tables=["user_summary"], target_table="user_report")
    )
    assert len(builder.dag.nodes) == 3
    assert len(builder.dag.edges) == 2


def test_lineage_graph_get_upstream():
    builder = LineageDAGBuilder()
    lineages = [
        TableLineage(source_tables=["users"], target_table="user_summary"),
        TableLineage(source_tables=["user_summary"], target_table="user_report"),
    ]
    builder.build(lineages)
    graph = LineageGraph(builder.dag)

    upstream = graph.get_upstream("user_report")
    assert "user_summary" in upstream
    assert "users" in upstream


def test_lineage_graph_get_downstream():
    builder = LineageDAGBuilder()
    lineages = [
        TableLineage(source_tables=["users"], target_table="user_summary"),
        TableLineage(source_tables=["user_summary"], target_table="user_report"),
    ]
    builder.build(lineages)
    graph = LineageGraph(builder.dag)

    downstream = graph.get_downstream("users")
    assert "user_summary" in downstream
    assert "user_report" in downstream


def test_lineage_graph_find_path():
    builder = LineageDAGBuilder()
    lineages = [
        TableLineage(source_tables=["users"], target_table="user_summary"),
        TableLineage(source_tables=["user_summary"], target_table="user_report"),
    ]
    builder.build(lineages)
    graph = LineageGraph(builder.dag)

    path = graph.find_path("users", "user_report")
    assert path is not None
    assert len(path) == 3
    assert path[0] == "users"
    assert path[-1] == "user_report"


def test_lineage_graph_impact_analysis():
    builder = LineageDAGBuilder()
    lineages = [
        TableLineage(source_tables=["users"], target_table="user_summary"),
        TableLineage(source_tables=["user_summary"], target_table="user_report"),
        TableLineage(source_tables=["users"], target_table="user_stats"),
    ]
    builder.build(lineages)
    graph = LineageGraph(builder.dag)

    impact = graph.impact_analysis("users")
    assert "user_summary" in impact
    assert "user_report" in impact
    assert "user_stats" in impact
    assert len(impact) == 3


def test_data_lineage_extractor_extract():
    extractor = DataLineageExtractor()
    sql_queries = [
        "CREATE TABLE user_summary AS SELECT id, name FROM users",
        "CREATE TABLE user_report AS SELECT * FROM user_summary",
    ]
    result = extractor.extract(sql_queries)
    assert "table_lineages" in result
    assert "graph" in result
    assert len(result["table_lineages"]) == 2


def test_data_lineage_extractor_visualize():
    extractor = DataLineageExtractor()
    sql_queries = [
        "CREATE TABLE user_summary AS SELECT id, name FROM users",
    ]
    result = extractor.extract(sql_queries)
    graphviz = extractor.visualize(result["graph"])
    assert "digraph" in graphviz
    assert "users" in graphviz
    assert "user_summary" in graphviz
