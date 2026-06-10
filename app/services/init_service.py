from app import db
from app.models import Role, User, Team, TeamMember


def init_database():
    db.create_all()
    init_roles()
    init_default_team()


def init_roles():
    roles = [
        {'name': 'admin', 'description': '系统管理员，拥有所有权限'},
        {'name': 'editor', 'description': '编辑者，可以创建和编辑看板'},
        {'name': 'viewer', 'description': '查看者，只能查看看板'},
    ]
    for role_data in roles:
        role = Role.query.filter_by(name=role_data['name']).first()
        if not role:
            role = Role(**role_data)
            db.session.add(role)
    db.session.commit()


def init_default_team():
    team = Team.query.filter_by(name='默认团队').first()
    if not team:
        team = Team(
            name='默认团队',
            description='系统默认团队，所有用户都会加入此团队',
            created_by=None
        )
        db.session.add(team)
        db.session.commit()


def add_user_to_default_team(user):
    team = Team.query.filter_by(name='默认团队').first()
    if team:
        existing = TeamMember.query.filter_by(team_id=team.id, user_id=user.id).first()
        if not existing:
            member = TeamMember(
                team_id=team.id,
                user_id=user.id,
                role='member'
            )
            db.session.add(member)
            db.session.commit()
