import pandas as pd
import pytest

from etl_engine.exceptions import TransformStepError
from etl_engine.transform.engine import TransformEngine


@pytest.mark.unit
@pytest.mark.exception
class TestUDFThrowsValueError:
    @pytest.fixture
    def sample_df(self):
        return pd.DataFrame({
            "id": [1, 2, 3],
            "name": ["a", "b", "c"],
            "value": [10.0, 20.0, 30.0],
        })

    @pytest.fixture
    def engine(self):
        return TransformEngine(use_dask=False)

    def test_udf_value_error_wrapped_in_transform_step_error(self, sample_df, engine):
        bad_udf_transformations = [
            {
                "type": "udf",
                "expression": {
                    "inline_code": "raise ValueError('bad data')",
                },
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, bad_udf_transformations)

        error = exc_info.value
        assert error.step_index == 0
        assert error.step_type == "udf"
        assert isinstance(error.cause, ValueError)
        assert "bad data" in str(error.cause)

    def test_udf_error_preserves_exception_chain(self, sample_df, engine):
        transformations = [
            {
                "type": "udf",
                "expression": {
                    "inline_code": "raise KeyError('missing_field')",
                },
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, transformations)

        assert exc_info.value.__cause__ is not None
        assert isinstance(exc_info.value.__cause__, KeyError)


@pytest.mark.unit
@pytest.mark.exception
class TestSQLSyntaxError:
    @pytest.fixture
    def sample_df(self):
        return pd.DataFrame({
            "user_id": [1, 2, 3],
            "email": ["a@x.com", "b@x.com", "c@x.com"],
        })

    @pytest.fixture
    def engine(self):
        return TransformEngine(use_dask=False)

    def test_sql_typo_wrapped_in_transform_step_error(self, sample_df, engine):
        bad_sql_transformations = [
            {
                "type": "sql",
                "expression": "SELECCTTT * FROM input",
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, bad_sql_transformations)

        error = exc_info.value
        assert error.step_index == 0
        assert error.step_type == "sql"
        assert error.expression == "SELECCTTT * FROM input"
        assert error.cause is not None

    def test_sql_error_message_context_preserved(self, sample_df, engine):
        transformations = [
            {
                "type": "sql",
                "expression": "SELECT nonexistent_column FROM input",
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, transformations)

        error_msg = str(exc_info.value)
        assert "step 0" in error_msg.lower() or "0" in error_msg
        assert "type=sql" in error_msg or "sql" in error_msg.lower()


@pytest.mark.unit
@pytest.mark.exception
class TestStepIndexReportedCorrectly:
    @pytest.fixture
    def sample_df(self):
        return pd.DataFrame({
            "id": [1, 2, 3],
            "val": [10, 20, 30],
        })

    @pytest.fixture
    def engine(self):
        return TransformEngine(use_dask=False)

    def test_second_step_failure_reports_index_1(self, sample_df, engine):
        transformations = [
            {
                "type": "sql",
                "expression": "SELECT id, val FROM input",
            },
            {
                "type": "udf",
                "expression": {
                    "inline_code": "raise RuntimeError('UDF explosion at step 2')",
                },
            },
            {
                "type": "sql",
                "expression": "SELECT * FROM input WHERE val > 15",
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, transformations)

        assert exc_info.value.step_index == 1
        assert exc_info.value.step_type == "udf"

    def test_third_step_failure_reports_correct_index(self, sample_df, engine):
        transformations = [
            {
                "type": "sql",
                "expression": "SELECT id FROM input",
            },
            {
                "type": "sql",
                "expression": "SELECT id, id * 2 AS doubled FROM input",
            },
            {
                "type": "udf",
                "expression": {
                    "inline_code": "raise TypeError('bad type at step 3')",
                },
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, transformations)

        assert exc_info.value.step_index == 2


@pytest.mark.unit
@pytest.mark.exception
class TestOriginalErrorPreserved:
    @pytest.fixture
    def sample_df(self):
        return pd.DataFrame({"x": [1, 2, 3]})

    @pytest.fixture
    def engine(self):
        return TransformEngine(use_dask=False)

    def test_cause_attribute_is_original_exception_type(self, sample_df, engine):
        transformations = [
            {
                "type": "udf",
                "expression": {
                    "inline_code": "raise ZeroDivisionError('div by zero')",
                },
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, transformations)

        error = exc_info.value
        assert type(error.cause) is ZeroDivisionError
        assert isinstance(error.cause, ZeroDivisionError)

    def test_original_error_message_accessible(self, sample_df, engine):
        custom_msg = "Custom error message with details: code=500"
        transformations = [
            {
                "type": "udf",
                "expression": {
                    "inline_code": f"raise AssertionError('{custom_msg}')",
                },
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, transformations)

        assert custom_msg in str(exc_info.value.cause)


@pytest.mark.unit
@pytest.mark.exception
class TestInvalidUDFConfig:
    @pytest.fixture
    def sample_df(self):
        return pd.DataFrame({"a": [1]})

    @pytest.fixture
    def engine(self):
        return TransformEngine(use_dask=False)

    def test_missing_inline_code_and_function_name_raises_error(self, sample_df, engine):
        transformations = [
            {
                "type": "udf",
                "expression": {},
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, transformations)

        error = exc_info.value
        assert error.step_index == 0
        assert error.step_type == "udf"
        assert isinstance(error.cause, ValueError)

        cause_msg = str(error.cause).lower()
        has_required_keywords = (
            "inline_code" in cause_msg
            or "module_path" in cause_msg
            or "function_name" in cause_msg
            or "udf_config" in cause_msg
            or "must contain" in cause_msg
        )
        assert has_required_keywords, (
            f"UDF config error should mention missing required fields. "
            f"Got cause: {error.cause}"
        )

    def test_empty_udf_config_dict_raises_appropriate_error(self, sample_df, engine):
        transformations = [
            {
                "type": "udf",
                "expression": {"something_unrelated": "xyz"},
            },
        ]

        with pytest.raises(TransformStepError) as exc_info:
            engine.apply(sample_df, transformations)

        assert exc_info.value.step_index == 0
        assert isinstance(exc_info.value.cause, ValueError)
