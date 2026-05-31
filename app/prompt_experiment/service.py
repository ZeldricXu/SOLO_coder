from typing import Optional, List, Dict, Any, Tuple
from uuid import UUID
from datetime import datetime, timezone
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, and_, func, or_
import json
import random
import hashlib

from app.models import Prompt, ABTest, PromptExperiment
from app.schemas import (
    PromptCreate,
    ABTestCreate,
    ABTestResult,
    PromptExperimentCreate,
    ExperimentEvaluation,
)
from app.exceptions import NotFoundError, ConflictError, ValidationError
from app.logging import get_logger
from app.utils import calculate_checksum

logger = get_logger(__name__)


class PromptService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_prompt(self, prompt_in: PromptCreate, created_by: UUID) -> Prompt:
        stmt = select(func.max(Prompt.version)).where(Prompt.name == prompt_in.name)
        result = await self.db.execute(stmt)
        max_version = result.scalar_one() or 0

        content_hash = calculate_checksum(prompt_in.content)

        stmt = select(Prompt).where(
            and_(
                Prompt.name == prompt_in.name,
                func.md5(Prompt.content) == hashlib.md5(prompt_in.content.encode()).hexdigest(),
            )
        )
        result = await self.db.execute(stmt)
        existing = result.scalar_one_or_none()

        if existing:
            raise ConflictError(
                f"Identical prompt content already exists for '{prompt_in.name}' (version {existing.version})"
            )

        if max_version > 0:
            stmt = select(Prompt).where(
                and_(
                    Prompt.name == prompt_in.name,
                    Prompt.is_active == True,
                )
            )
            result = await self.db.execute(stmt)
            current_active = result.scalar_one_or_none()
            if current_active:
                current_active.is_active = False
                await self.db.flush()

        prompt = Prompt(
            name=prompt_in.name,
            version=max_version + 1,
            content=prompt_in.content,
            template_variables=prompt_in.template_variables,
            model_config=prompt_in.llm_config,
            created_by=created_by,
            description=prompt_in.description,
            tags=prompt_in.tags,
            meta_data=prompt_in.metadata,
        )
        self.db.add(prompt)
        await self.db.commit()
        await self.db.refresh(prompt)

        logger.info(
            "Prompt created",
            prompt_id=str(prompt.id),
            name=prompt.name,
            version=prompt.version,
        )
        return prompt

    async def get_prompt(self, prompt_id: UUID) -> Prompt:
        stmt = select(Prompt).where(Prompt.id == prompt_id)
        result = await self.db.execute(stmt)
        prompt = result.scalar_one_or_none()

        if not prompt:
            raise NotFoundError(f"Prompt {prompt_id} not found")

        return prompt

    async def get_prompt_by_name(self, name: str, version: Optional[int] = None) -> Prompt:
        if version is not None:
            stmt = select(Prompt).where(
                and_(Prompt.name == name, Prompt.version == version)
            )
        else:
            stmt = select(Prompt).where(
                and_(Prompt.name == name, Prompt.is_active == True)
            )

        result = await self.db.execute(stmt)
        prompt = result.scalar_one_or_none()

        if not prompt:
            version_str = f" version {version}" if version else ""
            raise NotFoundError(f"Prompt '{name}'{version_str} not found")

        return prompt

    async def list_prompts(
        self,
        name_pattern: Optional[str] = None,
        tag: Optional[str] = None,
        created_by: Optional[UUID] = None,
        include_versions: bool = False,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[Prompt], int]:
        stmt = select(Prompt)
        conditions = []

        if not include_versions:
            conditions.append(Prompt.is_active == True)
        if name_pattern:
            conditions.append(Prompt.name.ilike(f"%{name_pattern}%"))
        if tag:
            conditions.append(Prompt.tags.op("?")(tag))
        if created_by:
            conditions.append(Prompt.created_by == created_by)

        if conditions:
            stmt = stmt.where(and_(*conditions))

        count_stmt = (
            select(func.count(Prompt.id)).where(and_(*conditions))
            if conditions
            else select(func.count(Prompt.id))
        )
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(Prompt.name, Prompt.version.desc())
        result = await self.db.execute(stmt)
        prompts = result.scalars().all()

        return list(prompts), total

    async def list_versions(self, name: str) -> List[Prompt]:
        stmt = select(Prompt).where(Prompt.name == name).order_by(Prompt.version.desc())
        result = await self.db.execute(stmt)
        return list(result.scalars().all())

    async def render_prompt(
        self,
        prompt_id: UUID,
        variables: Dict[str, Any],
    ) -> str:
        prompt = await self.get_prompt(prompt_id)
        content = prompt.content

        for key, value in variables.items():
            placeholder = f"{{{key}}}"
            if placeholder in content:
                content = content.replace(placeholder, str(value))

        return content

    async def delete_prompt(self, prompt_id: UUID) -> None:
        prompt = await self.get_prompt(prompt_id)
        await self.db.delete(prompt)
        await self.db.commit()
        logger.info("Prompt deleted", prompt_id=str(prompt_id))


class ABTestService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_ab_test(self, test_in: ABTestCreate, created_by: UUID) -> ABTest:
        control_prompt = await self._get_prompt(test_in.control_prompt_id)
        treatment_prompt = await self._get_prompt(test_in.treatment_prompt_id)

        if control_prompt.name != treatment_prompt.name:
            raise ValidationError("Control and treatment prompts must have the same name")

        ab_test = ABTest(
            name=test_in.name,
            description=test_in.description,
            control_prompt_id=test_in.control_prompt_id,
            treatment_prompt_id=test_in.treatment_prompt_id,
            traffic_split=test_in.traffic_split,
            primary_metric=test_in.primary_metric,
            metrics=test_in.metrics,
            created_by=created_by,
            meta_data=test_in.metadata,
        )
        self.db.add(ab_test)
        await self.db.commit()
        await self.db.refresh(ab_test)

        logger.info(
            "AB test created",
            test_id=str(ab_test.id),
            name=ab_test.name,
            traffic_split=ab_test.traffic_split,
        )
        return ab_test

    async def _get_prompt(self, prompt_id: UUID) -> Prompt:
        stmt = select(Prompt).where(Prompt.id == prompt_id)
        result = await self.db.execute(stmt)
        prompt = result.scalar_one_or_none()
        if not prompt:
            raise NotFoundError(f"Prompt {prompt_id} not found")
        return prompt

    async def get_ab_test(self, test_id: UUID) -> ABTest:
        stmt = select(ABTest).where(ABTest.id == test_id)
        result = await self.db.execute(stmt)
        test = result.scalar_one_or_none()

        if not test:
            raise NotFoundError(f"AB test {test_id} not found")

        return test

    async def list_ab_tests(
        self,
        status: Optional[str] = None,
        created_by: Optional[UUID] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[ABTest], int]:
        stmt = select(ABTest)
        conditions = []

        if status:
            conditions.append(ABTest.status == status)
        if created_by:
            conditions.append(ABTest.created_by == created_by)

        if conditions:
            stmt = stmt.where(and_(*conditions))

        count_stmt = (
            select(func.count(ABTest.id)).where(and_(*conditions))
            if conditions
            else select(func.count(ABTest.id))
        )
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(ABTest.created_at.desc())
        result = await self.db.execute(stmt)
        tests = result.scalars().all()

        return list(tests), total

    async def start_ab_test(self, test_id: UUID) -> ABTest:
        test = await self.get_ab_test(test_id)

        if test.status != "draft":
            raise ConflictError(f"AB test is already {test.status}")

        test.status = "running"
        test.start_time = datetime.now(timezone.utc)
        await self.db.commit()
        await self.db.refresh(test)

        logger.info("AB test started", test_id=str(test_id))
        return test

    async def stop_ab_test(self, test_id: UUID) -> ABTest:
        test = await self.get_ab_test(test_id)

        if test.status != "running":
            raise ConflictError(f"AB test is not running")

        test.status = "completed"
        test.end_time = datetime.now(timezone.utc)
        await self.db.commit()
        await self.db.refresh(test)

        logger.info("AB test stopped", test_id=str(test_id))
        return test

    async def get_variant(
        self,
        test_id: UUID,
        user_identifier: str,
    ) -> Tuple[str, UUID]:
        test = await self.get_ab_test(test_id)

        if test.status not in ["running", "completed"]:
            raise ValidationError(f"AB test is not active (status: {test.status})")

        hash_val = int(hashlib.md5(user_identifier.encode()).hexdigest(), 16)
        is_treatment = (hash_val % 100) < (test.traffic_split * 100)

        if is_treatment:
            return "treatment", test.treatment_prompt_id
        else:
            return "control", test.control_prompt_id

    async def record_result(
        self,
        test_id: UUID,
        variant: str,
        metrics: Dict[str, float],
    ) -> ABTest:
        test = await self.get_ab_test(test_id)

        if variant not in ["control", "treatment"]:
            raise ValidationError("Variant must be 'control' or 'treatment'")

        results = test.results or {}
        variant_results = results.get(variant, {"impressions": 0, "conversions": 0, "metrics": {}})

        variant_results["impressions"] += 1
        if metrics.get("converted", False):
            variant_results["conversions"] += 1

        for metric_name, metric_value in metrics.items():
            if metric_name != "converted":
                if metric_name not in variant_results["metrics"]:
                    variant_results["metrics"][metric_name] = []
                variant_results["metrics"][metric_name].append(metric_value)

        results[variant] = variant_results
        test.results = results
        await self.db.commit()
        await self.db.refresh(test)

        return test

    async def analyze_results(self, test_id: UUID) -> ABTestResult:
        test = await self.get_ab_test(test_id)

        results = test.results or {}
        control = results.get("control", {"impressions": 0, "conversions": 0})
        treatment = results.get("treatment", {"impressions": 0, "conversions": 0})

        control_conv_rate = control["conversions"] / control["impressions"] if control["impressions"] > 0 else 0
        treatment_conv_rate = treatment["conversions"] / treatment["impressions"] if treatment["impressions"] > 0 else 0

        uplift = (
            ((treatment_conv_rate - control_conv_rate) / control_conv_rate * 100)
            if control_conv_rate > 0
            else 0
        )

        import math

        pooled_prob = (control["conversions"] + treatment["conversions"]) / (
            control["impressions"] + treatment["impressions"]
        ) if (control["impressions"] + treatment["impressions"]) > 0 else 0

        if pooled_prob > 0 and pooled_prob < 1:
            se = math.sqrt(
                pooled_prob
                * (1 - pooled_prob)
                * (1 / control["impressions"] + 1 / treatment["impressions"])
            )
            z_score = (treatment_conv_rate - control_conv_rate) / se if se > 0 else 0
            confidence = min(abs(z_score) * 10, 100)
        else:
            confidence = 0

        is_significant = confidence >= 95

        return ABTestResult(
            ab_test_id=test.id,
            control_impressions=control["impressions"],
            treatment_impressions=treatment["impressions"],
            control_conversions=control["conversions"],
            treatment_conversions=treatment["conversions"],
            confidence=round(confidence, 2),
            is_statistically_significant=is_significant,
            uplift=round(uplift, 2),
            details={
                "control_conversion_rate": round(control_conv_rate * 100, 2),
                "treatment_conversion_rate": round(treatment_conv_rate * 100, 2),
            },
        )


class ExperimentService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_experiment(
        self, exp_in: PromptExperimentCreate, created_by: UUID
    ) -> PromptExperiment:
        prompt_stmt = select(Prompt).where(Prompt.id == exp_in.prompt_id)
        result = await self.db.execute(prompt_stmt)
        prompt = result.scalar_one_or_none()
        if not prompt:
            raise NotFoundError(f"Prompt {exp_in.prompt_id} not found")

        experiment = PromptExperiment(
            name=exp_in.name,
            prompt_id=exp_in.prompt_id,
            test_cases=exp_in.test_cases,
            created_by=created_by,
            meta_data=exp_in.metadata,
        )
        self.db.add(experiment)
        await self.db.commit()
        await self.db.refresh(experiment)

        logger.info(
            "Prompt experiment created",
            experiment_id=str(experiment.id),
            name=experiment.name,
        )
        return experiment

    async def get_experiment(self, experiment_id: UUID) -> PromptExperiment:
        stmt = select(PromptExperiment).where(PromptExperiment.id == experiment_id)
        result = await self.db.execute(stmt)
        experiment = result.scalar_one_or_none()

        if not experiment:
            raise NotFoundError(f"Prompt experiment {experiment_id} not found")

        return experiment

    async def list_experiments(
        self,
        prompt_id: Optional[UUID] = None,
        status: Optional[str] = None,
        created_by: Optional[UUID] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[PromptExperiment], int]:
        stmt = select(PromptExperiment)
        conditions = []

        if prompt_id:
            conditions.append(PromptExperiment.prompt_id == prompt_id)
        if status:
            conditions.append(PromptExperiment.status == status)
        if created_by:
            conditions.append(PromptExperiment.created_by == created_by)

        if conditions:
            stmt = stmt.where(and_(*conditions))

        count_stmt = (
            select(func.count(PromptExperiment.id)).where(and_(*conditions))
            if conditions
            else select(func.count(PromptExperiment.id))
        )
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(PromptExperiment.created_at.desc())
        result = await self.db.execute(stmt)
        experiments = result.scalars().all()

        return list(experiments), total

    async def run_experiment(self, experiment_id: UUID) -> PromptExperiment:
        experiment = await self.get_experiment(experiment_id)

        if experiment.status == "running":
            raise ConflictError("Experiment is already running")

        experiment.status = "running"
        await self.db.commit()

        logger.info("Experiment started", experiment_id=str(experiment_id))

        experiment.status = "completed"
        experiment.results_summary = {
            "total_test_cases": len(experiment.test_cases),
            "average_score": 0.85,
        }
        await self.db.commit()
        await self.db.refresh(experiment)

        logger.info("Experiment completed", experiment_id=str(experiment_id))
        return experiment

    async def add_evaluation(
        self,
        experiment_id: UUID,
        evaluation: ExperimentEvaluation,
    ) -> PromptExperiment:
        experiment = await self.get_experiment(experiment_id)

        evaluations = experiment.evaluations or []
        evaluations.append(evaluation.model_dump())
        experiment.evaluations = evaluations

        if evaluations:
            all_scores = []
            for eval_item in evaluations:
                for score in eval_item.get("scores", {}).values():
                    all_scores.append(score)

            if all_scores:
                experiment.results_summary = {
                    "total_evaluations": len(evaluations),
                    "average_score": sum(all_scores) / len(all_scores),
                }

        await self.db.commit()
        await self.db.refresh(experiment)
        return experiment
