import json
import hashlib
import time
import re
from datetime import datetime
from flask import current_app
from app import db, redis_client
from app.models import DataSource


def create_datasource(user_id, name, type, connection_config, description=None,
                      team_id=None, query_templates=None, cache_ttl=None, is_public=False):
    if type not in DataSource.TYPES:
        raise ValueError(f'不支持的数据源类型: {type}')

    datasource = DataSource(
        name=name,
        description=description,
        type=type,
        owner_id=user_id,
        team_id=team_id,
        is_public=is_public,
        cache_ttl=cache_ttl
    )
    datasource.set_connection_config(connection_config)
    if query_templates:
        datasource.set_query_templates(query_templates)

    db.session.add(datasource)
    db.session.commit()
    return datasource


def update_datasource(datasource_id, **kwargs):
    datasource = DataSource.query.get(datasource_id)
    if not datasource:
        raise ValueError('数据源不存在')

    allowed_fields = ['name', 'description', 'team_id', 'is_public', 'cache_ttl', 'status']
    for field, value in kwargs.items():
        if field in allowed_fields:
            setattr(datasource, field, value)

    if 'connection_config' in kwargs:
        datasource.set_connection_config(kwargs['connection_config'])
    if 'query_templates' in kwargs:
        datasource.set_query_templates(kwargs['query_templates'])

    datasource.updated_at = datetime.utcnow()
    db.session.add(datasource)
    db.session.commit()
    return datasource


def delete_datasource(datasource_id):
    datasource = DataSource.query.get(datasource_id)
    if not datasource:
        raise ValueError('数据源不存在')

    db.session.delete(datasource)
    db.session.commit()


def get_datasource(datasource_id):
    return DataSource.query.get(datasource_id)


def get_user_datasources(user_id, page=1, per_page=20, search=None, type=None, team_id=None):
    from app.models import TeamMember
    query = DataSource.query.outerjoin(TeamMember, DataSource.team_id == TeamMember.team_id).filter(
        (DataSource.owner_id == user_id) |
        (DataSource.is_public == True) |
        (TeamMember.user_id == user_id)
    ).distinct()

    if search:
        query = query.filter(DataSource.name.like(f'%{search}%'))
    if type:
        query = query.filter(DataSource.type == type)
    if team_id:
        query = query.filter(DataSource.team_id == team_id)

    return query.order_by(DataSource.updated_at.desc()).paginate(page=page, per_page=per_page)


def test_datasource_connection(datasource):
    config = datasource.get_connection_config()
    try:
        if datasource.type == 'mysql':
            return _test_mysql_connection(config)
        elif datasource.type == 'clickhouse':
            return _test_clickhouse_connection(config)
        elif datasource.type == 'prometheus':
            return _test_prometheus_connection(config)
        elif datasource.type == 'http':
            return _test_http_connection(config)
    except Exception as e:
        return {'success': False, 'error': str(e)}
    return {'success': False, 'error': '未知数据源类型'}


def _test_mysql_connection(config):
    import mysql.connector
    try:
        conn = mysql.connector.connect(
            host=config.get('host', 'localhost'),
            port=config.get('port', 3306),
            user=config.get('username', ''),
            password=config.get('password', ''),
            database=config.get('database', ''),
            connection_timeout=5
        )
        conn.close()
        return {'success': True, 'message': '连接成功'}
    except Exception as e:
        return {'success': False, 'error': str(e)}


def _test_clickhouse_connection(config):
    from clickhouse_driver import Client
    try:
        client = Client(
            host=config.get('host', 'localhost'),
            port=config.get('port', 9000),
            user=config.get('username', 'default'),
            password=config.get('password', ''),
            database=config.get('database', 'default'),
            connect_timeout=5
        )
        client.execute('SELECT 1')
        return {'success': True, 'message': '连接成功'}
    except Exception as e:
        return {'success': False, 'error': str(e)}


def _test_prometheus_connection(config):
    import requests
    try:
        base_url = config.get('base_url', '').rstrip('/')
        response = requests.get(
            f'{base_url}/api/v1/query',
            params={'query': 'up'},
            timeout=5,
            auth=(config.get('username'), config.get('password')) if config.get('username') else None
        )
        if response.status_code == 200:
            return {'success': True, 'message': '连接成功'}
        return {'success': False, 'error': f'HTTP {response.status_code}'}
    except Exception as e:
        return {'success': False, 'error': str(e)}


def _test_http_connection(config):
    import requests
    try:
        url = config.get('url')
        if not url:
            return {'success': False, 'error': '缺少URL配置'}

        method = config.get('method', 'GET')
        headers = config.get('headers', {})
        timeout = config.get('timeout', 10)

        response = requests.request(method, url, headers=headers, timeout=timeout)
        if 200 <= response.status_code < 300:
            return {'success': True, 'message': '连接成功'}
        return {'success': False, 'error': f'HTTP {response.status_code}'}
    except Exception as e:
        return {'success': False, 'error': str(e)}


def execute_query(datasource, query_template, params=None):
    cache_key = _get_cache_key(datasource, query_template, params)
    cache_ttl = datasource.cache_ttl or current_app.config['CACHE_TTL'].get(datasource.type, 300)

    if redis_client:
        cached = redis_client.get(f"query:{cache_key}")
        if cached:
            try:
                return json.loads(cached)
            except json.JSONDecodeError:
                pass

    result = _execute_query_internal(datasource, query_template, params)

    if redis_client and result.get('success'):
        try:
            redis_client.setex(
                f"query:{cache_key}",
                cache_ttl,
                json.dumps(result, default=str)
            )
        except Exception:
            pass

    datasource.increment_query_count()
    db.session.commit()

    return result


def _get_cache_key(datasource, query_template, params):
    key_parts = [
        str(datasource.id),
        datasource.type,
        query_template,
        json.dumps(params or {}, sort_keys=True, default=str)
    ]
    return hashlib.md5('|'.join(key_parts).encode()).hexdigest()


def _render_query_template(query_template, params):
    if not params:
        return query_template

    result = query_template
    for key, value in params.items():
        placeholder = '{{' + key + '}}'
        escaped_value = _escape_value(value)
        result = result.replace(placeholder, str(escaped_value))

    return result


def _escape_value(value):
    if isinstance(value, str):
        return value.replace("'", "''").replace(';', '')
    return value


def _execute_query_internal(datasource, query_template, params):
    config = datasource.get_connection_config()
    rendered_query = _render_query_template(query_template, params or {})

    start_time = time.time()
    try:
        if datasource.type == 'mysql':
            result = _execute_mysql_query(config, rendered_query)
        elif datasource.type == 'clickhouse':
            result = _execute_clickhouse_query(config, rendered_query)
        elif datasource.type == 'prometheus':
            result = _execute_prometheus_query(config, rendered_query, params)
        elif datasource.type == 'http':
            result = _execute_http_query(config, rendered_query, params)
        else:
            return {'success': False, 'error': '未知数据源类型'}

        result['execution_time'] = round((time.time() - start_time) * 1000, 2)
        return result
    except Exception as e:
        return {
            'success': False,
            'error': str(e),
            'execution_time': round((time.time() - start_time) * 1000, 2)
        }


def _execute_mysql_query(config, query):
    import mysql.connector
    conn = None
    try:
        conn = mysql.connector.connect(
            host=config.get('host', 'localhost'),
            port=config.get('port', 3306),
            user=config.get('username', ''),
            password=config.get('password', ''),
            database=config.get('database', ''),
            connection_timeout=30
        )
        cursor = conn.cursor(dictionary=True)
        cursor.execute(query)
        rows = cursor.fetchall()
        columns = [desc[0] for desc in cursor.description] if cursor.description else []

        categories = []
        values = []
        series_data = []

        if rows and columns:
            if len(columns) >= 2:
                categories = [str(row[columns[0]]) for row in rows]
                for col_idx in range(1, len(columns)):
                    col_name = columns[col_idx]
                    data = []
                    for row in rows:
                        val = row[col_name]
                        if isinstance(val, (int, float)):
                            data.append(float(val))
                        else:
                            data.append(0)
                    series_data.append({'name': col_name, 'data': data})
                    if col_idx == 1:
                        values = data

        return {
            'success': True,
            'data': {
                'rows': rows,
                'columns': columns,
                'categories': categories,
                'values': values,
                'series': series_data,
                'row_count': len(rows)
            }
        }
    finally:
        if conn:
            conn.close()


def _execute_clickhouse_query(config, query):
    from clickhouse_driver import Client
    client = None
    try:
        client = Client(
            host=config.get('host', 'localhost'),
            port=config.get('port', 9000),
            user=config.get('username', 'default'),
            password=config.get('password', ''),
            database=config.get('database', 'default'),
            connect_timeout=30
        )
        rows = client.execute(query, with_column_types=True)
        data_rows = rows[0]
        columns = [col[0] for col in rows[1]]

        categories = []
        values = []
        series_data = []

        if data_rows and columns:
            dict_rows = [dict(zip(columns, row)) for row in data_rows]
            if len(columns) >= 2:
                categories = [str(row[0]) for row in data_rows]
                for col_idx in range(1, len(columns)):
                    col_name = columns[col_idx]
                    data = []
                    for row in data_rows:
                        val = row[col_idx]
                        if isinstance(val, (int, float)):
                            data.append(float(val))
                        else:
                            data.append(0)
                    series_data.append({'name': col_name, 'data': data})
                    if col_idx == 1:
                        values = data

        return {
            'success': True,
            'data': {
                'rows': [dict(zip(columns, row)) for row in data_rows],
                'columns': columns,
                'categories': categories,
                'values': values,
                'series': series_data,
                'row_count': len(data_rows)
            }
        }
    finally:
        if client:
            client.disconnect()


def _execute_prometheus_query(config, query, params):
    import requests
    base_url = config.get('base_url', '').rstrip('/')
    endpoint = '/api/v1/query_range' if params and params.get('start') else '/api/v1/query'

    req_params = {'query': query}
    if params:
        for key in ['start', 'end', 'step', 'time']:
            if key in params:
                req_params[key] = params[key]

    auth = (config.get('username'), config.get('password')) if config.get('username') else None

    response = requests.get(
        f'{base_url}{endpoint}',
        params=req_params,
        timeout=30,
        auth=auth
    )

    if response.status_code != 200:
        return {'success': False, 'error': f'Prometheus API error: {response.status_code}'}

    data = response.json()
    if data.get('status') != 'success':
        return {'success': False, 'error': data.get('error', 'Prometheus query failed')}

    result = data.get('data', {}).get('result', [])
    categories = []
    values = []
    series_data = []

    if result:
        for series in result:
            metric = series.get('metric', {})
            values_data = series.get('values', [series.get('value', [])])

            series_name = metric.get('__name__', 'series')
            if len(metric) > 1:
                labels = [f'{k}={v}' for k, v in metric.items() if k != '__name__']
                series_name = f"{series_name}[{', '.join(labels)}]"

            data_points = []
            cats = []
            for ts, val in values_data:
                cats.append(datetime.fromtimestamp(float(ts)).strftime('%Y-%m-%d %H:%M:%S'))
                data_points.append(float(val))

            if not categories:
                categories = cats
            if not values:
                values = data_points

            series_data.append({'name': series_name, 'data': data_points})

    return {
        'success': True,
        'data': {
            'raw': data,
            'categories': categories,
            'values': values,
            'series': series_data,
            'row_count': len(result)
        }
    }


def _execute_http_query(config, query_template, params):
    import requests
    url = config.get('url')
    method = config.get('method', 'GET')
    headers = config.get('headers', {})
    timeout = config.get('timeout', 30)

    body = query_template if method not in ['GET', 'HEAD'] else None
    req_params = params if method in ['GET', 'HEAD'] else None

    if params and isinstance(params, dict):
        for key, value in params.items():
            placeholder = '{{' + key + '}}'
            if placeholder in url:
                url = url.replace(placeholder, str(value))

    response = requests.request(
        method,
        url,
        params=req_params,
        json=json.loads(body) if body else None,
        headers=headers,
        timeout=timeout
    )

    if not (200 <= response.status_code < 300):
        return {'success': False, 'error': f'HTTP {response.status_code}: {response.text[:200]}'}

    try:
        data = response.json()
    except json.JSONDecodeError:
        data = {'raw': response.text}

    categories = []
    values = []
    series_data = []

    if isinstance(data, dict):
        data_path = config.get('data_path', 'data')
        extracted_data = data
        for key in data_path.split('.'):
            if key in extracted_data:
                extracted_data = extracted_data[key]
            else:
                extracted_data = []
                break

        if isinstance(extracted_data, list) and extracted_data:
            first_item = extracted_data[0]
            if isinstance(first_item, dict):
                keys = list(first_item.keys())
                if len(keys) >= 2:
                    cat_key = config.get('category_key', keys[0])
                    value_key = config.get('value_key', keys[1])

                    categories = [str(item.get(cat_key, '')) for item in extracted_data]

                    for key in keys:
                        if key != cat_key:
                            data_points = []
                            for item in extracted_data:
                                val = item.get(key, 0)
                                if isinstance(val, (int, float)):
                                    data_points.append(float(val))
                                else:
                                    data_points.append(0)
                            series_data.append({'name': key, 'data': data_points})
                            if key == value_key:
                                values = data_points

    return {
        'success': True,
        'data': {
            'raw': data,
            'categories': categories,
            'values': values,
            'series': series_data,
            'row_count': len(categories)
        }
    }


def merge_queries(queries):
    merged = {
        'success': True,
        'data': {
            'categories': [],
            'values': [],
            'series': [],
            'results': []
        },
        'execution_time': 0
    }

    for query_result in queries:
        if not query_result.get('success'):
            merged['success'] = False
            merged['error'] = query_result.get('error', 'One or more queries failed')

        if 'data' in query_result:
            merged['data']['results'].append(query_result['data'])
            if not merged['data']['categories']:
                merged['data']['categories'] = query_result['data'].get('categories', [])
            merged['data']['series'].extend(query_result['data'].get('series', []))

        merged['execution_time'] += query_result.get('execution_time', 0)

    return merged


def sample_time_series_data(data, target_points=100, method='avg'):
    if not data or not data.get('values'):
        return data

    values = data.get('values', [])
    categories = data.get('categories', [])
    series_list = data.get('series', [])

    if len(values) <= target_points:
        return data

    sampled_data = {
        'categories': [],
        'values': [],
        'series': []
    }

    if categories and len(categories) == len(values):
        bucket_size = len(values) // target_points
        if bucket_size < 2:
            bucket_size = 2

        for i in range(0, len(values), bucket_size):
            bucket_values = values[i:i + bucket_size]
            bucket_cats = categories[i:i + bucket_size]

            if method == 'avg':
                sampled_value = sum(bucket_values) / len(bucket_values)
            elif method == 'max':
                sampled_value = max(bucket_values)
            elif method == 'min':
                sampled_value = min(bucket_values)
            elif method == 'first':
                sampled_value = bucket_values[0]
            elif method == 'last':
                sampled_value = bucket_values[-1]
            else:
                sampled_value = sum(bucket_values) / len(bucket_values)

            sampled_data['values'].append(round(sampled_value, 4))
            sampled_data['categories'].append(bucket_cats[len(bucket_cats) // 2])

        if series_list:
            for series in series_list:
                series_data = series.get('data', [])
                sampled_series = {'name': series.get('name', ''), 'data': []}
                for i in range(0, len(series_data), bucket_size):
                    bucket = series_data[i:i + bucket_size]
                    if bucket:
                        if method == 'avg':
                            val = sum(bucket) / len(bucket)
                        elif method == 'max':
                            val = max(bucket)
                        elif method == 'min':
                            val = min(bucket)
                        else:
                            val = sum(bucket) / len(bucket)
                        sampled_series['data'].append(round(val, 4))
                sampled_data['series'].append(sampled_series)

    sampled_data['rows'] = data.get('rows', [])[:len(sampled_data['values'])] if data.get('rows') else []
    sampled_data['columns'] = data.get('columns', [])
    sampled_data['row_count'] = len(sampled_data['values'])
    sampled_data['sampled'] = True
    sampled_data['original_count'] = len(values)

    return sampled_data


def paginate_data(data, page=1, per_page=20):
    if not data:
        return data

    total = data.get('row_count', 0)
    rows = data.get('rows', [])

    if not rows:
        return {
            **data,
            'paginated': True,
            'page': page,
            'per_page': per_page,
            'total': total,
            'pages': 0,
            'has_next': False,
            'has_prev': False,
        }

    total_pages = (total + per_page - 1) // per_page if total > 0 else 0
    page = max(1, min(page, total_pages if total_pages > 0 else 1))

    start_idx = (page - 1) * per_page
    end_idx = min(start_idx + per_page, len(rows))

    paginated_rows = rows[start_idx:end_idx]

    paginated_data = {
        **data,
        'rows': paginated_rows,
        'paginated': True,
        'page': page,
        'per_page': per_page,
        'total': total,
        'pages': total_pages,
        'has_next': page < total_pages,
        'has_prev': page > 1,
    }

    if 'values' in data and data['values']:
        paginated_data['values'] = data['values'][start_idx:end_idx]
    if 'categories' in data and data['categories']:
        paginated_data['categories'] = data['categories'][start_idx:end_idx]
    if 'series' in data and data['series']:
        paginated_series = []
        for s in data['series']:
            s_data = s.get('data', [])
            paginated_series.append({
                'name': s.get('name', ''),
                'data': s_data[start_idx:end_idx]
            })
        paginated_data['series'] = paginated_series
    if 'row_count' in paginated_data:
        paginated_data['row_count'] = len(paginated_rows)

    return paginated_data


def execute_query_with_options(datasource, query_template, params=None,
                         sample=None, sample_points=100, sample_method='avg',
                         page=None, per_page=20):
    result = execute_query(datasource, query_template, params)

    if not result.get('success'):
        return result

    data = result.get('data', {})

    if sample:
        data = sample_time_series_data(data, target_points=sample_points, method=sample_method)
        result['data'] = data
        result['sampled'] = True

    if page is not None:
        data = paginate_data(data, page=page, per_page=per_page)
        result['data'] = data
        result['paginated'] = True

    return result
