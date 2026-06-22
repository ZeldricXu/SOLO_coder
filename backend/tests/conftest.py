import pytest
import os
import tempfile
from datetime import datetime, date, timedelta
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from fastapi.testclient import TestClient
from unittest.mock import MagicMock, patch

from app.core.database import Base
from app.core.security import get_password_hash, create_access_token
from app.models import models
from app.core.utils import get_week_range, get_week_key


SQLALCHEMY_DATABASE_URL = "sqlite://"

engine_test = create_engine(
    SQLALCHEMY_DATABASE_URL,
    connect_args={"check_same_thread": False},
    echo=False,
)
TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine_test)


@pytest.fixture(scope="function")
def db_engine():
    Base.metadata.create_all(bind=engine_test)
    yield engine_test
    Base.metadata.drop_all(bind=engine_test)


@pytest.fixture(scope="function")
def db_session(db_engine):
    session = TestingSessionLocal()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture(scope="function")
def db(db_session):
    yield db_session


@pytest.fixture(scope="function")
def test_client(db_session):
    from main import app
    from app.core.database import get_db

    def _override_get_db():
        try:
            yield db_session
        finally:
            pass

    app.dependency_overrides[get_db] = _override_get_db

    with TestClient(app) as client:
        yield client

    app.dependency_overrides.clear()


@pytest.fixture(scope="function")
def app_client(db_session):
    from main import app
    from app.core.database import get_db

    def _override_get_db():
        try:
            yield db_session
        finally:
            pass

    app.dependency_overrides[get_db] = _override_get_db

    with TestClient(app) as client:
        yield client

    app.dependency_overrides.clear()


def _create_user(db, username="testuser", full_name="测试用户", role="user", team_id=None):
    u = models.User(
        username=username,
        email=f"{username}@test.com",
        full_name=full_name,
        hashed_password=get_password_hash("123456"),
        role=role,
        team_id=team_id,
        is_active=True,
    )
    db.add(u)
    db.flush()
    return u


def _create_team(db, name="测试团队", deadline_day=4, deadline_hour=18, deadline_minute=0, template_id=None, leader_id=None):
    t = models.Team(
        name=name,
        deadline_day=deadline_day,
        deadline_hour=deadline_hour,
        deadline_minute=deadline_minute,
        template_id=template_id,
        leader_id=leader_id,
    )
    db.add(t)
    db.flush()
    return t


def _create_template_with_fields(db, created_by=1):
    tpl = models.Template(
        name="测试模板",
        description="测试用",
        is_default=True,
        created_by=created_by,
        is_active=True,
    )
    db.add(tpl)
    db.flush()

    fields = [
        models.TemplateField(
            template_id=tpl.id,
            field_key="week_achievement",
            field_name="本周完成工作",
            field_type="markdown",
            is_required=True,
            sort_order=1,
            is_achievement_field=True,
        ),
        models.TemplateField(
            template_id=tpl.id,
            field_key="next_plan",
            field_name="下周工作计划",
            field_type="markdown",
            is_required=True,
            sort_order=2,
            is_plan_field=True,
        ),
        models.TemplateField(
            template_id=tpl.id,
            field_key="risk_block",
            field_name="风险与阻塞",
            field_type="markdown",
            is_required=False,
            sort_order=3,
            is_risk_field=True,
        ),
    ]
    for f in fields:
        db.add(f)
    db.flush()

    snapshot = [
        {
            "field_key": f.field_key,
            "field_name": f.field_name,
            "field_type": f.field_type,
            "options": f.options,
            "is_required": f.is_required,
            "sort_order": f.sort_order,
            "is_risk_field": f.is_risk_field,
            "is_plan_field": f.is_plan_field,
            "is_achievement_field": f.is_achievement_field,
        }
        for f in fields
    ]
    ver = models.TemplateVersion(
        template_id=tpl.id,
        version=1,
        change_note="初始版本",
        fields_snapshot=snapshot,
        created_by=created_by,
    )
    db.add(ver)
    db.flush()
    return tpl, ver, fields


def _create_report(db, submitter_id, week_key, content, status="draft",
                   template_id=None, template_version_id=None, proxy_submitter_id=None):
    try:
        from datetime import date, timedelta
        year_part, week_part = week_key.split("-W")
        jan4 = date(int(year_part), 1, 4)
        monday = jan4 - timedelta(days=jan4.weekday()) + timedelta(weeks=int(week_part) - 1)
        friday = monday + timedelta(days=4)
    except Exception:
        monday, friday = get_week_range()

    report = models.WeeklyReport(
        submitter_id=submitter_id,
        proxy_submitter_id=proxy_submitter_id,
        template_id=template_id,
        template_version_id=template_version_id,
        week_key=week_key,
        week_start=monday,
        week_end=friday,
        content=content,
        word_count=sum(len(v) for v in content.values() if isinstance(v, str)),
        status=status,
        submitted_at=datetime.utcnow() if status == "submitted" else None,
    )
    db.add(report)
    db.flush()
    return report


def _auth_headers(user):
    token = create_access_token(data={"sub": user.username})
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def seed_basic_data(db):
    admin = _create_user(db, "admin", "管理员", "super_admin")
    leader1 = _create_user(db, "leader1", "组长A", "user")
    member1 = _create_user(db, "member1", "成员甲", "user")
    member2 = _create_user(db, "member2", "成员乙", "user")
    leader2 = _create_user(db, "leader2", "组长B", "user")
    member3 = _create_user(db, "member3", "成员丙", "user")
    member4 = _create_user(db, "member4", "成员丁", "user")

    tpl, ver, fields = _create_template_with_fields(db, admin.id)

    team1 = _create_team(db, "研发一部", 4, 18, 0, tpl.id, leader1.id)
    team2 = _create_team(db, "产品部", 3, 17, 30, tpl.id, leader2.id)

    for u in [leader1, member1, member2]:
        u.team_id = team1.id
    for u in [leader2, member3, member4]:
        u.team_id = team2.id
    db.flush()

    ns1 = models.TeamNotificationSetting(team_id=team1.id)
    ns2 = models.TeamNotificationSetting(team_id=team2.id)
    db.add(ns1)
    db.add(ns2)
    db.commit()

    return {
        "admin": admin,
        "leader1": leader1,
        "member1": member1,
        "member2": member2,
        "leader2": leader2,
        "member3": member3,
        "member4": member4,
        "team1": team1,
        "team2": team2,
        "template": tpl,
        "template_version": ver,
        "fields": fields,
    }


@pytest.fixture
def auth_headers():
    def _auth_headers(user):
        token = create_access_token(data={"sub": user.username})
        return {"Authorization": f"Bearer {token}"}
    return _auth_headers


@pytest.fixture(scope="function")
def test_data(db_session):
    admin = _create_user(db_session, "admin", "系统管理员", "super_admin")

    tl_a = _create_user(db_session, "tl_a", "张组", "user")
    user_a1 = _create_user(db_session, "user_a1", "李开发", "user")
    user_a2 = _create_user(db_session, "user_a2", "王开发", "user")
    user_a3 = _create_user(db_session, "user_a3", "赵开发", "user")

    tl_b = _create_user(db_session, "tl_b", "刘组", "user")
    user_b1 = _create_user(db_session, "user_b1", "陈测试", "user")
    user_b2 = _create_user(db_session, "user_b2", "周测试", "user")

    template = models.Template(
        name="标准周报模板",
        description="公司通用周报模板",
        is_default=True,
        created_by=admin.id,
        is_active=True,
    )
    db_session.add(template)
    db_session.flush()

    fields_data = [
        {"field_key": "achievement", "field_name": "本周完成", "field_type": "markdown",
         "is_required": True, "sort_order": 1, "is_achievement_field": True},
        {"field_key": "plan", "field_name": "下周计划", "field_type": "markdown",
         "is_required": True, "sort_order": 2, "is_plan_field": True},
        {"field_key": "risk", "field_name": "本周风险/阻塞", "field_type": "markdown",
         "is_required": False, "sort_order": 3, "is_risk_field": True},
        {"field_key": "project", "field_name": "所属项目", "field_type": "select",
         "options": ["电商平台", "数据中台", "用户增长", "内部工具"],
         "is_required": True, "sort_order": 4},
    ]

    for i, fd in enumerate(fields_data):
        f = models.TemplateField(template_id=template.id, **fd)
        db_session.add(f)
    db_session.flush()

    fields_snapshot = [
        {
            "field_key": fd["field_key"],
            "field_name": fd["field_name"],
            "field_type": fd["field_type"],
            "options": fd.get("options"),
            "is_required": fd["is_required"],
            "sort_order": fd["sort_order"],
            "is_risk_field": fd.get("is_risk_field", False),
            "is_plan_field": fd.get("is_plan_field", False),
            "is_achievement_field": fd.get("is_achievement_field", False),
        }
        for fd in fields_data
    ]

    template_version = models.TemplateVersion(
        template_id=template.id,
        version=1,
        change_note="初始版本",
        fields_snapshot=fields_snapshot,
        created_by=admin.id,
    )
    db_session.add(template_version)
    db_session.flush()

    team_a = models.Team(
        name="研发一组",
        deadline_day=4,
        deadline_hour=18,
        deadline_minute=0,
        template_id=template.id,
        leader_id=tl_a.id,
    )
    db_session.add(team_a)

    team_b = models.Team(
        name="研发二组",
        deadline_day=3,
        deadline_hour=17,
        deadline_minute=0,
        template_id=template.id,
        leader_id=tl_b.id,
    )
    db_session.add(team_b)
    db_session.flush()

    for u in [tl_a, user_a1, user_a2, user_a3]:
        u.team_id = team_a.id
    for u in [tl_b, user_b1, user_b2]:
        u.team_id = team_b.id

    ns_a = models.TeamNotificationSetting(
        team_id=team_a.id,
        notify_wecom_enabled=True,
        wecom_webhook="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-key-a",
    )
    ns_b = models.TeamNotificationSetting(
        team_id=team_b.id,
        notify_wecom_enabled=True,
        wecom_webhook="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-key-b",
    )
    db_session.add(ns_a)
    db_session.add(ns_b)

    db_session.commit()

    return {
        "admin": admin,
        "team_a": team_a,
        "team_b": team_b,
        "tl_a": tl_a,
        "tl_b": tl_b,
        "users_a": [user_a1, user_a2, user_a3],
        "users_b": [user_b1, user_b2],
        "template": template,
        "template_version": template_version,
        "fields_snapshot": fields_snapshot,
        "db": db_session,
    }


def create_test_report(db, user, template_version, week_key=None, status="draft", content=None):
    if week_key is None:
        week_key = get_week_key()

    monday, friday = get_week_range()
    try:
        from datetime import date, timedelta
        year_part, week_part = week_key.split("-W")
        jan4 = date(int(year_part), 1, 4)
        monday = jan4 - timedelta(days=jan4.weekday()) + timedelta(weeks=int(week_part) - 1)
        friday = monday + timedelta(days=4)
    except Exception:
        pass

    if content is None:
        content = {
            "achievement": "- 完成了一些工作\n- 代码优化",
            "plan": "- 下周计划任务",
            "risk": "",
            "project": "电商平台",
        }

    word_count = sum(len(v) for v in content.values() if isinstance(v, str))

    report = models.WeeklyReport(
        submitter_id=user.id,
        template_id=template_version.template_id,
        template_version_id=template_version.id,
        week_key=week_key,
        week_start=monday,
        week_end=friday,
        content=content,
        word_count=word_count,
        status=status,
    )
    db.add(report)
    db.flush()
    return report


def create_reminder_log(db, user, report=None, week_key=None, reminder_type="first_reminder", channel="wecom"):
    if week_key is None:
        week_key = get_week_key()

    log = models.ReminderLog(
        report_id=report.id if report else None,
        user_id=user.id,
        week_key=week_key,
        reminder_type=reminder_type,
        channel=channel,
        status="success",
    )
    db.add(log)
    db.flush()
    return log


@pytest.fixture
def mock_notification(monkeypatch):
    mock_send = MagicMock(return_value=True)
    mock_send_reminder = MagicMock(return_value=True)

    monkeypatch.setattr("app.services.notification.NotificationService.send_reminder", mock_send_reminder)
    monkeypatch.setattr("app.services.notification.NotificationService.send", mock_send)

    return {
        "send": mock_send,
        "send_reminder": mock_send_reminder,
    }


@pytest.fixture
def temp_pdf_dir():
    with tempfile.TemporaryDirectory() as tmpdir:
        yield tmpdir
