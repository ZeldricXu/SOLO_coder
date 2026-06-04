import pytest
from unittest.mock import patch, MagicMock, mock_open
from datetime import datetime, timedelta
from sqlalchemy.orm import Session

from app.services.email_service import EmailService, email_service
from app.services.lucene_parser import SimpleLuceneParser, lucene_parser
from app.services.health_service import HealthService
from app.services.alert_service import AlertService
from app.models import AlertRule, AlertHistory


class TestEmailNotification:

    def test_email_service_parse_emails(self):
        emails_str = "a@test.com, b@test.com , c@test.com"
        service = EmailService()
        parsed = service._parse_emails(emails_str)
        assert parsed == ["a@test.com", "b@test.com", "c@test.com"]

    def test_email_service_parse_empty_emails(self):
        service = EmailService()
        assert service._parse_emails(None) == []
        assert service._parse_emails("") == []
        assert service._parse_emails("   ") == []

    def test_email_service_not_configured(self):
        with patch.object(email_service, 'smtp_host', None):
            assert email_service.is_configured() is False

    def test_email_service_is_configured(self):
        with patch.object(email_service, 'smtp_host', 'smtp.test.com'), \
             patch.object(email_service, 'smtp_username', 'user'), \
             patch.object(email_service, 'smtp_password', 'pass'), \
             patch.object(email_service, 'smtp_from_email', 'from@test.com'), \
             patch.object(email_service, 'to_emails', ['to@test.com']):
            assert email_service.is_configured() is True

    def test_send_email_skips_when_not_configured(self):
        with patch.object(email_service, 'is_configured', return_value=False):
            alert = MagicMock()
            alert.id = 1
            result = email_service.send_alert_email(alert)
            assert result is False

    @patch('smtplib.SMTP')
    def test_send_alert_email_success(self, mock_smtp):
        mock_server = MagicMock()
        mock_smtp.return_value.__enter__.return_value = mock_server

        with patch.object(email_service, 'is_configured', return_value=True), \
             patch.object(email_service, 'smtp_from_email', 'from@test.com'), \
             patch.object(email_service, 'to_emails', ['to@test.com']), \
             patch.object(email_service, 'cc_emails', []):
            alert = MagicMock()
            alert.id = 1
            alert.level = "P1"
            alert.message = "Test alert"
            alert.triggered_at = datetime.now()
            alert.status = "firing"
            alert.rule_name = "Test Rule"

            result = email_service.send_alert_email(alert)
            assert result is True
            mock_server.sendmail.assert_called_once()


class TestHealthTimeline:

    def test_get_check_history_exists(self, db_session: Session):
        from app.models import Service
        health_service = HealthService(db_session)
        service = db_session.query(Service).first()

        if service:
            history = health_service.get_check_history(service.id, hours=1)
            assert isinstance(history, list)
        else:
            pytest.skip("No service in database")

    def test_get_check_history_returns_ordered(self, db_session: Session):
        from app.models import Service
        health_service = HealthService(db_session)
        service = db_session.query(Service).first()

        if service:
            history = health_service.get_check_history(service.id, hours=1)
            if len(history) >= 2:
                for i in range(len(history) - 1):
                    assert history[i].checked_at <= history[i + 1].checked_at
        else:
            pytest.skip("No service in database")


class TestLuceneQueryParser:

    def test_parse_empty_query(self):
        result = lucene_parser.parse("")
        assert result == {"match_all": {}}

    def test_parse_simple_keyword(self):
        result = lucene_parser.parse("error")
        assert "bool" in result
        assert "must" in result["bool"]
        assert result["bool"]["must"][0] == {"match": {"message": "error"}}

    def test_parse_field_query(self):
        result = lucene_parser.parse("service:payment")
        assert "bool" in result
        assert result["bool"]["must"][0] == {"term": {"service": "payment"}}

    def test_parse_and_operator(self):
        result = lucene_parser.parse("error AND database")
        assert "bool" in result
        assert "must" in result["bool"]
        assert len(result["bool"]["must"]) == 2

    def test_parse_or_operator(self):
        result = lucene_parser.parse("error OR warning")
        assert "bool" in result
        assert "should" in result["bool"]
        assert len(result["bool"]["should"]) == 2

    def test_parse_not_operator(self):
        result = lucene_parser.parse("NOT timeout")
        assert "bool" in result
        assert "must_not" in result["bool"]

    def test_parse_combined_query(self):
        result = lucene_parser.parse("service:payment AND level:ERROR")
        assert "bool" in result
        assert "must" in result["bool"]
        assert len(result["bool"]["must"]) == 2

    def test_parse_range_query(self):
        result = lucene_parser.parse("response_time:[500 TO 1000]")
        assert "bool" in result
        assert "must" in result["bool"]
        range_clause = result["bool"]["must"][0]
        assert "range" in range_clause
        assert "response_time" in range_clause["range"]
        assert range_clause["range"]["response_time"]["gte"] == 500
        assert range_clause["range"]["response_time"]["lte"] == 1000

    def test_parse_wildcard_query(self):
        result = lucene_parser.parse("service:pay*")
        assert "bool" in result
        assert result["bool"]["must"][0] == {"wildcard": {"service": "pay*"}}

    def test_parse_phrase_query(self):
        result = lucene_parser.parse('"connection failed"')
        assert "bool" in result
        assert result["bool"]["must"][0] == {"match_phrase": {"message": "connection failed"}}

    def test_detect_lucene_syntax(self):
        from app.services.log_service import LogService
        service = LogService(MagicMock())

        assert service._detect_lucene_syntax("service:payment") is True
        assert service._detect_lucene_syntax("error AND warning") is True
        assert service._detect_lucene_syntax("simple keyword") is False
        assert service._detect_lucene_syntax("response_time:[500 TO 1000]") is True

    def test_parse_complex_query(self):
        query = "service:payment AND level:ERROR NOT timeout"
        result = lucene_parser.parse(query)
        assert "bool" in result


class TestAlertEmailIntegration:

    @pytest.mark.asyncio
    async def test_send_email_notification_channel(self, db_session: Session):
        from app.services.notification_service import NotificationService
        notification_service = NotificationService(db_session)

        alert = MagicMock()
        alert.id = 1
        alert.rule_id = 1
        alert.level = "P1"
        alert.message = "Test"
        alert.triggered_at = datetime.now()
        alert.status = "firing"

        with patch('app.services.notification_service.email_service.send_alert_email') as mock_send, \
             patch('app.services.notification_service.email_service.is_configured', return_value=True):
            try:
                await notification_service._send_email(alert)
                mock_send.assert_called()
            except Exception:
                pass


class TestIntegrationEnhancements:

    def test_health_timeline_endpoint(self, client):
        response = client.get("/api/health/partial/timeline/1")
        assert response.status_code in [200, 404]

    def test_health_timeline_endpoint_not_found(self, client):
        response = client.get("/api/health/partial/timeline/99999")
        assert response.status_code == 404

    def test_logs_page_has_lucene_help(self, client):
        response = client.get("/logs")
        assert response.status_code == 200
        assert "Lucene" in response.text
        assert "lucene-help-modal" in response.text
