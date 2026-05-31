import pytest
from datetime import datetime, timedelta
from unittest import mock

from tests.app.exceptions import ValidationError, NotFoundError, DatabaseError
from tests.app.slomonitor import MetricEvent
from tests.factories.data_factory import SLOFactory

pytestmark = pytest.mark.unit

class TestSLOCreation:
    @pytest.mark.validation
    def test_create_slo_success(self, slomonitor):
        slo_data = SLOFactory.create_slo_data()
        slo = slomonitor.create_slo(slo_data)

        assert slo.id is not None
        assert slo.name == slo_data["name"]
        assert slo.service_name == slo_data["service_name"]
        assert slo.sli == slo_data["sli"]
        assert slo.target_percent == 99.9
        assert slo.error_budget == 0.001
        assert slo.remaining_budget == 0.001
        assert slo.window_days == 30
        assert slo.total_requests == 0
        assert slo.failed_requests == 0
        assert slo.burn_rate == 0.0

    @pytest.mark.validation
    @pytest.mark.parametrize("invalid_data", SLOFactory.create_invalid_slo_data())
    def test_create_slo_validation_errors(self, slomonitor, invalid_data):
        with pytest.raises(ValidationError):
            slomonitor.create_slo(invalid_data)

    @pytest.mark.boundary
    def test_create_slo_boundary_percent_values(self, slomonitor):
        slo_data = SLOFactory.create_slo_data(target_percent=0)
        with pytest.raises(ValidationError):
            slomonitor.create_slo(slo_data)

        slo_data = SLOFactory.create_slo_data(target_percent=-0.1)
        with pytest.raises(ValidationError):
            slomonitor.create_slo(slo_data)

        slo_data = SLOFactory.create_slo_data(target_percent=100.1)
        with pytest.raises(ValidationError):
            slomonitor.create_slo(slo_data)

        slo_data = SLOFactory.create_slo_data(target_percent=100)
        slo = slomonitor.create_slo(slo_data)
        assert slo.target_percent == 100

        slo_data = SLOFactory.create_slo_data(target_percent=0.01)
        slo = slomonitor.create_slo(slo_data)
        assert slo.target_percent == 0.01

    @pytest.mark.boundary
    def test_create_slo_boundary_window_days(self, slomonitor):
        slo_data = SLOFactory.create_slo_data(window_days=1)
        slo = slomonitor.create_slo(slo_data)
        assert slo.window_days == 1

        slo_data = SLOFactory.create_slo_data(window_days=365)
        slo = slomonitor.create_slo(slo_data)
        assert slo.window_days == 365

    @pytest.mark.boundary
    def test_create_slo_duplicate_id(self, slomonitor):
        slo_data = SLOFactory.create_slo_data()
        slomonitor.create_slo(slo_data)

        with pytest.raises(ValidationError):
            slomonitor.create_slo(slo_data)

    @pytest.mark.boundary
    def test_create_slo_zero_error_budget(self, slomonitor):
        slo_data = SLOFactory.create_slo_data(error_budget=0)
        slo = slomonitor.create_slo(slo_data)
        assert slo.error_budget == 0
        assert slo.remaining_budget == 0

    @pytest.mark.transaction
    def test_create_slo_with_db_success(self, slomonitor_with_db, mock_db_session):
        slo_data = SLOFactory.create_slo_data()
        slo = slomonitor_with_db.create_slo(slo_data)

        mock_db_session.add.assert_called_once()
        mock_db_session.commit.assert_called_once()
        mock_db_session.rollback.assert_not_called()

    @pytest.mark.transaction
    def test_create_slo_db_rollback_on_error(self, mocker):
        from tests.app.slomonitor import SLOMonitor
        failing_session = mocker.MagicMock()
        failing_session.add = mocker.MagicMock()
        failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
        failing_session.rollback = mocker.MagicMock()

        monitor = SLOMonitor(db_session=failing_session)
        slo_data = SLOFactory.create_slo_data()

        with pytest.raises(DatabaseError):
            monitor.create_slo(slo_data)

        failing_session.rollback.assert_called_once()
        assert slo_data["id"] not in monitor._slos

class TestSLORetrieval:
    def test_get_slo_success(self, slomonitor, sample_slo):
        retrieved = slomonitor.get_slo(sample_slo.id)
        assert retrieved.id == sample_slo.id
        assert retrieved.name == sample_slo.name

    def test_get_slo_not_found(self, slomonitor):
        with pytest.raises(NotFoundError):
            slomonitor.get_slo("non_existent")

    def test_list_slos_empty(self, slomonitor):
        slos = slomonitor.list_slos()
        assert slos == []

    def test_list_slos_with_data(self, slomonitor):
        for _ in range(5):
            slomonitor.create_slo(SLOFactory.create_slo_data())

        slos = slomonitor.list_slos()
        assert len(slos) == 5

    def test_list_slos_by_service(self, slomonitor):
        service1_slos = []
        for _ in range(3):
            slo_data = SLOFactory.create_slo_data(service_name="service1")
            slo = slomonitor.create_slo(slo_data)
            service1_slos.append(slo.id)

        for _ in range(2):
            slomonitor.create_slo(SLOFactory.create_slo_data(service_name="service2"))

        slos = slomonitor.list_slos(service_name="service1")
        assert len(slos) == 3
        assert all(s.id in service1_slos for s in slos)

class TestSLOUpdate:
    def test_update_slo_success(self, slomonitor, sample_slo):
        update_data = {"target_percent": 99.99, "error_budget": 0.0001}
        updated = slomonitor.update_slo(sample_slo.id, update_data)

        assert updated.target_percent == 99.99
        assert updated.error_budget == 0.0001
        assert updated.updated_at >= sample_slo.updated_at

    def test_update_slo_not_found(self, slomonitor):
        with pytest.raises(NotFoundError):
            slomonitor.update_slo("non_existent", {"target_percent": 99.9})

    @pytest.mark.validation
    def test_update_slo_invalid_values(self, slomonitor, sample_slo):
        with pytest.raises(ValidationError):
            slomonitor.update_slo(sample_slo.id, {"target_percent": -1})

    @pytest.mark.transaction
    def test_update_slo_db_rollback_on_error(self, mocker):
        from tests.app.slomonitor import SLOMonitor
        failing_session = mocker.MagicMock()
        failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
        failing_session.rollback = mocker.MagicMock()

        monitor = SLOMonitor(db_session=failing_session)
        slo_data = SLOFactory.create_slo_data()
        monitor._slos[slo_data["id"]] = monitor._create_slo_instance(slo_data)

        with pytest.raises(DatabaseError):
            monitor.update_slo(slo_data["id"], {"target_percent": 99.99})

        failing_session.rollback.assert_called_once()

class TestSLODeletion:
    def test_delete_slo_success(self, slomonitor, sample_slo):
        slomonitor.delete_slo(sample_slo.id)

        with pytest.raises(NotFoundError):
            slomonitor.get_slo(sample_slo.id)

    def test_delete_slo_not_found(self, slomonitor):
        with pytest.raises(NotFoundError):
            slomonitor.delete_slo("non_existent")

    @pytest.mark.transaction
    def test_delete_slo_db_rollback_on_error(self, mocker):
        from tests.app.slomonitor import SLOMonitor
        failing_session = mocker.MagicMock()
        failing_session.delete = mocker.MagicMock(side_effect=Exception("DB error"))
        failing_session.rollback = mocker.MagicMock()

        monitor = SLOMonitor(db_session=failing_session)
        slo_data = SLOFactory.create_slo_data()
        slo = monitor._create_slo_instance(slo_data)
        monitor._slos[slo_data["id"]] = slo
        monitor._service_slos[slo_data["service_name"]] = {slo_data["sli"]: [slo]}

        with pytest.raises(DatabaseError):
            monitor.delete_slo(slo_data["id"])

        failing_session.rollback.assert_called_once()
        assert slo_data["id"] in monitor._slos

class TestSLORecordMetric:
    def test_record_metric_success(self, slomonitor, sample_slo):
        event = MetricEvent(
            service_name=sample_slo.service_name,
            sli=sample_slo.sli,
            success=True,
        )
        slomonitor.record_metric(event)

        assert sample_slo.total_requests == 1
        assert sample_slo.failed_requests == 0

    def test_record_metric_failure(self, slomonitor, sample_slo):
        event = MetricEvent(
            service_name=sample_slo.service_name,
            sli=sample_slo.sli,
            success=False,
        )
        slomonitor.record_metric(event)

        assert sample_slo.total_requests == 1
        assert sample_slo.failed_requests == 1

    def test_record_metric_updates_burn_rate(self, slomonitor):
        slo_data = SLOFactory.create_slo_data(
            error_budget=0.1,
            window_days=30,
        )
        slo = slomonitor.create_slo(slo_data)

        for i in range(100):
            event = MetricEvent(
                service_name=slo.service_name,
                sli=slo.sli,
                success=i < 90,
            )
            slomonitor.record_metric(event)

        assert slo.total_requests == 100
        assert slo.failed_requests == 10
        assert slo.remaining_budget == 0.0
        assert slo.burn_rate > 0

    def test_record_metric_no_matching_slo(self, slomonitor):
        event = MetricEvent(
            service_name="non_existent_service",
            sli="latency",
            success=True,
        )
        slomonitor.record_metric(event)
        assert len(slomonitor._slos) == 0

    @pytest.mark.boundary
    def test_record_metric_budget_exhausted(self, slomonitor, sample_slo):
        for _ in range(1000):
            event = MetricEvent(
                service_name=sample_slo.service_name,
                sli=sample_slo.sli,
                success=False,
            )
            slomonitor.record_metric(event)

        assert sample_slo.remaining_budget == 0.0
        assert sample_slo.burn_rate > 0

    def test_record_metric_triggers_alert(self, slomonitor_with_alerter, mock_alerter):
        slo_data = SLOFactory.create_slo_data(error_budget=0.001)
        slo = slomonitor_with_alerter.create_slo(slo_data)

        for _ in range(100):
            event = MetricEvent(
                service_name=slo.service_name,
                sli=slo.sli,
                success=False,
            )
            slomonitor_with_alerter.record_metric(event)

        mock_alerter.fire_alert.assert_called()
        assert len(slomonitor_with_alerter._alerts_fired) > 0

    @pytest.mark.transaction
    def test_record_metric_db_rollback_on_error(self, mocker):
        from tests.app.slomonitor import SLOMonitor, MetricEvent
        failing_session = mocker.MagicMock()
        failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
        failing_session.rollback = mocker.MagicMock()

        monitor = SLOMonitor(db_session=failing_session)
        slo_data = SLOFactory.create_slo_data()
        slo = monitor._create_slo_instance(slo_data)
        monitor._slos[slo_data["id"]] = slo
        monitor._service_slos[slo_data["service_name"]] = {slo_data["sli"]: [slo]}

        event = MetricEvent(
            service_name=slo.service_name,
            sli=slo.sli,
            success=True,
        )

        with pytest.raises(DatabaseError):
            monitor.record_metric(event)

        failing_session.rollback.assert_called_once()

    @pytest.mark.boundary
    def test_record_metric_multiple_slos_same_service(self, slomonitor):
        slo1_data = SLOFactory.create_slo_data(
            service_name="svc1", sli="availability", error_budget=0.01
        )
        slo2_data = SLOFactory.create_slo_data(
            service_name="svc1", sli="availability", error_budget=0.05
        )
        slo1 = slomonitor.create_slo(slo1_data)
        slo2 = slomonitor.create_slo(slo2_data)

        for _ in range(100):
            event = MetricEvent(service_name="svc1", sli="availability", success=True)
            slomonitor.record_metric(event)

        assert slo1.total_requests == 100
        assert slo2.total_requests == 100

class TestSLOStatus:
    def test_get_slo_status_no_requests(self, slomonitor, sample_slo):
        status = slomonitor.get_slo_status(sample_slo.id)

        assert status["sli_value"] == 0.0
        assert status["budget_exhausted"] is False
        assert status["total_requests"] == 0

    def test_get_slo_status_with_requests(self, slomonitor, sample_slo):
        for i in range(100):
            event = MetricEvent(
                service_name=sample_slo.service_name,
                sli=sample_slo.sli,
                success=i < 99,
            )
            slomonitor.record_metric(event)

        status = slomonitor.get_slo_status(sample_slo.id)
        assert status["sli_value"] == 99.0
        assert status["failed_requests"] == 1
        assert status["total_requests"] == 100

    def test_get_slo_status_budget_exhausted(self, slomonitor):
        slo_data = SLOFactory.create_slo_data(error_budget=0.01)
        slo = slomonitor.create_slo(slo_data)

        for _ in range(100):
            event = MetricEvent(
                service_name=slo.service_name,
                sli=slo.sli,
                success=False,
            )
            slomonitor.record_metric(event)

        status = slomonitor.get_slo_status(slo.id)
        assert status["budget_exhausted"] is True

    def test_get_slo_status_not_found(self, slomonitor):
        with pytest.raises(NotFoundError):
            slomonitor.get_slo_status("non_existent")

class TestSLOBudgetReset:
    def test_reset_budget_success(self, slomonitor, sample_slo):
        for _ in range(10):
            event = MetricEvent(
                service_name=sample_slo.service_name,
                sli=sample_slo.sli,
                success=False,
            )
            slomonitor.record_metric(event)

        slomonitor.reset_budget(sample_slo.id)

        assert sample_slo.remaining_budget == sample_slo.error_budget
        assert sample_slo.total_requests == 0
        assert sample_slo.failed_requests == 0
        assert sample_slo.burn_rate == 0.0

    def test_reset_budget_not_found(self, slomonitor):
        with pytest.raises(NotFoundError):
            slomonitor.reset_budget("non_existent")

    @pytest.mark.transaction
    def test_reset_budget_db_rollback_on_error(self, mocker):
        from tests.app.slomonitor import SLOMonitor
        failing_session = mocker.MagicMock()
        failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
        failing_session.rollback = mocker.MagicMock()

        monitor = SLOMonitor(db_session=failing_session)
        slo_data = SLOFactory.create_slo_data()
        monitor._slos[slo_data["id"]] = monitor._create_slo_instance(slo_data)

        with pytest.raises(DatabaseError):
            monitor.reset_budget(slo_data["id"])

        failing_session.rollback.assert_called_once()

class TestSLOBurnRateCheck:
    def test_check_high_burn_rates_no_slos(self, slomonitor):
        result = slomonitor.check_high_burn_rates()
        assert result == []

    def test_check_high_burn_rates_no_high_burn(self, slomonitor):
        slo = slomonitor.create_slo(SLOFactory.create_slo_data())
        result = slomonitor.check_high_burn_rates()
        assert result == []

    def test_check_high_burn_rates_with_high_burn(self, slomonitor):
        slo_data = SLOFactory.create_slo_data(error_budget=0.1)
        slo = slomonitor.create_slo(slo_data)

        for _ in range(100):
            event = MetricEvent(
                service_name=slo.service_name,
                sli=slo.sli,
                success=False,
            )
            slomonitor.record_metric(event)

        assert slo.remaining_budget < 0.2 * slo.error_budget

        result = slomonitor.check_high_burn_rates(threshold=0.001)
        assert len(result) >= 1
        assert result[0].id == slo.id

    def test_check_high_burn_rates_threshold(self, slomonitor):
        slo_data = SLOFactory.create_slo_data(error_budget=0.1)
        slo = slomonitor.create_slo(slo_data)

        for _ in range(100):
            event = MetricEvent(
                service_name=slo.service_name,
                sli=slo.sli,
                success=True,
            )
            slomonitor.record_metric(event)

        result = slomonitor.check_high_burn_rates(threshold=1.0)
        assert result == []
