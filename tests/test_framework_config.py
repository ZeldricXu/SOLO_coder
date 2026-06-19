import os
import sys

import pytest
import yaml
import tomllib


PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class TestPyProjectTomlPytestSection:

    def test_pyproject_toml_exists(self):
        pyproject_path = os.path.join(PROJECT_ROOT, "pyproject.toml")
        assert os.path.exists(pyproject_path), "pyproject.toml not found"

    def test_pytest_section_exists(self):
        pyproject_path = os.path.join(PROJECT_ROOT, "pyproject.toml")
        with open(pyproject_path, "rb") as f:
            config = tomllib.load(f)
        assert "tool" in config, "[tool] section missing"
        assert "pytest" in config["tool"], "[tool.pytest] section missing"
        assert "ini_options" in config["tool"]["pytest"], "[tool.pytest.ini_options] section missing"

    def test_pytest_asyncio_mode(self):
        pyproject_path = os.path.join(PROJECT_ROOT, "pyproject.toml")
        with open(pyproject_path, "rb") as f:
            config = tomllib.load(f)
        ini_options = config["tool"]["pytest"]["ini_options"]
        assert ini_options.get("asyncio_mode") == "auto", "asyncio_mode should be 'auto'"

    def test_pytest_pythonpath(self):
        pyproject_path = os.path.join(PROJECT_ROOT, "pyproject.toml")
        with open(pyproject_path, "rb") as f:
            config = tomllib.load(f)
        ini_options = config["tool"]["pytest"]["ini_options"]
        assert "pythonpath" in ini_options, "pythonpath not configured"
        assert "." in ini_options["pythonpath"], "pythonpath should include '.'"

    def test_pytest_testpaths(self):
        pyproject_path = os.path.join(PROJECT_ROOT, "pyproject.toml")
        with open(pyproject_path, "rb") as f:
            config = tomllib.load(f)
        ini_options = config["tool"]["pytest"]["ini_options"]
        assert "testpaths" in ini_options, "testpaths not configured"
        assert "tests" in ini_options["testpaths"], "testpaths should include 'tests'"

    def test_pytest_markers_defined(self):
        pyproject_path = os.path.join(PROJECT_ROOT, "pyproject.toml")
        with open(pyproject_path, "rb") as f:
            config = tomllib.load(f)
        ini_options = config["tool"]["pytest"]["ini_options"]
        assert "markers" in ini_options, "markers not configured"
        markers = ini_options["markers"]
        marker_names = [m.split(":")[0].strip() for m in markers]
        expected_markers = ["unit", "integration", "concurrency", "slow"]
        for marker in expected_markers:
            assert marker in marker_names, f"Marker '{marker}' not found in pytest config"

    def test_pytest_filterwarnings(self):
        pyproject_path = os.path.join(PROJECT_ROOT, "pyproject.toml")
        with open(pyproject_path, "rb") as f:
            config = tomllib.load(f)
        ini_options = config["tool"]["pytest"]["ini_options"]
        assert "filterwarnings" in ini_options, "filterwarnings not configured"
        filters = ini_options["filterwarnings"]
        assert any("DeprecationWarning" in f for f in filters), "DeprecationWarning filter missing"
        assert any("PendingDeprecationWarning" in f for f in filters), "PendingDeprecationWarning filter missing"


class TestDockerComposeServices:

    def test_docker_compose_exists(self):
        compose_path = os.path.join(PROJECT_ROOT, "tests", "docker-compose.yml")
        assert os.path.exists(compose_path), "tests/docker-compose.yml not found"

    def test_all_services_defined(self):
        compose_path = os.path.join(PROJECT_ROOT, "tests", "docker-compose.yml")
        with open(compose_path, "r") as f:
            compose = yaml.safe_load(f)
        assert "services" in compose, "services section missing"
        services = compose["services"]
        expected_services = ["mysql", "postgres", "redis", "minio"]
        for svc in expected_services:
            assert svc in services, f"Service '{svc}' not found in docker-compose.yml"

    def test_mysql_service_config(self):
        compose_path = os.path.join(PROJECT_ROOT, "tests", "docker-compose.yml")
        with open(compose_path, "r") as f:
            compose = yaml.safe_load(f)
        mysql = compose["services"]["mysql"]
        assert mysql["image"] == "mysql:8.3.0", "MySQL image mismatch"
        env = mysql["environment"]
        assert env["MYSQL_ROOT_PASSWORD"] == "etltest"
        assert env["MYSQL_DATABASE"] == "etl_test"
        assert env["MYSQL_USER"] == "etl"
        assert env["MYSQL_PASSWORD"] == "etltest"
        assert "3307:3306" in mysql["ports"]
        assert "healthcheck" in mysql

    def test_postgres_service_config(self):
        compose_path = os.path.join(PROJECT_ROOT, "tests", "docker-compose.yml")
        with open(compose_path, "r") as f:
            compose = yaml.safe_load(f)
        postgres = compose["services"]["postgres"]
        assert postgres["image"] == "postgres:16-alpine", "Postgres image mismatch"
        env = postgres["environment"]
        assert env["POSTGRES_USER"] == "etl"
        assert env["POSTGRES_PASSWORD"] == "etltest"
        assert env["POSTGRES_DB"] == "etl_test"
        assert "5433:5432" in postgres["ports"]
        assert "healthcheck" in postgres

    def test_redis_service_config(self):
        compose_path = os.path.join(PROJECT_ROOT, "tests", "docker-compose.yml")
        with open(compose_path, "r") as f:
            compose = yaml.safe_load(f)
        redis = compose["services"]["redis"]
        assert redis["image"] == "redis:7-alpine", "Redis image mismatch"
        assert "6380:6379" in redis["ports"]
        assert "healthcheck" in redis

    def test_minio_service_config(self):
        compose_path = os.path.join(PROJECT_ROOT, "tests", "docker-compose.yml")
        with open(compose_path, "r") as f:
            compose = yaml.safe_load(f)
        minio = compose["services"]["minio"]
        assert "minio/minio" in minio["image"]
        env = minio["environment"]
        assert env["MINIO_ROOT_USER"] == "etladmin"
        assert env["MINIO_ROOT_PASSWORD"] == "etladmin123"
        assert "9002:9000" in minio["ports"]
        assert "9003:9001" in minio["ports"]
        assert "healthcheck" in minio


class TestConftestFixturesImportable:

    def test_conftest_exists(self):
        conftest_path = os.path.join(PROJECT_ROOT, "tests", "conftest.py")
        assert os.path.exists(conftest_path), "tests/conftest.py not found"

    def test_sample_yaml_workflow_fixture(self, sample_yaml_workflow):
        assert sample_yaml_workflow is not None
        assert isinstance(sample_yaml_workflow, str)
        parsed = yaml.safe_load(sample_yaml_workflow)
        assert "workflow" in parsed
        tasks = parsed["workflow"]["tasks"]
        assert len(tasks) == 3
        task_ids = [t["id"] for t in tasks]
        assert "extract_users" in task_ids
        assert "clean_users" in task_ids
        assert "load_users" in task_ids

    def test_sample_yaml_with_cycle_fixture(self, sample_yaml_with_cycle):
        assert sample_yaml_with_cycle is not None
        assert isinstance(sample_yaml_with_cycle, str)
        parsed = yaml.safe_load(sample_yaml_with_cycle)
        assert "workflow" in parsed
        tasks = parsed["workflow"]["tasks"]
        assert len(tasks) == 3

    def test_mock_mysql_connection_fixture(self, mock_mysql_connection):
        assert mock_mysql_connection is not None
        cursor = mock_mysql_connection.cursor()
        result = cursor.fetchall()
        assert len(result) == 3
        assert result[0]["name"] == "Alice"

    def test_mock_redis_client_fixture(self, mock_redis_client):
        assert mock_redis_client is not None
        mock_redis_client.set("test_key", "test_value")
        assert mock_redis_client.exists("test_key") is True
        assert mock_redis_client.get("test_key") == "test_value"
        mock_redis_client.delete("test_key")
        assert mock_redis_client.exists("test_key") is False

    def test_expectation_suite_json_fixture(self, expectation_suite_json):
        assert expectation_suite_json is not None
        assert isinstance(expectation_suite_json, dict)
        assert "expectation_suite_name" in expectation_suite_json
        assert "expectations" in expectation_suite_json
        expectations = expectation_suite_json["expectations"]
        assert len(expectations) == 3

    def test_sample_transformations_fixture(self, sample_transformations):
        assert sample_transformations is not None
        assert isinstance(sample_transformations, list)
        assert len(sample_transformations) == 3
        types = [t["type"] for t in sample_transformations]
        assert types.count("sql") == 2
        assert types.count("udf") == 1

    def test_mock_connect_timeout_error_class_exists(self):
        sys.path.insert(0, PROJECT_ROOT)
        from tests.conftest import MockConnectTimeoutError
        assert issubclass(MockConnectTimeoutError, Exception)
        with pytest.raises(MockConnectTimeoutError):
            raise MockConnectTimeoutError("test timeout")


class TestYAMLFixturesParseCorrectly:

    def test_sample_yaml_workflow_dependencies(self, sample_yaml_workflow):
        parsed = yaml.safe_load(sample_yaml_workflow)
        tasks = {t["id"]: t for t in parsed["workflow"]["tasks"]}
        assert tasks["extract_users"]["dependencies"] == []
        assert tasks["clean_users"]["dependencies"] == ["extract_users"]
        assert tasks["load_users"]["dependencies"] == ["clean_users"]

    def test_sample_yaml_workflow_task_types(self, sample_yaml_workflow):
        parsed = yaml.safe_load(sample_yaml_workflow)
        tasks = {t["id"]: t for t in parsed["workflow"]["tasks"]}
        assert tasks["extract_users"]["type"] == "extract"
        assert tasks["clean_users"]["type"] == "transform"
        assert tasks["load_users"]["type"] == "load"

    def test_sample_yaml_with_cycle_forms_cycle(self, sample_yaml_with_cycle):
        parsed = yaml.safe_load(sample_yaml_with_cycle)
        tasks = {t["id"]: t for t in parsed["workflow"]["tasks"]}
        assert "task_c" in tasks["task_a"]["dependencies"]
        assert "task_a" in tasks["task_b"]["dependencies"]
        assert "task_b" in tasks["task_c"]["dependencies"]

    def test_expectation_suite_expectation_types(self, expectation_suite_json):
        expectations = expectation_suite_json["expectations"]
        types = [e["expectation_type"] for e in expectations]
        assert "expect_column_values_to_not_be_null" in types
        assert "expect_column_values_to_be_unique" in types
        assert "expect_column_values_to_be_between" in types

    def test_expectation_suite_columns(self, expectation_suite_json):
        expectations = expectation_suite_json["expectations"]
        by_type = {e["expectation_type"]: e for e in expectations}
        assert by_type["expect_column_values_to_not_be_null"]["kwargs"]["column"] == "name"
        assert by_type["expect_column_values_to_be_unique"]["kwargs"]["column"] == "id"
        range_exp = by_type["expect_column_values_to_be_between"]
        assert range_exp["kwargs"]["column"] == "value"
        assert range_exp["kwargs"]["min_value"] == 0
        assert range_exp["kwargs"]["max_value"] == 100

    def test_sample_transformations_sql_content(self, sample_transformations):
        by_id = {t["id"]: t for t in sample_transformations}
        assert "UPPER(name)" in by_id["t1"]["sql"]
        assert "value > 20" in by_id["t3"]["sql"]
        assert "computed_value" in by_id["t2"]["inline_code"]
