import json as json_module
import csv
import sqlite3
import sys
import os
from pathlib import Path

import click

from ..core import Color, cprint, get_config


SQLITE_AVAILABLE = True
MYSQL_AVAILABLE = False
PG_AVAILABLE = False

try:
    import pymysql
    MYSQL_AVAILABLE = True
except ImportError:
    pass

try:
    import psycopg2
    PG_AVAILABLE = True
except ImportError:
    pass


def parse_conn_string(conn_str):
    """Parse connection string like host:port:dbname or user:pass@host:port/dbname"""
    if '://' in conn_str:
        scheme, rest = conn_str.split('://', 1)
        db_type = scheme
    else:
        scheme = None
        rest = conn_str
        db_type = 'mysql'
    
    user = password = host = port = dbname = None
    
    if '@' in rest:
        auth, host_part = rest.split('@', 1)
        if ':' in auth:
            user, password = auth.split(':', 1)
        else:
            user = auth
    else:
        host_part = rest
    
    if '/' in host_part:
        host_port, dbname = host_part.split('/', 1)
    else:
        host_port = host_part
        parts = host_port.split(':')
        if len(parts) == 3:
            host, port, dbname = parts[0], int(parts[1]), parts[2]
            host_port = None
    
    if host_port:
        if ':' in host_port:
            host, port_str = host_port.split(':', 1)
            port = int(port_str)
        else:
            host = host_port
            port = None
    
    if scheme:
        if scheme == 'mysql':
            db_type = 'mysql'
            if port is None:
                port = 3306
        elif scheme in ['postgresql', 'postgres', 'pg']:
            db_type = 'postgresql'
            if port is None:
                port = 5432
        elif scheme == 'sqlite':
            db_type = 'sqlite'
    
    return {
        'type': db_type,
        'user': user,
        'password': password,
        'host': host,
        'port': port,
        'dbname': dbname,
    }


def get_connection_from_config(name):
    """Get named connection from config"""
    cfg = get_config()
    connections = cfg.get('db_connections', {})
    if name not in connections:
        return None
    conn = connections[name]
    if conn.get('type') == 'sqlite' and conn.get('path'):
        return {'type': 'sqlite', 'path': conn['path']}
    return conn


def connect_db(conn_info):
    """Create database connection"""
    db_type = conn_info.get('type', 'mysql')
    
    if db_type == 'sqlite':
        path = conn_info.get('path', conn_info.get('dbname', ':memory:'))
        if path == ':memory:' or os.path.exists(path) or os.path.exists(os.path.dirname(path) or '.'):
            conn = sqlite3.connect(path)
            conn.row_factory = sqlite3.Row
            return conn
        raise ValueError(f"SQLite database not found: {path}")
    
    if db_type == 'mysql':
        if not MYSQL_AVAILABLE:
            raise ImportError("pymysql not installed. Install: pip install pymysql")
        return pymysql.connect(
            host=conn_info.get('host', 'localhost'),
            port=conn_info.get('port', 3306),
            user=conn_info.get('user', 'root'),
            password=conn_info.get('password', ''),
            database=conn_info.get('dbname'),
            cursorclass=pymysql.cursors.DictCursor,
        )
    
    if db_type in ['postgresql', 'postgres', 'pg']:
        if not PG_AVAILABLE:
            raise ImportError("psycopg2 not installed. Install: pip install psycopg2-binary")
        return psycopg2.connect(
            host=conn_info.get('host', 'localhost'),
            port=conn_info.get('port', 5432),
            user=conn_info.get('user', 'postgres'),
            password=conn_info.get('password', ''),
            dbname=conn_info.get('dbname'),
        )
    
    raise ValueError(f"Unsupported database type: {db_type}")


def format_table(rows, headers=None):
    """Format rows as table for terminal output"""
    if not rows:
        return "No results"
    
    if headers is None:
        if isinstance(rows[0], dict):
            headers = list(rows[0].keys())
        else:
            headers = [f'col{i+1}' for i in range(len(rows[0]))]
    
    data = []
    for row in rows:
        if isinstance(row, dict):
            data.append([str(row.get(h, '')) for h in headers])
        else:
            data.append([str(v) for v in row])
    
    col_widths = [len(h) for h in headers]
    for row in data:
        for i, val in enumerate(row):
            if len(val) > col_widths[i]:
                col_widths[i] = min(len(val), 80)
    
    separator = '+' + '+'.join(['-' * (w + 2) for w in col_widths]) + '+'
    header_line = '|' + '|'.join([f' {h.ljust(w)} ' for h, w in zip(headers, col_widths)]) + '|'
    
    output = [separator, header_line, separator]
    for row in data:
        row_line = '|' + '|'.join([f' {v[:w].ljust(w)} ' for v, w in zip(row, col_widths)]) + '|'
        output.append(row_line)
    output.append(separator)
    
    return '\n'.join(output)


def execute_query(conn, query):
    """Execute SQL query and return results"""
    cursor = conn.cursor()
    cursor.execute(query)
    
    if query.strip().upper().startswith(('SELECT', 'SHOW', 'DESCRIBE', 'EXPLAIN', 'PRAGMA')):
        results = cursor.fetchall()
        if results and isinstance(results[0], (sqlite3.Row, dict)):
            if isinstance(results[0], sqlite3.Row):
                headers = [desc[0] for desc in cursor.description]
                results = [dict(zip(headers, row)) for row in results]
        elif results and not isinstance(results[0], dict):
            headers = [desc[0] for desc in cursor.description]
            results = [dict(zip(headers, row)) for row in results]
        return results, cursor.description
    else:
        conn.commit()
        return [], None


@click.group()
def db():
    """Database tools"""
    pass


@db.command('query')
@click.argument('sql', required=False)
@click.option('--conn', '-c', help='Connection string (host:port:dbname or user:pass@host:port/dbname)')
@click.option('--connect', 'connect_name', help='Named connection from config')
@click.option('--type', 'db_type', type=click.Choice(['mysql', 'postgresql', 'sqlite']), default='mysql', help='Database type')
@click.option('--host', '-H', default='localhost', help='Database host')
@click.option('--port', '-p', type=int, help='Database port')
@click.option('--user', '-u', help='Database user')
@click.option('--password', '-P', help='Database password')
@click.option('--database', '-d', help='Database name')
@click.option('--path', help='SQLite database file path')
@click.option('--format', 'output_format', default='table', 
              type=click.Choice(['table', 'csv', 'json', 'vertical']),
              help='Output format')
@click.option('--output', '-o', type=click.Path(), help='Output file')
def db_query(sql, conn, connect_name, db_type, host, port, user, password, database, path, output_format, output):
    """Execute SQL query
    
    Examples:
      devkit db query "SELECT * FROM users LIMIT 10" --conn localhost:3306:testdb
      devkit db query "SELECT * FROM users" --connect prod --format json
      devkit db query "SELECT 1" --type sqlite --path /tmp/test.db
    """
    conn_info = None
    
    if connect_name:
        conn_info = get_connection_from_config(connect_name)
        if not conn_info:
            cprint(f"Connection '{connect_name}' not found in config", Color.RED)
            cprint("Add with: devkit config set db_connections.prod '{\"type\": \"mysql\", ...}'", Color.YELLOW)
            return
    elif conn:
        conn_info = parse_conn_string(conn)
    elif path or db_type == 'sqlite':
        conn_info = {'type': 'sqlite', 'path': path or ':memory:'}
    else:
        if not database:
            cprint("Please provide --conn, --connect, or --database", Color.RED)
            return
        if port is None:
            port = 3306 if db_type == 'mysql' else 5432
        conn_info = {
            'type': db_type,
            'host': host,
            'port': port,
            'user': user,
            'password': password,
            'dbname': database,
        }
    
    if not sql and not sys.stdin.isatty():
        sql = sys.stdin.read()
    
    if not sql:
        cprint("Please provide SQL query or pipe it", Color.RED)
        return
    
    try:
        with connect_db(conn_info) as connection:
            results, description = execute_query(connection, sql)
            
            if output_format == 'table':
                output_content = format_table(results)
            elif output_format == 'csv':
                if results and isinstance(results[0], dict):
                    headers = list(results[0].keys())
                    lines = [','.join(headers)]
                    for row in results:
                        lines.append(','.join(str(row.get(h, '')) for h in headers))
                    output_content = '\n'.join(lines)
                else:
                    output_content = '\n'.join(','.join(str(v) for v in row) for row in results)
            elif output_format == 'json':
                output_content = json_module.dumps(list(results), indent=2, ensure_ascii=False)
            elif output_format == 'vertical':
                output_lines = []
                for i, row in enumerate(results):
                    output_lines.append(f'*************************** {i+1}. row ***************************')
                    if isinstance(row, dict):
                        max_key = max(len(k) for k in row.keys())
                        for k, v in row.items():
                            output_lines.append(f'{k.ljust(max_key)}: {v}')
                    else:
                        for j, v in enumerate(row):
                            output_lines.append(f'col{j+1}: {v}')
                output_content = '\n'.join(output_lines)
            
            if output:
                with open(output, 'w', encoding='utf-8') as f:
                    f.write(output_content)
                cprint(f'Results written to {output}', Color.GREEN)
            else:
                click.echo(output_content)
                if results is not None and output_format == 'table':
                    cprint(f'\n{len(results)} rows returned', Color.CYAN)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)
        if os.environ.get('DEVKIT_DEBUG'):
            import traceback
            traceback.print_exc()


@db.command('shell')
@click.option('--conn', '-c', help='Connection string')
@click.option('--connect', 'connect_name', help='Named connection from config')
@click.option('--type', 'db_type', type=click.Choice(['mysql', 'postgresql', 'sqlite']), default='mysql')
@click.option('--host', '-H', default='localhost')
@click.option('--port', '-p', type=int)
@click.option('--user', '-u')
@click.option('--password', '-P')
@click.option('--database', '-d')
@click.option('--path', help='SQLite database file path')
def db_shell(conn, connect_name, db_type, host, port, user, password, database, path):
    """Interactive SQL shell
    
    Examples:
      devkit db shell --conn localhost:3306:testdb
      devkit db shell --connect prod
      devkit db shell --type sqlite --path ./app.db
    """
    conn_info = None
    
    if connect_name:
        conn_info = get_connection_from_config(connect_name)
        if not conn_info:
            cprint(f"Connection '{connect_name}' not found", Color.RED)
            return
    elif conn:
        conn_info = parse_conn_string(conn)
    elif path or db_type == 'sqlite':
        conn_info = {'type': 'sqlite', 'path': path or ':memory:'}
    else:
        if port is None:
            port = 3306 if db_type == 'mysql' else 5432
        conn_info = {
            'type': db_type,
            'host': host,
            'port': port,
            'user': user,
            'password': password,
            'dbname': database,
        }
    
    try:
        with connect_db(conn_info) as connection:
            db_name = conn_info.get('dbname', conn_info.get('path', 'unknown'))
            cprint(f'Connected to {conn_info["type"]}: {db_name}', Color.GREEN)
            cprint('Type SQL to execute, or exit/quit to leave, or help for help', Color.CYAN)
            cprint('Special commands: .tables, .schema [table], .exit, .output [file]', Color.YELLOW)
            
            output_file = None
            
            while True:
                try:
                    sql = click.prompt(f'{db_name}> ', type=str, default='')
                except (EOFError, KeyboardInterrupt):
                    click.echo()
                    break
                
                sql = sql.strip()
                if not sql:
                    continue
                
                if sql.lower() in ['exit', 'quit', '.exit', '.quit']:
                    break
                
                if sql.lower() == '.tables':
                    try:
                        if conn_info['type'] == 'sqlite':
                            results, _ = execute_query(connection, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
                        elif conn_info['type'] == 'mysql':
                            results, _ = execute_query(connection, 'SHOW TABLES')
                        else:
                            results, _ = execute_query(connection, "SELECT table_name FROM information_schema.tables WHERE table_schema = CURRENT_SCHEMA() ORDER BY table_name")
                        output_content = format_table(results)
                        click.echo(output_content)
                    except Exception as e:
                        cprint(f'Error: {e}', Color.RED)
                    continue
                
                if sql.lower().startswith('.schema'):
                    parts = sql.split(None, 1)
                    table = parts[1] if len(parts) > 1 else None
                    try:
                        if conn_info['type'] == 'sqlite':
                            if table:
                                results, _ = execute_query(connection, f"SELECT sql FROM sqlite_master WHERE type='table' AND name='{table}'")
                            else:
                                results, _ = execute_query(connection, "SELECT sql FROM sqlite_master WHERE type='table' ORDER BY name")
                        elif conn_info['type'] == 'mysql':
                            if table:
                                results, _ = execute_query(connection, f'SHOW CREATE TABLE {table}')
                            else:
                                results, _ = execute_query(connection, 'SHOW TABLES')
                        else:
                            if table:
                                results, _ = execute_query(connection, f"""
                                    SELECT column_name, data_type, is_nullable, column_default
                                    FROM information_schema.columns 
                                    WHERE table_name = '{table}' 
                                    ORDER BY ordinal_position
                                """)
                        output_content = format_table(results)
                        click.echo(output_content)
                    except Exception as e:
                        cprint(f'Error: {e}', Color.RED)
                    continue
                
                if sql.lower().startswith('.output'):
                    parts = sql.split(None, 1)
                    if len(parts) > 1:
                        output_file = parts[1]
                        cprint(f'Output set to {output_file}', Color.CYAN)
                    else:
                        output_file = None
                        cprint('Output reset to stdout', Color.CYAN)
                    continue
                
                if sql.lower() == 'help':
                    cprint('Commands:', Color.CYAN)
                    cprint('  .tables          - List all tables', Color.YELLOW)
                    cprint('  .schema [table]  - Show table schema', Color.YELLOW)
                    cprint('  .output [file]   - Set output file', Color.YELLOW)
                    cprint('  .exit/.quit      - Exit shell', Color.YELLOW)
                    continue
                
                try:
                    results, description = execute_query(connection, sql)
                    
                    output_content = format_table(results)
                    
                    if output_file:
                        with open(output_file, 'a', encoding='utf-8') as f:
                            f.write(output_content + '\n')
                        cprint(f'Results appended to {output_file}', Color.GREEN)
                    else:
                        click.echo(output_content)
                    
                    if results is not None and isinstance(results, list):
                        cprint(f'\n{len(results)} rows returned', Color.CYAN)
                except Exception as e:
                    cprint(f'Error: {e}', Color.RED)
    except ImportError as e:
        cprint(f'{e}', Color.RED)
    except Exception as e:
        cprint(f'Connection error: {e}', Color.RED)


@db.command('schema')
@click.option('--conn', '-c', help='Connection string')
@click.option('--connect', 'connect_name', help='Named connection from config')
@click.option('--type', 'db_type', type=click.Choice(['mysql', 'postgresql', 'sqlite']), default='mysql')
@click.option('--host', '-H', default='localhost')
@click.option('--port', '-p', type=int)
@click.option('--user', '-u')
@click.option('--password', '-P')
@click.option('--database', '-d')
@click.option('--path', help='SQLite database file path')
@click.option('--table', '-t', help='Specific table to export')
@click.option('--output', '-o', type=click.Path(), help='Output file')
def db_schema(conn, connect_name, db_type, host, port, user, password, database, path, table, output):
    """Export database schema as CREATE TABLE statements
    
    Examples:
      devkit db schema --conn localhost:3306:testdb
      devkit db schema --connect prod --table users -o schema.sql
    """
    conn_info = None
    
    if connect_name:
        conn_info = get_connection_from_config(connect_name)
        if not conn_info:
            cprint(f"Connection '{connect_name}' not found", Color.RED)
            return
    elif conn:
        conn_info = parse_conn_string(conn)
    elif path or db_type == 'sqlite':
        conn_info = {'type': 'sqlite', 'path': path or ':memory:'}
    else:
        if port is None:
            port = 3306 if db_type == 'mysql' else 5432
        conn_info = {
            'type': db_type,
            'host': host,
            'port': port,
            'user': user,
            'password': password,
            'dbname': database,
        }
    
    try:
        with connect_db(conn_info) as connection:
            cursor = connection.cursor()
            
            if conn_info['type'] == 'sqlite':
                if table:
                    cursor.execute(f"SELECT sql FROM sqlite_master WHERE type='table' AND name='{table}'")
                else:
                    cursor.execute("SELECT sql FROM sqlite_master WHERE type='table' ORDER BY name")
                schemas = [row[0] for row in cursor.fetchall()]
            elif conn_info['type'] == 'mysql':
                if table:
                    cursor.execute(f'SHOW CREATE TABLE `{table}`')
                    schemas = [row[1] for row in cursor.fetchall()]
                else:
                    cursor.execute('SHOW TABLES')
                    tables = [row[0] for row in cursor.fetchall()]
                    schemas = []
                    for t in tables:
                        cursor.execute(f'SHOW CREATE TABLE `{t}`')
                        schemas.append(cursor.fetchone()[1])
            else:
                if table:
                    cursor.execute(f"""
                        SELECT 'CREATE TABLE ' || table_name || ' (' || 
                               string_agg(column_name || ' ' || data_type || 
                               CASE WHEN is_nullable = 'NO' THEN ' NOT NULL' ELSE '' END, ', ') || 
                               ');' as create_table
                        FROM information_schema.columns 
                        WHERE table_name = '{table}' 
                        GROUP BY table_name
                    """)
                    schemas = [row[0] for row in cursor.fetchall()]
                else:
                    cursor.execute("""
                        SELECT table_name, column_name, data_type, is_nullable
                        FROM information_schema.columns 
                        WHERE table_schema = CURRENT_SCHEMA()
                        ORDER BY table_name, ordinal_position
                    """)
                    rows = cursor.fetchall()
                    schemas = []
                    current_table = None
                    current_cols = []
                    for row in rows:
                        if row[0] != current_table:
                            if current_table:
                                schemas.append(f'CREATE TABLE {current_table} ({", ".join(current_cols)});')
                            current_table = row[0]
                            current_cols = []
                        col_def = f'{row[1]} {row[2]}'
                        if row[3] == 'NO':
                            col_def += ' NOT NULL'
                        current_cols.append(col_def)
                    if current_table:
                        schemas.append(f'CREATE TABLE {current_table} ({", ".join(current_cols)});')
            
            output_content = '\n\n'.join(schemas) + '\n'
            
            if output:
                with open(output, 'w', encoding='utf-8') as f:
                    f.write(output_content)
                cprint(f'Schema written to {output}', Color.GREEN)
            else:
                click.echo(output_content)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@db.command('export')
@click.argument('query', required=False)
@click.option('--conn', '-c', help='Connection string')
@click.option('--connect', 'connect_name', help='Named connection from config')
@click.option('--type', 'db_type', type=click.Choice(['mysql', 'postgresql', 'sqlite']), default='mysql')
@click.option('--host', '-H', default='localhost')
@click.option('--port', '-p', type=int)
@click.option('--user', '-u')
@click.option('--password', '-P')
@click.option('--database', '-d')
@click.option('--path', help='SQLite database file path')
@click.option('--table', '-t', help='Export entire table')
@click.option('--format', 'output_format', default='csv', 
              type=click.Choice(['csv', 'json', 'sql']),
              help='Output format')
@click.option('--output', '-o', required=True, type=click.Path(), help='Output file')
def db_export(query, conn, connect_name, db_type, host, port, user, password, database, path, table, output_format, output):
    """Export query results to file
    
    Examples:
      devkit db export --table users -o users.csv --connect prod
      devkit db export "SELECT * FROM orders WHERE date > '2024-01-01'" --conn localhost:3306:testdb -o orders.json --format json
    """
    conn_info = None
    
    if connect_name:
        conn_info = get_connection_from_config(connect_name)
        if not conn_info:
            cprint(f"Connection '{connect_name}' not found", Color.RED)
            return
    elif conn:
        conn_info = parse_conn_string(conn)
    elif path or db_type == 'sqlite':
        conn_info = {'type': 'sqlite', 'path': path or ':memory:'}
    else:
        if port is None:
            port = 3306 if db_type == 'mysql' else 5432
        conn_info = {
            'type': db_type,
            'host': host,
            'port': port,
            'user': user,
            'password': password,
            'dbname': database,
        }
    
    if table:
        query = f'SELECT * FROM {table}'
    
    if not query and not sys.stdin.isatty():
        query = sys.stdin.read()
    
    if not query:
        cprint("Please provide --table, SQL query, or pipe it", Color.RED)
        return
    
    try:
        with connect_db(conn_info) as connection:
            results, description = execute_query(connection, query)
            
            if not results:
                cprint('No data to export', Color.YELLOW)
                return
            
            if not isinstance(results[0], dict) and description:
                headers = [desc[0] for desc in description]
                results = [dict(zip(headers, row)) for row in results]
            
            if output_format == 'csv':
                headers = list(results[0].keys())
                with open(output, 'w', newline='', encoding='utf-8') as f:
                    writer = csv.DictWriter(f, fieldnames=headers)
                    writer.writeheader()
                    writer.writerows(results)
            elif output_format == 'json':
                with open(output, 'w', encoding='utf-8') as f:
                    json_module.dump(list(results), f, indent=2, ensure_ascii=False)
            elif output_format == 'sql':
                table_name = table or 'exported_data'
                lines = []
                headers = list(results[0].keys())
                for row in results:
                    values = []
                    for h in headers:
                        v = row.get(h)
                        if v is None:
                            values.append('NULL')
                        elif isinstance(v, (int, float)):
                            values.append(str(v))
                        else:
                            escaped = str(v).replace("'", "''")
                            values.append(f"'{escaped}'")
                    lines.append(f"INSERT INTO {table_name} ({', '.join(headers)}) VALUES ({', '.join(values)});")
                with open(output, 'w', encoding='utf-8') as f:
                    f.write('\n'.join(lines) + '\n')
            
            cprint(f'Exported {len(results)} rows to {output}', Color.GREEN)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@db.command('add-connection')
@click.argument('name')
@click.argument('conn_string')
def db_add_connection(name, conn_string):
    """Add a named database connection to config
    
    Example:
      devkit db add-connection prod host:3306:dbname
    """
    conn_info = parse_conn_string(conn_string)
    cfg = get_config()
    cfg.set(f'db_connections.{name}', conn_info)
    cprint(f'Added connection: {name}', Color.GREEN)


@db.command('list-connections')
def db_list_connections():
    """List all named database connections"""
    cfg = get_config()
    connections = cfg.get('db_connections', {})
    if not connections:
        cprint('No connections configured', Color.YELLOW)
        return
    
    for name, conn in connections.items():
        if conn.get('type') == 'sqlite':
            cprint(f'{name}: sqlite://{conn.get("path", "")}', Color.CYAN)
        else:
            port = conn.get('port', '')
            cprint(f'{name}: {conn.get("type", "mysql")}://{conn.get("user", "")}@{conn.get("host", "")}:{port}/{conn.get("dbname", "")}', Color.CYAN)
