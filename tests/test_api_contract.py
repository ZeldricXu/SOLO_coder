import pytest
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.api_contract import get_contract_testing_service, SchemaValidator, MockGenerator


def test_schema_validation():
    service = get_contract_testing_service()
    schema = {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "age": {"type": "integer"},
        },
        "required": ["name"],
    }
    data = {"name": "test", "age": 25}
    result = service.validate(data, schema)
    assert result.valid is True


def test_schema_validation_missing_required():
    service = get_contract_testing_service()
    schema = {
        "type": "object",
        "properties": {"name": {"type": "string"}},
        "required": ["name"],
    }
    data = {}
    result = service.validate(data, schema)
    assert result.valid is False
    assert len(result.issues) > 0


def test_schema_validation_type_mismatch():
    service = get_contract_testing_service()
    schema = {"type": "object", "properties": {"age": {"type": "integer"}}}
    data = {"age": "twenty"}
    result = service.validate(data, schema)
    assert result.valid is False


def test_mock_generator():
    service = get_contract_testing_service()
    schema = {
        "type": "object",
        "properties": {
            "id": {"type": "integer"},
            "name": {"type": "string"},
            "active": {"type": "boolean"},
        },
    }
    mock = service.generate_mock(schema)
    assert isinstance(mock, dict)
    assert "id" in mock
    assert "name" in mock
    assert "active" in mock


def test_openapi_mock_server():
    service = get_contract_testing_service()
    spec = {
        "paths": {
            "/users": {
                "get": {
                    "responses": {
                        "200": {
                            "content": {
                                "application/json": {
                                    "schema": {
                                        "type": "object",
                                        "properties": {"id": {"type": "integer"}, "name": {"type": "string"}},
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    service.register_schema("test_api", "openapi", spec)
    response = service.mock("test_api", "GET", "/users")
    assert response["status"] == 200
    assert "body" in response
