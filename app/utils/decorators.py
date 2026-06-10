from functools import wraps
from flask import jsonify, request, abort
from flask_login import current_user
from functools import lru_cache
import hashlib
import json
from app import redis_client, db
from app.models import User


def login_required_api(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if not current_user.is_authenticated:
            return jsonify({'error': 'Unauthorized', 'message': '请先登录'}), 401
        return f(*args, **kwargs)
    return decorated_function


def permission_required(permission):
    def decorator(f):
        @wraps(f)
        def decorated_function(*args, **kwargs):
            if not current_user.is_authenticated:
                return jsonify({'error': 'Unauthorized', 'message': '请先登录'}), 401
            if not current_user.has_permission(permission):
                return jsonify({'error': 'Forbidden', 'message': '权限不足'}), 403
            return f(*args, **kwargs)
        return decorated_function
    return decorator


def dashboard_access_required(permission='view'):
    def decorator(f):
        @wraps(f)
        def decorated_function(*args, **kwargs):
            if not current_user.is_authenticated:
                return jsonify({'error': 'Unauthorized', 'message': '请先登录'}), 401
            dashboard_id = kwargs.get('dashboard_id') or request.view_args.get('dashboard_id')
            if not dashboard_id:
                return jsonify({'error': 'Bad Request', 'message': '缺少看板ID'}), 400
            if not current_user.has_dashboard_access(dashboard_id, permission):
                return jsonify({'error': 'Forbidden', 'message': '看板访问权限不足'}), 403
            return f(*args, **kwargs)
        return decorated_function
    return decorator


def cache_result(ttl=None, key_prefix=None):
    def decorator(f):
        @wraps(f)
        def decorated_function(*args, **kwargs):
            if redis_client is None:
                return f(*args, **kwargs)

            cache_key_parts = [key_prefix or f.__name__]
            cache_key_parts.extend([str(a) for a in args])
            cache_key_parts.extend([f"{k}={v}" for k, v in sorted(kwargs.items())])
            cache_key = hashlib.md5('|'.join(cache_key_parts).encode()).hexdigest()

            cached = redis_client.get(f"cache:{cache_key}")
            if cached:
                try:
                    return json.loads(cached)
                except json.JSONDecodeError:
                    pass

            result = f(*args, **kwargs)

            try:
                redis_client.setex(
                    f"cache:{cache_key}",
                    ttl or 300,
                    json.dumps(result, default=str)
                )
            except Exception:
                pass

            return result
        return decorated_function
    return decorator


def transactional(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        try:
            result = f(*args, **kwargs)
            db.session.commit()
            return result
        except Exception as e:
            db.session.rollback()
            raise e
    return decorated_function


def validate_json(*required_fields):
    def decorator(f):
        @wraps(f)
        def decorated_function(*args, **kwargs):
            if not request.is_json:
                return jsonify({'error': 'Bad Request', 'message': '请求必须是JSON格式'}), 400
            data = request.get_json()
            missing = [field for field in required_fields if field not in data]
            if missing:
                return jsonify({
                    'error': 'Bad Request',
                    'message': f'缺少必填字段: {", ".join(missing)}'
                }), 400
            return f(*args, **kwargs)
        return decorated_function
    return decorator


def paginate(default_per_page=20, max_per_page=100):
    def decorator(f):
        @wraps(f)
        def decorated_function(*args, **kwargs):
            page = request.args.get('page', 1, type=int)
            per_page = min(request.args.get('per_page', default_per_page, type=int), max_per_page)
            kwargs['page'] = page
            kwargs['per_page'] = per_page
            return f(*args, **kwargs)
        return decorated_function
    return decorator
