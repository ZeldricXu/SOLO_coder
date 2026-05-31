from datetime import datetime
from typing import Any, Callable, Dict, List, Optional
from app.logger import logger


class ValidationError(Exception):
    pass


class ProcessingError(Exception):
    pass


class PipelineStage:
    def __init__(self, name: str, handler: Callable, description: str = ""):
        self.name = name
        self.handler = handler
        self.description = description
        self.enabled = True
    
    async def execute(self, data: Any, context: Dict[str, Any]) -> Any:
        if not self.enabled:
            return data
        
        try:
            logger.info("Executing pipeline stage", stage=self.name)
            if hasattr(self.handler, '__call__'):
                import asyncio
                if asyncio.iscoroutinefunction(self.handler):
                    result = await self.handler(data, context)
                else:
                    result = self.handler(data, context)
                return result
        except Exception as e:
            logger.error("Pipeline stage failed", stage=self.name, error=str(e))
            raise ProcessingError(f"Stage {self.name} failed: {e}")


class DataPipeline:
    def __init__(self, name: str = "default"):
        self.name = name
        self.stages: List[PipelineStage] = []
        self.validators: List[Callable] = []
    
    def add_stage(self, stage: PipelineStage) -> 'DataPipeline':
        self.stages.append(stage)
        logger.info("Added pipeline stage", pipeline=self.name, stage=stage.name)
        return self
    
    def add_validator(self, validator: Callable) -> 'DataPipeline':
        self.validators.append(validator)
        return self
    
    async def validate(self, data: Any, context: Dict[str, Any]) -> bool:
        for validator in self.validators:
            import asyncio
            if asyncio.iscoroutinefunction(validator):
                valid = await validator(data, context)
            else:
                valid = validator(data, context)
            
            if not valid:
                raise ValidationError(f"Validation failed in {getattr(validator, '__name__', 'validator')}")
        
        return True
    
    async def execute(self, data: Any, context: Dict[str, Any] = None) -> Dict[str, Any]:
        if context is None:
            context = {}
        
        context["pipeline_name"] = self.name
        context["start_time"] = datetime.utcnow().isoformat()
        
        await self.validate(data, context)
        
        current_data = data
        execution_log = []
        
        for stage in self.stages:
            stage_start = datetime.utcnow()
            try:
                current_data = await stage.execute(current_data, context)
                stage_duration = (datetime.utcnow() - stage_start).total_seconds()
                execution_log.append({
                    "stage": stage.name,
                    "status": "success",
                    "duration_seconds": stage_duration
                })
            except Exception as e:
                stage_duration = (datetime.utcnow() - stage_start).total_seconds()
                execution_log.append({
                    "stage": stage.name,
                    "status": "failed",
                    "error": str(e),
                    "duration_seconds": stage_duration
                })
                raise ProcessingError(f"Pipeline execution failed at stage {stage.name}: {e}")
        
        context["end_time"] = datetime.utcnow().isoformat()
        
        return {
            "pipeline": self.name,
            "result": current_data,
            "context": context,
            "execution_log": execution_log
        }


class DataTransformer:
    def __init__(self):
        self._transformers: Dict[str, Callable] = {}
        self._formatters: Dict[str, Callable] = {}
    
    def register_transformer(self, name: str, transformer: Callable):
        self._transformers[name] = transformer
        logger.info("Registered data transformer", name=name)
    
    def register_formatter(self, format_name: str, formatter: Callable):
        self._formatters[format_name] = formatter
        logger.info("Registered data formatter", format_name=format_name)
    
    async def transform(self, data: Any, transformer_name: str) -> Any:
        if transformer_name not in self._transformers:
            raise ProcessingError(f"Transformer not found: {transformer_name}")
        
        transformer = self._transformers[transformer_name]
        import asyncio
        if asyncio.iscoroutinefunction(transformer):
            return await transformer(data)
        return transformer(data)
    
    async def format(self, data: Any, format_name: str) -> Any:
        if format_name not in self._formatters:
            raise ProcessingError(f"Formatter not found: {format_name}")
        
        formatter = self._formatters[format_name]
        import asyncio
        if asyncio.iscoroutinefunction(formatter):
            return await formatter(data)
        return formatter(data)


class Standardizer:
    def __init__(self):
        self._schemas: Dict[str, Dict[str, Any]] = {}
    
    def register_schema(self, schema_name: str, schema: Dict[str, Any]):
        self._schemas[schema_name] = schema
        logger.info("Registered standardization schema", schema_name=schema_name)
    
    def validate_against_schema(self, data: Dict[str, Any], schema_name: str) -> bool:
        if schema_name not in self._schemas:
            raise ProcessingError(f"Schema not found: {schema_name}")
        
        schema = self._schemas[schema_name]
        
        for field, field_config in schema.get("properties", {}).items():
            if field_config.get("required") and field not in data:
                raise ValidationError(f"Missing required field: {field}")
            
            if field in data and "type" in field_config:
                actual_type = type(data[field]).__name__
                expected_type = field_config["type"]
                if expected_type == "integer":
                    if not isinstance(data[field], (int, float)):
                        raise ValidationError(f"Type mismatch for {field}: expected integer/float, got {actual_type}")
                elif expected_type == "number":
                    if not isinstance(data[field], (int, float)):
                        raise ValidationError(f"Type mismatch for {field}: expected number, got {actual_type}")
                elif expected_type == "string":
                    if not isinstance(data[field], str):
                        raise ValidationError(f"Type mismatch for {field}: expected string, got {actual_type}")
                elif expected_type == "object":
                    if not isinstance(data[field], dict):
                        raise ValidationError(f"Type mismatch for {field}: expected object, got {actual_type}")
                elif expected_type == "array":
                    if not isinstance(data[field], list):
                        raise ValidationError(f"Type mismatch for {field}: expected array, got {actual_type}")
                elif expected_type == "boolean":
                    if not isinstance(data[field], bool):
                        raise ValidationError(f"Type mismatch for {field}: expected boolean, got {actual_type}")
        
        return True
    
    def normalize(self, data: Dict[str, Any], schema_name: str) -> Dict[str, Any]:
        if schema_name not in self._schemas:
            return data
        
        schema = self._schemas[schema_name]
        normalized = {}
        
        for field, value in data.items():
            if field in schema.get("properties", {}):
                field_config = schema["properties"][field]
                normalized[field] = self._normalize_field(value, field_config)
            else:
                normalized[field] = value
        
        for field, field_config in schema.get("properties", {}).items():
            if field not in normalized and "default" in field_config:
                normalized[field] = field_config["default"]
        
        return normalized
    
    def _normalize_field(self, value: Any, config: Dict[str, Any]) -> Any:
        if value is None:
            return config.get("default")
        
        if config.get("type") == "integer":
            try:
                return int(float(value))
            except (TypeError, ValueError):
                return value
        elif config.get("type") == "number":
            try:
                return float(value)
            except (TypeError, ValueError):
                return value
        elif config.get("type") == "string":
            return str(value)
        elif config.get("type") == "boolean":
            return bool(value)
        
        return value


data_transformer = DataTransformer()
standardizer = Standardizer()


def create_default_pipeline() -> DataPipeline:
    pipeline = DataPipeline(name="default_processing")
    
    def validate_input(data: Any, context: Dict[str, Any]) -> bool:
        if data is None:
            raise ValidationError("Input data cannot be None")
        return True
    
    def normalize_structure(data: Any, context: Dict[str, Any]) -> Any:
        if isinstance(data, dict):
            return {
                "data": data,
                "metadata": {
                    "original_type": "dict",
                    "received_at": datetime.utcnow().isoformat()
                }
            }
        return {
            "data": data,
            "metadata": {
                "original_type": type(data).__name__,
                "received_at": datetime.utcnow().isoformat()
            }
        }
    
    def add_timestamps(data: Any, context: Dict[str, Any]) -> Any:
        if isinstance(data, dict):
            data["processed_at"] = datetime.utcnow().isoformat()
        return data
    
    pipeline.add_validator(validate_input)
    pipeline.add_stage(PipelineStage("normalize_structure", normalize_structure))
    pipeline.add_stage(PipelineStage("add_timestamps", add_timestamps))
    
    return pipeline


async def execute_processing_handler(
    payload: Any,
    trace_id: str = None,
    pipeline_name: str = None
) -> Dict[str, Any]:
    import uuid
    
    context = {
        "trace_id": trace_id or str(uuid.uuid4()),
        "started_at": datetime.utcnow()
    }
    
    logger.info("Starting processing handler", trace_id=context["trace_id"])
    
    try:
        if payload is None:
            raise ValidationError("Payload cannot be None")
        
        pipeline = create_default_pipeline()
        result = await pipeline.execute(payload, context)
        
        logger.info("Processing completed successfully", trace_id=context["trace_id"])
        return {
            "code": 200,
            "data": result,
            "trace_id": context["trace_id"]
        }
    
    except ValidationError as e:
        logger.error("Validation error", trace_id=context["trace_id"], error=str(e))
        return {
            "code": 422,
            "error": str(e),
            "trace_id": context["trace_id"]
        }
    
    except Exception as e:
        logger.error("Processing error", trace_id=context["trace_id"], error=str(e))
        return {
            "code": 500,
            "error": "Internal processing error",
            "trace_id": context["trace_id"]
        }
