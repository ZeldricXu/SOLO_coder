import asyncio
import json
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, TypeVar
from uuid import uuid4


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


T = TypeVar("T")


class ProcessingException(Exception):
    def __init__(
        self,
        message: str,
        field: Optional[str] = None,
        value: Any = None,
        rule_id: Optional[str] = None,
    ):
        super().__init__(message)
        self.field = field
        self.value = value
        self.rule_id = rule_id


class ProcessingStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    PARTIAL = "partial"


@dataclass
class ProcessingResult:
    status: ProcessingStatus
    data: Optional[Any] = None
    original_data: Optional[Any] = None
    errors: List[Dict[str, Any]] = field(default_factory=list)
    warnings: List[Dict[str, Any]] = field(default_factory=list)
    transformations: List[Dict[str, Any]] = field(default_factory=list)
    started_at: datetime = field(default_factory=utc_now)
    completed_at: Optional[datetime] = None
    duration_ms: float = 0.0

    @property
    def is_success(self) -> bool:
        return self.status == ProcessingStatus.SUCCESS

    @property
    def is_failed(self) -> bool:
        return self.status == ProcessingStatus.FAILED

    def add_error(
        self,
        message: str,
        field: Optional[str] = None,
        value: Any = None,
        rule_id: Optional[str] = None,
    ) -> None:
        self.errors.append({
            "message": message,
            "field": field,
            "value": value,
            "rule_id": rule_id,
            "timestamp": utc_now().isoformat(),
        })

    def add_warning(
        self,
        message: str,
        field: Optional[str] = None,
    ) -> None:
        self.warnings.append({
            "message": message,
            "field": field,
            "timestamp": utc_now().isoformat(),
        })

    def add_transformation(
        self,
        field: str,
        from_value: Any,
        to_value: Any,
        rule_id: Optional[str] = None,
    ) -> None:
        self.transformations.append({
            "field": field,
            "from": from_value,
            "to": to_value,
            "rule_id": rule_id,
            "timestamp": utc_now().isoformat(),
        })


@dataclass
class HandlerContext:
    trace_id: str
    namespace: str = "default"
    config: Dict[str, Any] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)
    result: ProcessingResult = field(default_factory=ProcessingResult)


@dataclass
class ValidationRule:
    rule_id: str
    field: Optional[str] = None
    condition: str = "required"
    message: str = "Validation failed"
    pattern: Optional[str] = None
    min_length: Optional[int] = None
    max_length: Optional[int] = None
    min_value: Optional[float] = None
    max_value: Optional[float] = None
    allowed_values: Optional[List[Any]] = None
    custom_validator: Optional[Callable[[Any, HandlerContext], bool]] = None
    stop_on_fail: bool = False


@dataclass
class TransformationRule:
    rule_id: str
    field: Optional[str] = None
    operation: str = "copy"
    mapping: Optional[Dict[Any, Any]] = None
    expression: Optional[str] = None
    formatter: Optional[str] = None
    custom_transformer: Optional[Callable[[Any, HandlerContext], Any]] = None


@dataclass
class StandardizationRule:
    rule_id: str
    target_format: str = "string"
    field_name: str = "standardized"
    source_fields: List[str] = field(default_factory=list)
    template: Optional[str] = None


@dataclass
class FieldMapping:
    source_field: str
    target_field: str
    required: bool = False
    default_value: Any = None
    transform: Optional[Callable[[Any], Any]] = None


class SchemaValidator:
    def __init__(self, rules: List[ValidationRule]):
        self._rules = rules

    def add_rule(self, rule: ValidationRule) -> None:
        self._rules.append(rule)

    def validate(
        self,
        data: Any,
        context: Optional[HandlerContext] = None,
    ) -> ProcessingResult:
        ctx = context or HandlerContext(trace_id=generate_id("trace"))
        result = ProcessingResult(
            status=ProcessingStatus.RUNNING,
            original_data=data,
        )

        try:
            for rule in self._rules:
                valid = self._check_rule(data, rule, ctx, result)
                if not valid and rule.stop_on_fail:
                    break

            if result.errors:
                result.status = ProcessingStatus.FAILED
            else:
                result.status = ProcessingStatus.SUCCESS
                result.data = data

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.add_error(str(e))

        finally:
            result.completed_at = utc_now()

        return result

    def _check_rule(
        self,
        data: Any,
        rule: ValidationRule,
        ctx: HandlerContext,
        result: ProcessingResult,
    ) -> bool:
        value = None
        if rule.field and isinstance(data, dict):
            value = data.get(rule.field)
        else:
            value = data

        condition = rule.condition

        try:
            if condition == "required":
                if value is None or (isinstance(value, str) and not value.strip()):
                    result.add_error(
                        rule.message or f"Field '{rule.field}' is required",
                        field=rule.field,
                        value=value,
                        rule_id=rule.rule_id,
                    )
                    return False

            elif condition == "pattern":
                if value and rule.pattern and not re.match(rule.pattern, str(value)):
                    result.add_error(
                        rule.message or f"Field '{rule.field}' does not match pattern",
                        field=rule.field,
                        value=value,
                        rule_id=rule.rule_id,
                    )
                    return False

            elif condition == "min_length":
                if value and rule.min_length is not None and len(str(value)) < rule.min_length:
                    result.add_error(
                        rule.message or f"Field '{rule.field}' is too short",
                        field=rule.field,
                        value=value,
                        rule_id=rule.rule_id,
                    )
                    return False

            elif condition == "max_length":
                if value and rule.max_length is not None and len(str(value)) > rule.max_length:
                    result.add_error(
                        rule.message or f"Field '{rule.field}' is too long",
                        field=rule.field,
                        value=value,
                        rule_id=rule.rule_id,
                    )
                    return False

            elif condition == "min_value":
                if value is not None and rule.min_value is not None:
                    try:
                        if float(value) < rule.min_value:
                            result.add_error(
                                rule.message or f"Field '{rule.field}' is below minimum",
                                field=rule.field,
                                value=value,
                                rule_id=rule.rule_id,
                            )
                            return False
                    except (ValueError, TypeError):
                        pass

            elif condition == "max_value":
                if value is not None and rule.max_value is not None:
                    try:
                        if float(value) > rule.max_value:
                            result.add_error(
                                rule.message or f"Field '{rule.field}' is above maximum",
                                field=rule.field,
                                value=value,
                                rule_id=rule.rule_id,
                            )
                            return False
                    except (ValueError, TypeError):
                        pass

            elif condition == "in":
                if value is not None and rule.allowed_values and value not in rule.allowed_values:
                    result.add_error(
                        rule.message or f"Field '{rule.field}' has invalid value",
                        field=rule.field,
                        value=value,
                        rule_id=rule.rule_id,
                    )
                    return False

            elif condition == "custom" and rule.custom_validator:
                if not rule.custom_validator(value, ctx):
                    result.add_error(
                        rule.message or f"Field '{rule.field}' failed custom validation",
                        field=rule.field,
                        value=value,
                        rule_id=rule.rule_id,
                    )
                    return False

        except Exception as e:
            result.add_error(
                f"Validation error: {e}",
                field=rule.field,
                value=value,
                rule_id=rule.rule_id,
            )
            return False

        return True


class DataTransformer:
    def __init__(self, rules: List[TransformationRule]):
        self._rules = rules

    def add_rule(self, rule: TransformationRule) -> None:
        self._rules.append(rule)

    def transform(
        self,
        data: Any,
        context: Optional[HandlerContext] = None,
    ) -> ProcessingResult:
        ctx = context or HandlerContext(trace_id=generate_id("trace"))
        result = ProcessingResult(
            status=ProcessingStatus.RUNNING,
            original_data=data,
        )

        try:
            transformed = data

            for rule in self._rules:
                transformed = self._apply_rule(transformed, rule, ctx, result)

            result.status = ProcessingStatus.SUCCESS
            result.data = transformed

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.add_error(str(e))

        finally:
            result.completed_at = utc_now()

        return result

    def _apply_rule(
        self,
        data: Any,
        rule: TransformationRule,
        ctx: HandlerContext,
        result: ProcessingResult,
    ) -> Any:
        if not isinstance(data, dict):
            return data

        if rule.field not in data:
            return data

        original_value = data[rule.field]
        new_value = original_value

        try:
            if rule.operation == "copy" and rule.mapping:
                if original_value in rule.mapping:
                    new_value = rule.mapping[original_value]

            elif rule.operation == "uppercase":
                if isinstance(original_value, str):
                    new_value = original_value.upper()

            elif rule.operation == "lowercase":
                if isinstance(original_value, str):
                    new_value = original_value.lower()

            elif rule.operation == "strip":
                if isinstance(original_value, str):
                    new_value = original_value.strip()

            elif rule.operation == "trim":
                if isinstance(original_value, str):
                    new_value = original_value.strip()

            elif rule.operation == "int":
                if original_value is not None:
                    new_value = int(original_value)

            elif rule.operation == "float":
                if original_value is not None:
                    new_value = float(original_value)

            elif rule.operation == "string":
                if original_value is not None:
                    new_value = str(original_value)

            elif rule.operation == "bool":
                if original_value is not None:
                    if isinstance(original_value, str):
                        new_value = original_value.lower() in ("true", "1", "yes", "on")
                    else:
                        new_value = bool(original_value)

            elif rule.operation == "date":
                if original_value is not None:
                    if rule.formatter:
                        new_value = datetime.strptime(str(original_value), rule.formatter)
                    else:
                        new_value = datetime.fromisoformat(str(original_value))

            elif rule.operation == "custom" and rule.custom_transformer:
                new_value = rule.custom_transformer(original_value, ctx)

        except Exception as e:
            result.add_warning(
                f"Transformation failed for field '{rule.field}': {e}",
                field=rule.field,
            )
            return data

        if new_value != original_value:
            result.add_transformation(
                field=rule.field,
                from_value=original_value,
                to_value=new_value,
                rule_id=rule.rule_id,
            )
            data[rule.field] = new_value

        return data


class DataNormalizer:
    def __init__(self, rules: List[StandardizationRule]):
        self._rules = rules

    def add_rule(self, rule: StandardizationRule) -> None:
        self._rules.append(rule)

    def normalize(
        self,
        data: Any,
        context: Optional[HandlerContext] = None,
    ) -> ProcessingResult:
        ctx = context or HandlerContext(trace_id=generate_id("trace"))
        result = ProcessingResult(
            status=ProcessingStatus.RUNNING,
            original_data=data,
        )

        try:
            normalized = dict(data) if isinstance(data, dict) else data

            for rule in self._rules:
                normalized = self._apply_rule(normalized, rule, ctx, result)

            result.status = ProcessingStatus.SUCCESS
            result.data = normalized

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.add_error(str(e))

        finally:
            result.completed_at = utc_now()

        return result

    def _apply_rule(
        self,
        data: Any,
        rule: StandardizationRule,
        ctx: HandlerContext,
        result: ProcessingResult,
    ) -> Any:
        if not isinstance(data, dict):
            return data

        if rule.template:
            values = {k: data.get(k, "") for k in rule.source_fields}
            try:
                standardized = rule.template.format(**values)
            except Exception:
                standardized = ""

            result.add_transformation(
                field=rule.field_name,
                from_value=None,
                to_value=standardized,
                rule_id=rule.rule_id,
            )
            data[rule.field_name] = standardized

        elif rule.source_fields:
            parts = []
            for field in rule.source_fields:
                if field in data and data[field] is not None:
                    parts.append(str(data[field]))
            standardized = "_".join(parts)

            result.add_transformation(
                field=rule.field_name,
                from_value=None,
                to_value=standardized,
                rule_id=rule.rule_id,
            )
            data[rule.field_name] = standardized

        return data


class DataCleaner:
    def __init__(self):
        self._rules: List[Callable[[Any, HandlerContext], Any]] = []

    def add_rule(self, rule: Callable[[Any, HandlerContext], Any]) -> None:
        self._rules.append(rule)

    def clean(
        self,
        data: Any,
        context: Optional[HandlerContext] = None,
    ) -> ProcessingResult:
        ctx = context or HandlerContext(trace_id=generate_id("trace"))
        result = ProcessingResult(
            status=ProcessingStatus.RUNNING,
            original_data=data,
        )

        try:
            cleaned = data

            for rule in self._rules:
                cleaned = rule(cleaned, ctx)

            result.status = ProcessingStatus.SUCCESS
            result.data = cleaned

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.add_error(str(e))

        finally:
            result.completed_at = utc_now()

        return result


class DataConverter:
    def __init__(self, mappings: List[FieldMapping]):
        self._mappings = mappings

    def add_mapping(self, mapping: FieldMapping) -> None:
        self._mappings.append(mapping)

    def convert(
        self,
        data: Any,
        context: Optional[HandlerContext] = None,
    ) -> ProcessingResult:
        ctx = context or HandlerContext(trace_id=generate_id("trace"))
        result = ProcessingResult(
            status=ProcessingStatus.RUNNING,
            original_data=data,
        )

        try:
            if not isinstance(data, dict):
                result.status = ProcessingStatus.FAILED
                result.add_error("Input data must be a dictionary")
                return result

            converted: Dict[str, Any] = {}

            for mapping in self._mappings:
                if mapping.source_field in data:
                    value = data[mapping.source_field]
                    if mapping.transform:
                        value = mapping.transform(value)
                    converted[mapping.target_field] = value
                elif mapping.required:
                    result.add_error(
                        f"Required field '{mapping.source_field}' missing",
                        field=mapping.source_field,
                    )
                elif mapping.default_value is not None:
                    converted[mapping.target_field] = mapping.default_value

            if result.errors:
                result.status = ProcessingStatus.FAILED
            else:
                result.status = ProcessingStatus.SUCCESS
                result.data = converted

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.add_error(str(e))

        finally:
            result.completed_at = utc_now()

        return result


class RuleEngine:
    def __init__(self):
        self._validators: Dict[str, SchemaValidator] = {}
        self._transformers: Dict[str, DataTransformer] = {}
        self._normalizers: Dict[str, DataNormalizer] = {}
        self._cleaners: Dict[str, DataCleaner] = {}
        self._converters: Dict[str, DataConverter] = {}

    def register_validator(self, name: str, validator: SchemaValidator) -> None:
        self._validators[name] = validator

    def register_transformer(self, name: str, transformer: DataTransformer) -> None:
        self._transformers[name] = transformer

    def register_normalizer(self, name: str, normalizer: DataNormalizer) -> None:
        self._normalizers[name] = normalizer

    def register_cleaner(self, name: str, cleaner: DataCleaner) -> None:
        self._cleaners[name] = cleaner

    def register_converter(self, name: str, converter: DataConverter) -> None:
        self._converters[name] = converter

    async def execute_rules(
        self,
        data: Any,
        validator_name: Optional[str] = None,
        cleaner_name: Optional[str] = None,
        transformer_name: Optional[str] = None,
        normalizer_name: Optional[str] = None,
        converter_name: Optional[str] = None,
        context: Optional[HandlerContext] = None,
    ) -> ProcessingResult:
        ctx = context or HandlerContext(trace_id=generate_id("trace"))
        result = ProcessingResult(
            status=ProcessingStatus.RUNNING,
            original_data=data,
        )

        current_data = data

        try:
            if validator_name and validator_name in self._validators:
                validator = self._validators[validator_name]
                val_result = validator.validate(current_data, ctx)
                if val_result.errors:
                    result.errors.extend(val_result.errors)
                    result.status = ProcessingStatus.FAILED
                    return result
                if val_result.data is not None:
                    current_data = val_result.data

            if cleaner_name and cleaner_name in self._cleaners:
                cleaner = self._cleaners[cleaner_name]
                clean_result = cleaner.clean(current_data, ctx)
                if clean_result.errors:
                    result.errors.extend(clean_result.errors)
                current_data = clean_result.data or current_data
                result.warnings.extend(clean_result.warnings)

            if transformer_name and transformer_name in self._transformers:
                transformer = self._transformers[transformer_name]
                trans_result = transformer.transform(current_data, ctx)
                if trans_result.errors:
                    result.errors.extend(trans_result.errors)
                current_data = trans_result.data or current_data
                result.transformations.extend(trans_result.transformations)

            if normalizer_name and normalizer_name in self._normalizers:
                normalizer = self._normalizers[normalizer_name]
                norm_result = normalizer.normalize(current_data, ctx)
                if norm_result.errors:
                    result.errors.extend(norm_result.errors)
                current_data = norm_result.data or current_data
                result.transformations.extend(norm_result.transformations)

            if converter_name and converter_name in self._converters:
                converter = self._converters[converter_name]
                conv_result = converter.convert(current_data, ctx)
                if conv_result.errors:
                    result.errors.extend(conv_result.errors)
                current_data = conv_result.data or current_data

            if result.errors:
                result.status = ProcessingStatus.FAILED
            else:
                result.status = ProcessingStatus.SUCCESS
                result.data = current_data

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.add_error(str(e))

        finally:
            result.completed_at = utc_now()

        return result


class PipelineStep:
    def __init__(
        self,
        name: str,
        processor: Callable[[Any, HandlerContext], ProcessingResult],
        stop_on_fail: bool = True,
    ):
        self.name = name
        self.processor = processor
        self.stop_on_fail = stop_on_fail


class DataPipeline:
    def __init__(self, steps: List[PipelineStep]):
        self._steps = steps

    def add_step(self, step: PipelineStep) -> None:
        self._steps.append(step)

    async def execute(
        self,
        data: Any,
        context: Optional[HandlerContext] = None,
    ) -> ProcessingResult:
        ctx = context or HandlerContext(trace_id=generate_id("trace"))
        result = ProcessingResult(
            status=ProcessingStatus.RUNNING,
            original_data=data,
        )

        current_data = data

        try:
            for step in self._steps:
                try:
                    step_result = self.processor(current_data, ctx)
                    if asyncio.iscoroutine(step_result):
                        step_result = await step_result

                    result.warnings.extend(step_result.warnings)
                    result.transformations.extend(step_result.transformations)

                    if step_result.errors:
                        result.errors.extend(step_result.errors)
                        if step.stop_on_fail:
                            result.status = ProcessingStatus.FAILED
                            return result

                    if step_result.data is not None:
                        current_data = step_result.data

                except Exception as e:
                    result.add_error(
                        f"Step '{step.name}' failed: {e}",
                        field=step.name,
                    )
                    if step.stop_on_fail:
                        result.status = ProcessingStatus.FAILED
                        return result

            result.status = ProcessingStatus.SUCCESS
            result.data = current_data

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.add_error(str(e))

        finally:
            result.completed_at = utc_now()

        return result


class PipelineBuilder:
    def __init__(self):
        self._steps: List[PipelineStep] = []

    def add_validation(
        self,
        validator: SchemaValidator,
        stop_on_fail: bool = True,
    ) -> "PipelineBuilder":
        self._steps.append(PipelineStep(
            name="validation",
            processor=lambda d, c: validator.validate(d, c),
            stop_on_fail=stop_on_fail,
        ))
        return self

    def add_cleaning(
        self,
        cleaner: DataCleaner,
        stop_on_fail: bool = False,
    ) -> "PipelineBuilder":
        self._steps.append(PipelineStep(
            name="cleaning",
            processor=lambda d, c: cleaner.clean(d, c),
            stop_on_fail=stop_on_fail,
        ))
        return self

    def add_transformation(
        self,
        transformer: DataTransformer,
        stop_on_fail: bool = False,
    ) -> "PipelineBuilder":
        self._steps.append(PipelineStep(
            name="transformation",
            processor=lambda d, c: transformer.transform(d, c),
            stop_on_fail=stop_on_fail,
        ))
        return self

    def add_normalization(
        self,
        normalizer: DataNormalizer,
        stop_on_fail: bool = False,
    ) -> "PipelineBuilder":
        self._steps.append(PipelineStep(
            name="normalization",
            processor=lambda d, c: normalizer.normalize(d, c),
            stop_on_fail=stop_on_fail,
        ))
        return self

    def add_conversion(
        self,
        converter: DataConverter,
        stop_on_fail: bool = True,
    ) -> "PipelineBuilder":
        self._steps.append(PipelineStep(
            name="conversion",
            processor=lambda d, c: converter.convert(d, c),
            stop_on_fail=stop_on_fail,
        ))
        return self

    def add_custom_step(
        self,
        name: str,
        processor: Callable[[Any, HandlerContext], ProcessingResult],
        stop_on_fail: bool = True,
    ) -> "PipelineBuilder":
        self._steps.append(PipelineStep(
            name=name,
            processor=processor,
            stop_on_fail=stop_on_fail,
        ))
        return self

    def build(self) -> DataPipeline:
        return DataPipeline(list(self._steps))


class ProcessingEngine:
    def __init__(self):
        self._pipelines: Dict[str, DataPipeline] = {}
        self._rule_engine = RuleEngine()

    @property
    def rule_engine(self) -> RuleEngine:
        return self._rule_engine

    def register_pipeline(self, name: str, pipeline: DataPipeline) -> None:
        self._pipelines[name] = pipeline

    def get_pipeline(self, name: str) -> Optional[DataPipeline]:
        return self._pipelines.get(name)

    def list_pipelines(self) -> List[str]:
        return list(self._pipelines.keys())

    async def process(
        self,
        pipeline_name: str,
        data: Any,
        context: Optional[HandlerContext] = None,
    ) -> ProcessingResult:
        pipeline = self._pipelines.get(pipeline_name)
        if not pipeline:
            result = ProcessingResult(
                status=ProcessingStatus.FAILED,
                original_data=data,
            )
            result.add_error(f"Pipeline '{pipeline_name}' not found")
            result.completed_at = utc_now()
            return result

        return await pipeline.execute(data, context)


_engine_instance: Optional[ProcessingEngine] = None


def get_processing_engine() -> ProcessingEngine:
    global _engine_instance
    if _engine_instance is None:
        _engine_instance = ProcessingEngine()
    return _engine_instance
