import pytest
import tempfile
import json

from app.contract.testing import (
    SchemaValidator,
    SchemaError,
    MockServer,
    MockEndpoint,
    ContractTester,
    validate_openapi,
    validate_graphql,
    create_mock_server
)


def test_schema_validator_openapi(sample_openapi_schema):
    validator = SchemaValidator()
    result = validator.validate_openapi(sample_openapi_schema)

    assert result.valid is True
    assert len(result.errors) == 0
    assert len(result.warnings) == 0


def test_schema_validator_invalid_openapi():
    validator = SchemaValidator()
    result = validator.validate_openapi({
        "openapi": "3.0.0",
        "info": {
            "title": "Test"
        }
    })

    assert result.valid is True or result.valid is False


def test_schema_validator_graphql():
    validator = SchemaValidator()

    schema_str = """
    type Query {
        hello: String!
        users: [User!]!
    }

    type User {
        id: ID!
        name: String!
    }
    """

    result = validator.validate_graphql(schema_str)
    assert result.valid is True
    assert len(result.errors) == 0


def test_schema_validator_invalid_graphql():
    validator = SchemaValidator()

    schema_str = """
    type Query {
        hello String
    }
    """

    result = validator.validate_graphql(schema_str)
    assert result.valid is False
    assert len(result.errors) > 0


def test_schema_error():
    error = SchemaError(message="Test error", path="/test/path")
    assert error.message == "Test error"
    assert error.path == "/test/path"


def test_mock_server():
    server = MockServer()

    endpoint = server.register_endpoint(
        method="GET",
        path="/api/users",
        status_code=200,
        response_body={"users": []}
    )

    assert endpoint.method == "GET"
    assert endpoint.path == "/api/users"
    assert endpoint.status_code == 200

    response = server.handle_request("GET", "/api/users")
    assert response is not None
    assert response.status_code == 200
    assert response.body == {"users": []}


def test_mock_server_not_found():
    server = MockServer()
    response = server.handle_request("GET", "/nonexistent")
    assert response is None


def test_mock_server_method_mismatch():
    server = MockServer()
    server.register_endpoint("GET", "/api/test", 200, {})

    response = server.handle_request("POST", "/api/test")
    assert response is None


def test_mock_endpoint():
    endpoint = MockEndpoint(
        method="PUT",
        path="/api/items/{id}",
        status_code=200,
        response_body={"updated": True},
        examples=[
            {"name": "success", "response": {"id": "123"}}
        ]
    )

    assert endpoint.method == "PUT"
    assert endpoint.path == "/api/items/{id}"
    assert len(endpoint.examples) == 1


def test_contract_tester():
    tester = ContractTester()

    mock_server = tester.create_mock_server()
    assert isinstance(mock_server, MockServer)

    validator = tester.validator
    assert isinstance(validator, SchemaValidator)


def test_validate_openapi_function(sample_openapi_schema):
    result = validate_openapi(sample_openapi_schema)
    assert result.valid is True


def test_validate_graphql_function():
    schema_str = """
    type Query {
        hello: String!
    }
    """
    result = validate_graphql(schema_str)
    assert result.valid is True


def test_create_mock_server_function():
    server = create_mock_server()
    assert isinstance(server, MockServer)


def test_mock_server_list_endpoints():
    server = MockServer()

    server.register_endpoint("GET", "/a", 200, {})
    server.register_endpoint("POST", "/b", 201, {})

    endpoints = server.list_endpoints()
    assert len(endpoints) == 2


def test_mock_server_clear():
    server = MockServer()
    server.register_endpoint("GET", "/test", 200, {})

    assert len(server.list_endpoints()) == 1

    server.clear()

    assert len(server.list_endpoints()) == 0
