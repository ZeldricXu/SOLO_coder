import pytest
from tests.fixtures.test_data_builder import DataBuilder


@pytest.fixture
def data_builder():
    return DataBuilder(seed=42)


@pytest.fixture
def sample_select_query(data_builder):
    return data_builder.build_valid_select_query()


@pytest.fixture
def window_queries(data_builder):
    return data_builder.build_window_queries_with_variations()


@pytest.fixture
def invalid_queries(data_builder):
    return data_builder.build_invalid_syntax_queries()


@pytest.fixture
def lineage_single_sql(data_builder):
    return data_builder.build_lineage_single_sql()


@pytest.fixture
def lineage_multi_sql(data_builder):
    return data_builder.build_lineage_multi_sql()


@pytest.fixture
def lifecycle_hot_data(data_builder):
    return data_builder.build_lifecycle_hot_data()


@pytest.fixture
def lifecycle_cold_data(data_builder):
    return data_builder.build_lifecycle_cold_data()


@pytest.fixture
def lifecycle_archive_data(data_builder):
    return data_builder.build_lifecycle_archive_data()


@pytest.fixture
def sample_records(data_builder):
    return data_builder.build_sample_records(100)
