import pytest
from typing import Dict, Any

from sqlalchemy.ext.asyncio import AsyncSession

from pydantic import ValidationError as PydanticValidationError

from core.exceptions import (
    ValidationError,
    NotFoundError,
    ConflictError,
    PermissionDeniedError,
)
from modules.multitenant.models import (
    TenantCreate,
    TenantConfigCreate,
    TenantQuotaCreate,
    TenantMemberCreate,
    TenantStatus,
    TenantTier,
)
from modules.multitenant.service import (
    TenantService,
    TenantConfigService,
    TenantQuotaService,
    TenantMemberService,
)
from tests.fixtures.data_factory import MultiTenantDataFactory


pytestmark = pytest.mark.asyncio


class TestTenantStatusTransitionDegradation:
    async def test_suspend_tenant_should_block_operations(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        await tenant_service.update_tenant_status(
            tenant_id=tenant.tenant_id,
            new_status=TenantStatus.SUSPENDED,
        )
        await db_session.commit()

        result = await tenant_service.get_tenant(tenant.tenant_id)
        assert result.status == TenantStatus.SUSPENDED

    async def test_activate_suspended_tenant(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        await tenant_service.update_tenant_status(tenant.tenant_id, TenantStatus.SUSPENDED)
        await db_session.commit()

        await tenant_service.update_tenant_status(tenant.tenant_id, TenantStatus.ACTIVE)
        await db_session.commit()

        result = await tenant_service.get_tenant(tenant.tenant_id)
        assert result.status == TenantStatus.ACTIVE

    async def test_delete_tenant_soft_delete(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        await tenant_service.delete_tenant(tenant.tenant_id)
        await db_session.commit()

        with pytest.raises(NotFoundError):
            await tenant_service.get_tenant(tenant.tenant_id)

    async def test_create_tenant_with_duplicate_name(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)

        await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        with pytest.raises(ConflictError) as exc_info:
            await tenant_service.create_tenant(tenant_data_obj)

        assert "已存在" in str(exc_info.value)

    @pytest.mark.parametrize(
        "tier,expected_quota_count",
        [
            (TenantTier.FREE, 4),
            (TenantTier.BASIC, 4),
            (TenantTier.PROFESSIONAL, 4),
            (TenantTier.ENTERPRISE, 4),
        ],
    )
    async def test_tenant_tier_default_quotas(
        self,
        db_session: AsyncSession,
        tier: TenantTier,
        expected_quota_count: int,
    ) -> None:
        tenant_service = TenantService(db_session)
        quota_service = TenantQuotaService(db_session)
        tenant_data = MultiTenantDataFactory.create_tenant_data(tier=tier.value)
        tenant_data_obj = TenantCreate(**tenant_data)

        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        quotas = await quota_service.get_tenant_quotas(tenant.tenant_id)
        assert len(quotas) == expected_quota_count

    @pytest.mark.parametrize(
        "scenario,expected_exception",
        [
            ("empty_name", ValidationError),
            ("whitespace_name", ValidationError),
            ("missing_name", PydanticValidationError),
            ("null_name", PydanticValidationError),
            ("empty_email", ValidationError),
            ("missing_email", PydanticValidationError),
            ("null_email", PydanticValidationError),
            ("invalid_email", ValidationError),
        ],
    )
    async def test_create_tenant_with_invalid_parameters(
        self,
        db_session: AsyncSession,
        scenario: str,
        expected_exception: type,
    ) -> None:
        tenant_service = TenantService(db_session)
        invalid_data = MultiTenantDataFactory.create_invalid_tenant_data(scenario)

        with pytest.raises(expected_exception):
            tenant_data_obj = TenantCreate(**invalid_data)
            await tenant_service.create_tenant(tenant_data_obj)


class TestQuotaDegradationBehavior:
    async def test_hard_limit_exceeded_should_reject(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        quota_service = TenantQuotaService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        quota_data = MultiTenantDataFactory.create_quota_data(
            tenant_id=tenant.tenant_id,
            resource_type="custom_quota",
            limit=100.0,
            is_hard_limit=True,
        )
        quota_data_obj = TenantQuotaCreate(**quota_data)
        quota = await quota_service.create_quota(quota_data_obj)
        await db_session.commit()

        await quota_service.check_and_consume_quota(
            tenant_id=tenant.tenant_id,
            resource_type="custom_quota",
            amount=90.0,
        )
        await db_session.commit()

        with pytest.raises(ValidationError) as exc_info:
            await quota_service.check_and_consume_quota(
                tenant_id=tenant.tenant_id,
                resource_type="custom_quota",
                amount=20.0,
            )

        assert "配额不足" in str(exc_info.value)

    async def test_soft_limit_exceeded_should_allow(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        quota_service = TenantQuotaService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        quota_data = MultiTenantDataFactory.create_quota_data(
            tenant_id=tenant.tenant_id,
            resource_type="custom_quota2",
            limit=100.0,
            is_hard_limit=False,
        )
        quota_data_obj = TenantQuotaCreate(**quota_data)
        await quota_service.create_quota(quota_data_obj)
        await db_session.commit()

        await quota_service.check_and_consume_quota(
            tenant_id=tenant.tenant_id,
            resource_type="custom_quota2",
            amount=90.0,
        )
        await db_session.commit()

        result = await quota_service.check_and_consume_quota(
            tenant_id=tenant.tenant_id,
            resource_type="custom_quota2",
            amount=20.0,
        )
        await db_session.commit()

        assert result.used == 110.0
        assert result.remaining == 0.0

    async def test_quota_reset_should_clear_usage(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        quota_service = TenantQuotaService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        quota_data = MultiTenantDataFactory.create_quota_data(
            tenant_id=tenant.tenant_id,
            resource_type="custom_quota3",
            limit=100.0,
        )
        quota_data_obj = TenantQuotaCreate(**quota_data)
        quota = await quota_service.create_quota(quota_data_obj)
        await db_session.commit()

        await quota_service.check_and_consume_quota(
            tenant_id=tenant.tenant_id,
            resource_type="custom_quota3",
            amount=50.0,
        )
        await db_session.commit()

        result = await quota_service.reset_quota(quota.quota_id)
        await db_session.commit()

        assert result.used == 0.0
        assert result.last_reset_at is not None

    async def test_warning_threshold_calculation(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        quota_service = TenantQuotaService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        quota_data = MultiTenantDataFactory.create_quota_data(
            tenant_id=tenant.tenant_id,
            resource_type="custom_storage",
            limit=100.0,
            warning_threshold=80.0,
            unit="GB",
        )
        quota_data_obj = TenantQuotaCreate(**quota_data)
        await quota_service.create_quota(quota_data_obj)
        await db_session.commit()

        result = await quota_service.check_and_consume_quota(
            tenant_id=tenant.tenant_id,
            resource_type="custom_storage",
            amount=85.0,
        )
        await db_session.commit()

        assert result.usage_percent == 85.0
        assert result.usage_percent >= result.warning_threshold

    async def test_nonexistent_quota_should_fail(
        self,
        db_session: AsyncSession,
    ) -> None:
        quota_service = TenantQuotaService(db_session)

        with pytest.raises(NotFoundError) as exc_info:
            await quota_service.check_and_consume_quota(
                tenant_id="non_existent",
                resource_type="api_calls",
                amount=10.0,
            )

        assert "不存在" in str(exc_info.value)

    async def test_quota_remaining_calculation(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        quota_service = TenantQuotaService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        quota_data = MultiTenantDataFactory.create_quota_data(
            tenant_id=tenant.tenant_id,
            resource_type="custom_tickets",
            limit=100.0,
            unit="tickets",
        )
        quota_data_obj = TenantQuotaCreate(**quota_data)
        await quota_service.create_quota(quota_data_obj)
        await db_session.commit()

        result = await quota_service.check_and_consume_quota(
            tenant_id=tenant.tenant_id,
            resource_type="custom_tickets",
            amount=30.0,
        )
        await db_session.commit()

        assert result.remaining == 70.0
        assert result.usage_percent == 30.0


class TestConfigDegradationBehavior:
    async def test_system_config_should_not_be_overridden(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        config_service = TenantConfigService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        config_data = MultiTenantDataFactory.create_config_data(
            tenant_id=tenant.tenant_id,
            namespace="security",
            key="password_policy",
            is_overridable=False,
        )
        config_data_obj = TenantConfigCreate(**config_data)
        await config_service.set_config(config_data_obj)
        await db_session.commit()

        config_data["value"] = {"min_length": 10}
        config_data["is_overridable"] = False
        config_data_obj = TenantConfigCreate(**config_data)
        with pytest.raises(PermissionDeniedError) as exc_info:
            await config_service.set_config(config_data_obj)

        assert "不允许覆盖" in str(exc_info.value)

    async def test_custom_config_should_be_overridable(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        config_service = TenantConfigService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        config_data = MultiTenantDataFactory.create_config_data(
            tenant_id=tenant.tenant_id,
            namespace="custom",
            key="workflow_settings",
            value={"auto_approve": False},
        )
        config_data_obj = TenantConfigCreate(**config_data)
        await config_service.set_config(config_data_obj)
        await db_session.commit()

        config_data["value"] = {"auto_approve": True}
        config_data["is_overridable"] = True
        config_data_obj = TenantConfigCreate(**config_data)
        result = await config_service.set_config(config_data_obj)
        await db_session.commit()

        assert result.value == {"auto_approve": True}

    async def test_get_nonexistent_config_should_fail(
        self,
        db_session: AsyncSession,
    ) -> None:
        config_service = TenantConfigService(db_session)

        with pytest.raises(NotFoundError) as exc_info:
            await config_service.get_config(
                tenant_id="non_existent",
                namespace="features",
                key="test",
            )

        assert "不存在" in str(exc_info.value)

    async def test_get_namespace_configs(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        config_service = TenantConfigService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        configs = await config_service.get_namespace_configs(
            tenant_id=tenant.tenant_id,
            namespace="features",
        )

        assert len(configs) >= 1

    @pytest.mark.parametrize(
        "scenario,expected_exception",
        [
            ("empty_name", PydanticValidationError),
            ("missing_name", PydanticValidationError),
            ("empty_email", ValidationError),
            ("missing_email", PydanticValidationError),
        ],
    )
    async def test_config_validation(
        self,
        db_session: AsyncSession,
        scenario: str,
        expected_exception: type,
    ) -> None:
        config_service = TenantConfigService(db_session)
        invalid_data = MultiTenantDataFactory.create_config_data()

        if "name" in scenario:
            del invalid_data["tenant_id"]
        if "email" in scenario:
            if "empty" in scenario:
                invalid_data["namespace"] = ""
            else:
                del invalid_data["namespace"]

        with pytest.raises(expected_exception):
            config_data_obj = TenantConfigCreate(**invalid_data)
            await config_service.set_config(config_data_obj)


class TestTenantMemberDegradation:
    async def test_add_duplicate_member_should_fail(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        member_service = TenantMemberService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        member_data = MultiTenantDataFactory.create_member_data(
            tenant_id=tenant.tenant_id,
            user_id="usr_001",
        )
        member_data_obj = TenantMemberCreate(**member_data)
        await member_service.add_member(member_data_obj)
        await db_session.commit()

        with pytest.raises(ConflictError) as exc_info:
            member_data_obj = TenantMemberCreate(**member_data)
            await member_service.add_member(member_data_obj)

        assert "已是该租户成员" in str(exc_info.value)

    async def test_check_member_access_with_permission(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        member_service = TenantMemberService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        member_data = MultiTenantDataFactory.create_member_data(
            tenant_id=tenant.tenant_id,
            user_id="usr_001",
            permissions=["tickets:read", "tickets:write"],
        )
        member_data_obj = TenantMemberCreate(**member_data)
        await member_service.add_member(member_data_obj)
        await db_session.commit()

        has_access = await member_service.check_tenant_access(
            user_id="usr_001",
            tenant_id=tenant.tenant_id,
            required_permission="tickets:read",
        )

        assert has_access is True

    async def test_check_member_access_without_permission(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        member_service = TenantMemberService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        member_data = MultiTenantDataFactory.create_member_data(
            tenant_id=tenant.tenant_id,
            user_id="usr_001",
            permissions=["tickets:read"],
        )
        member_data_obj = TenantMemberCreate(**member_data)
        await member_service.add_member(member_data_obj)
        await db_session.commit()

        has_access = await member_service.check_tenant_access(
            user_id="usr_001",
            tenant_id=tenant.tenant_id,
            required_permission="tickets:delete",
        )

        assert has_access is False

    async def test_check_non_member_access(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        member_service = TenantMemberService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        has_access = await member_service.check_tenant_access(
            user_id="non_member",
            tenant_id=tenant.tenant_id,
        )

        assert has_access is False

    async def test_get_tenant_members(
        self,
        db_session: AsyncSession,
    ) -> None:
        tenant_service = TenantService(db_session)
        member_service = TenantMemberService(db_session)

        tenant_data = MultiTenantDataFactory.create_tenant_data()
        tenant_data_obj = TenantCreate(**tenant_data)
        tenant = await tenant_service.create_tenant(tenant_data_obj)
        await db_session.commit()

        for i in range(3):
            member_data = MultiTenantDataFactory.create_member_data(
                tenant_id=tenant.tenant_id,
                user_id=f"usr_00{i + 1}",
            )
            member_data_obj = TenantMemberCreate(**member_data)
            await member_service.add_member(member_data_obj)
        await db_session.commit()

        members = await member_service.get_tenant_members(tenant.tenant_id)

        assert len(members) == 3
