from __future__ import annotations

from datetime import datetime, timedelta

import pytest
import numpy as np
from sqlalchemy.orm import Session

from app.models import (
    Inventory,
    TransactionType,
    AlertRuleType,
    ThresholdType,
    AlertLevel,
    AlertStatus,
    InventoryAlert,
)
from app.services.alert_service import AlertService
from app.services.forecast_service import ForecastService
from app.schemas.alert import (
    AlertRuleCreate,
    AlertRuleUpdate,
    AlertAcknowledgeRequest,
    AlertResolveRequest,
)
from app.schemas.purchase_order import ForecastMethodEnum
from tests.factories import get_factory

pytestmark = [pytest.mark.unit, pytest.mark.alert]


class TestAlertRuleMatching:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.user = self.factory.user.create(username="alert_test_user")
        self.product, skus = self.factory.create_product_with_sku(num_skus=5)
        self.sku_list = skus
        self.warehouse = self.factory.warehouse.create()
        self.db.commit()

        self.alert_service = AlertService(self.db, self.user)
        self.forecast_service = ForecastService(self.db)

    def test_low_stock_alert_rule_matches_correct_skus(self):
        low_stock_skus = self.sku_list[:3]
        normal_stock_skus = self.sku_list[3:]

        for sku in low_stock_skus:
            self.factory.inventory.create(
                sku_id=sku.id,
                warehouse_id=self.warehouse.id,
                quantity=50,
            )
            sku.safety_stock = 100
            sku.maximum_stock = 500

        for sku in normal_stock_skus:
            self.factory.inventory.create(
                sku_id=sku.id,
                warehouse_id=self.warehouse.id,
                quantity=200,
            )
            sku.safety_stock = 100
            sku.maximum_stock = 500
        self.db.commit()

        alert_rule_create = AlertRuleCreate(
            name="低库存预警规则",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
            sku_ids=[sku.id for sku in self.sku_list],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        matched_sku_ids = [alert.sku_id for alert in result.new_alerts]

        assert len(result.new_alerts) == 3
        for sku in low_stock_skus:
            assert sku.id in matched_sku_ids

    def test_high_stock_alert_rule_matching(self):
        high_stock_sku = self.sku_list[0]
        normal_stock_sku = self.sku_list[1]

        self.factory.inventory.create(
            sku_id=high_stock_sku.id,
            warehouse_id=self.warehouse.id,
            quantity=600,
        )
        self.factory.inventory.create(
            sku_id=normal_stock_sku.id,
            warehouse_id=self.warehouse.id,
            quantity=300,
        )
        high_stock_sku.maximum_stock = 500
        normal_stock_sku.maximum_stock = 500
        self.db.commit()

        alert_rule_create = AlertRuleCreate(
            name="高库存预警规则",
            rule_type=AlertRuleType.HIGH_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=500,
            warning_value=500,
            critical_value=600,
            is_active=True,
            sku_ids=[high_stock_sku.id, normal_stock_sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        assert len(result.new_alerts) == 1
        assert result.new_alerts[0].sku_id == high_stock_sku.id
        assert result.new_alerts[0].alert_level == AlertLevel.CRITICAL

    def test_out_of_stock_alert_rule_matching(self):
        out_of_stock_sku = self.sku_list[0]
        in_stock_sku = self.sku_list[1]

        self.factory.inventory.create(
            sku_id=out_of_stock_sku.id,
            warehouse_id=self.warehouse.id,
            quantity=0,
        )
        self.factory.inventory.create(
            sku_id=in_stock_sku.id,
            warehouse_id=self.warehouse.id,
            quantity=100,
        )
        self.db.commit()

        alert_rule_create = AlertRuleCreate(
            name="缺货预警规则",
            rule_type=AlertRuleType.OUT_OF_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=0,
            warning_value=0,
            critical_value=0,
            is_active=True,
            sku_ids=[out_of_stock_sku.id, in_stock_sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        assert len(result.new_alerts) == 1
        assert result.new_alerts[0].sku_id == out_of_stock_sku.id

    def test_percentage_threshold_alert_rule(self):
        sku = self.sku_list[0]
        sku.maximum_stock = 500
        self.factory.inventory.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            quantity=80,
        )
        self.db.commit()

        alert_rule_create = AlertRuleCreate(
            name="低库存百分比预警",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.PERCENTAGE,
            threshold_value=20,
            warning_value=20,
            critical_value=10,
            is_active=True,
            sku_ids=[sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        assert len(result.new_alerts) == 1
        assert result.new_alerts[0].threshold_value == 100

    def test_alert_rule_with_category_filter(self):
        category = self.factory.category.create(name="电子产品")
        product = self.factory.product.create(name="手机", category_id=category.id)
        sku1 = self.factory.sku.create(product_id=product.id)
        sku2 = self.factory.sku.create(product_id=self.product.id)

        self.factory.inventory.create(
            sku_id=sku1.id,
            warehouse_id=self.warehouse.id,
            quantity=30,
        )
        self.factory.inventory.create(
            sku_id=sku2.id,
            warehouse_id=self.warehouse.id,
            quantity=30,
        )
        sku1.safety_stock = 100
        sku2.safety_stock = 100
        self.db.commit()

        alert_rule_create = AlertRuleCreate(
            name="电子产品低库存预警",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
            category_id=category.id,
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        assert len(result.new_alerts) == 1
        assert result.new_alerts[0].sku_id == sku1.id

    def test_expiring_batch_alert_rule(self):
        sku = self.sku_list[0]
        expiring_batch = self.factory.batch.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            expiration_date=datetime.utcnow() + timedelta(days=5),
            remaining_quantity=100,
        )
        normal_batch = self.factory.batch.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            expiration_date=datetime.utcnow() + timedelta(days=60),
            remaining_quantity=200,
        )
        self.factory.inventory.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            quantity=300,
        )
        self.db.commit()

        alert_rule_create = AlertRuleCreate(
            name="临期预警规则",
            rule_type=AlertRuleType.EXPIRING,
            threshold_type=ThresholdType.FIXED,
            threshold_value=7,
            warning_value=7,
            critical_value=3,
            is_active=True,
            sku_ids=[sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        assert len(result.new_alerts) == 1
        assert result.new_alerts[0].current_value == 100

    def test_slow_moving_alert_rule(self):
        sku = self.sku_list[0]
        self.factory.inventory.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            quantity=100,
        )
        self.db.commit()

        alert_rule_create = AlertRuleCreate(
            name="滞销预警规则",
            rule_type=AlertRuleType.SLOW_MOVING,
            threshold_type=ThresholdType.FIXED,
            threshold_value=30,
            warning_value=30,
            critical_value=60,
            is_active=True,
            sku_ids=[sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        assert len(result.new_alerts) == 1
        assert result.new_alerts[0].sku_id == sku.id


class TestForecastAlgorithmDegradation:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.user = self.factory.user.create()
        self.product, skus = self.factory.create_product_with_sku(num_skus=2)
        self.sku_with_history, self.sku_without_history = skus
        self.warehouse = self.factory.warehouse.create()
        self.db.commit()

        self.forecast_service = ForecastService(self.db)

    def test_forecast_with_insufficient_history_falls_back_to_moving_average(self):
        sku = self.sku_without_history
        sku.reorder_point = 150
        self.db.commit()

        data = self.forecast_service.get_history_data(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            days=90,
        )

        assert len(data) <= 10

        _, forecast, metrics = self.forecast_service.forecast_demand(
            sku_id=sku.id,
            method=ForecastMethodEnum.ARIMA,
            periods=30,
            warehouse_id=self.warehouse.id,
            history_days=30,
        )

        assert len(forecast) == 30
        assert all(f >= 0 for f in forecast)

    def test_forecast_with_90_plus_days_history_uses_chosen_method(self):
        sku = self.sku_with_history
        sku.reorder_point = 150
        self.db.commit()

        end_date = datetime.utcnow()
        for i in range(100):
            transaction_date = end_date - timedelta(days=99 - i)
            quantity = -(10 + i % 5 * 2)
            self.factory.inventory_transaction.create(
                sku_id=sku.id,
                warehouse_id=self.warehouse.id,
                transaction_type=TransactionType.SALE,
                quantity=quantity,
                created_at=transaction_date,
            )
        self.db.commit()

        data = self.forecast_service.get_history_data(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            days=90,
        )

        assert len(data) >= 60

        _, forecast, metrics = self.forecast_service.forecast_demand(
            sku_id=sku.id,
            method=ForecastMethodEnum.EXPONENTIAL_SMOOTHING,
            periods=30,
            warehouse_id=self.warehouse.id,
            history_days=90,
        )

        assert len(forecast) == 30
        assert metrics.mape is not None

    def test_moving_average_fallback_when_arima_fails(self):
        sku = self.sku_with_history
        sku.reorder_point = 150
        self.db.commit()

        for i in range(30):
            transaction_date = datetime.utcnow() - timedelta(days=29 - i)
            quantity = -(5 + i % 3)
            self.factory.inventory_transaction.create(
                sku_id=sku.id,
                warehouse_id=self.warehouse.id,
                transaction_type=TransactionType.SALE,
                quantity=quantity,
                created_at=transaction_date,
            )
        self.db.commit()

        _, forecast, metrics = self.forecast_service.forecast_demand(
            sku_id=sku.id,
            method=ForecastMethodEnum.ARIMA,
            periods=30,
            warehouse_id=self.warehouse.id,
            history_days=30,
        )

        assert len(forecast) == 30
        assert all(f >= 0 for f in forecast)

    def test_simple_moving_average_calculation(self):
        sku = self.sku_without_history
        sku.reorder_point = 150
        self.db.commit()

        for i in range(14):
            transaction_date = datetime.utcnow() - timedelta(days=13 - i)
            quantity = -(10 + i % 5)
            self.factory.inventory_transaction.create(
                sku_id=sku.id,
                warehouse_id=self.warehouse.id,
                transaction_type=TransactionType.SALE,
                quantity=quantity,
                created_at=transaction_date,
            )
        self.db.commit()

        _, forecast, _ = self.forecast_service.forecast_demand(
            sku_id=sku.id,
            method=ForecastMethodEnum.MOVING_AVERAGE,
            periods=7,
            warehouse_id=self.warehouse.id,
            history_days=30,
            window=7,
        )

        assert len(forecast) == 7
        avg_value = np.mean(forecast)
        assert 10 <= avg_value <= 20

    def test_forecast_with_zero_history_returns_default(self):
        sku = self.sku_without_history
        sku.reorder_point = 300
        self.db.commit()

        data = self.forecast_service.get_history_data(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            days=90,
        )

        assert len(data) > 0
        assert data.sum() == 0

        _, forecast, _ = self.forecast_service.forecast_demand(
            sku_id=sku.id,
            method=ForecastMethodEnum.ARIMA,
            periods=30,
            warehouse_id=self.warehouse.id,
            history_days=90,
        )

        assert len(forecast) == 30

    def test_seasonality_analysis_with_insufficient_data(self):
        sku = self.sku_without_history

        for i in range(20):
            transaction_date = datetime.utcnow() - timedelta(days=19 - i)
            quantity = -(10 + i % 7 * 3)
            self.factory.inventory_transaction.create(
                sku_id=sku.id,
                warehouse_id=self.warehouse.id,
                transaction_type=TransactionType.SALE,
                quantity=quantity,
                created_at=transaction_date,
            )
        self.db.commit()

        result = self.forecast_service.analyze_seasonality(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            history_days=60,
        )

        assert result["has_seasonality"] is False
        assert result["period"] is None


class TestReplenishmentSuggestion:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.user = self.factory.user.create(username="replenishment_user")
        self.product, skus = self.factory.create_product_with_sku(num_skus=3)
        self.sku1, self.sku2, self.sku3 = skus
        self.warehouse = self.factory.warehouse.create()
        self.supplier = self.factory.supplier.create()
        self.db.commit()

        self.alert_service = AlertService(self.db, self.user)
        self.forecast_service = ForecastService(self.db)

    def test_replenishment_suggestion_quantity_calculation(self):
        self.sku1.maximum_stock = 500
        self.sku1.lead_time_days = 7
        inv1 = self.factory.inventory.create(
            sku_id=self.sku1.id,
            warehouse_id=self.warehouse.id,
            quantity=150,
        )
        self.db.commit()

        for i in range(60):
            transaction_date = datetime.utcnow() - timedelta(days=59 - i)
            quantity = -(8 + i % 4 * 2)
            self.factory.inventory_transaction.create(
                sku_id=self.sku1.id,
                warehouse_id=self.warehouse.id,
                transaction_type=TransactionType.SALE,
                quantity=quantity,
                created_at=transaction_date,
            )
        self.db.commit()

        _, forecast, _ = self.forecast_service.forecast_demand(
            sku_id=self.sku1.id,
            method=ForecastMethodEnum.MOVING_AVERAGE,
            periods=30,
            warehouse_id=self.warehouse.id,
            history_days=60,
        )

        lead_time_demand = self.forecast_service.calculate_lead_time_demand(
            sku_id=self.sku1.id,
            lead_time_days=7,
            warehouse_id=self.warehouse.id,
            method=ForecastMethodEnum.MOVING_AVERAGE,
            history_days=60,
        )

        safety_stock = self.forecast_service.calculate_safety_stock(
            sku_id=self.sku1.id,
            warehouse_id=self.warehouse.id,
            service_level=0.95,
            lead_time_days=7,
            history_days=60,
        )

        total_required = lead_time_demand + float(np.sum(forecast)) + safety_stock
        suggested_qty = max(0, int(total_required) - inv1.quantity)

        assert suggested_qty > 0
        assert suggested_qty <= 500 - inv1.quantity

    def test_replenishment_with_lead_time_weighting(self):
        self.sku1.lead_time_days = 14
        self.sku2.lead_time_days = 7
        inv1 = self.factory.inventory.create(
            sku_id=self.sku1.id,
            warehouse_id=self.warehouse.id,
            quantity=100,
        )
        inv2 = self.factory.inventory.create(
            sku_id=self.sku2.id,
            warehouse_id=self.warehouse.id,
            quantity=100,
        )
        self.db.commit()

        for i in range(60):
            transaction_date = datetime.utcnow() - timedelta(days=59 - i)
            for sku in [self.sku1, self.sku2]:
                self.factory.inventory_transaction.create(
                    sku_id=sku.id,
                    warehouse_id=self.warehouse.id,
                    transaction_type=TransactionType.SALE,
                    quantity=-(5 + i % 5),
                    created_at=transaction_date,
                )
        self.db.commit()

        lead_time_demand1 = self.forecast_service.calculate_lead_time_demand(
            sku_id=self.sku1.id,
            lead_time_days=14,
            warehouse_id=self.warehouse.id,
            method=ForecastMethodEnum.MOVING_AVERAGE,
            history_days=60,
        )

        lead_time_demand2 = self.forecast_service.calculate_lead_time_demand(
            sku_id=self.sku2.id,
            lead_time_days=7,
            warehouse_id=self.warehouse.id,
            method=ForecastMethodEnum.MOVING_AVERAGE,
            history_days=60,
        )

        assert lead_time_demand1 > lead_time_demand2

    def test_auto_forecast_selects_best_model(self):
        self.sku1.reorder_point = 150
        self.db.commit()

        for i in range(100):
            transaction_date = datetime.utcnow() - timedelta(days=99 - i)
            quantity = -(10 + (i // 10) * 2 + i % 5)
            self.factory.inventory_transaction.create(
                sku_id=self.sku1.id,
                warehouse_id=self.warehouse.id,
                transaction_type=TransactionType.SALE,
                quantity=quantity,
                created_at=transaction_date,
            )
        self.db.commit()

        best_model, forecast, all_metrics = self.forecast_service.auto_forecast(
            sku_id=self.sku1.id,
            periods=30,
            warehouse_id=self.warehouse.id,
            history_days=90,
        )

        assert best_model in ["ma", "es", "des", "lr", "arima"]
        assert len(forecast) == 30
        assert len(all_metrics) > 0

    def test_safety_stock_calculation(self):
        self.sku1.reorder_point = 150
        self.db.commit()

        for i in range(60):
            transaction_date = datetime.utcnow() - timedelta(days=59 - i)
            quantity = -(10 + i % 5 * 3)
            self.factory.inventory_transaction.create(
                sku_id=self.sku1.id,
                warehouse_id=self.warehouse.id,
                transaction_type=TransactionType.SALE,
                quantity=quantity,
                created_at=transaction_date,
            )
        self.db.commit()

        safety_stock = self.forecast_service.calculate_safety_stock(
            sku_id=self.sku1.id,
            warehouse_id=self.warehouse.id,
            service_level=0.95,
            lead_time_days=7,
            history_days=60,
        )

        assert safety_stock >= 0

        safety_stock_99 = self.forecast_service.calculate_safety_stock(
            sku_id=self.sku1.id,
            warehouse_id=self.warehouse.id,
            service_level=0.99,
            lead_time_days=7,
            history_days=60,
        )

        assert safety_stock_99 >= safety_stock


class TestAlertLifecycleManagement:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.user = self.factory.user.create(username="alert_user")
        self.product, skus = self.factory.create_product_with_sku(num_skus=1)
        self.sku = skus[0]
        self.warehouse = self.factory.warehouse.create()
        self.db.commit()

        self.alert_service = AlertService(self.db, self.user)

        self.sku.safety_stock = 100
        self.factory.inventory.create(
            sku_id=self.sku.id,
            warehouse_id=self.warehouse.id,
            quantity=30,
        )
        self.db.commit()

    def test_alert_acknowledge(self):
        alert_rule_create = AlertRuleCreate(
            name="测试预警",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
            sku_ids=[self.sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        assert len(result.new_alerts) == 1
        alert_id = result.new_alerts[0].id

        acknowledge_request = AlertAcknowledgeRequest(
            remark="已收到，正在处理",
        )

        acknowledged_alert = self.alert_service.acknowledge_alert(
            alert_id, acknowledge_request
        )
        self.db.commit()

        assert acknowledged_alert.status == AlertStatus.ACKNOWLEDGED
        assert acknowledged_alert.acknowledged_by == self.user.id
        assert acknowledged_alert.acknowledged_at is not None

    def test_alert_resolve(self):
        alert_rule_create = AlertRuleCreate(
            name="测试预警",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
            sku_ids=[self.sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        alert_id = result.new_alerts[0].id

        resolve_request = AlertResolveRequest(
            resolution="已补货，库存恢复正常",
            remark="采购单PO-2024-001已入库",
        )

        resolved_alert = self.alert_service.resolve_alert(alert_id, resolve_request)
        self.db.commit()

        assert resolved_alert.status == AlertStatus.RESOLVED
        assert resolved_alert.resolved_by == self.user.id
        assert resolved_alert.resolved_at is not None

    def test_alert_close(self):
        alert_rule_create = AlertRuleCreate(
            name="测试预警",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
            sku_ids=[self.sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        alert_id = result.new_alerts[0].id

        closed_alert = self.alert_service.close_alert(alert_id)
        self.db.commit()

        assert closed_alert.status == AlertStatus.CLOSED

    def test_cannot_acknowledge_resolved_alert(self):
        alert_rule_create = AlertRuleCreate(
            name="测试预警",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
            sku_ids=[self.sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        alert_id = result.new_alerts[0].id

        resolve_request = AlertResolveRequest(resolution="已处理")
        self.alert_service.resolve_alert(alert_id, resolve_request)
        self.db.commit()

        with pytest.raises(ValueError) as exc_info:
            self.alert_service.acknowledge_alert(
                alert_id, AlertAcknowledgeRequest(remark="test")
            )

        assert "Cannot acknowledge" in str(exc_info.value)

    def test_auto_resolve_when_condition_clears(self):
        alert_rule_create = AlertRuleCreate(
            name="低库存预警",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
            sku_ids=[self.sku.id],
            warehouse_ids=[self.warehouse.id],
        )

        rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        result = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        assert len(result.new_alerts) == 1

        inventory = (
            self.db.query(Inventory)
            .filter(Inventory.sku_id == self.sku.id)
            .filter(Inventory.warehouse_id == self.warehouse.id)
            .first()
        )
        inventory.quantity = 200
        self.db.commit()

        result2 = self.alert_service.check_alerts_sync(rule_id=rule.id)
        self.db.commit()

        assert result2.resolved_alerts_count == 1

        resolved_alert = (
            self.db.query(InventoryAlert)
            .filter(InventoryAlert.sku_id == self.sku.id)
            .first()
        )

        assert resolved_alert.status == AlertStatus.RESOLVED


class TestAlertRuleCrudOperations:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.user = self.factory.user.create()
        self.db.commit()

        self.alert_service = AlertService(self.db, self.user)

    def test_create_alert_rule(self):
        rule_create = AlertRuleCreate(
            name="测试预警规则",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
            description="测试规则",
        )

        rule = self.alert_service.create_rule(rule_create)
        self.db.commit()

        assert rule.id is not None
        assert rule.name == "测试预警规则"
        assert rule.rule_type == AlertRuleType.LOW_STOCK
        assert rule.is_active is True

    def test_update_alert_rule(self):
        rule_create = AlertRuleCreate(
            name="测试预警规则",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
        )

        rule = self.alert_service.create_rule(rule_create)
        self.db.commit()

        rule_update = AlertRuleUpdate(
            name="更新后的预警规则",
            threshold_value=150,
        )

        updated_rule = self.alert_service.update_rule(rule.id, rule_update)
        self.db.commit()

        assert updated_rule.name == "更新后的预警规则"
        assert updated_rule.threshold_value == 150

    def test_disable_alert_rule(self):
        rule_create = AlertRuleCreate(
            name="测试预警规则",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
        )

        rule = self.alert_service.create_rule(rule_create)
        self.db.commit()

        disabled_rule = self.alert_service.disable_rule(rule.id)
        self.db.commit()

        assert disabled_rule.is_active is False

    def test_enable_alert_rule(self):
        rule_create = AlertRuleCreate(
            name="测试预警规则",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=False,
        )

        rule = self.alert_service.create_rule(rule_create)
        self.db.commit()

        enabled_rule = self.alert_service.enable_rule(rule.id)
        self.db.commit()

        assert enabled_rule.is_active is True

    def test_delete_alert_rule(self):
        rule_create = AlertRuleCreate(
            name="测试预警规则",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
        )

        rule = self.alert_service.create_rule(rule_create)
        self.db.commit()

        deleted_rule = self.alert_service.delete_rule(rule.id)
        self.db.commit()

        assert deleted_rule is not None

        retrieved = self.alert_service.get_rule(rule.id)
        assert retrieved is None

    def test_list_alert_rules_with_filters(self):
        for i in range(5):
            rule_type = AlertRuleType.LOW_STOCK if i % 2 == 0 else AlertRuleType.HIGH_STOCK
            is_active = i % 2 == 0
            self.factory.alert_rule.create(
                name=f"规则{i}",
                rule_type=rule_type,
                is_active=is_active,
            )
        self.db.commit()

        rules, total, _ = self.alert_service.list_rules(
            rule_type=AlertRuleType.LOW_STOCK,
            is_active=True,
        )

        assert total == 3
        assert len(rules) == 3

    def test_alert_rule_duplicate_name_not_allowed(self):
        rule_create1 = AlertRuleCreate(
            name="唯一名称规则",
            code="RULE-001",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
        )

        self.alert_service.create_rule(rule_create1)
        self.db.commit()

        rule_create2 = AlertRuleCreate(
            name="唯一名称规则",
            code="RULE-002",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
        )

        with pytest.raises(ValueError):
            self.alert_service.create_rule(rule_create2)
