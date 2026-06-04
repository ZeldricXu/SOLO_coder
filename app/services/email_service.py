import smtplib
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from typing import List, Optional, Dict, Any
from pathlib import Path

from jinja2 import Environment, FileSystemLoader

from app.config import settings


class EmailService:
    """SMTP 邮件发送服务，负责告警通知邮件的渲染和投递。

    主要职责：
    - 告警邮件发送：使用 Jinja2 模板渲染 HTML 邮件
    - 通用邮件发送：支持自定义主题和内容的邮件
    - 配置校验：检查 SMTP 配置是否完整

    对外接口：
    - is_configured(): 检查 SMTP 配置是否完整
    - send_alert_email(alert, metrics): 发送告警通知邮件
    - send_custom_email(subject, html_content): 发送自定义邮件

    依赖的外部服务：
    - SMTP 服务器（通过环境变量配置）
    - Jinja2 模板引擎（templates/emails/ 目录）

    注意：本服务为同步实现，在异步上下文中需通过 run_in_executor 调用。
    """
    def __init__(self):
        self.smtp_host = settings.smtp_host
        self.smtp_port = settings.smtp_port
        self.smtp_username = settings.smtp_username
        self.smtp_password = settings.smtp_password
        self.smtp_from_email = settings.smtp_from_email
        self.smtp_use_tls = settings.smtp_use_tls

        self.to_emails = self._parse_emails(settings.smtp_to_emails)
        self.cc_emails = self._parse_emails(settings.smtp_cc_emails)

        template_dir = Path(__file__).parent.parent / "templates" / "emails"
        self.jinja_env = Environment(
            loader=FileSystemLoader(str(template_dir)),
            autoescape=True
        )

    def _parse_emails(self, emails_str: Optional[str]) -> List[str]:
        if not emails_str:
            return []
        return [email.strip() for email in emails_str.split(",") if email.strip()]

    def is_configured(self) -> bool:
        return all([
            self.smtp_host,
            self.smtp_username,
            self.smtp_password,
            self.smtp_from_email,
            self.to_emails
        ])

    def _render_template(self, template_name: str, context: Dict[str, Any]) -> str:
        template = self.jinja_env.get_template(template_name)
        return template.render(context)

    def send_alert_email(
        self,
        alert: Any,
        metrics: Optional[Dict[str, Any]] = None,
        base_url: str = "http://localhost:8000",
        custom_to_emails: Optional[List[str]] = None,
        custom_cc_emails: Optional[List[str]] = None
    ) -> bool:
        if not self.is_configured():
            return False

        to_emails = custom_to_emails or self.to_emails
        cc_emails = custom_cc_emails or self.cc_emails

        if not to_emails:
            return False

        try:
            context = {
                "alert": {
                    "id": alert.id,
                    "level": alert.level,
                    "rule_name": getattr(alert, 'rule_name', 'Unknown Rule'),
                    "message": alert.message,
                    "triggered_at": alert.triggered_at,
                    "status": alert.status,
                },
                "metrics": metrics or {},
                "base_url": base_url,
            }

            html_content = self._render_template("alert_notification.html", context)

            msg = MIMEMultipart("alternative")
            msg["From"] = self.smtp_from_email
            msg["To"] = ", ".join(to_emails)
            if cc_emails:
                msg["Cc"] = ", ".join(cc_emails)
            msg["Subject"] = f"[{alert.level}] 运维告警 - {getattr(alert, 'rule_name', 'Alert')}"

            msg.attach(MIMEText(html_content, "html"))

            all_recipients = to_emails + cc_emails

            with smtplib.SMTP(self.smtp_host, self.smtp_port) as server:
                if self.smtp_use_tls:
                    server.starttls()
                server.login(self.smtp_username, self.smtp_password)
                server.sendmail(self.smtp_from_email, all_recipients, msg.as_string())

            return True
        except Exception as e:
            print(f"Failed to send alert email: {e}")
            return False

    def send_custom_email(
        self,
        subject: str,
        html_content: str,
        to_emails: Optional[List[str]] = None,
        cc_emails: Optional[List[str]] = None
    ) -> bool:
        if not self.is_configured():
            return False

        to_emails = to_emails or self.to_emails
        cc_emails = cc_emails or self.cc_emails

        if not to_emails:
            return False

        try:
            msg = MIMEMultipart("alternative")
            msg["From"] = self.smtp_from_email
            msg["To"] = ", ".join(to_emails)
            if cc_emails:
                msg["Cc"] = ", ".join(cc_emails)
            msg["Subject"] = subject

            msg.attach(MIMEText(html_content, "html"))

            all_recipients = to_emails + cc_emails

            with smtplib.SMTP(self.smtp_host, self.smtp_port) as server:
                if self.smtp_use_tls:
                    server.starttls()
                server.login(self.smtp_username, self.smtp_password)
                server.sendmail(self.smtp_from_email, all_recipients, msg.as_string())

            return True
        except Exception as e:
            print(f"Failed to send custom email: {e}")
            return False


email_service = EmailService()
