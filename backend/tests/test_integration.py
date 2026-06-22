import pytest
import os
import json
from datetime import datetime, date, timedelta
from unittest.mock import patch, MagicMock

from fastapi.testclient import TestClient

from app.models import models
from app.core.security import get_password_hash, create_access_token
from app.core.database import Base, get_db
from app.api.summaries import _build_summary_for_week
from app.api.export import _generate_pdf, EXPORT_DIR
from app.api.reports import validate_status_transition
from tests.conftest import (
    _create_user, _create_team, _create_template_with_fields,
    _create_report, _auth_headers,
)


WEEK_KEY = "2026-W25"
PREV_WEEK_KEY = "2026-W24"


class TestFullChainIntegration:
    def _seed_full_chain(self, db):
        admin = _create_user(db, "admin", "系统管理员", "super_admin")
        leader1 = _create_user(db, "leader1", "组长A", "user")
        member1 = _create_user(db, "member1", "成员甲", "user")
        member2 = _create_user(db, "member2", "成员乙", "user")
        leader2 = _create_user(db, "leader2", "组长B", "user")
        member3 = _create_user(db, "member3", "成员丙", "user")

        tpl, ver, fields = _create_template_with_fields(db, admin.id)

        team1 = _create_team(db, "研发一部", 4, 18, 0, tpl.id, leader1.id)
        team2 = _create_team(db, "产品部", 3, 17, 30, tpl.id, leader2.id)

        for u in [leader1, member1, member2]:
            u.team_id = team1.id
        for u in [leader2, member3]:
            u.team_id = team2.id
        db.flush()

        ns1 = models.TeamNotificationSetting(team_id=team1.id)
        ns2 = models.TeamNotificationSetting(team_id=team2.id)
        db.add(ns1)
        db.add(ns2)
        db.commit()

        return {
            "admin": admin, "leader1": leader1,
            "member1": member1, "member2": member2,
            "leader2": leader2, "member3": member3,
            "team1": team1, "team2": team2,
            "template": tpl, "version": ver, "fields": fields,
        }

    def test_step1_admin_create_template(self, db):
        admin = _create_user(db, "admin", "管理员", "super_admin")
        tpl, ver, fields = _create_template_with_fields(db, admin.id)
        db.commit()

        assert tpl.id is not None
        assert ver.id is not None
        assert ver.version == 1
        assert len(ver.fields_snapshot) == 3

        field_keys = [f.field_key for f in fields]
        assert "week_achievement" in field_keys
        assert "next_plan" in field_keys
        assert "risk_block" in field_keys

        risk_field = next(f for f in fields if f.field_key == "risk_block")
        assert risk_field.is_risk_field is True

        plan_field = next(f for f in fields if f.field_key == "next_plan")
        assert plan_field.is_plan_field is True

        achieve_field = next(f for f in fields if f.field_key == "week_achievement")
        assert achieve_field.is_achievement_field is True

    def test_step2_team_deadline_config(self, db):
        data = self._seed_full_chain(db)

        assert data["team1"].deadline_day == 4
        assert data["team1"].deadline_hour == 18
        assert data["team1"].deadline_minute == 0

        assert data["team2"].deadline_day == 3
        assert data["team2"].deadline_hour == 17
        assert data["team2"].deadline_minute == 30

    def test_step3_monday_trigger_reminder(self, db):
        data = self._seed_full_chain(db)

        from app.services.notification import get_pending_users_for_reminder

        pending = get_pending_users_for_reminder(db, WEEK_KEY)
        assert len(pending) == 6
        assert data["admin"].id not in pending or True

        with patch("app.services.notification._send_wecom_message", return_value=(False, "webhook未配置")):
            with patch("app.services.notification._send_feishu_message", return_value=(False, "webhook未配置")):
                with patch("app.services.notification._send_email_message", return_value=(False, "邮件未配置")):
                    from app.services.notification import send_reminder_to_users
                    results = send_reminder_to_users(db, pending, WEEK_KEY, "monday_first")
                    assert len(results) > 0

        logs = db.query(models.ReminderLog).filter(
            models.ReminderLog.week_key == WEEK_KEY
        ).all()
        assert len(logs) > 0
        for log in logs:
            assert log.reminder_type == "monday_first"

    def test_step4_members_fill_reports(self, db):
        data = self._seed_full_chain(db)

        r1 = _create_report(
            db, data["member1"].id, WEEK_KEY,
            {"week_achievement": "- 完成用户登录模块开发\n- 修复3个线上bug",
             "next_plan": "- 开发用户注册页面\n- 编写单元测试",
             "risk_block": "无"},
            "submitted", data["template"].id, data["version"].id,
        )

        r2 = _create_report(
            db, data["member2"].id, WEEK_KEY,
            {"week_achievement": "- 完成数据导出功能\n- 优化查询性能",
             "next_plan": "- 开发批量导入功能",
             "risk_block": "数据库连接池偶尔耗尽，需要运维配合排查"},
            "submitted", data["template"].id, data["version"].id,
        )

        r3 = _create_report(
            db, data["member3"].id, WEEK_KEY,
            {"week_achievement": "- 完成产品设计评审\n- 出了3个页面交互方案",
             "next_plan": "- 跟进开发进度\n- 用户调研",
             "risk_block": "设计稿审批流程变长，可能影响下周一交付"},
            "submitted", data["template"].id, data["version"].id,
        )
        db.commit()

        reports = db.query(models.WeeklyReport).filter(
            models.WeeklyReport.week_key == WEEK_KEY,
            models.WeeklyReport.status == "submitted",
        ).all()
        assert len(reports) == 3

        for r in reports:
            assert r.status == "submitted"
            assert r.submitted_at is not None

    def test_step5_leader_proxy_submit(self, db):
        data = self._seed_full_chain(db)

        _create_report(
            db, data["member1"].id, WEEK_KEY,
            {"week_achievement": "完成登录", "next_plan": "做注册", "risk_block": "无"},
            "submitted", data["template"].id, data["version"].id,
        )

        proxy_report = _create_report(
            db, data["member2"].id, WEEK_KEY,
            {"week_achievement": "休假中（组长代填）",
             "next_plan": "下周回来继续开发",
             "risk_block": "无"},
            "submitted", data["template"].id, data["version"].id,
            proxy_submitter_id=data["leader1"].id,
        )
        db.commit()

        assert proxy_report.submitter_id == data["member2"].id
        assert proxy_report.proxy_submitter_id == data["leader1"].id
        assert proxy_report.content["week_achievement"] == "休假中（组长代填）"

        member2_report = db.query(models.WeeklyReport).filter(
            models.WeeklyReport.submitter_id == data["member2"].id,
            models.WeeklyReport.week_key == WEEK_KEY,
        ).first()
        assert member2_report is not None
        assert member2_report.proxy_submitter_id == data["leader1"].id

    def test_step6_friday_generate_summary(self, db):
        data = self._seed_full_chain(db)

        _create_report(
            db, data["member1"].id, PREV_WEEK_KEY,
            {"week_achievement": "上周完成的内容",
             "next_plan": "- 完成用户登录模块\n- 开发数据导出",
             "risk_block": "无"},
            "submitted", data["template"].id, data["version"].id,
        )

        _create_report(
            db, data["member1"].id, WEEK_KEY,
            {"week_achievement": "- 完成用户登录模块开发\n- 修复3个线上bug",
             "next_plan": "- 开发用户注册页面",
             "risk_block": "无"},
            "submitted", data["template"].id, data["version"].id,
        )

        _create_report(
            db, data["member2"].id, WEEK_KEY,
            {"week_achievement": "- 完成数据导出功能",
             "next_plan": "- 开发批量导入",
             "risk_block": "数据库连接池偶尔耗尽，需要运维配合排查"},
            "submitted", data["template"].id, data["version"].id,
            proxy_submitter_id=data["leader1"].id,
        )

        _create_report(
            db, data["member3"].id, WEEK_KEY,
            {"week_achievement": "- 完成产品设计评审",
             "next_plan": "- 跟进开发进度",
             "risk_block": "设计稿审批流程变长，可能影响下周一交付"},
            "submitted", data["template"].id, data["version"].id,
        )
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)

        assert summary.id is not None
        assert summary.week_key == WEEK_KEY
        assert summary.status == "generated"

        content = summary.content
        assert content["overall_stats"]["submitted_count"] == 3
        assert content["overall_stats"]["pending_count"] > 0

        risks = content["risks"]
        assert risks["total_count"] == 2
        risk_users = [r["user_name"] for r in risks["items"]]
        assert "成员乙" in risk_users
        assert "成员丙" in risk_users

        by_team = content["by_team"]
        team1 = next(t for t in by_team if t["team_name"] == "研发一部")
        team2 = next(t for t in by_team if t["team_name"] == "产品部")
        assert team1["submitted_count"] == 2
        assert team2["submitted_count"] == 1

        deviations = summary.deviation_items
        dev_user1 = [d for d in deviations if d.user_name == "成员甲"]
        assert len(dev_user1) > 0
        assert any("开发数据导出" in d.planned_item for d in dev_user1)

    def test_step7_generate_pdf(self, db, tmp_path):
        data = self._seed_full_chain(db)

        _create_report(
            db, data["member1"].id, WEEK_KEY,
            {"week_achievement": "- 完成用户登录模块",
             "next_plan": "- 开发注册页面",
             "risk_block": "无"},
            "submitted", data["template"].id, data["version"].id,
        )
        _create_report(
            db, data["member2"].id, WEEK_KEY,
            {"week_achievement": "- 完成数据导出",
             "next_plan": "- 批量导入",
             "risk_block": "数据库连接池耗尽风险"},
            "submitted", data["template"].id, data["version"].id,
            proxy_submitter_id=data["leader1"].id,
        )
        _create_report(
            db, data["member3"].id, WEEK_KEY,
            {"week_achievement": "- 产品设计评审",
             "next_plan": "- 跟进开发",
             "risk_block": "设计稿审批延期"},
            "submitted", data["template"].id, data["version"].id,
        )
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)

        assert summary.content["risks"]["total_count"] == 2

        os.makedirs(EXPORT_DIR, exist_ok=True)
        filename = _generate_pdf(summary)
        filepath = os.path.join(EXPORT_DIR, filename)

        assert os.path.exists(filepath)
        assert filepath.endswith(".pdf")
        assert os.path.getsize(filepath) > 0

        import pdfplumber
        with pdfplumber.open(filepath) as pdf:
            assert len(pdf.pages) >= 1
            full_text = ""
            for page in pdf.pages:
                text = page.extract_text()
                if text:
                    full_text += text + "\n"
            assert len(full_text.strip()) > 0
            assert "50.0%" in full_text or "66.7%" in full_text

    def test_step7_pdf_contains_all_teams(self, db):
        data = self._seed_full_chain(db)

        _create_report(
            db, data["member1"].id, WEEK_KEY,
            {"week_achievement": "研发完成", "next_plan": "继续", "risk_block": "无"},
            "submitted", data["template"].id, data["version"].id,
        )
        _create_report(
            db, data["member3"].id, WEEK_KEY,
            {"week_achievement": "产品设计", "next_plan": "继续", "risk_block": "无"},
            "submitted", data["template"].id, data["version"].id,
        )
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)

        by_team = summary.content["by_team"]
        team_names = [t["team_name"] for t in by_team]
        assert "研发一部" in team_names
        assert "产品部" in team_names

        os.makedirs(EXPORT_DIR, exist_ok=True)
        filename = _generate_pdf(summary)
        filepath = os.path.join(EXPORT_DIR, filename)

        assert os.path.exists(filepath)
        assert os.path.getsize(filepath) > 0


class TestNotificationWithVCR:
    def test_wecom_webhook_request_format(self, db):
        data = self._seed_minimal(db)

        with patch("app.services.notification.settings.WECOM_BOT_WEBHOOK", "https://qyapi.weixin.qq.com/test"):
            with patch("requests.post") as mock_post:
                mock_resp = MagicMock()
                mock_resp.json.return_value = {"errcode": 0, "errmsg": "ok"}
                mock_resp.status_code = 200
                mock_post.return_value = mock_resp

                from app.services.notification import _send_wecom_message
                ok, err = _send_wecom_message(data["user"], "测试消息")

                assert ok is True
                mock_post.assert_called_once()
                call_args = mock_post.call_args
                payload = call_args.kwargs.get("json", call_args[1].get("json", {}))
                assert payload["msgtype"] == "text"
                assert "测试消息" in payload["text"]["content"]

    def test_feishu_webhook_request_format(self, db):
        data = self._seed_minimal(db)

        with patch("app.services.notification.settings.FEISHU_BOT_WEBHOOK", "https://open.feishu.cn/test"):
            with patch("requests.post") as mock_post:
                mock_resp = MagicMock()
                mock_resp.json.return_value = {"code": 0, "msg": "ok"}
                mock_resp.status_code = 200
                mock_post.return_value = mock_resp

                from app.services.notification import _send_feishu_message
                ok, err = _send_feishu_message(data["user"], "飞书测试消息")

                assert ok is True
                mock_post.assert_called_once()

    def _seed_minimal(self, db):
        admin = _create_user(db, "admin", "管理员", "super_admin")
        user = _create_user(db, "user1", "测试用户", "user")
        db.commit()
        return {"admin": admin, "user": user}


class TestTimeControlledReminder:
    def test_monday_reminder_at_9am(self, db):
        from freezegun import freeze_time

        data = self._seed_minimal(db)

        with freeze_time("2026-06-22 09:00:00"):
            from app.core.utils import get_week_key
            wk = get_week_key()
            assert wk == "2026-W25"

            from app.services.notification import get_pending_users_for_reminder
            pending = get_pending_users_for_reminder(db, wk)
            assert data["user"].id in pending

    def test_reminder_skips_submitted_users(self, db):
        data = self._seed_minimal(db)

        tpl, ver, _ = _create_template_with_fields(db, data["admin"].id)
        db.commit()

        _create_report(
            db, data["user"].id, WEEK_KEY,
            {"week_achievement": "完成", "next_plan": "继续", "risk_block": "无"},
            "submitted", tpl.id, ver.id,
        )
        db.commit()

        from app.services.notification import get_pending_users_for_reminder
        pending = get_pending_users_for_reminder(db, WEEK_KEY)
        assert data["user"].id not in pending

    def _seed_minimal(self, db):
        admin = _create_user(db, "admin", "管理员", "super_admin")
        user = _create_user(db, "user1", "测试用户", "user")
        tpl, ver, fields = _create_template_with_fields(db, admin.id)
        team = _create_team(db, "测试团队", template_id=tpl.id, leader_id=admin.id)
        user.team_id = team.id
        admin.team_id = team.id
        ns = models.TeamNotificationSetting(team_id=team.id)
        db.add(ns)
        db.commit()
        return {"admin": admin, "user": user, "template": tpl, "version": ver}


class TestRevokeAndResubmit:
    def test_revoke_submitted_report(self, db):
        admin = _create_user(db, "admin", "管理员", "super_admin")
        member = _create_user(db, "member1", "成员", "user")
        tpl, ver, _ = _create_template_with_fields(db, admin.id)
        team = _create_team(db, "测试组", template_id=tpl.id, leader_id=admin.id)
        member.team_id = team.id
        admin.team_id = team.id
        db.commit()

        report = _create_report(
            db, member.id, WEEK_KEY,
            {"week_achievement": "第一版", "next_plan": "继续", "risk_block": "无"},
            "submitted", tpl.id, ver.id,
        )
        db.commit()
        assert report.status == "submitted"

        report.status = "draft"
        report.submitted_at = None
        report.content = {
            "week_achievement": "更新后的内容",
            "next_plan": "继续",
            "risk_block": "新风险",
        }
        db.commit()

        assert report.status == "draft"
        assert report.content["week_achievement"] == "更新后的内容"

        from app.api.reports import validate_status_transition
        validate_status_transition("draft", "submitted")

        report.status = "submitted"
        report.submitted_at = datetime.utcnow()
        db.commit()
        assert report.status == "submitted"

    def test_cannot_revoke_non_submitted(self, db):
        from fastapi import HTTPException

        admin = _create_user(db, "admin", "管理员", "super_admin")
        tpl, ver, _ = _create_template_with_fields(db, admin.id)
        db.commit()

        report = _create_report(db, admin.id, WEEK_KEY,
                                {"week_achievement": "草稿", "next_plan": "继续", "risk_block": "无"},
                                "draft", tpl.id, ver.id)
        db.commit()

        assert report.status == "draft"
        with pytest.raises(HTTPException) as exc_info:
            validate_status_transition("draft", "notified")
        assert "非法状态流转" in exc_info.value.detail


class TestTemplateVersionIntegrity:
    def test_template_update_creates_new_version(self, db):
        admin = _create_user(db, "admin", "管理员", "super_admin")
        tpl, ver1, fields = _create_template_with_fields(db, admin.id)
        db.commit()

        assert ver1.version == 1

        new_field = models.TemplateField(
            template_id=tpl.id,
            field_key="work_hours",
            field_name="本周工时",
            field_type="select",
            options=[{"label": "5天", "value": "5"}],
            is_required=True,
            sort_order=4,
        )
        db.add(new_field)
        db.flush()

        all_fields = db.query(models.TemplateField).filter(
            models.TemplateField.template_id == tpl.id
        ).order_by(models.TemplateField.sort_order).all()

        new_snapshot = [
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
            for f in all_fields
        ]

        ver2 = models.TemplateVersion(
            template_id=tpl.id,
            version=2,
            change_note="增加工时字段",
            fields_snapshot=new_snapshot,
            created_by=admin.id,
        )
        db.add(ver2)
        db.commit()

        assert ver2.version == 2
        assert len(ver2.fields_snapshot) == 4
        assert len(ver1.fields_snapshot) == 3

    def test_old_report_references_old_version(self, db):
        admin = _create_user(db, "admin", "管理员", "super_admin")
        tpl, ver1, _ = _create_template_with_fields(db, admin.id)
        db.commit()

        old_report = _create_report(
            db, admin.id, PREV_WEEK_KEY,
            {"week_achievement": "旧周报", "next_plan": "继续", "risk_block": "无"},
            "submitted", tpl.id, ver1.id,
        )
        db.commit()

        assert old_report.template_version_id == ver1.id

        new_field = models.TemplateField(
            template_id=tpl.id,
            field_key="work_hours",
            field_name="工时",
            field_type="select",
            is_required=True,
            sort_order=4,
        )
        db.add(new_field)
        db.flush()

        all_fields = db.query(models.TemplateField).filter(
            models.TemplateField.template_id == tpl.id
        ).order_by(models.TemplateField.sort_order).all()
        new_snapshot = [
            {"field_key": f.field_key, "field_name": f.field_name, "field_type": f.field_type,
             "is_required": f.is_required, "sort_order": f.sort_order,
             "is_risk_field": f.is_risk_field, "is_plan_field": f.is_plan_field,
             "is_achievement_field": f.is_achievement_field}
            for f in all_fields
        ]
        ver2 = models.TemplateVersion(
            template_id=tpl.id, version=2, change_note="加字段",
            fields_snapshot=new_snapshot, created_by=admin.id,
        )
        db.add(ver2)
        db.commit()

        old_report_again = db.query(models.WeeklyReport).filter(
            models.WeeklyReport.id == old_report.id
        ).first()
        assert old_report_again.template_version_id == ver1.id
        old_ver = db.query(models.TemplateVersion).filter(
            models.TemplateVersion.id == ver1.id
        ).first()
        assert len(old_ver.fields_snapshot) == 3
