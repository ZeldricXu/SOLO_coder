from datetime import datetime
from app import db
from app.models import User, Role
from app.services.init_service import add_user_to_default_team
from email_validator import validate_email, EmailNotValidError


def register_user(email, password, name, role_name='viewer'):
    try:
        valid = validate_email(email)
        email = valid.normalized
    except EmailNotValidError as e:
        raise ValueError(f'邮箱格式不正确: {str(e)}')

    existing = User.query.filter_by(email=email).first()
    if existing:
        raise ValueError('该邮箱已被注册')

    if len(password) < 6:
        raise ValueError('密码长度至少6位')

    role = Role.query.filter_by(name=role_name).first()
    if not role:
        role = Role.query.filter_by(name='viewer').first()

    user = User(
        email=email,
        name=name,
        role_id=role.id if role else None
    )
    user.set_password(password)

    db.session.add(user)
    db.session.commit()

    add_user_to_default_team(user)

    return user


def create_admin_user(email, password, name):
    return register_user(email, password, name, role_name='admin')


def authenticate_user(email, password):
    user = User.query.filter_by(email=email).first()
    if not user:
        return None
    if not user.check_password(password):
        return None
    if not user.is_active:
        return None
    user.last_login_at = datetime.utcnow()
    db.session.add(user)
    db.session.commit()
    return user


def update_user_profile(user_id, **kwargs):
    user = User.query.get(user_id)
    if not user:
        raise ValueError('用户不存在')

    allowed_fields = ['name', 'avatar']
    for field, value in kwargs.items():
        if field in allowed_fields:
            setattr(user, field, value)

    db.session.add(user)
    db.session.commit()
    return user


def change_password(user_id, old_password, new_password):
    user = User.query.get(user_id)
    if not user:
        raise ValueError('用户不存在')

    if not user.check_password(old_password):
        raise ValueError('原密码不正确')

    if len(new_password) < 6:
        raise ValueError('新密码长度至少6位')

    user.set_password(new_password)
    db.session.add(user)
    db.session.commit()
    return user


def reset_password(email, new_password):
    user = User.query.filter_by(email=email).first()
    if not user:
        raise ValueError('用户不存在')

    if len(new_password) < 6:
        raise ValueError('新密码长度至少6位')

    user.set_password(new_password)
    db.session.add(user)
    db.session.commit()
    return user


def get_user_list(page=1, per_page=20, search=None):
    query = User.query
    if search:
        query = query.filter(
            (User.name.like(f'%{search}%')) |
            (User.email.like(f'%{search}%'))
        )
    return query.order_by(User.created_at.desc()).paginate(page=page, per_page=per_page)
