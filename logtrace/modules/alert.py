import smtplib
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from typing import Dict, Any, List
from datetime import datetime

from logtrace.core.config import ConfigManager
from logtrace.core.models import AlertRecord


class AlertManager:
    def __init__(self, config: ConfigManager):
        self.config = config
        self.channels = self._load_channels()
        self.alert_history: List[AlertRecord] = []

    def _load_channels(self) -> Dict[str, Any]:
        alert_config = self.config.get_alert_config()
        channels = {}
        for channel in alert_config.get('channels', []):
            if channel.get('enabled', False):
                channels[channel['type']] = channel
        return channels

    def send_alert(self, alert_info: Dict[str, Any], storage=None) -> bool:
        channels = []
        if 'console' in self.channels:
            self._send_console_alert(alert_info)
            channels.append('console')

        if 'email' in self.channels:
            if self._send_email_alert(alert_info):
                channels.append('email')

        if not channels:
            return False

        alert_record = AlertRecord.create(
            rule_id=alert_info['rule_id'],
            node_id=alert_info['node_id'],
            exception_count=alert_info['exception_count'],
            notify_channels=channels
        )

        if storage:
            try:
                storage.store_alert(alert_record)
            except Exception as e:
                print(f"Error storing alert: {e}")

        self.alert_history.append(alert_record)
        return True

    def _send_console_alert(self, alert_info: Dict[str, Any]):
        print(f"[ALERT] [{alert_info.get('severity', 'medium').upper()}] "
              f"Rule: {alert_info.get('rule_name', 'unknown')} "
              f"| Node: {alert_info.get('node_id', 'unknown')} "
              f"| Count: {alert_info.get('exception_count', 0)} "
              f"| Time: {alert_info.get('alert_time', datetime.utcnow())}")

    def _send_email_alert(self, alert_info: Dict[str, Any]) -> bool:
        email_config = self.channels.get('email', {})
        smtp_server = email_config.get('smtp_server')
        smtp_port = email_config.get('smtp_port', 587)
        sender = email_config.get('sender')
        recipients = email_config.get('recipients', [])

        if not smtp_server or not sender or not recipients:
            return False

        try:
            msg = MIMEMultipart()
            msg['From'] = sender
            msg['To'] = ', '.join(recipients)
            msg['Subject'] = f"[LogTrace Alert] {alert_info.get('rule_name', 'Alert Triggered')}"

            body = f"""
LogTrace Alert Notification
===========================

Severity: {alert_info.get('severity', 'medium').upper()}
Rule Name: {alert_info.get('rule_name', 'unknown')}
Rule ID: {alert_info.get('rule_id', 'unknown')}
Node ID: {alert_info.get('node_id', 'unknown')}
Exception Count: {alert_info.get('exception_count', 0)}
Alert Time: {alert_info.get('alert_time', datetime.utcnow())}

Please check the LogTrace dashboard for more details.
            """

            msg.attach(MIMEText(body, 'plain'))

            with smtplib.SMTP(smtp_server, smtp_port) as server:
                server.starttls()
                server.sendmail(sender, recipients, msg.as_string())
            return True
        except Exception as e:
            print(f"Error sending email alert: {e}")
            return False

    def get_alert_history(self, limit: int = 100) -> List[Dict[str, Any]]:
        return [alert.to_dict() for alert in self.alert_history[-limit:]]

    def reload_channels(self):
        self.channels = self._load_channels()
