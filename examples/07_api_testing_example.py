"""
示例7: API契约测试模块
"""

import asyncio
import sys
import os
import json

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from src.modules.api_testing import ApiContractTester


def main():
    print("=== API契约测试示例 ===\n")

    tester = ApiContractTester()

    openapi_schema = {
        "openapi": "3.0.0",
        "info": {
            "title": "User API",
            "version": "1.0.0",
            "description": "用户管理API"
        },
        "paths": {
            "/users": {
                "get": {
                    "summary": "获取用户列表",
                    "parameters": [
                        {
                            "name": "page",
                            "in": "query",
                            "required": True,
                            "schema": {"type": "integer"}
                        }
                    ],
                    "responses": {
                        "200": {
                            "description": "成功",
                            "content": {
                                "application/json": {
                                    "schema": {
                                        "type": "object",
                                        "properties": {
                                            "data": {
                                                "type": "array",
                                                "items": {
                                                    "type": "object",
                                                    "properties": {
                                                        "id": {"type": "integer", "example": 1},
                                                        "name": {"type": "string", "example": "Alice"},
                                                        "email": {"type": "string", "example": "alice@example.com"}
                                                    }
                                                }
                                            },
                                            "total": {"type": "integer", "example": 100}
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                "post": {
                    "summary": "创建用户",
                    "requestBody": {
                        "required": True,
                        "content": {
                            "application/json": {
                                "schema": {
                                    "type": "object",
                                    "properties": {
                                        "name": {"type": "string"},
                                        "email": {"type": "string"}
                                    },
                                    "required": ["name", "email"]
                                }
                            }
                        }
                    },
                    "responses": {
                        "201": {
                            "description": "创建成功",
                            "content": {
                                "application/json": {
                                    "schema": {
                                        "type": "object",
                                        "properties": {
                                            "id": {"type": "integer"},
                                            "name": {"type": "string"},
                                            "email": {"type": "string"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            "/users/{id}": {
                "get": {
                    "summary": "获取用户详情",
                    "parameters": [
                        {
                            "name": "id",
                            "in": "path",
                            "required": True,
                            "schema": {"type": "integer"}
                        }
                    ],
                    "responses": {
                        "200": {
                            "description": "成功",
                            "content": {
                                "application/json": {
                                    "schema": {
                                        "type": "object",
                                        "properties": {
                                            "id": {"type": "integer", "example": 1},
                                            "name": {"type": "string", "example": "Alice"},
                                            "email": {"type": "string", "example": "alice@example.com"}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    print("1. 注册 OpenAPI Schema:")
    result = tester.register_schema("user-api", openapi_schema, "openapi")
    print(f"   校验通过: {result.valid}")
    if not result.valid:
        for err in result.errors:
            print(f"   错误: {err.path} - {err.message}")

    print("\n2. 校验请求:")
    req_result = tester.validate_request(
        "user-api",
        "/users",
        "GET",
        {"query": {"page": "1"}}
    )
    print(f"   GET /users?page=1: {'通过' if req_result.valid else '失败'}")

    req_result2 = tester.validate_request(
        "user-api",
        "/users",
        "GET",
        {"query": {}}
    )
    print(f"   GET /users (缺少page参数): {'通过' if req_result2.valid else '失败'}")
    for err in req_result2.errors:
        print(f"     - {err.message}")

    print("\n3. Mock Server 响应:")
    mock_server = tester.get_mock_server("user-api")
    if mock_server:
        endpoints = mock_server._endpoints
        print(f"   生成的 Mock 端点: {len(endpoints)} 个")
        for (method, path) in endpoints:
            print(f"   - {method} {path}")

    print("\n4. 发送 Mock 请求:")
    status, body, headers = tester.mock_request("user-api", "GET", "/users")
    print(f"   GET /users -> {status}")
    print(f"   响应体: {json.dumps(body, indent=2, ensure_ascii=False)}")

    status, body, headers = tester.mock_request("user-api", "GET", "/users/123")
    print(f"\n   GET /users/123 -> {status}")
    print(f"   响应体: {json.dumps(body, indent=2, ensure_ascii=False)}")

    print("\n5. GraphQL Schema 测试:")
    graphql_schema = {
        "schema": '''
        type Query {
            getUser(id: ID!): User
            listUsers(limit: Int = 10): [User!]!
        }

        type User {
            id: ID!
            name: String!
            email: String!
            age: Int
        }
        '''
    }

    result = tester.register_schema("user-graphql", graphql_schema, "graphql")
    print(f"   GraphQL Schema 校验通过: {result.valid}")

    status, body, headers = tester.mock_request("user-graphql", "POST", "/graphql")
    print(f"   POST /graphql -> {status}")
    print(f"   响应体: {json.dumps(body, indent=2, ensure_ascii=False)}")


if __name__ == "__main__":
    main()
