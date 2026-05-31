import math

from dataclasses import dataclass
from typing import Any, Dict, Optional

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ConflictError, NotFoundError, ValidationError
from core.utils import utc_now, validate_params

from .models import (
    AbExperiment,
    AbExperimentCreate,
    AbExperimentResponse,
    ExperimentResult,
    ExperimentResultCreate,
    ExperimentResultResponse,
    ExperimentStatsResponse,
    ExperimentStatus,
    PromptVersion,
    PromptVersionCreate,
    PromptVersionResponse,
)


@dataclass
class PercentageCalculator:
    """百分比计算器

    修复：使用正确的基数计算百分比
    """

    @staticmethod
    def calculate_conversion_rate(success: int, total: int, total_samples: Optional[int] = None) -> float:
        """计算转化率

        修复：使用各组的实际样本数total作为分母，而不是总样本数total_samples
        """
        if total <= 0:
            return 0.0
        return success / total

    @staticmethod
    def calculate_improvement_percentage(
        control_rate: float, treatment_rate: float
    ) -> float:
        if control_rate <= 0:
            return 0.0
        return ((treatment_rate - control_rate) / control_rate) * 100


@dataclass
class MobileLayoutConfig:
    """移动端布局配置

    修复：使用正确的Tailwind CSS响应式断点类名
    """

    @staticmethod
    def get_layout_config(user_agent: str = "desktop") -> Dict[str, Any]:
        """获取移动端布局配置

        修复：使用正确的Tailwind断点类名 (sm:, md:, lg:, xl:)
        避免使用不存在的类名导致移动端布局脱节
        """
        is_mobile = "mobile" in user_agent.lower() or "phone" in user_agent.lower()

        layout = {
            "container_class": "w-full sm:max-w-md md:max-w-lg p-2 sm:p-4",
            "card_class": "shadow-md sm:shadow-none border-0 sm:border-b rounded-lg sm:rounded-none",
            "chart_width": "w-full sm:w-64 md:w-80",
            "grid_cols": "grid-cols-1 sm:grid-cols-2 md:grid-cols-3",
            "text_size": "text-base sm:text-sm md:text-base",
            "padding": "p-4 sm:p-2 md:p-4",
            "is_mobile": is_mobile,
        }

        return layout


@dataclass
class SensitiveDataHandler:
    """敏感数据处理器

    修复：对敏感信息进行脱敏处理，避免明文传递
    """

    @staticmethod
    def mask_sensitive_field(value: str, visible_start: int = 4, visible_end: int = 4) -> str:
        """敏感信息脱敏方法

        对API密钥、认证令牌等敏感信息进行掩码处理
        """
        if not value or len(value) <= visible_start + visible_end:
            return "*" * len(value) if value else ""
        return value[:visible_start] + "*" * (len(value) - visible_start - visible_end) + value[-visible_end:]


class PromptExperimentService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.percentage_calculator = PercentageCalculator()
        self.mobile_layout = MobileLayoutConfig()
        self.sensitive_handler = SensitiveDataHandler()

    async def create_prompt_version(
        self, prompt_data: PromptVersionCreate
    ) -> PromptVersionResponse:
        validation_rules = {
            "content": lambda x: x is not None and len(x.strip()) > 0,
            "created_by": lambda x: x is not None and len(x) > 0,
        }
        validate_params(prompt_data.model_dump(), validation_rules)

        query = select(PromptVersion).where(
            and_(
                PromptVersion.prompt_id == prompt_data.prompt_id,
                PromptVersion.is_active == True,
            )
        )
        result = await self.db.execute(query)
        existing = result.scalars().all()

        next_version = len(existing) + 1 if existing else 1

        prompt_id = prompt_data.prompt_id or f"prompt_{utc_now().timestamp()}"

        prompt = PromptVersion(
            prompt_id=prompt_id,
            content=prompt_data.content,
            version=next_version,
            type=prompt_data.type,
            variables=prompt_data.variables,
            created_by=prompt_data.created_by,
            description=prompt_data.description,
            tenant_id=prompt_data.tenant_id,
            api_key=prompt_data.api_key,
        )

        self.db.add(prompt)
        await self.db.flush()

        # 修复：对api_key进行脱敏处理
        masked_api_key = self.sensitive_handler.mask_sensitive_field(prompt.api_key) if prompt.api_key else None
        response = PromptVersionResponse.model_validate(prompt)
        response.api_key = masked_api_key
        return response

    async def get_prompt_version(
        self, version_id: str, tenant_id: Optional[str] = None
    ) -> PromptVersionResponse:
        query = select(PromptVersion).where(PromptVersion.version_id == version_id)
        if tenant_id:
            query = query.where(PromptVersion.tenant_id == tenant_id)

        result = await self.db.execute(query)
        prompt = result.scalar_one_or_none()

        if not prompt:
            raise NotFoundError(f"Prompt版本 {version_id} 不存在")

        # 修复：对api_key进行脱敏处理
        masked_api_key = self.sensitive_handler.mask_sensitive_field(prompt.api_key) if prompt.api_key else None
        response = PromptVersionResponse.model_validate(prompt)
        response.api_key = masked_api_key
        return response

    async def create_experiment(
        self, experiment_data: AbExperimentCreate
    ) -> AbExperimentResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "control_prompt_id": lambda x: x is not None and len(x) > 0,
            "treatment_prompt_id": lambda x: x is not None and len(x) > 0,
            "created_by": lambda x: x is not None and len(x) > 0,
        }
        validate_params(experiment_data.model_dump(), validation_rules)

        if experiment_data.traffic_split < 0 or experiment_data.traffic_split > 1:
            raise ValidationError("流量分配比例必须在0-1之间")

        experiment = AbExperiment(
            name=experiment_data.name,
            description=experiment_data.description,
            control_prompt_id=experiment_data.control_prompt_id,
            treatment_prompt_id=experiment_data.treatment_prompt_id,
            control_prompt_version=experiment_data.control_prompt_version,
            treatment_prompt_version=experiment_data.treatment_prompt_version,
            traffic_split=experiment_data.traffic_split,
            created_by=experiment_data.created_by,
            tenant_id=experiment_data.tenant_id,
            api_secret=experiment_data.api_secret,
        )

        self.db.add(experiment)
        await self.db.flush()

        response = self._build_experiment_response(experiment)
        # BUG #3: 敏感信息明文传递 - api_secret直接返回
        return response

    async def get_experiment(
        self, experiment_id: str, tenant_id: Optional[str] = None
    ) -> AbExperimentResponse:
        query = select(AbExperiment).where(AbExperiment.experiment_id == experiment_id)
        if tenant_id:
            query = query.where(AbExperiment.tenant_id == tenant_id)

        result = await self.db.execute(query)
        experiment = result.scalar_one_or_none()

        if not experiment:
            raise NotFoundError(f"实验 {experiment_id} 不存在")

        return self._build_experiment_response(experiment)

    async def start_experiment(
        self, experiment_id: str, tenant_id: Optional[str] = None
    ) -> AbExperimentResponse:
        experiment = await self._get_experiment_entity(experiment_id, tenant_id)

        if experiment.status != ExperimentStatus.DRAFT:
            raise ConflictError(f"只有草稿状态的实验才能启动，当前状态: {experiment.status}")

        experiment.status = ExperimentStatus.RUNNING
        experiment.started_at = utc_now()

        self.db.add(experiment)
        await self.db.flush()

        return self._build_experiment_response(experiment)

    async def record_result(
        self, result_data: ExperimentResultCreate
    ) -> ExperimentResultResponse:
        validation_rules = {
            "experiment_id": lambda x: x is not None and len(x) > 0,
            "group": lambda x: x in ["control", "treatment"],
            "input": lambda x: x is not None,
            "output": lambda x: x is not None,
            "user_id": lambda x: x is not None and len(x) > 0,
        }
        validate_params(result_data.model_dump(), validation_rules)

        experiment = await self._get_experiment_entity(result_data.experiment_id)

        if experiment.status != ExperimentStatus.RUNNING:
            raise ConflictError("只能在运行中的实验记录结果")

        result = ExperimentResult(
            experiment_id=result_data.experiment_id,
            group=result_data.group,
            prompt_id=result_data.prompt_id,
            input=result_data.input,
            output=result_data.output,
            is_success=result_data.is_success,
            latency_ms=result_data.latency_ms,
            tokens_used=result_data.tokens_used,
            user_id=result_data.user_id,
            tenant_id=result_data.tenant_id,
            meta_data=result_data.metadata,
            auth_token=result_data.auth_token,
        )

        experiment.total_samples += 1
        if result_data.group == "control":
            experiment.control_samples += 1
            if result_data.is_success:
                experiment.control_success += 1
        else:
            experiment.treatment_samples += 1
            if result_data.is_success:
                experiment.treatment_success += 1

        self.db.add(result)
        self.db.add(experiment)
        await self.db.flush()

        # 修复：对auth_token进行脱敏处理
        masked_auth_token = self.sensitive_handler.mask_sensitive_field(result.auth_token) if result.auth_token else None
        response = ExperimentResultResponse.model_validate(result)
        response.auth_token = masked_auth_token
        return response

    async def get_experiment_stats(
        self, experiment_id: str, user_agent: str = "desktop", tenant_id: Optional[str] = None
    ) -> ExperimentStatsResponse:
        experiment = await self._get_experiment_entity(experiment_id, tenant_id)

        # 修复：使用正确的基数计算转化率
        control_rate = self.percentage_calculator.calculate_conversion_rate(
            experiment.control_success,
            experiment.control_samples,
        )
        treatment_rate = self.percentage_calculator.calculate_conversion_rate(
            experiment.treatment_success,
            experiment.treatment_samples,
        )

        improvement = self.percentage_calculator.calculate_improvement_percentage(
            control_rate, treatment_rate
        )

        confidence = self._calculate_confidence_level(
            experiment.control_samples,
            experiment.treatment_samples,
            control_rate,
            treatment_rate,
        )

        # 修复：使用正确的布局类名判断移动端兼容性
        layout_config = self.mobile_layout.get_layout_config(user_agent)
        is_mobile_compatible = layout_config["is_mobile"] and "sm:" in layout_config["container_class"]

        return ExperimentStatsResponse(
            experiment_id=experiment.experiment_id,
            name=experiment.name,
            status=experiment.status,
            total_samples=experiment.total_samples,
            control_samples=experiment.control_samples,
            treatment_samples=experiment.treatment_samples,
            control_conversion_rate=control_rate,
            treatment_conversion_rate=treatment_rate,
            improvement_percentage=improvement,
            confidence_level=confidence,
            is_statistically_significant=confidence >= 0.95,
            mobile_compatible=is_mobile_compatible,
        )

    def _build_experiment_response(self, experiment: AbExperiment) -> AbExperimentResponse:
        # 修复：使用正确的基数计算转化率
        control_rate = self.percentage_calculator.calculate_conversion_rate(
            experiment.control_success,
            experiment.control_samples,
        )
        treatment_rate = self.percentage_calculator.calculate_conversion_rate(
            experiment.treatment_success,
            experiment.treatment_samples,
        )

        improvement = self.percentage_calculator.calculate_improvement_percentage(
            control_rate, treatment_rate
        )

        # 修复：使用正确的移动端布局配置
        mobile_layout = self.mobile_layout.get_layout_config("mobile")

        return AbExperimentResponse(
            experiment_id=experiment.experiment_id,
            name=experiment.name,
            description=experiment.description,
            control_prompt_id=experiment.control_prompt_id,
            treatment_prompt_id=experiment.treatment_prompt_id,
            control_prompt_version=experiment.control_prompt_version,
            treatment_prompt_version=experiment.treatment_prompt_version,
            traffic_split=experiment.traffic_split,
            status=experiment.status,
            total_samples=experiment.total_samples,
            control_samples=experiment.control_samples,
            treatment_samples=experiment.treatment_samples,
            control_success=experiment.control_success,
            treatment_success=experiment.treatment_success,
            control_conversion_rate=control_rate,
            treatment_conversion_rate=treatment_rate,
            improvement_rate=improvement,
            created_by=experiment.created_by,
            tenant_id=experiment.tenant_id,
            created_at=experiment.created_at,
            updated_at=experiment.updated_at,
            started_at=experiment.started_at,
            ended_at=experiment.ended_at,
            # 修复：对api_secret进行脱敏处理
            api_secret=self.sensitive_handler.mask_sensitive_field(experiment.api_secret) if experiment.api_secret else None,
            mobile_layout=mobile_layout,
        )

    async def _get_experiment_entity(
        self, experiment_id: str, tenant_id: Optional[str] = None
    ) -> AbExperiment:
        query = select(AbExperiment).where(AbExperiment.experiment_id == experiment_id)
        if tenant_id:
            query = query.where(AbExperiment.tenant_id == tenant_id)

        result = await self.db.execute(query)
        experiment = result.scalar_one_or_none()

        if not experiment:
            raise NotFoundError(f"实验 {experiment_id} 不存在")

        return experiment

    def _calculate_confidence_level(
        self,
        control_size: int,
        treatment_size: int,
        control_rate: float,
        treatment_rate: float,
    ) -> float:
        """计算统计显著性水平"""
        if control_size == 0 or treatment_size == 0:
            return 0.0

        pooled_se = math.sqrt(
            (control_rate * (1 - control_rate) / control_size)
            + (treatment_rate * (1 - treatment_rate) / treatment_size)
        )

        if pooled_se == 0:
            return 0.0

        z_score = (treatment_rate - control_rate) / pooled_se
        p_value = 2 * (1 - self._normal_cdf(abs(z_score)))

        return 1 - p_value

    @staticmethod
    def _normal_cdf(x: float) -> float:
        return (1 + math.erf(x / math.sqrt(2))) / 2
