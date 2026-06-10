from datetime import datetime
from app import db
from app.models import ShareLink, Dashboard, User


def create_share_link(dashboard_id, user_id, permission='view', expires_hours=24,
                      password=None, max_views=None):
    dashboard = Dashboard.query.get(dashboard_id)
    if not dashboard:
        raise ValueError('看板不存在')

    share = ShareLink.create(
        dashboard_id=dashboard_id,
        user_id=user_id,
        permission=permission,
        expires_hours=expires_hours,
        password=password,
        max_views=max_views
    )
    return share


def get_share_link(token):
    return ShareLink.query.filter_by(token=token).first()


def get_share_links(dashboard_id=None, user_id=None, page=1, per_page=20):
    query = ShareLink.query
    if dashboard_id:
        query = query.filter_by(dashboard_id=dashboard_id)
    if user_id:
        query = query.filter_by(user_id=user_id)
    return query.order_by(ShareLink.created_at.desc()).paginate(page=page, per_page=per_page)


def revoke_share_link(share_id, user_id):
    share = ShareLink.query.get(share_id)
    if not share:
        raise ValueError('分享链接不存在')
    if share.user_id != user_id:
        raise ValueError('无权限撤销此分享链接')

    share.revoke()
    db.session.commit()
    return share


def access_share_link(token, password=None):
    share = ShareLink.query.filter_by(token=token).first()
    if not share:
        return None, '分享链接不存在'

    if not share.is_valid():
        return None, '分享链接已过期或已被撤销'

    if share.password_hash and not share.check_password(password or ''):
        return None, '密码不正确'

    share.increment_view()
    db.session.commit()

    return share, None


def get_dashboard_by_share_token(token):
    share = ShareLink.query.filter_by(token=token).first()
    if share and share.is_valid():
        return share.dashboard
    return None
