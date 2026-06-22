import pytest
from datetime import date, timedelta

from app.api.summaries import (
    _find_field_by_flag,
    _find_field_by_name,
    _split_lines,
    _build_summary_for_week,
)
from app.models import models
from tests.conftest import (
    _create_user, _create_team, _create_template_with_fields, _create_report,
)


WEEK_KEY = "2026-W25"
PREV_WEEK_KEY = "2026-W24"
MONDAY = date(2026, 6, 22)
FRIDAY = date(2026, 6, 26)


class TestFieldDetection:
    def test_find_risk_field_by_flag(self):
        snapshot = [
            {"field_key": "week_achievement", "is_risk_field": False, "is_plan_field": False, "is_achievement_field": True},
            {"field_key": "risk_block", "is_risk_field": True, "is_plan_field": False, "is_achievement_field": False},
        ]
        assert _find_field_by_flag(snapshot, "is_risk_field") == "risk_block"

    def test_find_plan_field_by_flag(self):
        snapshot = [
            {"field_key": "next_plan", "is_risk_field": False, "is_plan_field": True, "is_achievement_field": False},
        ]
        assert _find_field_by_flag(snapshot, "is_plan_field") == "next_plan"

    def test_find_achievement_field_by_flag(self):
        snapshot = [
            {"field_key": "week_achievement", "is_achievement_field": True},
        ]
        assert _find_field_by_flag(snapshot, "is_achievement_field") == "week_achievement"

    def test_find_risk_field_by_name_keywords(self):
        snapshot = [
            {"field_key": "my_risks", "field_name": "本周风险与阻塞"},
        ]
        assert _find_field_by_name(snapshot, ["风险", "阻塞", "risk", "block"]) == "my_risks"

    def test_find_plan_field_by_name_keywords(self):
        snapshot = [
            {"field_key": "future", "field_name": "下周工作计划"},
        ]
        assert _find_field_by_name(snapshot, ["下周计划", "下周", "计划", "plan", "next"]) == "future"

    def test_find_achievement_by_name(self):
        snapshot = [
            {"field_key": "done", "field_name": "本周完成工作"},
        ]
        assert _find_field_by_name(snapshot, ["本周完成", "完成", "成果", "achievement", "done"]) == "done"

    def test_no_matching_field_returns_none(self):
        snapshot = [
            {"field_key": "other", "field_name": "其他"},
        ]
        assert _find_field_by_flag(snapshot, "is_risk_field") is None
        assert _find_field_by_name(snapshot, ["风险"]) is None

    def test_flag_takes_priority_over_name(self):
        snapshot = [
            {"field_key": "custom_risk", "field_name": "自定义风险字段", "is_risk_field": True},
            {"field_key": "named_risk", "field_name": "风险与阻塞", "is_risk_field": False},
        ]
        assert _find_field_by_flag(snapshot, "is_risk_field") == "custom_risk"


class TestSplitLines:
    def test_basic_split(self):
        result = _split_lines("第一行\n第二行\n第三行")
        assert len(result) == 3
        assert result[0] == "第一行"

    def test_strips_whitespace(self):
        result = _split_lines("  行1  \n  行2  ")
        assert result == ["行1", "行2"]

    def test_empty_string(self):
        assert _split_lines("") == []

    def test_none_input(self):
        assert _split_lines(None) == []

    def test_carriage_return(self):
        result = _split_lines("行1\r\n行2\r\n行3")
        assert len(result) == 3

    def test_skips_empty_lines(self):
        result = _split_lines("行1\n\n行3")
        assert len(result) == 2


class TestSummaryGeneration:
    def _setup_team_with_reports(self, db, week_key=WEEK_KEY):
        admin = _create_user(db, "admin", "管理员", "super_admin")
        user1 = _create_user(db, "user1", "张三", "user")
        user2 = _create_user(db, "user2", "李四", "user")
        user3 = _create_user(db, "user3", "王五", "user")

        tpl, ver, fields = _create_template_with_fields(db, admin.id)

        team1 = _create_team(db, "研发一部", template_id=tpl.id, leader_id=admin.id)
        team2 = _create_team(db, "产品部", template_id=tpl.id)

        user1.team_id = team1.id
        user2.team_id = team1.id
        user3.team_id = team2.id
        admin.team_id = team1.id
        db.flush()

        ns1 = models.TeamNotificationSetting(team_id=team1.id)
        ns2 = models.TeamNotificationSetting(team_id=team2.id)
        db.add(ns1)
        db.add(ns2)
        db.commit()

        return {
            "admin": admin, "user1": user1, "user2": user2, "user3": user3,
            "team1": team1, "team2": team2,
            "template": tpl, "version": ver, "fields": fields,
        }

    def test_team_grouping(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "完成模块A", "next_plan": "开发模块B", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        _create_report(db, data["user2"].id, WEEK_KEY,
                       {"week_achievement": "修复bug", "next_plan": "测试", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        _create_report(db, data["user3"].id, WEEK_KEY,
                       {"week_achievement": "设计评审", "next_plan": "出方案", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        content = summary.content

        by_team = content["by_team"]
        team1_data = next(t for t in by_team if t["team_name"] == "研发一部")
        team2_data = next(t for t in by_team if t["team_name"] == "产品部")

        assert team1_data["submitted_count"] == 2
        assert team2_data["submitted_count"] == 1
        assert len(team1_data["reports"]) == 2
        assert len(team2_data["reports"]) == 1

    def test_risk_field_extraction(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "完成模块A", "next_plan": "开发模块B",
                        "risk_block": "接口联调被后端阻塞，进度延迟2天"},
                       "submitted", data["template"].id, data["version"].id)
        _create_report(db, data["user2"].id, WEEK_KEY,
                       {"week_achievement": "修复bug", "next_plan": "测试",
                        "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        content = summary.content

        risks = content["risks"]
        assert risks["total_count"] == 1
        assert len(risks["items"]) == 1
        risk_item = risks["items"][0]
        assert "接口联调被后端阻塞" in risk_item["content"]
        assert risk_item["user_name"] == "张三"

    def test_risk_field_filters_none_values(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "完成", "next_plan": "继续",
                        "risk_block": "没有"},
                       "submitted", data["template"].id, data["version"].id)
        _create_report(db, data["user2"].id, WEEK_KEY,
                       {"week_achievement": "完成", "next_plan": "继续",
                        "risk_block": "none"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        assert summary.content["risks"]["total_count"] == 0

    def test_unsubmitted_user_marked_in_stats(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "完成", "next_plan": "继续", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        content = summary.content

        stats = content["overall_stats"]
        assert stats["submitted_count"] == 1
        assert stats["pending_count"] > 0

        team1 = next(t for t in content["by_team"] if t["team_name"] == "研发一部")
        assert team1["submitted_count"] == 1
        assert team1["total_members"] >= 2

    def test_latest_submission_dedup(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "第一版内容", "next_plan": "继续", "risk_block": "无"},
                       "draft", data["template"].id, data["version"].id)

        _create_report(db, data["user2"].id, WEEK_KEY,
                       {"week_achievement": "正常提交", "next_plan": "继续", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        content = summary.content

        team1 = next(t for t in content["by_team"] if t["team_name"] == "研发一部")
        submitted_names = [r["user_name"] for r in team1["reports"]]
        assert "张三" not in submitted_names
        assert "李四" in submitted_names

    def test_plan_deviation_detection(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, PREV_WEEK_KEY,
                       {"week_achievement": "上上周完成", "next_plan": "- 完成支付模块\n- 上线促销页面", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "只完成了支付模块的前端部分", "next_plan": "下周继续", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)

        deviations = summary.deviation_items
        assert len(deviations) > 0
        dev_user_items = [d for d in deviations if d.user_name == "张三"]
        assert len(dev_user_items) > 0
        assert any("促销页面" in d.planned_item for d in dev_user_items)

    def test_no_deviation_when_plan_fulfilled(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, PREV_WEEK_KEY,
                       {"week_achievement": "上周完成", "next_plan": "- 完成登录模块", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "完成登录模块的全部开发和测试", "next_plan": "下周做注册", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        dev_user_items = [d for d in summary.deviation_items if d.user_name == "张三"]
        assert len(dev_user_items) == 0

    def test_overall_stats_correctness(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "完成", "next_plan": "继续", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        _create_report(db, data["user2"].id, WEEK_KEY,
                       {"week_achievement": "完成", "next_plan": "继续", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        stats = summary.content["overall_stats"]

        total_active = db.query(models.User).filter(models.User.is_active == True).count()
        assert stats["total_users"] == total_active
        assert stats["submitted_count"] == 2
        assert stats["pending_count"] == total_active - 2
        expected_rate = round(2 / total_active * 100, 1)
        assert stats["submission_rate"] == expected_rate

    def test_summary_regeneration_updates_content(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "第一版", "next_plan": "继续", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary1 = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        assert summary1.content["overall_stats"]["submitted_count"] == 1

        _create_report(db, data["user2"].id, WEEK_KEY,
                       {"week_achievement": "第二版补充", "next_plan": "继续", "risk_block": "无"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary2 = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        assert summary2.id == summary1.id
        assert summary2.content["overall_stats"]["submitted_count"] == 2
        assert summary2.status == "regenerated"

    def test_risk_items_in_team_level(self, db):
        data = self._setup_team_with_reports(db)

        _create_report(db, data["user1"].id, WEEK_KEY,
                       {"week_achievement": "完成", "next_plan": "继续",
                        "risk_block": "数据库连接池耗尽风险"},
                       "submitted", data["template"].id, data["version"].id)
        _create_report(db, data["user3"].id, WEEK_KEY,
                       {"week_achievement": "设计", "next_plan": "评审",
                        "risk_block": "设计稿延期"},
                       "submitted", data["template"].id, data["version"].id)
        db.commit()

        summary = _build_summary_for_week(db, WEEK_KEY, data["admin"].id)
        content = summary.content

        team1 = next(t for t in content["by_team"] if t["team_name"] == "研发一部")
        team2 = next(t for t in content["by_team"] if t["team_name"] == "产品部")

        assert len(team1["risks"]) == 1
        assert "数据库连接池耗尽" in team1["risks"][0]["content"]
        assert len(team2["risks"]) == 1
        assert "设计稿延期" in team2["risks"][0]["content"]
