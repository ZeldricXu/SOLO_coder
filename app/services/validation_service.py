import os
import json
import re
from typing import List, Dict, Any, Optional, Tuple, Callable
from datetime import datetime, date
from abc import ABC, abstractmethod

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.extraction import FieldDataTypeEnum
from app.schemas.common import ValidationError as ValidationErrorSchema
from app.models.extraction import ExtractionResult, ExtractedField
from app.models.document import DocumentStatus, Document
from app.core.database import get_sync_db

logger = get_logger(__name__)
settings = get_settings()


class ValidationRule(ABC):
    rule_name: str
    description: str

    @abstractmethod
    def validate(
        self,
        field_value: Any,
        field_name: str,
        field_type: FieldDataTypeEnum,
        context: Dict[str, Any],
    ) -> Tuple[bool, List[ValidationErrorSchema]]:
        pass


class DateFormatRule(ValidationRule):
    rule_name = "date_format"
    description = "Validate date format and range"

    def validate(
        self,
        field_value: Any,
        field_name: str,
        field_type: FieldDataTypeEnum,
        context: Dict[str, Any],
    ) -> Tuple[bool, List[ValidationErrorSchema]]:
        errors: List[ValidationErrorSchema] = []

        if field_value is None or field_value == "":
            return True, errors

        value_str = str(field_value).strip()

        formats = [
            r"^\d{4}-\d{2}-\d{2}$",
            r"^\d{4}/\d{2}/\d{2}$",
            r"^\d{4}年\d{1,2}月\d{1,2}日$",
        ]

        if not any(re.match(fmt, value_str) for fmt in formats):
            errors.append(ValidationErrorSchema(
                field_name=field_name,
                error_code="INVALID_DATE_FORMAT",
                error_message=f"日期格式不正确: {value_str}, 期望格式: YYYY-MM-DD",
                severity="error",
            ))
            return False, errors

        try:
            if "年" in value_str:
                value_str = value_str.replace("年", "-").replace("月", "-").replace("日", "")
            value_str = value_str.replace("/", "-")
            parsed_date = datetime.strptime(value_str, "%Y-%m-%d").date()

            today = date.today()
            min_date = date(1900, 1, 1)

            if parsed_date > today:
                errors.append(ValidationErrorSchema(
                    field_name=field_name,
                    error_code="DATE_IN_FUTURE",
                    error_message=f"日期不能晚于今天: {value_str}",
                    severity="warning",
                    suggested_value=today.strftime("%Y-%m-%d"),
                ))

            if parsed_date < min_date:
                errors.append(ValidationErrorSchema(
                    field_name=field_name,
                    error_code="DATE_TOO_EARLY",
                    error_message=f"日期过早: {value_str}",
                    severity="warning",
                ))

        except ValueError:
            errors.append(ValidationErrorSchema(
                field_name=field_name,
                error_code="INVALID_DATE",
                error_message=f"无效的日期: {value_str}",
                severity="error",
            ))
            return False, errors

        return len(errors) == 0, errors


class AmountRule(ValidationRule):
    rule_name = "amount"
    description = "Validate amount is positive and within reasonable range"

    def validate(
        self,
        field_value: Any,
        field_name: str,
        field_type: FieldDataTypeEnum,
        context: Dict[str, Any],
    ) -> Tuple[bool, List[ValidationErrorSchema]]:
        errors: List[ValidationErrorSchema] = []

        if field_value is None or field_value == "":
            return True, errors

        try:
            amount = float(field_value)

            if amount < 0:
                errors.append(ValidationErrorSchema(
                    field_name=field_name,
                    error_code="NEGATIVE_AMOUNT",
                    error_message=f"金额不能为负数: {amount}",
                    severity="error",
                    suggested_value=str(abs(amount)),
                ))
                return False, errors

            if amount == 0:
                errors.append(ValidationErrorSchema(
                    field_name=field_name,
                    error_code="ZERO_AMOUNT",
                    error_message=f"金额为0，请确认是否正确",
                    severity="warning",
                ))

            max_amount = context.get("max_amount", 10000000)
            if amount > max_amount:
                errors.append(ValidationErrorSchema(
                    field_name=field_name,
                    error_code="AMOUNT_TOO_LARGE",
                    error_message=f"金额超过合理范围: {amount} > {max_amount}",
                    severity="warning",
                ))

            if amount < 1 and amount > 0:
                errors.append(ValidationErrorSchema(
                    field_name=field_name,
                    error_code="AMOUNT_TOO_SMALL",
                    error_message=f"金额过小，请确认是否正确: {amount}",
                    severity="warning",
                ))

        except (ValueError, TypeError):
            errors.append(ValidationErrorSchema(
                field_name=field_name,
                error_code="INVALID_AMOUNT",
                error_message=f"无效的金额格式: {field_value}",
                severity="error",
            ))
            return False, errors

        return len(errors) == 0, errors


class ICD10CodeRule(ValidationRule):
    rule_name = "icd10_code"
    description = "Validate ICD-10 diagnosis code format and existence"

    def __init__(self):
        self.icd10_codes = self._load_icd10_codes()

    def _load_icd10_codes(self) -> Dict[str, str]:
        codes = {}
        try:
            if os.path.exists(settings.ICD10_CODES_FILE):
                with open(settings.ICD10_CODES_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, dict):
                        codes = data
                    elif isinstance(data, list):
                        for item in data:
                            if isinstance(item, dict) and "code" in item:
                                codes[item["code"]] = item.get("description", "")
        except Exception as e:
            logger.warning(f"Failed to load ICD-10 codes: {e}")

        if not codes:
            codes = self._get_default_icd10_codes()

        return codes

    def _get_default_icd10_codes(self) -> Dict[str, str]:
        return {
            "A00": "霍乱",
            "A01": "伤寒和副伤寒",
            "B00": "疱疹病毒感染",
            "C00": "唇恶性肿瘤",
            "C34": "支气管和肺恶性肿瘤",
            "D50": "缺铁性贫血",
            "E10": "1型糖尿病",
            "E11": "2型糖尿病",
            "F00": "阿尔茨海默病",
            "G00": "细菌性脑膜炎",
            "H00": "眼睑疾病",
            "I10": "特发性(原发性)高血压",
            "I20": "心绞痛",
            "I21": "急性心肌梗死",
            "I63": "脑梗死",
            "J00": "急性鼻咽炎",
            "J40": "支气管炎",
            "J44": "慢性阻塞性肺病",
            "K00": "牙和支持器官疾病",
            "K20": "食管炎",
            "K25": "胃溃疡",
            "K35": "急性阑尾炎",
            "L00": "皮肤和皮下组织感染",
            "M00": "感染性关节炎",
            "N00": "急性肾炎综合征",
            "O00": "异位妊娠",
            "P00": "母体疾病影响胎儿",
            "Q00": "先天性畸形",
            "R00": "症状和体征",
            "S00": "头部损伤",
            "T00": "损伤",
            "Z00": "健康检查",
        }

    def validate(
        self,
        field_value: Any,
        field_name: str,
        field_type: FieldDataTypeEnum,
        context: Dict[str, Any],
    ) -> Tuple[bool, List[ValidationErrorSchema]]:
        errors: List[ValidationErrorSchema] = []

        if field_value is None or field_value == "":
            return True, errors

        code = str(field_value).strip().upper()

        pattern = r"^[A-Z]\d{2}(?:\.\d{1,2})?$"
        if not re.match(pattern, code):
            errors.append(ValidationErrorSchema(
                field_name=field_name,
                error_code="INVALID_ICD10_FORMAT",
                error_message=f"ICD-10编码格式不正确: {code}, 正确格式: A00 或 A00.1",
                severity="error",
            ))
            return False, errors

        base_code = code.split(".")[0]
        if base_code not in self.icd10_codes:
            errors.append(ValidationErrorSchema(
                field_name=field_name,
                error_code="UNKNOWN_ICD10_CODE",
                error_message=f"未知的ICD-10编码: {code}",
                severity="warning",
            ))
            return False, errors

        return len(errors) == 0, errors


class IDCardRule(ValidationRule):
    rule_name = "id_card"
    description = "Validate Chinese ID card number format and checksum"

    def validate(
        self,
        field_value: Any,
        field_name: str,
        field_type: FieldDataTypeEnum,
        context: Dict[str, Any],
    ) -> Tuple[bool, List[ValidationErrorSchema]]:
        errors: List[ValidationErrorSchema] = []

        if field_value is None or field_value == "":
            return True, errors

        id_num = str(field_value).strip().upper()

        if len(id_num) != 18:
            errors.append(ValidationErrorSchema(
                field_name=field_name,
                error_code="INVALID_ID_LENGTH",
                error_message=f"身份证号长度不正确: {len(id_num)}位，应为18位",
                severity="error",
            ))
            return False, errors

        if not re.match(r"^\d{17}[\dX]$", id_num):
            errors.append(ValidationErrorSchema(
                field_name=field_name,
                error_code="INVALID_ID_FORMAT",
                error_message=f"身份证号格式不正确，应为17位数字加1位校验位",
                severity="error",
            ))
            return False, errors

        weights = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
        check_codes = ["1", "0", "X", "9", "8", "7", "6", "5", "4", "3", "2"]

        try:
            total = sum(int(id_num[i]) * weights[i] for i in range(17))
            check_code = check_codes[total % 11]

            if check_code != id_num[17]:
                errors.append(ValidationErrorSchema(
                    field_name=field_name,
                    error_code="INVALID_ID_CHECKSUM",
                    error_message=f"身份证号校验位错误，正确校验位应为: {check_code}",
                    severity="error",
                ))
                return False, errors

            birth_year = int(id_num[6:10])
            birth_month = int(id_num[10:12])
            birth_day = int(id_num[12:14])

            try:
                birth_date = date(birth_year, birth_month, birth_day)
                today = date.today()

                if birth_date > today:
                    errors.append(ValidationErrorSchema(
                        field_name=field_name,
                        error_code="BIRTH_DATE_IN_FUTURE",
                        error_message=f"出生日期不能晚于今天",
                        severity="warning",
                    ))

                age = today.year - birth_year
                if age > 130:
                    errors.append(ValidationErrorSchema(
                        field_name=field_name,
                        error_code="AGE_TOO_HIGH",
                        error_message=f"年龄超过合理范围: {age}岁",
                        severity="warning",
                    ))
            except ValueError:
                errors.append(ValidationErrorSchema(
                    field_name=field_name,
                    error_code="INVALID_BIRTH_DATE",
                    error_message=f"身份证号中的出生日期无效",
                    severity="error",
                ))
                return False, errors

        except Exception as e:
            logger.warning(f"ID card validation error: {e}")

        return len(errors) == 0, errors


class PhoneNumberRule(ValidationRule):
    rule_name = "phone_number"
    description = "Validate Chinese phone number format"

    def validate(
        self,
        field_value: Any,
        field_name: str,
        field_type: FieldDataTypeEnum,
        context: Dict[str, Any],
    ) -> Tuple[bool, List[ValidationErrorSchema]]:
        errors: List[ValidationErrorSchema] = []

        if field_value is None or field_value == "":
            return True, errors

        phone = str(field_value).strip()
        phone = re.sub(r"[\s\-\(\)]", "", phone)

        if not re.match(r"^1[3-9]\d{9}$", phone):
            errors.append(ValidationErrorSchema(
                field_name=field_name,
                error_code="INVALID_PHONE",
                error_message=f"手机号格式不正确: {field_value}",
                severity="error",
            ))
            return False, errors

        return True, errors


class RequiredFieldRule(ValidationRule):
    rule_name = "required"
    description = "Validate required fields are not empty"

    def validate(
        self,
        field_value: Any,
        field_name: str,
        field_type: FieldDataTypeEnum,
        context: Dict[str, Any],
    ) -> Tuple[bool, List[ValidationErrorSchema]]:
        errors: List[ValidationErrorSchema] = []
        is_required = context.get("required", False)

        if is_required and (field_value is None or str(field_value).strip() == ""):
            errors.append(ValidationErrorSchema(
                field_name=field_name,
                error_code="REQUIRED_FIELD_EMPTY",
                error_message=f"必填字段不能为空",
                severity="error",
            ))
            return False, errors

        return True, errors


class ValidationService:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        self.rules: Dict[str, ValidationRule] = {}
        self._register_rules()

    def _register_rules(self):
        rule_classes = [
            DateFormatRule,
            AmountRule,
            ICD10CodeRule,
            IDCardRule,
            PhoneNumberRule,
            RequiredFieldRule,
        ]

        for rule_cls in rule_classes:
            rule = rule_cls()
            self.rules[rule.rule_name] = rule

        self.field_rule_mapping = {
            "date": ["required", "date_format"],
            "treatment_date": ["required", "date_format"],
            "discharge_date": ["date_format"],
            "amount": ["required", "amount"],
            "total_amount": ["required", "amount"],
            "diagnosis_code": ["required", "icd10_code"],
            "id_card": ["required", "id_card"],
            "phone": ["phone_number"],
            "patient_name": ["required"],
            "name": ["required"],
        }

    def get_field_rules(self, field_name: str) -> List[str]:
        field_lower = field_name.lower()

        for key, rules in self.field_rule_mapping.items():
            if key in field_lower:
                return rules

        return []

    def validate_field(
        self,
        field_name: str,
        field_value: Any,
        field_type: FieldDataTypeEnum,
        required: bool = False,
        custom_rules: Optional[List[str]] = None,
        context: Optional[Dict[str, Any]] = None,
    ) -> Tuple[str, List[ValidationErrorSchema]]:
        context = context or {}
        context["required"] = required

        rule_names = custom_rules or self.get_field_rules(field_name)
        all_errors: List[ValidationErrorSchema] = []
        is_valid = True

        for rule_name in rule_names:
            rule = self.rules.get(rule_name)
            if rule:
                try:
                    valid, errors = rule.validate(field_value, field_name, field_type, context)
                    if not valid:
                        is_valid = False
                    all_errors.extend(errors)
                except Exception as e:
                    logger.error(f"Validation rule {rule_name} failed for {field_name}: {e}")

        if not is_valid:
            status = "error"
        elif all_errors:
            status = "warning"
        else:
            status = "valid"

        return status, all_errors

    def validate_extraction_result(
        self,
        extraction_result_id: int,
    ) -> Dict[str, Any]:
        logger.info(f"Validating extraction result: {extraction_result_id}")

        db = next(get_sync_db())
        try:
            extraction_result = db.query(ExtractionResult).filter(
                ExtractionResult.id == extraction_result_id
            ).first()

            if not extraction_result:
                raise ValueError(f"Extraction result not found: {extraction_result_id}")

            fields = db.query(ExtractedField).filter(
                ExtractedField.extraction_result_id == extraction_result_id
            ).all()

            validation_results = []
            has_errors = False
            has_warnings = False

            for field in fields:
                from app.models.extraction import FieldValidationStatus

                field_name = field.field_name
                field_value = field.normalized_value or field.value

                context = {}

                schema_fields = extraction_result.raw_extraction.get("fields", []) if extraction_result.raw_extraction else []
                for sf in schema_fields:
                    if sf.get("field_name") == field_name:
                        context["required"] = sf.get("required", False)
                        context["validation_rules"] = sf.get("validation_rules", {})
                        max_amount = context["validation_rules"].get("max") if context["validation_rules"] else None
                        if max_amount:
                            context["max_amount"] = max_amount
                        break

                status, errors = self.validate_field(
                    field_name=field_name,
                    field_value=field_value,
                    field_type=FieldDataTypeEnum(field.field_type),
                    required=context.get("required", False),
                    context=context,
                )

                if status == "error":
                    has_errors = True
                elif status == "warning":
                    has_warnings = True

                suggested_value = None
                for err in errors:
                    if err.suggested_value:
                        suggested_value = err.suggested_value
                        break

                field.validation_status = FieldValidationStatus(status)
                field.validation_errors = [e.model_dump() for e in errors if e.severity == "error"]
                field.validation_warnings = [e.model_dump() for e in errors if e.severity == "warning"]
                field.suggested_value = suggested_value

                validation_results.append({
                    "field_id": field.id,
                    "field_name": field_name,
                    "value": field_value,
                    "status": status,
                    "errors": [e.model_dump() for e in errors],
                    "suggested_value": suggested_value,
                })

            document_id = extraction_result.document_id
            doc = db.query(Document).filter(Document.id == document_id).first()

            if has_errors or has_warnings:
                from app.models.document import DocumentStatus

                low_confidence_fields = [f for f in fields if f.is_low_confidence]

                if low_confidence_fields or has_errors:
                    doc.status = DocumentStatus.NEEDS_REVIEW
                else:
                    doc.status = DocumentStatus.VALIDATED
            else:
                from app.models.document import DocumentStatus

                doc.status = DocumentStatus.VALIDATED

            db.commit()

            result = {
                "extraction_result_id": extraction_result_id,
                "document_id": document_id,
                "total_fields": len(fields),
                "valid_fields": sum(1 for r in validation_results if r["status"] == "valid"),
                "error_fields": sum(1 for r in validation_results if r["status"] == "error"),
                "warning_fields": sum(1 for r in validation_results if r["status"] == "warning"),
                "has_errors": has_errors,
                "has_warnings": has_warnings,
                "needs_review": has_errors or any(f.is_low_confidence for f in fields),
                "field_results": validation_results,
            }

            logger.info(
                f"Validation complete for extraction {extraction_result_id}: "
                f"{result['valid_fields']}/{result['total_fields']} valid, "
                f"{result['error_fields']} errors, {result['warning_fields']} warnings"
            )

            return result

        except Exception as e:
            logger.error(f"Validation failed for extraction {extraction_result_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def register_custom_rule(self, rule: ValidationRule) -> None:
        self.rules[rule.rule_name] = rule
        logger.info(f"Registered custom validation rule: {rule.rule_name}")

    def get_all_rules(self) -> List[Dict[str, str]]:
        return [
            {"name": name, "description": rule.description}
            for name, rule in self.rules.items()
        ]
