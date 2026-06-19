import pandas as pd
import pytest

from etl_engine.quality.rules import QualityRule
from etl_engine.quality.validator import QualityValidator
from etl_engine.transform.schema_inference import compare_schemas, infer_schema


@pytest.mark.unit
@pytest.mark.exception
class TestColumnMissingFromDataFrame:
    @pytest.fixture
    def mismatch_df(self):
        return pd.DataFrame({
            "id": [1, 2, 3, 4, 5],
            "name": ["Alice", "Bob", "Charlie", "David", "Eve"],
            "value": [10.5, 20.3, 30.1, 40.7, 50.9],
        })

    @pytest.fixture
    def rules_expecting_different_columns(self):
        return [
            QualityRule(
                rule_type="null_rate",
                column="user_id",
                params={"max_null_rate": 0.05},
                threshold=1.0,
                strategy="alert",
            ),
            QualityRule(
                rule_type="null_rate",
                column="email",
                params={"max_null_rate": 0.1},
                threshold=1.0,
                strategy="alert",
            ),
            QualityRule(
                rule_type="uniqueness",
                column="amount",
                params={"expect_unique": True},
                threshold=1.0,
                strategy="alert",
            ),
        ]

    def test_validation_detects_missing_columns(self, mismatch_df, rules_expecting_different_columns):
        validator = QualityValidator(rules_expecting_different_columns)
        result = validator.validate(mismatch_df)

        assert result.passed is False
        assert result.failed_rules >= 3

    def test_rule_results_show_column_not_found_details(
        self, mismatch_df, rules_expecting_different_columns
    ):
        validator = QualityValidator(rules_expecting_different_columns)
        result = validator.validate(mismatch_df)

        failed_results = [r for r in result.rule_results if not r.passed]
        assert len(failed_results) >= 3

        for fr in failed_results:
            details_msg = str(fr.details).lower()
            has_not_found = (
                "not found" in details_msg
                or "column" in details_msg
                or "error" in details_msg
            )
            assert has_not_found, (
                f"Failed rule should mention missing column in details. "
                f"Got: column={fr.column}, details={fr.details}"
            )

    def test_compare_schemas_lists_missing_columns(self, mismatch_df):
        expected_schema = {
            "columns": [
                {"name": "user_id", "dtype": "int"},
                {"name": "email", "dtype": "string"},
                {"name": "amount", "dtype": "float"},
            ],
        }
        actual_schema = infer_schema(mismatch_df)
        diff = compare_schemas(expected_schema, actual_schema)

        assert "missing_columns" in diff
        missing = set(diff["missing_columns"])
        expected_missing = {"user_id", "email", "amount"}
        assert expected_missing.issubset(missing), (
            f"Expected missing columns {expected_missing}, got {missing}"
        )


@pytest.mark.unit
@pytest.mark.exception
class TestExtraColumnsNotExpected:
    @pytest.fixture
    def df_with_extra_cols(self):
        return pd.DataFrame({
            "user_id": [1, 2, 3],
            "email": ["a@b.com", "c@d.com", "e@f.com"],
            "amount": [10.0, 20.0, 30.0],
            "extra_col_1": ["x", "y", "z"],
            "extra_col_2": [True, False, True],
        })

    def test_compare_schemas_reports_extra_columns(self, df_with_extra_cols):
        expected_schema = {
            "columns": [
                {"name": "user_id", "dtype": "int"},
                {"name": "email", "dtype": "string"},
                {"name": "amount", "dtype": "float"},
            ],
        }
        actual_schema = infer_schema(df_with_extra_cols)
        diff = compare_schemas(expected_schema, actual_schema)

        assert "extra_columns" in diff
        extra = set(diff["extra_columns"])
        expected_extra = {"extra_col_1", "extra_col_2"}
        assert expected_extra.issubset(extra), (
            f"Expected extra columns {expected_extra}, got {extra}"
        )

    def test_both_missing_and_extra_reported_simultaneously(self):
        df = pd.DataFrame({
            "a": [1],
            "b": [2],
            "c": [3],
        })
        expected_schema = {
            "columns": [
                {"name": "x", "dtype": "int"},
                {"name": "y", "dtype": "int"},
                {"name": "b", "dtype": "int"},
            ],
        }
        actual_schema = infer_schema(df)
        diff = compare_schemas(expected_schema, actual_schema)

        assert set(diff["missing_columns"]) == {"x", "y"}
        assert set(diff["extra_columns"]) == {"a", "c"}


@pytest.mark.unit
@pytest.mark.exception
class TestTypeMismatchReported:
    @pytest.fixture
    def df_with_string_ids(self):
        return pd.DataFrame({
            "id": ["user-1", "user-2", "user-3", "user-4", "user-5"],
            "name": ["Alice", "Bob", "Charlie", "David", "Eve"],
        })

    def test_compare_schemas_reports_type_changes(self, df_with_string_ids):
        expected_schema = {
            "columns": [
                {"name": "id", "dtype": "int"},
                {"name": "name", "dtype": "string"},
            ],
        }
        actual_schema = infer_schema(df_with_string_ids)
        diff = compare_schemas(expected_schema, actual_schema)

        assert "type_changes" in diff
        type_change_names = [tc["name"] for tc in diff["type_changes"]]
        assert "id" in type_change_names, (
            f"Expected 'id' in type_changes, got {diff['type_changes']}"
        )

        id_change = next(tc for tc in diff["type_changes"] if tc["name"] == "id")
        assert id_change["expected_dtype"] == "int"
        assert id_change["actual_dtype"] == "string"

    def test_numeric_expected_but_got_string_in_validation(self, df_with_string_ids):
        rules = [
            QualityRule(
                rule_type="value_range",
                column="id",
                params={"min_value": 1, "max_value": 1000},
                threshold=1.0,
                strategy="block",
            ),
        ]
        validator = QualityValidator(rules)
        result = validator.validate(df_with_string_ids)

        assert result.passed is False
        id_result = next(r for r in result.rule_results if r.column == "id")
        assert id_result.passed is False


@pytest.mark.unit
@pytest.mark.exception
class TestClearDiffOutput:
    @pytest.fixture
    def messy_dataframe(self):
        return pd.DataFrame({
            "col_a": [1, 2, 3],
            "col_b": [10.5, 20.5, 30.5],
            "col_c": ["yes", "no", "maybe"],
            "col_d": pd.to_datetime(["2024-01-01", "2024-01-02", "2024-01-03"]),
        })

    def test_compare_schemas_output_has_required_keys(self, messy_dataframe):
        expected_schema = {
            "columns": [
                {"name": "col_a", "dtype": "string"},
                {"name": "col_b", "dtype": "int"},
                {"name": "col_x", "dtype": "float"},
            ],
        }
        actual_schema = infer_schema(messy_dataframe)
        diff = compare_schemas(expected_schema, actual_schema)

        assert "missing_columns" in diff
        assert isinstance(diff["missing_columns"], list)

        assert "extra_columns" in diff
        assert isinstance(diff["extra_columns"], list)

        assert "type_changes" in diff
        assert isinstance(diff["type_changes"], list)

    def test_all_diff_fields_populated_correctly(self, messy_dataframe):
        expected_schema = {
            "columns": [
                {"name": "col_a", "dtype": "string"},
                {"name": "col_b", "dtype": "int"},
                {"name": "col_x", "dtype": "float"},
            ],
        }
        actual_schema = infer_schema(messy_dataframe)
        diff = compare_schemas(expected_schema, actual_schema)

        assert "col_x" in diff["missing_columns"]
        assert "col_c" in diff["extra_columns"] or "col_d" in diff["extra_columns"]

        changed_cols = {tc["name"] for tc in diff["type_changes"]}
        assert "col_a" in changed_cols or "col_b" in changed_cols

        for tc in diff["type_changes"]:
            assert "expected_dtype" in tc
            assert "actual_dtype" in tc
            assert tc["expected_dtype"] != tc["actual_dtype"]


@pytest.mark.unit
@pytest.mark.exception
class TestColumnNameTypoDetected:
    @pytest.fixture
    def df_with_userid_typo(self):
        return pd.DataFrame({
            "userid": [101, 102, 103, 104],
            "email": ["u1@test.com", "u2@test.com", "u3@test.com", "u4@test.com"],
        })

    def test_typo_column_name_reported_as_missing(self, df_with_userid_typo):
        expected_schema = {
            "columns": [
                {"name": "user_id", "dtype": "int"},
                {"name": "email", "dtype": "string"},
            ],
        }
        actual_schema = infer_schema(df_with_userid_typo)
        diff = compare_schemas(expected_schema, actual_schema)

        assert "user_id" in diff["missing_columns"], (
            f"Column 'user_id' (expected) should be in missing_columns. "
            f"Actual columns: {df_with_userid_typo.columns.tolist()}. "
            f"Missing: {diff['missing_columns']}"
        )

    def test_validation_rules_catch_typo_as_missing(self, df_with_userid_typo):
        rules = [
            QualityRule(
                rule_type="uniqueness",
                column="user_id",
                params={"expect_unique": True},
                threshold=1.0,
                strategy="alert",
            ),
        ]
        validator = QualityValidator(rules)
        result = validator.validate(df_with_userid_typo)

        assert result.passed is False
        user_id_result = next(
            (r for r in result.rule_results if r.column == "user_id"), None
        )
        assert user_id_result is not None
        assert user_id_result.passed is False

        details_msg = str(user_id_result.details).lower()
        assert "not found" in details_msg or "column" in details_msg, (
            f"Typo 'user_id' vs 'userid' should trigger column not found error. "
            f"Got details: {user_id_result.details}"
        )
