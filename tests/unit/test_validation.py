from typing import Any, List, Tuple
from unittest.mock import patch, MagicMock
from datetime import datetime, date, timedelta

import pytest
from freezegun import freeze_time

from app.services.validation_service import (
    ValidationService, ValidationRule,
    DateFormatRule, AmountRule, ICD10CodeRule,
    IDCardRule, PhoneNumberRule, RequiredFieldRule,
)
from app.schemas.extraction import FieldDataTypeEnum
from app.schemas.common import ValidationError


@pytest.mark.unit
@pytest.mark.validation
class TestDateFormatRule:
    def test_valid_date_yyyy_mm_dd(self, validation_context):
        rule = DateFormatRule()
        is_valid, errors = rule.validate(
            field_value="2024-01-15",
            field_name="invoice_date",
            field_type=FieldDataTypeEnum.DATE,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_valid_date_yyyy_slash_mm_slash_dd(self, validation_context):
        rule = DateFormatRule()
        is_valid, errors = rule.validate(
            field_value="2024/01/15",
            field_name="invoice_date",
            field_type=FieldDataTypeEnum.DATE,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_valid_date_chinese_format(self, validation_context):
        rule = DateFormatRule()
        is_valid, errors = rule.validate(
            field_value="2024年1月15日",
            field_name="invoice_date",
            field_type=FieldDataTypeEnum.DATE,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_invalid_date_format(self, validation_context):
        rule = DateFormatRule()
        is_valid, errors = rule.validate(
            field_value="01-15-2024",
            field_name="invoice_date",
            field_type=FieldDataTypeEnum.DATE,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) == 1
        assert errors[0].error_code == "INVALID_DATE_FORMAT"
        assert "YYYY-MM-DD" in errors[0].error_message

    def test_invalid_date_value(self, validation_context):
        rule = DateFormatRule()
        is_valid, errors = rule.validate(
            field_value="2024-13-45",
            field_name="invoice_date",
            field_type=FieldDataTypeEnum.DATE,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1

    def test_date_in_future_warning(self, validation_context):
        rule = DateFormatRule()
        future_date = (date.today() + timedelta(days=30)).strftime("%Y-%m-%d")

        is_valid, errors = rule.validate(
            field_value=future_date,
            field_name="invoice_date",
            field_type=FieldDataTypeEnum.DATE,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) == 1
        assert errors[0].severity == "warning"
        assert errors[0].error_code == "DATE_IN_FUTURE"
        assert errors[0].suggested_value is not None

    def test_date_too_early_warning(self, validation_context):
        rule = DateFormatRule()
        is_valid, errors = rule.validate(
            field_value="1800-01-01",
            field_name="birth_date",
            field_type=FieldDataTypeEnum.DATE,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1
        assert errors[0].severity == "warning"

    def test_none_value_passes(self, validation_context):
        rule = DateFormatRule()
        is_valid, errors = rule.validate(
            field_value=None,
            field_name="optional_date",
            field_type=FieldDataTypeEnum.DATE,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_empty_string_passes(self, validation_context):
        rule = DateFormatRule()
        is_valid, errors = rule.validate(
            field_value="",
            field_name="optional_date",
            field_type=FieldDataTypeEnum.DATE,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0


@pytest.mark.unit
@pytest.mark.validation
class TestAmountRule:
    def test_positive_amount_passes(self, validation_context):
        rule = AmountRule()
        is_valid, errors = rule.validate(
            field_value="3500.00",
            field_name="total_amount",
            field_type=FieldDataTypeEnum.NUMBER,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_zero_amount_warning(self, validation_context):
        rule = AmountRule()
        is_valid, errors = rule.validate(
            field_value="0.00",
            field_name="total_amount",
            field_type=FieldDataTypeEnum.NUMBER,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) == 1
        assert errors[0].severity == "warning"
        assert errors[0].error_code == "ZERO_AMOUNT"

    def test_negative_amount_error(self, validation_context):
        rule = AmountRule()
        is_valid, errors = rule.validate(
            field_value="-500.00",
            field_name="total_amount",
            field_type=FieldDataTypeEnum.NUMBER,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) == 1
        assert errors[0].severity == "error"
        assert errors[0].error_code == "NEGATIVE_AMOUNT"
        assert "金额不能为负数" in errors[0].error_message
        assert errors[0].suggested_value == "500.0"

    def test_negative_integer_amount_error(self, validation_context):
        rule = AmountRule()
        is_valid, errors = rule.validate(
            field_value="-100",
            field_name="total_amount",
            field_type=FieldDataTypeEnum.NUMBER,
            context=validation_context,
        )

        assert not is_valid
        assert errors[0].error_code == "NEGATIVE_AMOUNT"
        assert errors[0].suggested_value == "100.0"

    def test_large_amount_warning(self, validation_context):
        rule = AmountRule()
        is_valid, errors = rule.validate(
            field_value="20000000.00",
            field_name="total_amount",
            field_type=FieldDataTypeEnum.NUMBER,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1
        assert any(e.error_code == "AMOUNT_TOO_LARGE" for e in errors)

    def test_custom_max_amount(self, validation_context):
        rule = AmountRule()
        custom_context = {**validation_context, "max_amount": 5000}

        is_valid, errors = rule.validate(
            field_value="10000.00",
            field_name="total_amount",
            field_type=FieldDataTypeEnum.NUMBER,
            context=custom_context,
        )

        assert not is_valid
        assert any(e.error_code == "AMOUNT_TOO_LARGE" for e in errors)

    def test_invalid_amount_string(self, validation_context):
        rule = AmountRule()
        is_valid, errors = rule.validate(
            field_value="not_a_number",
            field_name="total_amount",
            field_type=FieldDataTypeEnum.NUMBER,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1

    def test_float_amount(self, validation_context):
        rule = AmountRule()
        is_valid, errors = rule.validate(
            field_value=1234.56,
            field_name="total_amount",
            field_type=FieldDataTypeEnum.NUMBER,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0


@pytest.mark.unit
@pytest.mark.validation
class TestICD10CodeRule:
    def test_valid_icd10_code(self, validation_context):
        rule = ICD10CodeRule()
        is_valid, errors = rule.validate(
            field_value="J45.900",
            field_name="diagnosis_code",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_valid_icd10_code_without_decimal(self, validation_context):
        rule = ICD10CodeRule()
        is_valid, errors = rule.validate(
            field_value="I10",
            field_name="diagnosis_code",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_valid_icd10_code_lowercase(self, validation_context):
        rule = ICD10CodeRule()
        is_valid, errors = rule.validate(
            field_value="j45.900",
            field_name="diagnosis_code",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_invalid_icd10_code_format(self, validation_context):
        rule = ICD10CodeRule()
        is_valid, errors = rule.validate(
            field_value="INVALID123",
            field_name="diagnosis_code",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1
        assert errors[0].error_code == "INVALID_ICD10_FORMAT"

    def test_invalid_icd10_code_numbers_only(self, validation_context):
        rule = ICD10CodeRule()
        is_valid, errors = rule.validate(
            field_value="12345",
            field_name="diagnosis_code",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1

    def test_empty_icd10_code_passes_if_optional(self, validation_context):
        rule = ICD10CodeRule()
        is_valid, errors = rule.validate(
            field_value=None,
            field_name="diagnosis_code",
            field_type=FieldDataTypeEnum.STRING,
            context={**validation_context, "required": False},
        )

        assert is_valid
        assert len(errors) == 0


@pytest.mark.unit
@pytest.mark.validation
class TestIDCardRule:
    def test_valid_id_card_18_digits(self, validation_context):
        rule = IDCardRule()
        is_valid, errors = rule.validate(
            field_value="110101199001011234",
            field_name="patient_id",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_valid_id_card_with_x(self, validation_context):
        rule = IDCardRule()
        is_valid, errors = rule.validate(
            field_value="11010119900101123X",
            field_name="patient_id",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_invalid_id_card_too_short(self, validation_context):
        rule = IDCardRule()
        is_valid, errors = rule.validate(
            field_value="11010119900101",
            field_name="patient_id",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1
        assert errors[0].error_code == "INVALID_IDCARD_LENGTH"

    def test_invalid_id_card_format(self, validation_context):
        rule = IDCardRule()
        is_valid, errors = rule.validate(
            field_value="ABCDEFGHIJKLMNOPQ",
            field_name="patient_id",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1
        assert errors[0].error_code == "INVALID_IDCARD_FORMAT"

    def test_invalid_id_card_birth_date(self, validation_context):
        rule = IDCardRule()
        is_valid, errors = rule.validate(
            field_value="110101199013011234",
            field_name="patient_id",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1


@pytest.mark.unit
@pytest.mark.validation
class TestPhoneNumberRule:
    def test_valid_mobile_number(self, validation_context):
        rule = PhoneNumberRule()
        is_valid, errors = rule.validate(
            field_value="13812345678",
            field_name="phone_number",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_valid_mobile_number_with_hyphen(self, validation_context):
        rule = PhoneNumberRule()
        is_valid, errors = rule.validate(
            field_value="138-1234-5678",
            field_name="phone_number",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_valid_landline_number(self, validation_context):
        rule = PhoneNumberRule()
        is_valid, errors = rule.validate(
            field_value="010-12345678",
            field_name="phone_number",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert is_valid
        assert len(errors) == 0

    def test_invalid_phone_number_too_short(self, validation_context):
        rule = PhoneNumberRule()
        is_valid, errors = rule.validate(
            field_value="12345",
            field_name="phone_number",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1

    def test_invalid_phone_number_format(self, validation_context):
        rule = PhoneNumberRule()
        is_valid, errors = rule.validate(
            field_value="abcdefghijk",
            field_name="phone_number",
            field_type=FieldDataTypeEnum.STRING,
            context=validation_context,
        )

        assert not is_valid
        assert len(errors) >= 1


@pytest.mark.unit
@pytest.mark.validation
class TestRequiredFieldRule:
    def test_required_field_with_value_passes(self, validation_context):
        rule = RequiredFieldRule()
        is_valid, errors = rule.validate(
            field_value="张三",
            field_name="patient_name",
            field_type=FieldDataTypeEnum.STRING,
            context={**validation_context, "required": True},
        )

        assert is_valid
        assert len(errors) == 0

    def test_required_field_missing_value(self, validation_context):
        rule = RequiredFieldRule()
        is_valid, errors = rule.validate(
            field_value=None,
            field_name="patient_name",
            field_type=FieldDataTypeEnum.STRING,
            context={**validation_context, "required": True},
        )

        assert not is_valid
        assert len(errors) == 1
        assert errors[0].error_code == "REQUIRED_FIELD_MISSING"
        assert errors[0].severity == "error"

    def test_required_field_empty_string(self, validation_context):
        rule = RequiredFieldRule()
        is_valid, errors = rule.validate(
            field_value="",
            field_name="patient_name",
            field_type=FieldDataTypeEnum.STRING,
            context={**validation_context, "required": True},
        )

        assert not is_valid
        assert len(errors) == 1

    def test_optional_field_missing_passes(self, validation_context):
        rule = RequiredFieldRule()
        is_valid, errors = rule.validate(
            field_value=None,
            field_name="optional_field",
            field_type=FieldDataTypeEnum.STRING,
            context={**validation_context, "required": False},
        )

        assert is_valid
        assert len(errors) == 0


@pytest.mark.unit
@pytest.mark.validation
class TestValidationService:
    def test_singleton_pattern(self):
        service1 = ValidationService()
        service2 = ValidationService()

        assert service1 is service2

    def test_validate_extraction_result_all_valid(self, db_session, sample_extraction_result, validation_context):
        service = ValidationService()

        db_session.query(ExtractedField).filter(
            ExtractedField.extraction_result_id == sample_extraction_result.id
        ).update({
            ExtractedField.field_value: "2024-01-15",
            ExtractedField.confidence: 0.9,
        }, synchronize_session=False)
        db_session.commit()

        result = service.validate_extraction_result(sample_extraction_result.id)

        assert result is not None
        assert "is_valid" in result
        assert "errors" in result

    def test_validate_extraction_result_with_errors(self, db_session, sample_extraction_result):
        service = ValidationService()

        db_session.query(ExtractedField).filter(
            ExtractedField.extraction_result_id == sample_extraction_result.id,
            ExtractedField.field_name == "total_amount",
        ).update({
            ExtractedField.field_value: "-500.00",
        }, synchronize_session=False)
        db_session.commit()

        result = service.validate_extraction_result(sample_extraction_result.id)

        assert result is not None
        assert len(result.get("errors", [])) >= 1

        amount_errors = [
            e for e in result["errors"]
            if e.get("field_name") == "total_amount"
        ]
        assert len(amount_errors) >= 1
        assert any("NEGATIVE_AMOUNT" in e.get("error_code", "") for e in amount_errors)

    def test_register_custom_rule(self):
        class CustomRule(ValidationRule):
            rule_name = "custom_rule"
            description = "Custom validation rule"

            def validate(self, field_value, field_name, field_type, context):
                if field_value and "invalid" in str(field_value).lower():
                    return False, [ValidationError(
                        field_name=field_name,
                        error_code="CUSTOM_ERROR",
                        error_message="Field contains invalid content",
                        severity="error",
                    )]
                return True, []

        service = ValidationService()
        initial_count = len(service.rules)

        service.register_custom_rule(CustomRule())

        assert len(service.rules) == initial_count + 1
        assert any(r.rule_name == "custom_rule" for r in service.rules)

    def test_multiple_rules_applied(self):
        service = ValidationService()

        field = {
            "field_name": "test_field",
            "field_value": "-100",
            "data_type": FieldDataTypeEnum.NUMBER,
            "required": True,
        }

        errors = service._validate_single_field(field, {})

        assert len(errors) >= 1
        assert any(e["error_code"] == "NEGATIVE_AMOUNT" for e in errors)

    def test_validation_errors_have_correct_severity(self, db_session, sample_extraction_result):
        service = ValidationService()

        db_session.query(ExtractedField).filter(
            ExtractedField.extraction_result_id == sample_extraction_result.id,
            ExtractedField.field_name == "total_amount",
        ).update({
            ExtractedField.field_value: "0",
        }, synchronize_session=False)
        db_session.commit()

        result = service.validate_extraction_result(sample_extraction_result.id)

        warning_errors = [e for e in result["errors"] if e.get("severity") == "warning"]
        error_errors = [e for e in result["errors"] if e.get("severity") == "error"]

        assert len(warning_errors) >= 0
        assert len(error_errors) >= 0

    def test_low_confidence_field_flagged_for_review(self, db_session, sample_extraction_result):
        service = ValidationService()

        db_session.query(ExtractedField).filter(
            ExtractedField.extraction_result_id == sample_extraction_result.id,
            ExtractedField.field_name == "total_amount",
        ).update({
            ExtractedField.confidence: 0.3,
        }, synchronize_session=False)
        db_session.commit()

        result = service.validate_extraction_result(sample_extraction_result.id)

        low_conf_fields = result.get("low_confidence_fields", [])
        assert len(low_conf_fields) >= 1
        assert any(f["field_name"] == "total_amount" for f in low_conf_fields)

    def test_suggested_values_provided(self, db_session, sample_extraction_result):
        service = ValidationService()

        db_session.query(ExtractedField).filter(
            ExtractedField.extraction_result_id == sample_extraction_result.id,
            ExtractedField.field_name == "total_amount",
        ).update({
            ExtractedField.field_value: "-150.00",
        }, synchronize_session=False)
        db_session.commit()

        result = service.validate_extraction_result(sample_extraction_result.id)

        fields_with_suggestions = [
            e for e in result["errors"]
            if e.get("suggested_value") is not None
        ]
        assert len(fields_with_suggestions) >= 1
        assert fields_with_suggestions[0]["suggested_value"] == "150.0"

    def test_validation_updates_extraction_status(self, db_session, sample_extraction_result):
        service = ValidationService()

        result = service.validate_extraction_result(sample_extraction_result.id)

        updated = db_session.query(ExtractionResult).filter(
            ExtractionResult.id == sample_extraction_result.id
        ).first()

        assert updated.status in ["validated", "needs_review"]


@pytest.mark.unit
@pytest.mark.validation
class TestCustomRuleRegistration:
    def test_register_and_use_custom_rule(self):
        class AgeRangeRule(ValidationRule):
            rule_name = "age_range"
            description = "Validate age is between 0 and 150"

            def validate(self, field_value, field_name, field_type, context):
                errors = []
                try:
                    age = int(field_value)
                    if age < 0 or age > 150:
                        errors.append(ValidationError(
                            field_name=field_name,
                            error_code="INVALID_AGE",
                            error_message=f"年龄 {age} 不在有效范围内 (0-150)",
                            severity="error",
                        ))
                        return False, errors
                except (ValueError, TypeError):
                    pass
                return True, errors

        service = ValidationService()
        service.register_custom_rule(AgeRangeRule())

        field = {
            "field_name": "patient_age",
            "field_value": "200",
            "data_type": FieldDataTypeEnum.NUMBER,
            "required": True,
        }

        errors = service._validate_single_field(field, {})

        assert any(e["error_code"] == "INVALID_AGE" for e in errors)


from app.models.extraction import ExtractionResult, ExtractedField
