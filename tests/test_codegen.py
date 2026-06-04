import json
import os
import tempfile
import time
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest
from click.testing import CliRunner

from devkit.cli import cli


class TestCodegenJsonTypes:
    def test_generate_typescript_from_json(self, runner, tmp_path):
        input_json = '{"name": "Alice", "age": 30, "email": "test@example.com"}'
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", input_json, "-l", "typescript"])
        assert result.exit_code == 0
        assert "interface Root" in result.output
        assert "name: string" in result.output
        assert "age: number" in result.output
        assert "email: string" in result.output

    def test_generate_python_dataclass_from_json(self, runner, tmp_path):
        input_json = '{"name": "Alice", "age": 30}'
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", input_json, "-l", "python"])
        assert result.exit_code == 0
        assert "@dataclass" in result.output
        assert "class Root" in result.output
        assert "name: str" in result.output
        assert "age: int" in result.output

    def test_generate_go_struct_from_json(self, runner, tmp_path):
        input_json = '{"user_name": "Alice", "user_age": 30}'
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", input_json, "-l", "go"])
        assert result.exit_code == 0
        assert "type Root struct" in result.output
        assert "UserName" in result.output
        assert "json:\"user_name\"" in result.output
        assert "UserAge" in result.output

    def test_generate_java_pojo_from_json(self, runner, tmp_path):
        input_json = '{"name": "Alice"}'
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", input_json, "-l", "java"])
        assert result.exit_code == 0
        assert "public class Root" in result.output
        assert "private String name" in result.output
        assert "getName()" in result.output
        assert "setName(" in result.output

    def test_nested_object_generates_separate_type(self, runner):
        input_json = '{"user": {"name": "Alice", "address": {"city": "Beijing"}}}'
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", input_json, "-l", "typescript"])
        assert result.exit_code == 0
        assert "interface RootUser" in result.output
        assert "interface RootUserAddress" in result.output
        assert "address: RootUserAddress" in result.output

    def test_array_of_objects_generates_type(self, runner):
        input_json = '{"users": [{"name": "Alice"}, {"name": "Bob"}]}'
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", input_json, "-l", "typescript"])
        assert result.exit_code == 0
        assert "interface RootUsersItem" in result.output
        assert "users: RootUsersItem[]" in result.output

    def test_null_field_optional(self, runner):
        input_json = '{"name": null, "age": 30}'
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", input_json, "-l", "typescript"])
        assert result.exit_code == 0
        assert "name?: any" in result.output or "name?: unknown" in result.output

    def test_json_file_input(self, runner, tmp_path):
        f = tmp_path / "input.json"
        f.write_text('{"name": "Alice"}')
        result = runner.invoke(cli, ["codegen", "json", "types", str(f), "-l", "python"])
        assert result.exit_code == 0
        assert "class Root" in result.output

    def test_invalid_json(self, runner):
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", "{invalid json", "-l", "python"])
        assert result.exit_code == 0
        assert "Invalid JSON" in result.output

    def test_no_input(self, runner):
        result = runner.invoke(cli, ["codegen", "json", "types", "-l", "python"])
        assert result.exit_code == 0
        assert "No input provided" in result.output

    def test_output_to_file(self, runner, tmp_path):
        output_file = tmp_path / "output"
        input_json = '{"name": "Alice"}'
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", input_json, "-l", "typescript", "-o", str(output_file)])
        assert result.exit_code == 0
        assert (output_file.with_suffix('.ts')).exists()

    def test_custom_root_name(self, runner):
        input_json = '{"name": "Alice"}'
        result = runner.invoke(cli, ["codegen", "json", "types", "-c", input_json, "-l", "python", "--root-name", "User"])
        assert result.exit_code == 0
        assert "class User" in result.output


class TestCodegenSqlOrm:
    def test_generate_sqlalchemy_from_sql(self, runner):
        sql = """
        CREATE TABLE users (
            id INT PRIMARY KEY AUTO_INCREMENT,
            name VARCHAR(255) NOT NULL,
            email VARCHAR(255),
            age INT,
            created_at DATETIME
        );
        """
        result = runner.invoke(cli, ["codegen", "sql", "orm", "-c", sql, "-t", "sqlalchemy"])
        assert result.exit_code == 0
        assert "class Users(Base)" in result.output
        assert "__tablename__ = 'users'" in result.output
        assert "id = Column(Integer, primary_key=True" in result.output
        assert "name = Column(VARCHAR(255), nullable=False" in result.output
        assert "email = Column(VARCHAR(255)" in result.output

    def test_generate_prisma_from_sql(self, runner):
        sql = """
        CREATE TABLE users (
            id INT PRIMARY KEY AUTO_INCREMENT,
            name VARCHAR(255) NOT NULL
        );
        """
        result = runner.invoke(cli, ["codegen", "sql", "orm", "-c", sql, "-t", "prisma"])
        assert result.exit_code == 0
        assert "model Users" in result.output
        assert '@id' in result.output
        assert '@default(autoincrement())' in result.output
        assert '@@map("users")' in result.output

    def test_generate_gorm_from_sql(self, runner):
        sql = """
        CREATE TABLE users (
            id INT PRIMARY KEY AUTO_INCREMENT,
            user_name VARCHAR(255) NOT NULL
        );
        """
        result = runner.invoke(cli, ["codegen", "sql", "orm", "-c", sql, "-t", "gorm"])
        assert result.exit_code == 0
        assert "type Users struct" in result.output
        assert "UserName" in result.output
        assert 'gorm:"column:user_name' in result.output
        assert 'TableName() string' in result.output

    def test_no_sql_input(self, runner):
        result = runner.invoke(cli, ["codegen", "sql", "orm", "-t", "sqlalchemy"])
        assert result.exit_code == 0
        assert "No input provided" in result.output

    def test_multiple_tables(self, runner):
        sql = """
        CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255));
        CREATE TABLE posts (id INT PRIMARY KEY, title VARCHAR(255), user_id INT);
        """
        result = runner.invoke(cli, ["codegen", "sql", "orm", "-c", sql, "-t", "sqlalchemy"])
        assert result.exit_code == 0
        assert "class Users(Base)" in result.output
        assert "class Posts(Base)" in result.output


class TestCodegenOpenapiClient:
    def test_generate_typescript_client(self, runner):
        spec = """
        openapi: 3.0.0
        info:
          title: Test API
          version: 1.0.0
        paths:
          /users:
            get:
              operationId: getUsers
              responses:
                '200':
                  description: Success
            post:
              operationId: createUser
              requestBody:
                content:
                  application/json:
                    schema: {}
              responses:
                '201':
                  description: Created
          /users/{id}:
            get:
              operationId: getUserById
              parameters:
                - name: id
                  in: path
                  required: true
                  schema:
                    type: string
              responses:
                '200':
                  description: Success
        """
        result = runner.invoke(cli, ["codegen", "openapi", "client", "-c", spec, "-l", "typescript"])
        assert result.exit_code == 0
        assert "export class" in result.output
        assert "async getUsers(" in result.output
        assert "async createUser(" in result.output
        assert "async getUserById(" in result.output
        assert 'method: \'GET\'' in result.output
        assert 'method: \'POST\'' in result.output
        assert "${this.baseUrl}/users" in result.output
        assert "${this.baseUrl}/users/${id}" in result.output

    def test_generate_python_client(self, runner):
        spec = """
        openapi: 3.0.0
        info:
          title: Test API
          version: 1.0.0
        paths:
          /users:
            get:
              operationId: get_users
              responses:
                '200':
                  description: Success
        """
        result = runner.invoke(cli, ["codegen", "openapi", "client", "-c", spec, "-l", "python"])
        assert result.exit_code == 0
        assert "class" in result.output
        assert "def get_users(" in result.output
        assert "requests.get" in result.output

    def test_custom_class_name(self, runner):
        spec = """
        openapi: 3.0.0
        info:
          title: Test
          version: 1.0.0
        paths: {}
        """
        result = runner.invoke(cli, ["codegen", "openapi", "client", "-c", spec, "-l", "typescript", "--class-name", "MyApiClient"])
        assert result.exit_code == 0
        assert "export class MyApiClient" in result.output

    def test_invalid_yaml(self, runner):
        result = runner.invoke(cli, ["codegen", "openapi", "client", "-c", "invalid yaml: [", "-l", "python"])
        assert result.exit_code == 0
        assert "Invalid YAML" in result.output
