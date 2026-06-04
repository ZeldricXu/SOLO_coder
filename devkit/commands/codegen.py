import json as json_module
import re
import sys
from pathlib import Path

import click
import yaml

try:
    from jinja2 import Environment, DictLoader, select_autoescape
    JINJA2_AVAILABLE = True
except ImportError:
    JINJA2_AVAILABLE = False

from ..core import Color, cprint


TEMPLATES = {
    'typescript_interface': '''{% for type in types %}
interface {{ type.name }} {
{% for field in type.fields %}
  {{ field.name }}{% if not field.required %}?{% endif %}: {{ field.type }};
{% endfor %}
}
{% endfor %}''',

    'python_dataclass': '''from dataclasses import dataclass
from typing import Optional, List, Dict, Any

{% for type in types %}
@dataclass
class {{ type.name }}:
{% for field in type.fields %}
    {{ field.name }}: {% if not field.required %}Optional[{% endif %}{{ field.type }}{% if not field.required %}]{% endif %}
{% endfor %}
{% endfor %}''',

    'go_struct': '''package main

{% for type in types %}
type {{ type.name }} struct {
{% for field in type.fields %}
    {{ field.go_name }} {{ field.type }} `json:"{{ field.name }}{% if not field.required %},omitempty{% endif %}"`
{% endfor %}
}
{% endfor %}''',

    'java_pojo': '''{% for type in types %}
public class {{ type.name }} {
{% for field in type.fields %}
    private {{ field.type }} {{ field.name }};

    public {{ field.type }} get{{ field.name[0]|upper }}{{ field.name[1:] }}() {
        return {{ field.name }};
    }

    public void set{{ field.name[0]|upper }}{{ field.name[1:] }}({{ field.type }} {{ field.name }}) {
        this.{{ field.name }} = {{ field.name }};
    }

{% endfor %}
}
{% endfor %}''',

    'sqlalchemy_model': '''from sqlalchemy import Column, Integer, String, Float, Boolean, DateTime, Text, ForeignKey
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import relationship

Base = declarative_base()

{% for table in tables %}
class {{ table.class_name }}(Base):
    __tablename__ = '{{ table.name }}'
{% for column in table.columns %}
    {{ column.name }} = Column({{ column.type }}{% if column.primary_key %}, primary_key=True{% endif %}{% if column.nullable == False %}, nullable=False{% endif %}{% if column.foreign_key %}, ForeignKey('{{ column.foreign_key }}'){% endif %})
{% endfor %}
{% for rel in table.relationships %}
    {{ rel.name }} = relationship('{{ rel.target }}', backref='{{ rel.backref }}')
{% endfor %}

{% endfor %}''',

    'prisma_schema': '''{% for table in tables %}
model {{ table.class_name }} {
{% for column in table.columns %}
  {{ column.name }} {{ column.prisma_type }}{% if column.primary_key %} @id{% endif %}{% if column.auto_increment %} @default(autoincrement()){% endif %}{% if column.nullable == False %}?{% endif %}{% if column.unique %} @unique{% endif %}
{% endfor %}
  @@map("{{ table.name }}")
}
{% endfor %}''',

    'gorm_struct': '''package models

import "time"

{% for table in tables %}
type {{ table.class_name }} struct {
{% for column in table.columns %}
    {{ column.go_name }} {{ column.go_type }} `gorm:"column:{{ column.name }}{% if column.primary_key %};primaryKey{% endif %}{% if column.auto_increment %};autoIncrement{% endif %}{% if column.nullable == False %};not null{% endif %}{% if column.unique %};unique{% endif %}" json:"{{ column.name }}{% if not column.nullable %},omitempty{% endif %}"`
{% endfor %}
}

func ({{ table.class_name }}) TableName() string {
    return "{{ table.name }}"
}

{% endfor %}''',

    'api_client_typescript': '''export class {{ class_name }} {
    private baseUrl: string;
    private headers: Record<string, string>;

    constructor(baseUrl: string, headers: Record<string, string> = {}) {
        this.baseUrl = baseUrl;
        this.headers = headers;
    }

{% for endpoint in endpoints %}
    async {{ endpoint.operation_id }}({% for param in endpoint.params %}{{ param.name }}: {{ param.type }}{% if not loop.last %}, {% endif %}{% endfor %}): Promise<{{ endpoint.response_type }}> {
        const url = `${this.baseUrl}{{ endpoint.path_template }}`;
        const response = await fetch(url, {
            method: '{{ endpoint.method }}',
            headers: {
                ...this.headers,
                'Content-Type': 'application/json',
            },
{% if endpoint.method in ['POST', 'PUT', 'PATCH'] %}
            body: JSON.stringify({{ endpoint.body_param }}),
{% endif %}
        });
        return response.json();
    }

{% endfor %}
}''',

    'api_client_python': '''import requests
from typing import Optional, Dict, Any, List


class {{ class_name }}:
    def __init__(self, base_url: str, headers: Optional[Dict[str, str]] = None):
        self.base_url = base_url
        self.headers = headers or {}

{% for endpoint in endpoints %}
    def {{ endpoint.operation_id }}({% for param in endpoint.params %}{{ param.name }}: {{ param.type }}{% if not loop.last %}, {% endif %}{% endfor %}) -> {{ endpoint.response_type }}:
        url = f"{self.base_url}{{ endpoint.path }}"
        response = requests.{{ endpoint.method.lower() }}(
            url,
            headers={**self.headers, 'Content-Type': 'application/json'},
{% if endpoint.method in ['POST', 'PUT', 'PATCH'] %}
            json={{ endpoint.body_param }},
{% endif %}
        )
        response.raise_for_status()
        return response.json()

{% endfor %}''',
}


def _jinja_env():
    return Environment(
        loader=DictLoader(TEMPLATES),
        autoescape=select_autoescape(disabled_extensions=('j2', 'jinja2')),
        trim_blocks=True,
        lstrip_blocks=True,
    )


def json_to_type_descriptor(data, root_name='Root'):
    types = []
    
    def get_type_name(value, parent_name, field_name):
        if isinstance(value, bool):
            return 'boolean', 'bool', 'boolean', 'Boolean'
        elif isinstance(value, int):
            return 'number', 'int', 'int', 'Integer'
        elif isinstance(value, float):
            return 'number', 'float', 'float64', 'Double'
        elif isinstance(value, str):
            return 'string', 'str', 'string', 'String'
        elif value is None:
            return 'any', 'Any', 'interface{}', 'Object'
        elif isinstance(value, list):
            if value and isinstance(value[0], dict):
                nested_name = f'{parent_name}{field_name[0].upper()}{field_name[1:]}Item'
                nested_type = process_object(value[0], nested_name)
                types.append(nested_type)
                return f'{nested_name}[]', f'List[{nested_name}]', f'[]{nested_name}', f'List<{nested_name}>'
            elif value:
                item_types = get_type_name(value[0], parent_name, field_name)
                return f'{item_types[0]}[]', f'List[{item_types[1]}]', f'[]{item_types[2]}', f'List<{item_types[3]}>'
            else:
                return 'any[]', 'List[Any]', '[]interface{}', 'List<Object>'
        elif isinstance(value, dict):
            nested_name = f'{parent_name}{field_name[0].upper()}{field_name[1:]}'
            nested_type = process_object(value, nested_name)
            types.append(nested_type)
            return nested_name, nested_name, nested_name, nested_name
        return 'any', 'Any', 'interface{}', 'Object'
    
    def process_object(obj, type_name):
        fields = []
        for key, value in obj.items():
            ts_type, py_type, go_type, java_type = get_type_name(value, type_name, key)
            fields.append({
                'name': key,
                'go_name': ''.join(part.capitalize() for part in key.split('_')),
                'type': {
                    'typescript': ts_type,
                    'python': py_type,
                    'go': go_type,
                    'java': java_type,
                },
                'required': value is not None,
            })
        return {'name': type_name, 'fields': fields}
    
    if isinstance(data, dict):
        root_type = process_object(data, root_name)
        types.insert(0, root_type)
    elif isinstance(data, list) and data and isinstance(data[0], dict):
        root_type = process_object(data[0], root_name)
        types.insert(0, root_type)
    
    return types


def extract_primitive_type(schema_type):
    type_mapping = {
        'string': ('string', 'str', 'string', 'String'),
        'integer': ('number', 'int', 'int', 'Integer'),
        'number': ('number', 'float', 'float64', 'Double'),
        'boolean': ('boolean', 'bool', 'bool', 'Boolean'),
        'array': ('any[]', 'List[Any]', '[]interface{}', 'List<Object>'),
        'object': ('any', 'Any', 'interface{}', 'Object'),
    }
    return type_mapping.get(schema_type, ('any', 'Any', 'interface{}', 'Object'))


def jsonschema_to_type_descriptor(schema, root_name='Root'):
    types = []
    
    def get_type_name(subschema, parent_name, field_name):
        if not isinstance(subschema, dict):
            return 'any', 'Any', 'interface{}', 'Object'
        
        if '$ref' in subschema:
            ref = subschema['$ref'].split('/')[-1]
            return ref, ref, ref, ref
        
        schema_type = subschema.get('type', 'object')
        
        if schema_type == 'array' and 'items' in subschema:
            item_schema = subschema['items']
            item_name = get_type_name(item_schema, parent_name, field_name)
            return (
                f'{item_name[0]}[]', 
                f'List[{item_name[1]}]', 
                f'[]{item_name[2]}', 
                f'List<{item_name[3]}>'
            )
        
        if schema_type == 'object':
            nested_name = f'{parent_name}{field_name[0].upper()}{field_name[1:]}'
            nested_type = process_object(subschema, nested_name)
            if nested_type['fields']:
                types.append(nested_type)
            return nested_name, nested_name, nested_name, nested_name
        
        return extract_primitive_type(schema_type)
    
    def process_object(subschema, type_name):
        fields = []
        required = subschema.get('required', [])
        properties = subschema.get('properties', {})
        
        for key, prop_schema in properties.items():
            ts_type, py_type, go_type, java_type = get_type_name(prop_schema, type_name, key)
            fields.append({
                'name': key,
                'go_name': ''.join(part.capitalize() for part in key.split('_')),
                'type': {
                    'typescript': ts_type,
                    'python': py_type,
                    'go': go_type,
                    'java': java_type,
                },
                'required': key in required,
            })
        
        return {'name': type_name, 'fields': fields}
    
    if 'definitions' in schema:
        for name, def_schema in schema['definitions'].items():
            types.append(process_object(def_schema, name))
    
    if 'properties' in schema:
        root_type = process_object(schema, root_name)
        types.insert(0, root_type)
    
    return types


def parse_sql_create_table(sql):
    tables = []
    
    table_pattern = re.compile(r'CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?(\w+)`?\s*\(', re.IGNORECASE)
    
    type_mapping = {
        'int': ('Integer', 'Int', 'int64'),
        'bigint': ('BigInteger', 'BigInt', 'int64'),
        'smallint': ('SmallInteger', 'Int', 'int32'),
        'tinyint': ('SmallInteger', 'Int', 'int8'),
        'varchar': ('String', 'String', 'string'),
        'char': ('String', 'String', 'string'),
        'text': ('Text', 'String', 'string'),
        'datetime': ('DateTime', 'DateTime', 'time.Time'),
        'date': ('Date', 'DateTime', 'time.Time'),
        'timestamp': ('DateTime', 'DateTime', 'time.Time'),
        'float': ('Float', 'Float', 'float64'),
        'double': ('Float', 'Float', 'float64'),
        'decimal': ('Numeric', 'Decimal', 'float64'),
        'boolean': ('Boolean', 'Boolean', 'bool'),
        'bool': ('Boolean', 'Boolean', 'bool'),
        'json': ('JSON', 'Json', 'string'),
        'blob': ('LargeBinary', 'Bytes', '[]byte'),
    }
    
    pos = 0
    while True:
        table_match = table_pattern.search(sql, pos)
        if not table_match:
            break
        
        table_name = table_match.group(1)
        start_pos = table_match.end()
        
        depth = 1
        end_pos = start_pos
        while end_pos < len(sql) and depth > 0:
            if sql[end_pos] == '(':
                depth += 1
            elif sql[end_pos] == ')':
                depth -= 1
            end_pos += 1
        
        columns_sql = sql[start_pos:end_pos - 1]
        columns_sql = re.sub(r'\s+', ' ', columns_sql.strip())
        
        class_name = ''.join(part.capitalize() for part in table_name.split('_'))
        
        columns = []
        relationships = []
        
        column_pattern = re.compile(r'`?(\w+)`?\s+(\w+(?:\(\d+(?:,\d+)?\))?)\s*(.*?)(?:,|$)', re.IGNORECASE)
        
        for col_match in column_pattern.finditer(columns_sql):
            col_name = col_match.group(1)
            col_type_raw = col_match.group(2)
            constraints = col_match.group(3).lower()
            
            base_type = col_type_raw.split('(')[0].lower()
            sa_type, prisma_type, go_type = type_mapping.get(base_type, ('String', 'String', 'string'))
            
            if base_type in ['varchar', 'char']:
                sa_type = col_type_raw.upper()
            
            is_primary = 'primary key' in constraints
            is_auto = 'auto_increment' in constraints or 'identity' in constraints
            is_nullable = 'not null' not in constraints and not is_primary
            is_unique = 'unique' in constraints
            
            foreign_key = None
            fk_match = re.search(r'foreign key\s*\(`?\w+`?\)\s*references\s*`?(\w+)`?\s*\(`?(\w+)`?\)', constraints)
            if fk_match:
                foreign_table = fk_match.group(1)
                foreign_col = fk_match.group(2)
                foreign_key = f'{foreign_table}.{foreign_col}'
                
                rel_name = f'{foreign_table}_ref'
                target_class = ''.join(part.capitalize() for part in foreign_table.split('_'))
                relationships.append({
                    'name': rel_name,
                    'target': target_class,
                    'backref': table_name + '_list',
                })
            
            columns.append({
                'name': col_name,
                'go_name': ''.join(part.capitalize() for part in col_name.split('_')),
                'type': sa_type,
                'prisma_type': prisma_type,
                'go_type': go_type,
                'primary_key': is_primary,
                'auto_increment': is_auto,
                'nullable': is_nullable,
                'unique': is_unique,
                'foreign_key': foreign_key,
            })
        
        tables.append({
            'name': table_name,
            'class_name': class_name,
            'columns': columns,
            'relationships': relationships,
        })
        
        pos = end_pos
    
    return tables


def parse_openapi(openapi_spec):
    endpoints = []
    
    paths = openapi_spec.get('paths', {})
    
    for path, methods in paths.items():
        for method, spec in methods.items():
            if method not in ['get', 'post', 'put', 'delete', 'patch']:
                continue
            
            operation_id = spec.get('operationId', f'{method}_{path.replace("/", "_").replace("{", "").replace("}", "")}')
            operation_id = re.sub(r'[^a-zA-Z0-9_]', '_', operation_id)
            
            params = []
            path_params = spec.get('parameters', [])
            
            path_template = path
            for param in path_params:
                if param.get('in') == 'path':
                    pname = param['name']
                    path_template = path_template.replace(f'{{{pname}}}', f'${{{pname}}}')
                    params.append({'name': pname, 'type': 'string'})
            
            body_param = None
            if 'requestBody' in spec:
                body_param = 'requestBody'
                params.append({'name': 'requestBody', 'type': 'any'})
            
            endpoints.append({
                'method': method.upper(),
                'path': path,
                'path_template': path_template,
                'operation_id': operation_id,
                'summary': spec.get('summary', ''),
                'params': params,
                'body_param': body_param,
                'response_type': 'any',
            })
    
    return endpoints


def select_language_type(types, language):
    for t in types:
        for f in t['fields']:
            f['type'] = f['type'][language]
    return types


@click.group()
def codegen():
    """Code generation commands"""
    if not JINJA2_AVAILABLE:
        cprint('Warning: Jinja2 not installed. Install with: pip install Jinja2', Color.YELLOW)


@codegen.group(name='json')
def codegen_json():
    """Generate types from JSON or JSON Schema"""
    pass


@codegen_json.command('types')
@click.argument('input_file', type=click.Path(exists=True), required=False)
@click.option('--content', '-c', help='JSON content string')
@click.option('--language', '-l', default='typescript', 
              type=click.Choice(['typescript', 'python', 'go', 'java']),
              show_default=True, help='Target language')
@click.option('--root-name', default='Root', help='Root type name')
@click.option('--schema', is_flag=True, help='Input is JSON Schema')
@click.option('--output', '-o', type=click.Path(), help='Output file')
def json_types(input_file, content, language, root_name, schema, output):
    """Generate type definitions from JSON
    
    Examples:
      devkit codegen json types data.json -l typescript
      cat data.json | devkit codegen json types -l python
      devkit codegen json types --schema schema.json -l go
    """
    if not JINJA2_AVAILABLE:
        cprint('Jinja2 is required for code generation. Install: pip install Jinja2', Color.RED)
        return
    
    input_data = None
    if input_file:
        with open(input_file, 'r', encoding='utf-8') as f:
            input_data = f.read()
    elif content:
        input_data = content
    elif not sys.stdin.isatty():
        input_data = sys.stdin.read()
    
    if not input_data:
        cprint('No input provided. Use file, --content, or pipe.', Color.RED)
        return
    
    try:
        data = json_module.loads(input_data)
    except Exception as e:
        cprint(f'Invalid JSON: {e}', Color.RED)
        return
    
    if schema:
        types = jsonschema_to_type_descriptor(data, root_name)
    else:
        types = json_to_type_descriptor(data, root_name)
    
    types = select_language_type(types, language)
    
    template_map = {
        'typescript': 'typescript_interface',
        'python': 'python_dataclass',
        'go': 'go_struct',
        'java': 'java_pojo',
    }
    
    env = _jinja_env()
    template = env.get_template(template_map[language])
    output_content = template.render(types=types)
    
    if output:
        ext_map = {
            'typescript': '.ts',
            'python': '.py',
            'go': '.go',
            'java': '.java',
        }
        if not output.endswith(ext_map[language]):
            output = output + ext_map[language]
        with open(output, 'w', encoding='utf-8') as f:
            f.write(output_content)
        cprint(f'Generated {output}', Color.GREEN)
    else:
        click.echo(output_content)


@codegen.group(name='sql')
def codegen_sql():
    """Generate ORM models from SQL CREATE TABLE statements"""
    pass


@codegen_sql.command('orm')
@click.argument('input_file', type=click.Path(exists=True), required=False)
@click.option('--content', '-c', help='SQL content string')
@click.option('--target', '-t', default='sqlalchemy', 
              type=click.Choice(['sqlalchemy', 'prisma', 'gorm']),
              show_default=True, help='Target ORM/framework')
@click.option('--output', '-o', type=click.Path(), help='Output file')
def sql_orm(input_file, content, target, output):
    """Generate ORM models from SQL CREATE TABLE
    
    Examples:
      devkit codegen sql orm schema.sql -t sqlalchemy
      cat schema.sql | devkit codegen sql orm -t prisma
    """
    if not JINJA2_AVAILABLE:
        cprint('Jinja2 is required for code generation. Install: pip install Jinja2', Color.RED)
        return
    
    sql_content = None
    if input_file:
        with open(input_file, 'r', encoding='utf-8') as f:
            sql_content = f.read()
    elif content:
        sql_content = content
    elif not sys.stdin.isatty():
        sql_content = sys.stdin.read()
    
    if not sql_content:
        cprint('No input provided. Use file, --content, or pipe.', Color.RED)
        return
    
    tables = parse_sql_create_table(sql_content)
    
    if not tables:
        cprint('No CREATE TABLE statements found', Color.YELLOW)
        return
    
    template_map = {
        'sqlalchemy': 'sqlalchemy_model',
        'prisma': 'prisma_schema',
        'gorm': 'gorm_struct',
    }
    
    env = _jinja_env()
    template = env.get_template(template_map[target])
    output_content = template.render(tables=tables)
    
    if output:
        ext_map = {
            'sqlalchemy': '.py',
            'prisma': '.prisma',
            'gorm': '.go',
        }
        if not output.endswith(ext_map[target]):
            output = output + ext_map[target]
        with open(output, 'w', encoding='utf-8') as f:
            f.write(output_content)
        cprint(f'Generated {output}', Color.GREEN)
    else:
        click.echo(output_content)


@codegen.group(name='openapi')
def codegen_openapi():
    """Generate API client code from OpenAPI/Swagger spec"""
    pass


@codegen_openapi.command('client')
@click.argument('input_file', type=click.Path(exists=True), required=False)
@click.option('--content', '-c', help='OpenAPI YAML content')
@click.option('--language', '-l', default='typescript', 
              type=click.Choice(['typescript', 'python']),
              show_default=True, help='Target language')
@click.option('--class-name', default='ApiClient', help='Client class name')
@click.option('--output', '-o', type=click.Path(), help='Output file')
def openapi_client(input_file, content, language, class_name, output):
    """Generate API client from OpenAPI/Swagger YAML spec
    
    Examples:
      devkit codegen openapi client openapi.yml -l typescript
      cat openapi.yml | devkit codegen openapi client -l python
    """
    if not JINJA2_AVAILABLE:
        cprint('Jinja2 is required for code generation. Install: pip install Jinja2', Color.RED)
        return
    
    yaml_content = None
    if input_file:
        with open(input_file, 'r', encoding='utf-8') as f:
            yaml_content = f.read()
    elif content:
        yaml_content = content
    elif not sys.stdin.isatty():
        yaml_content = sys.stdin.read()
    
    if not yaml_content:
        cprint('No input provided. Use file, --content, or pipe.', Color.RED)
        return
    
    try:
        openapi_spec = yaml.safe_load(yaml_content)
    except Exception as e:
        cprint(f'Invalid YAML: {e}', Color.RED)
        return
    
    endpoints = parse_openapi(openapi_spec)
    
    template_map = {
        'typescript': 'api_client_typescript',
        'python': 'api_client_python',
    }
    
    env = _jinja_env()
    template = env.get_template(template_map[language])
    output_content = template.render(endpoints=endpoints, class_name=class_name)
    
    if output:
        ext_map = {
            'typescript': '.ts',
            'python': '.py',
        }
        if not output.endswith(ext_map[language]):
            output = output + ext_map[language]
        with open(output, 'w', encoding='utf-8') as f:
            f.write(output_content)
        cprint(f'Generated {output}', Color.GREEN)
    else:
        click.echo(output_content)
