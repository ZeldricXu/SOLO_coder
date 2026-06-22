import pytest
from datetime import datetime, date, timedelta
from unittest.mock import patch, MagicMock

from app.api.reports import (
    validate_status_transition,
    REPORT_STATUS_FLOW,
    REPORT_STATUS_HUMAN,
    _calc_word_count,
    _validate_required_fields,
)
from app.core.utils import is_within_deadline, get_week_key
from tests.conftest import (
    _create_user, _create_team, _create_template_with_fields,
    _create_report, _auth_headers,
)


WEEK_KEY = "2026-W25"
MONDAY = date(2026, 6, 22)
FRIDAY = date(2026, 6, 26)


class TestStatusTransition:
    def test_valid_flow_not_started_to_notified(self):
        validate_status_transition("not_started", "notified")

    def test_valid_flow_notified_to_reminded(self):
        validate_status_transition("notified", "reminded")

    def test_valid_flow_notified_to_submitted(self):
        validate_status_transition("notified", "submitted")

    def test_valid_flow_reminded_to_submitted(self):
        validate_status_transition("reminded", "submitted")

    def test_valid_flow_reminded_to_reminded(self):
        validate_status_transition("reminded", "reminded")

    def test_valid_flow_draft_to_submitted(self):
        validate_status_transition("draft", "submitted")

    def test_valid_flow_submitted_to_draft(self):
        validate_status_transition("submitted", "draft")

    def test_valid_flow_submitted_to_summarized(self):
        validate_status_transition("submitted", "summarized")

    def test_valid_flow_same_status_noop(self):
        validate_status_transition("draft", "draft")
        validate_status_transition("submitted", "submitted")

    def test_invalid_flow_not_started_to_submitted(self):
        from fastapi import HTTPException
        with pytest.raises(HTTPException) as exc_info:
            validate_status_transition("not_started", "submitted")
        assert "非法状态流转" in exc_info.value.detail
        assert "未开始" in exc_info.value.detail

    def test_invalid_flow_submitted_to_notified(self):
        from fastapi import HTTPException
        with pytest.raises(HTTPException) as exc_info:
            validate_status_transition("submitted", "notified")
        assert "非法状态流转" in exc_info.value.detail

    def test_invalid_flow_summarized_to_anything(self):
        from fastapi import HTTPException
        for target in ["draft", "submitted", "notified", "reminded"]:
            with pytest.raises(Exception):
                validate_status_transition("summarized", target)

    def test_invalid_flow_not_started_to_draft(self):
        from fastapi import HTTPException
        with pytest.raises(HTTPException):
            validate_status_transition("not_started", "draft")

    def test_all_defined_states_have_transitions(self):
        for state in REPORT_STATUS_HUMAN:
            assert state in REPORT_STATUS_FLOW

    def test_full_lifecycle_not_started_to_summarized(self):
        transitions = [
            ("not_started", "notified"),
            ("notified", "reminded"),
            ("reminded", "submitted"),
            ("submitted", "summarized"),
        ]
        for current, target in transitions:
            validate_status_transition(current, target)


class TestDeadlineLogic:
    def test_is_within_deadline_before(self):
        deadline = datetime(2026, 6, 26, 18, 0, 0)
        now = datetime(2026, 6, 26, 10, 0, 0)
        assert is_within_deadline(deadline, now) is True

    def test_is_within_deadline_exactly(self):
        deadline = datetime(2026, 6, 26, 18, 0, 0)
        now = datetime(2026, 6, 26, 18, 0, 0)
        assert is_within_deadline(deadline, now) is True

    def test_is_within_deadline_after(self):
        deadline = datetime(2026, 6, 26, 18, 0, 0)
        now = datetime(2026, 6, 26, 19, 0, 0)
        assert is_within_deadline(deadline, now) is False

    def test_different_team_deadlines(self, db):
        team_early = _create_team(db, "早截止团队", 3, 17, 30)
        team_late = _create_team(db, "晚截止团队", 4, 18, 0)
        db.commit()

        early_deadline = datetime(2026, 6, 24, 17, 30, 0)
        late_deadline = datetime(2026, 6, 25, 18, 0, 0)

        now_thu_afternoon = datetime(2026, 6, 25, 15, 0, 0)
        assert is_within_deadline(early_deadline, now_thu_afternoon) is False
        assert is_within_deadline(late_deadline, now_thu_afternoon) is True

    def test_team_deadline_day_field(self, db):
        team_fri = _create_team(db, "周五截止", 4, 18, 0)
        team_thu = _create_team(db, "周四截止", 3, 17, 30)
        team_wed = _create_team(db, "周三截止", 2, 12, 0)
        db.commit()

        assert team_fri.deadline_day == 4
        assert team_thu.deadline_day == 3
        assert team_wed.deadline_day == 2


class TestProxySubmission:
    def test_proxy_submit_creates_report_with_proxy_id(self, db):
        admin = _create_user(db, "admin", "管理员", "super_admin")
        member = _create_user(db, "member1", "成员甲", "user")
        tpl, ver, fields = _create_template_with_fields(db, admin.id)
        team = _create_team(db, "测试组", template_id=tpl.id, leader_id=admin.id)
        member.team_id = team.id
        db.commit()

        content = {
            "week_achievement": "完成了登录模块",
            "next_plan": "开发注册页面",
            "risk_block": "无",
        }
        report = _create_report(
            db,
            submitter_id=member.id,
            week_key=WEEK_KEY,
            content=content,
            status="submitted",
            template_id=tpl.id,
            template_version_id=ver.id,
            proxy_submitter_id=admin.id,
        )
        db.commit()

        assert report.submitter_id == member.id
        assert report.proxy_submitter_id == admin.id
        assert report.status == "submitted"
        assert report.content["week_achievement"] == "完成了登录模块"

    def test_proxy_submit_records_actual_operator(self, db):
        leader = _create_user(db, "leader1", "组长", "user")
        member = _create_user(db, "member1", "成员甲", "user")
        tpl, ver, _ = _create_template_with_fields(db, leader.id)
        team = _create_team(db, "测试组", template_id=tpl.id, leader_id=leader.id)
        member.team_id = team.id
        leader.team_id = team.id
        db.commit()

        content = {
            "week_achievement": "休假中，由组长代填",
            "next_plan": "下周回来继续开发",
            "risk_block": "无",
        }
        report = _create_report(
            db,
            submitter_id=member.id,
            week_key=WEEK_KEY,
            content=content,
            status="submitted",
            template_id=tpl.id,
            template_version_id=ver.id,
            proxy_submitter_id=leader.id,
        )
        db.commit()

        fetched = db.query(models.WeeklyReport).filter(
            models.WeeklyReport.id == report.id
        ).first()
        assert fetched.proxy_submitter_id == leader.id
        assert fetched.submitter_id == member.id

    def test_duplicate_submit_same_week_same_user(self, db):
        from fastapi import HTTPException

        member = _create_user(db, "member1", "成员甲", "user")
        tpl, ver, _ = _create_template_with_fields(db, member.id)
        db.commit()

        content1 = {"week_achievement": "第一版", "next_plan": "继续", "risk_block": "无"}
        report1 = _create_report(
            db, member.id, WEEK_KEY, content1, "submitted",
            tpl.id, ver.id,
        )
        db.commit()

        existing = db.query(models.WeeklyReport).filter(
            models.WeeklyReport.submitter_id == member.id,
            models.WeeklyReport.week_key == WEEK_KEY,
        ).first()
        assert existing is not None
        assert existing.content["week_achievement"] == "第一版"

        existing.content = {
            "week_achievement": "第二版（更新）",
            "next_plan": "继续",
            "risk_block": "无",
        }
        existing.word_count = _calc_word_count(existing.content)
        db.commit()

        assert existing.content["week_achievement"] == "第二版（更新）"


class TestWordCount:
    def test_calc_word_count_basic(self):
        content = {"key1": "Hello world", "key2": "你好世界"}
        count = _calc_word_count(content)
        assert count > 0

    def test_calc_word_count_strips_markdown(self):
        content = {"key1": "## 标题\n- 列表项1\n- 列表项2"}
        count = _calc_word_count(content)
        assert count > 0

    def test_calc_word_count_empty(self):
        assert _calc_word_count({}) == 0
        assert _calc_word_count({"k": ""}) == 0


class TestRequiredFieldValidation:
    def test_missing_required_fields(self):
        fields = [
            {"field_key": "week_achievement", "field_name": "本周完成", "is_required": True},
            {"field_key": "next_plan", "field_name": "下周计划", "is_required": True},
            {"field_key": "risk_block", "field_name": "风险", "is_required": False},
        ]
        content = {"week_achievement": "做了一些事"}
        missing = _validate_required_fields(content, fields)
        assert "下周计划" in missing

    def test_all_required_filled(self):
        fields = [
            {"field_key": "week_achievement", "field_name": "本周完成", "is_required": True},
            {"field_key": "next_plan", "field_name": "下周计划", "is_required": True},
        ]
        content = {"week_achievement": "做了一些事", "next_plan": "继续做"}
        missing = _validate_required_fields(content, fields)
        assert len(missing) == 0

    def test_required_field_whitespace_only(self):
        fields = [
            {"field_key": "week_achievement", "field_name": "本周完成", "is_required": True},
        ]
        content = {"week_achievement": "   "}
        missing = _validate_required_fields(content, fields)
        assert "本周完成" in missing

    def test_optional_field_not_filled_ok(self):
        fields = [
            {"field_key": "risk_block", "field_name": "风险", "is_required": False},
        ]
        content = {}
        missing = _validate_required_fields(content, fields)
        assert len(missing) == 0


from app.models import models
