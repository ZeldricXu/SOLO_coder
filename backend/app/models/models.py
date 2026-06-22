from sqlalchemy import Column, Integer, String, Text, Boolean, DateTime, ForeignKey, JSON, Date, Float
from sqlalchemy.orm import relationship
from datetime import datetime
from app.core.database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(50), unique=True, index=True, nullable=False)
    email = Column(String(100), unique=True, index=True, nullable=False)
    full_name = Column(String(50), nullable=False)
    hashed_password = Column(String(255), nullable=False)
    role = Column(String(20), default="user")
    team_id = Column(Integer, ForeignKey("teams.id"), nullable=True)
    wecom_userid = Column(String(100), nullable=True)
    feishu_open_id = Column(String(100), nullable=True)
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    team = relationship("Team", back_populates="members", foreign_keys=[team_id])
    submitted_reports = relationship("WeeklyReport", back_populates="submitter", foreign_keys="WeeklyReport.submitter_id")
    proxy_reports = relationship("WeeklyReport", back_populates="proxy_submitter", foreign_keys="WeeklyReport.proxy_submitter_id")


class Team(Base):
    __tablename__ = "teams"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(50), unique=True, nullable=False)
    description = Column(String(255), nullable=True)
    leader_id = Column(Integer, ForeignKey("users.id"), nullable=True)
    deadline_day = Column(Integer, default=4)
    deadline_hour = Column(Integer, default=18)
    deadline_minute = Column(Integer, default=0)
    template_id = Column(Integer, ForeignKey("templates.id"), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    members = relationship("User", back_populates="team", foreign_keys="User.team_id")
    settings = relationship("TeamNotificationSetting", back_populates="team", uselist=False)
    template = relationship("Template")


class TeamNotificationSetting(Base):
    __tablename__ = "team_notification_settings"

    id = Column(Integer, primary_key=True, index=True)
    team_id = Column(Integer, ForeignKey("teams.id"), unique=True, nullable=False)
    wecom_webhook = Column(String(500), nullable=True)
    feishu_webhook = Column(String(500), nullable=True)
    notify_emails = Column(String(1000), nullable=True)
    notify_wecom_enabled = Column(Boolean, default=False)
    notify_feishu_enabled = Column(Boolean, default=False)
    notify_email_enabled = Column(Boolean, default=False)

    team = relationship("Team", back_populates="settings")


class Template(Base):
    __tablename__ = "templates"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)
    description = Column(String(255), nullable=True)
    is_default = Column(Boolean, default=False)
    created_by = Column(Integer, ForeignKey("users.id"), nullable=True)
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    versions = relationship("TemplateVersion", back_populates="template", order_by="TemplateVersion.version.desc()")
    fields = relationship("TemplateField", back_populates="template", order_by="TemplateField.sort_order")


class TemplateVersion(Base):
    __tablename__ = "template_versions"

    id = Column(Integer, primary_key=True, index=True)
    template_id = Column(Integer, ForeignKey("templates.id"), nullable=False)
    version = Column(Integer, default=1)
    change_note = Column(String(500), nullable=True)
    fields_snapshot = Column(JSON, nullable=False)
    created_by = Column(Integer, ForeignKey("users.id"), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)

    template = relationship("Template", back_populates="versions")


class TemplateField(Base):
    __tablename__ = "template_fields"

    id = Column(Integer, primary_key=True, index=True)
    template_id = Column(Integer, ForeignKey("templates.id"), nullable=False)
    field_key = Column(String(50), nullable=False)
    field_name = Column(String(100), nullable=False)
    field_type = Column(String(20), default="markdown")
    options = Column(JSON, nullable=True)
    placeholder = Column(String(500), nullable=True)
    is_required = Column(Boolean, default=True)
    sort_order = Column(Integer, default=0)
    is_risk_field = Column(Boolean, default=False)
    is_plan_field = Column(Boolean, default=False)
    is_achievement_field = Column(Boolean, default=False)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    template = relationship("Template", back_populates="fields")


class WeeklyReport(Base):
    __tablename__ = "weekly_reports"

    id = Column(Integer, primary_key=True, index=True)
    submitter_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    proxy_submitter_id = Column(Integer, ForeignKey("users.id"), nullable=True)
    template_version_id = Column(Integer, ForeignKey("template_versions.id"), nullable=True)
    template_id = Column(Integer, ForeignKey("templates.id"), nullable=True)
    week_key = Column(String(20), index=True, nullable=False)
    week_start = Column(Date, nullable=False)
    week_end = Column(Date, nullable=False)
    content = Column(JSON, nullable=False)
    word_count = Column(Integer, default=0)
    status = Column(String(20), default="draft")
    submitted_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    submitter = relationship("User", back_populates="submitted_reports", foreign_keys=[submitter_id])
    proxy_submitter = relationship("User", back_populates="proxy_reports", foreign_keys=[proxy_submitter_id])
    template = relationship("Template")
    template_version = relationship("TemplateVersion")
    field_values = relationship("ReportFieldValue", back_populates="report", cascade="all, delete-orphan")
    reminders = relationship("ReminderLog", back_populates="report")


class ReportFieldValue(Base):
    __tablename__ = "report_field_values"

    id = Column(Integer, primary_key=True, index=True)
    report_id = Column(Integer, ForeignKey("weekly_reports.id"), nullable=False)
    field_id = Column(Integer, ForeignKey("template_fields.id"), nullable=True)
    field_key = Column(String(50), nullable=False)
    field_name = Column(String(100), nullable=False)
    value = Column(Text, nullable=True)
    word_count = Column(Integer, default=0)

    report = relationship("WeeklyReport", back_populates="field_values")


class ReminderLog(Base):
    __tablename__ = "reminder_logs"

    id = Column(Integer, primary_key=True, index=True)
    report_id = Column(Integer, ForeignKey("weekly_reports.id"), nullable=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    week_key = Column(String(20), nullable=False)
    reminder_type = Column(String(30), nullable=False)
    channel = Column(String(20), nullable=False)
    status = Column(String(20), default="success")
    error_message = Column(String(1000), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)

    report = relationship("WeeklyReport", back_populates="reminders")
    user = relationship("User")


class WeeklySummary(Base):
    __tablename__ = "weekly_summaries"

    id = Column(Integer, primary_key=True, index=True)
    week_key = Column(String(20), index=True, nullable=False)
    week_start = Column(Date, nullable=False)
    week_end = Column(Date, nullable=False)
    content = Column(JSON, nullable=False)
    generated_at = Column(DateTime, default=datetime.utcnow)
    generated_by = Column(Integer, ForeignKey("users.id"), nullable=True)
    pdf_path = Column(String(500), nullable=True)
    distributed_to = Column(JSON, nullable=True)
    status = Column(String(20), default="generated")

    deviation_items = relationship("PlanDeviationItem", back_populates="summary", cascade="all, delete-orphan")


class PlanDeviationItem(Base):
    __tablename__ = "plan_deviation_items"

    id = Column(Integer, primary_key=True, index=True)
    summary_id = Column(Integer, ForeignKey("weekly_summaries.id"), nullable=False)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    user_name = Column(String(100), nullable=False)
    planned_item = Column(Text, nullable=False)
    actual_status = Column(String(50), nullable=False)
    note = Column(Text, nullable=True)
    deviation_level = Column(String(20), default="minor")

    summary = relationship("WeeklySummary", back_populates="deviation_items")
    user = relationship("User")


class StatisticsCache(Base):
    __tablename__ = "statistics_cache"

    id = Column(Integer, primary_key=True, index=True)
    stat_key = Column(String(100), unique=True, index=True, nullable=False)
    stat_type = Column(String(50), nullable=False)
    week_key = Column(String(20), nullable=True)
    data = Column(JSON, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
