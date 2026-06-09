import os
import yaml
import logging
from typing import List, Optional, Dict, Any
from pathlib import Path
from threading import Lock

from sqlalchemy import desc, func, cast, String

from app.core.database import get_sync_db
from app.models.extraction import ExtractionSchema as ExtractionSchemaModel
from app.models.extraction import ExtractionResult
from app.schemas.extraction import (
    ExtractionSchemaCreate,
    ExtractionSchemaUpdate,
    ExtractionSchemaResponse,
    ExtractionSchemaWithStats,
    FieldSchema,
)

logger = logging.getLogger(__name__)


def _json_array_contains(column, value):
    """Database-agnostic check if a JSON array column contains a value."""
    return cast(column, String).like(f'%"{value}"%')


class ExtractionSchemaService:
    _instance = None
    _lock = Lock()

    def __new__(cls, *args, **kwargs):
        if not cls._instance:
            with cls._lock:
                if not cls._instance:
                    cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, schemas_dir: str = None):
        self.schemas_dir = schemas_dir or os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "schemas"
        )
        self._schema_cache: Dict[str, ExtractionSchemaResponse] = {}
        self._default_schema: Optional[ExtractionSchemaResponse] = None

    def _parse_yaml(self, yaml_content: str) -> Dict[str, Any]:
        """Parse YAML content into a dictionary."""
        try:
            data = yaml.safe_load(yaml_content)
            return data
        except yaml.YAMLError as e:
            logger.error(f"Failed to parse YAML: {e}")
            raise ValueError(f"Invalid YAML format: {str(e)}")

    def _load_yaml_file(self, yaml_path: str) -> Dict[str, Any]:
        """Load and parse a YAML file."""
        try:
            with open(yaml_path, "r", encoding="utf-8") as f:
                yaml_content = f.read()
            return self._parse_yaml(yaml_content)
        except FileNotFoundError:
            logger.error(f"YAML file not found: {yaml_path}")
            raise ValueError(f"Schema file not found: {yaml_path}")
        except Exception as e:
            logger.error(f"Failed to load YAML file {yaml_path}: {e}")
            raise

    def _yaml_to_schema_dict(self, yaml_data: Dict[str, Any]) -> Dict[str, Any]:
        """Convert YAML data to schema dictionary format."""
        fields = []
        for field_data in yaml_data.get("fields", []):
            field = {
                "field_name": field_data.get("field_name"),
                "field_type": field_data.get("field_type", "string"),
                "description": field_data.get("description"),
                "required": field_data.get("required", False),
                "default_value": field_data.get("default_value"),
                "validation_rules": field_data.get("validation_rules", {}),
                "examples": field_data.get("examples", []),
            }
            fields.append(field)

        return {
            "schema_name": yaml_data.get("schema_name"),
            "schema_version": yaml_data.get("schema_version", "1.0"),
            "description": yaml_data.get("description"),
            "business_line": yaml_data.get("business_line"),
            "document_types": yaml_data.get("document_types", []),
            "fields": fields,
            "is_active": yaml_data.get("is_active", True),
            "is_default": yaml_data.get("is_default", False),
            "created_by": yaml_data.get("created_by", "system"),
        }

    def load_schemas_from_directory(self, directory: str = None) -> List[ExtractionSchemaResponse]:
        """Load all YAML schema files from a directory and store them in the database."""
        directory = directory or self.schemas_dir
        loaded_schemas = []

        if not os.path.exists(directory):
            logger.warning(f"Schemas directory not found: {directory}")
            return loaded_schemas

        for filename in os.listdir(directory):
            if not (filename.endswith(".yaml") or filename.endswith(".yml")):
                continue

            yaml_path = os.path.join(directory, filename)
            try:
                yaml_data = self._load_yaml_file(yaml_path)
                schema_dict = self._yaml_to_schema_dict(yaml_data)

                existing = self.get_schema_by_name(schema_dict["schema_name"])
                if existing:
                    logger.info(f"Schema '{schema_dict['schema_name']}' already exists, skipping")
                    continue

                with open(yaml_path, "r", encoding="utf-8") as f:
                    yaml_content = f.read()

                schema_dict["yaml_source_path"] = yaml_path
                schema_dict["yaml_content"] = yaml_content

                schema = self.create_schema(ExtractionSchemaCreate(**schema_dict))
                loaded_schemas.append(schema)
                logger.info(f"Loaded schema: {schema.schema_name} from {filename}")

            except Exception as e:
                logger.error(f"Failed to load schema from {filename}: {e}")
                continue

        return loaded_schemas

    def load_schema_from_yaml_content(
        self, yaml_content: str, created_by: str = "admin"
    ) -> ExtractionSchemaResponse:
        """Create a schema from YAML content."""
        yaml_data = self._parse_yaml(yaml_content)
        schema_dict = self._yaml_to_schema_dict(yaml_data)
        schema_dict["created_by"] = created_by
        schema_dict["yaml_content"] = yaml_content

        return self.create_schema(ExtractionSchemaCreate(**schema_dict))

    def get_schema(self, schema_id: int) -> Optional[ExtractionSchemaResponse]:
        """Get a schema by ID."""
        if schema_id in self._schema_cache:
            return self._schema_cache[schema_id]

        db = next(get_sync_db())
        try:
            schema = db.query(ExtractionSchemaModel).filter(ExtractionSchemaModel.id == schema_id).first()
            if schema:
                schema_resp = ExtractionSchemaResponse.model_validate(schema)
                self._schema_cache[schema_id] = schema_resp
                return schema_resp
            return None
        finally:
            db.close()

    def get_schema_by_name(self, schema_name: str) -> Optional[ExtractionSchemaResponse]:
        """Get a schema by name."""
        for cached_schema in self._schema_cache.values():
            if cached_schema.schema_name == schema_name:
                return cached_schema

        db = next(get_sync_db())
        try:
            schema = (
                db.query(ExtractionSchemaModel)
                .filter(ExtractionSchemaModel.schema_name == schema_name)
                .filter(ExtractionSchemaModel.is_active == True)
                .first()
            )
            if schema:
                schema_resp = ExtractionSchemaResponse.model_validate(schema)
                self._schema_cache[schema.id] = schema_resp
                return schema_resp
            return None
        finally:
            db.close()

    def get_default_schema(self) -> Optional[ExtractionSchemaResponse]:
        """Get the default schema."""
        if self._default_schema:
            return self._default_schema

        db = next(get_sync_db())
        try:
            schema = (
                db.query(ExtractionSchemaModel)
                .filter(ExtractionSchemaModel.is_default == True)
                .filter(ExtractionSchemaModel.is_active == True)
                .first()
            )
            if schema:
                schema_resp = ExtractionSchemaResponse.model_validate(schema)
                self._default_schema = schema_resp
                return schema_resp
            return None
        finally:
            db.close()

    def list_schemas(
        self,
        business_line: Optional[str] = None,
        document_type: Optional[str] = None,
        is_active: Optional[bool] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> List[ExtractionSchemaResponse]:
        """List schemas with optional filters."""
        db = next(get_sync_db())
        try:
            query = db.query(ExtractionSchemaModel)

            if business_line:
                query = query.filter(ExtractionSchemaModel.business_line == business_line)
            if document_type:
                query = query.filter(
                    _json_array_contains(ExtractionSchemaModel.document_types, document_type)
                )
            if is_active is not None:
                query = query.filter(ExtractionSchemaModel.is_active == is_active)

            schemas = query.order_by(desc(ExtractionSchemaModel.created_at)).offset(skip).limit(limit).all()
            return [ExtractionSchemaResponse.model_validate(s) for s in schemas]
        finally:
            db.close()

    def create_schema(self, schema_in: ExtractionSchemaCreate) -> ExtractionSchemaResponse:
        """Create a new extraction schema."""
        db = next(get_sync_db())
        try:
            if schema_in.is_default:
                db.query(ExtractionSchemaModel).update({ExtractionSchemaModel.is_default: False})
                self._default_schema = None

            fields_data = [f.model_dump() for f in schema_in.fields]

            schema = ExtractionSchemaModel(
                schema_name=schema_in.schema_name,
                schema_version=schema_in.schema_version,
                description=schema_in.description,
                business_line=schema_in.business_line,
                document_types=schema_in.document_types,
                fields=fields_data,
                is_active=schema_in.is_active,
                is_default=schema_in.is_default,
                created_by=schema_in.created_by,
                yaml_source_path=schema_in.yaml_source_path,
                yaml_content=schema_in.yaml_content,
            )

            db.add(schema)
            db.commit()
            db.refresh(schema)

            schema_resp = ExtractionSchemaResponse.model_validate(schema)
            self._schema_cache[schema.id] = schema_resp

            if schema.is_default:
                self._default_schema = schema_resp

            return schema_resp
        finally:
            db.close()

    def update_schema(
        self, schema_id: int, schema_in: ExtractionSchemaUpdate
    ) -> Optional[ExtractionSchemaResponse]:
        """Update an existing schema."""
        db = next(get_sync_db())
        try:
            schema = db.query(ExtractionSchemaModel).filter(ExtractionSchemaModel.id == schema_id).first()
            if not schema:
                return None

            if schema_in.is_default:
                db.query(ExtractionSchemaModel).update({ExtractionSchemaModel.is_default: False})
                self._default_schema = None

            update_data = schema_in.model_dump(exclude_unset=True)
            if "fields" in update_data and update_data["fields"] is not None:
                if update_data["fields"] and isinstance(update_data["fields"][0], FieldSchema):
                    update_data["fields"] = [f.model_dump() for f in update_data["fields"]]

            for key, value in update_data.items():
                if value is not None and hasattr(schema, key):
                    setattr(schema, key, value)

            db.commit()
            db.refresh(schema)

            schema_resp = ExtractionSchemaResponse.model_validate(schema)
            self._schema_cache[schema_id] = schema_resp

            if schema.is_default:
                self._default_schema = schema_resp

            return schema_resp
        finally:
            db.close()

    def delete_schema(self, schema_id: int) -> bool:
        """Delete a schema."""
        db = next(get_sync_db())
        try:
            schema = db.query(ExtractionSchemaModel).filter(ExtractionSchemaModel.id == schema_id).first()
            if not schema:
                return False

            if schema.is_default:
                logger.warning("Cannot delete default schema")
                return False

            db.delete(schema)
            db.commit()

            if schema_id in self._schema_cache:
                del self._schema_cache[schema_id]

            return True
        finally:
            db.close()

    def set_default_schema(self, schema_id: int) -> Optional[ExtractionSchemaResponse]:
        """Set a schema as the default schema."""
        db = next(get_sync_db())
        try:
            db.query(ExtractionSchemaModel).update({ExtractionSchemaModel.is_default: False})

            schema = db.query(ExtractionSchemaModel).filter(ExtractionSchemaModel.id == schema_id).first()
            if not schema:
                return None

            schema.is_default = True
            db.commit()
            db.refresh(schema)

            schema_resp = ExtractionSchemaResponse.model_validate(schema)
            self._default_schema = schema_resp
            self._schema_cache[schema_id] = schema_resp

            return schema_resp
        finally:
            db.close()

    def get_schema_with_stats(
        self, schema_id: int, start_date=None, end_date=None
    ) -> Optional[ExtractionSchemaWithStats]:
        """Get schema with usage statistics."""
        schema = self.get_schema(schema_id)
        if not schema:
            return None

        db = next(get_sync_db())
        try:
            query = db.query(
                func.count(ExtractionResult.id).label("count"),
                func.avg(ExtractionResult.overall_confidence).label("avg_confidence"),
                func.max(ExtractionResult.created_at).label("last_used"),
            ).filter(ExtractionResult.schema_name == schema.schema_name)

            if start_date:
                query = query.filter(ExtractionResult.created_at >= start_date)
            if end_date:
                query = query.filter(ExtractionResult.created_at <= end_date)

            result = query.first()

            return ExtractionSchemaWithStats(
                **schema.model_dump(),
                usage_count=result.count or 0,
                average_confidence=result.avg_confidence,
                last_used_at=result.last_used,
            )
        finally:
            db.close()

    def export_schema_to_yaml(self, schema_id: int) -> Optional[str]:
        """Export a schema to YAML format."""
        schema = self.get_schema(schema_id)
        if not schema:
            return None

        yaml_dict = {
            "schema_name": schema.schema_name,
            "schema_version": schema.schema_version,
            "description": schema.description,
            "document_types": schema.document_types,
            "business_line": schema.business_line,
            "created_by": schema.created_by,
            "created_at": schema.created_at.isoformat(),
            "is_active": schema.is_active,
            "is_default": schema.is_default,
            "fields": [],
        }

        for field in schema.fields:
            if isinstance(field, FieldSchema):
                field_dict = {
                    "field_name": field.field_name,
                    "field_type": field.field_type,
                    "description": field.description,
                    "required": field.required or False,
                }
                if field.validation_rules:
                    field_dict["validation_rules"] = field.validation_rules
                if field.examples:
                    field_dict["examples"] = field.examples
            else:
                field_dict = {
                    "field_name": field.get("field_name"),
                    "field_type": field.get("field_type"),
                    "description": field.get("description"),
                    "required": field.get("required", False),
                }
                if field.get("validation_rules"):
                    field_dict["validation_rules"] = field.get("validation_rules")
                if field.get("examples"):
                    field_dict["examples"] = field.get("examples")
            yaml_dict["fields"].append(field_dict)

        return yaml.dump(yaml_dict, allow_unicode=True, sort_keys=False)

    def get_schema_for_document(
        self,
        business_line: Optional[str] = None,
        document_type: Optional[str] = None,
    ) -> Optional[ExtractionSchemaResponse]:
        """Get the appropriate schema for a document based on business line and document type."""
        if business_line:
            db = next(get_sync_db())
            try:
                query = (
                    db.query(ExtractionSchemaModel)
                    .filter(ExtractionSchemaModel.business_line == business_line)
                    .filter(ExtractionSchemaModel.is_active == True)
                )

                if document_type:
                    query = query.filter(
                        _json_array_contains(ExtractionSchemaModel.document_types, document_type)
                    )

                schema = query.first()
                if schema:
                    return ExtractionSchemaResponse.model_validate(schema)
            finally:
                db.close()

        return self.get_default_schema()

    def clear_cache(self):
        """Clear the schema cache."""
        self._schema_cache.clear()
        self._default_schema = None
