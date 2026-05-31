from typing import List, Dict, Any, Optional
from datetime import datetime, timezone
import hashlib
import random
import asyncio

from .schemas import (
    PromptStatus,
    ExperimentStatus,
    TrafficAllocationMode,
    MetricType,
    PromptVersion,
    PromptCreateRequest,
    PromptUpdateRequest,
    PromptVersionResponse,
    ABExperimentCreateRequest,
    ABExperimentUpdateRequest,
    ABExperimentResponse,
    ABVariant,
    ExperimentResult,
    ExperimentMetrics,
    PromptComparisonRequest,
    PromptComparisonResponse,
)
from common.logger import get_logger
from common.utils import generate_id, utc_now

logger = get_logger(__name__)


class PromptExperimentService:
    def __init__(self):
        self.prompts: Dict[str, Dict[str, Any]] = {}
        self.prompt_versions: Dict[str, List[PromptVersion]] = {}
        self.experiments: Dict[str, Dict[str, Any]] = {}
        self.experiment_results: Dict[str, List[ExperimentResult]] = {}

    async def create_prompt(self, request: PromptCreateRequest) -> PromptVersion:
        prompt_id = request.prompt_id

        if prompt_id in self.prompts:
            raise ValueError(f"Prompt {prompt_id} already exists")

        version_id = f"{prompt_id}_v1_{generate_id()[8:]}"
        now = utc_now()

        prompt_version = PromptVersion(
            version_id=version_id,
            prompt_id=prompt_id,
            version=1,
            content=request.content,
            description=request.description,
            variables=request.variables,
            system_prompt=request.system_prompt,
            temperature=request.temperature,
            max_tokens=request.max_tokens,
            top_p=request.top_p,
            frequency_penalty=request.frequency_penalty,
            presence_penalty=request.presence_penalty,
            stop_sequences=request.stop_sequences,
            status=PromptStatus.DRAFT,
            tags=request.tags or {},
            created_by=request.created_by,
            created_at=now,
            updated_at=now,
        )

        self.prompts[prompt_id] = {
            "prompt_id": prompt_id,
            "latest_version": 1,
            "created_at": now,
            "created_by": request.created_by,
        }
        self.prompt_versions[prompt_id] = [prompt_version]

        logger.info(f"Created prompt {prompt_id} v1")
        return prompt_version

    async def update_prompt(
        self, prompt_id: str, request: PromptUpdateRequest
    ) -> PromptVersion:
        if prompt_id not in self.prompts:
            raise ValueError(f"Prompt {prompt_id} not found")

        current_versions = self.prompt_versions.get(prompt_id, [])
        if not current_versions:
            raise ValueError(f"No versions found for prompt {prompt_id}")

        latest = current_versions[-1]
        new_version_num = latest.version + 1
        version_id = f"{prompt_id}_v{new_version_num}_{generate_id()[8:]}"
        now = utc_now()

        update_dict = request.model_dump(exclude_unset=True)
        if "updated_by" in update_dict:
            del update_dict["updated_by"]

        new_version = PromptVersion(
            version_id=version_id,
            prompt_id=prompt_id,
            version=new_version_num,
            content=request.content or latest.content,
            description=request.description if request.description is not None else latest.description,
            variables=request.variables if request.variables is not None else latest.variables,
            system_prompt=request.system_prompt if request.system_prompt is not None else latest.system_prompt,
            temperature=request.temperature if request.temperature is not None else latest.temperature,
            max_tokens=request.max_tokens if request.max_tokens is not None else latest.max_tokens,
            top_p=request.top_p if request.top_p is not None else latest.top_p,
            frequency_penalty=request.frequency_penalty if request.frequency_penalty is not None else latest.frequency_penalty,
            presence_penalty=request.presence_penalty if request.presence_penalty is not None else latest.presence_penalty,
            stop_sequences=request.stop_sequences if request.stop_sequences is not None else latest.stop_sequences,
            status=request.status or latest.status,
            tags=request.tags if request.tags is not None else latest.tags,
            created_by=latest.created_by,
            created_at=now,
            updated_at=now,
        )

        current_versions.append(new_version)
        self.prompts[prompt_id]["latest_version"] = new_version_num

        logger.info(f"Created prompt {prompt_id} v{new_version_num}")
        return new_version

    def get_prompt_versions(self, prompt_id: str) -> PromptVersionResponse:
        if prompt_id not in self.prompts:
            raise ValueError(f"Prompt {prompt_id} not found")

        versions = self.prompt_versions.get(prompt_id, [])
        return PromptVersionResponse(
            prompt_id=prompt_id,
            latest_version=self.prompts[prompt_id]["latest_version"],
            versions=versions,
        )

    def get_prompt_version(self, prompt_id: str, version: Optional[int] = None) -> PromptVersion:
        if prompt_id not in self.prompt_versions:
            raise ValueError(f"Prompt {prompt_id} not found")

        versions = self.prompt_versions[prompt_id]
        if not versions:
            raise ValueError(f"No versions found for prompt {prompt_id}")

        if version is None:
            return versions[-1]

        for v in versions:
            if v.version == version:
                return v
        raise ValueError(f"Version {version} not found for prompt {prompt_id}")

    def list_prompts(self, status: Optional[PromptStatus] = None) -> List[PromptVersion]:
        result = []
        for prompt_id, versions in self.prompt_versions.items():
            if versions:
                latest = versions[-1]
                if status is None or latest.status == status:
                    result.append(latest)
        return result

    async def create_experiment(
        self, request: ABExperimentCreateRequest
    ) -> ABExperimentResponse:
        experiment_id = request.experiment_id

        if experiment_id in self.experiments:
            raise ValueError(f"Experiment {experiment_id} already exists")

        self._validate_variants(request.variants)

        for variant in request.variants:
            prompt_id = variant.prompt_version_id.rsplit("_v", 1)[0]
            if prompt_id not in self.prompts:
                raise ValueError(f"Prompt version {variant.prompt_version_id} not found")

        now = utc_now()
        experiment = {
            "experiment_id": experiment_id,
            "name": request.name,
            "description": request.description,
            "status": ExperimentStatus.CREATED,
            "variants": request.variants,
            "traffic_allocation_mode": request.traffic_allocation_mode,
            "target_sample_size": request.target_sample_size,
            "metrics": request.metrics,
            "start_time": request.start_time,
            "end_time": request.end_time,
            "created_by": request.created_by,
            "created_at": now,
            "updated_at": now,
        }

        self.experiments[experiment_id] = experiment
        self.experiment_results[experiment_id] = []

        logger.info(f"Created experiment {experiment_id}")
        return self._build_experiment_response(experiment)

    async def update_experiment(
        self, experiment_id: str, request: ABExperimentUpdateRequest
    ) -> ABExperimentResponse:
        if experiment_id not in self.experiments:
            raise ValueError(f"Experiment {experiment_id} not found")

        experiment = self.experiments[experiment_id]

        if request.name is not None:
            experiment["name"] = request.name
        if request.description is not None:
            experiment["description"] = request.description
        if request.status is not None:
            experiment["status"] = request.status
        if request.variants is not None:
            self._validate_variants(request.variants)
            experiment["variants"] = request.variants
        if request.end_time is not None:
            experiment["end_time"] = request.end_time

        experiment["updated_at"] = utc_now()

        logger.info(f"Updated experiment {experiment_id}")
        return self._build_experiment_response(experiment)

    def get_experiment(self, experiment_id: str) -> ABExperimentResponse:
        if experiment_id not in self.experiments:
            raise ValueError(f"Experiment {experiment_id} not found")

        return self._build_experiment_response(self.experiments[experiment_id])

    def list_experiments(
        self,
        status: Optional[ExperimentStatus] = None,
        created_by: Optional[str] = None,
    ) -> List[ABExperimentResponse]:
        results = []
        for exp in self.experiments.values():
            if status and exp["status"] != status:
                continue
            if created_by and exp["created_by"] != created_by:
                continue
            results.append(self._build_experiment_response(exp))
        return results

    def allocate_variant(
        self,
        experiment_id: str,
        user_id: Optional[str] = None,
        session_id: Optional[str] = None,
        attributes: Optional[Dict[str, Any]] = None,
    ) -> Optional[ABVariant]:
        if experiment_id not in self.experiments:
            return None

        experiment = self.experiments[experiment_id]
        if experiment["status"] not in [ExperimentStatus.RUNNING, ExperimentStatus.CREATED]:
            return None

        variants = experiment["variants"]
        if not variants:
            return None

        mode = experiment["traffic_allocation_mode"]
        hash_input = ""

        if mode == TrafficAllocationMode.USER_ID and user_id:
            hash_input = user_id
        elif mode == TrafficAllocationMode.SESSION_ID and session_id:
            hash_input = session_id
        elif mode == TrafficAllocationMode.ATTRIBUTE_BASED and attributes:
            hash_input = str(sorted(attributes.items()))
        else:
            hash_input = generate_id()

        hash_val = int(hashlib.md5(hash_input.encode()).hexdigest(), 16)
        rand = (hash_val % 10000) / 100.0

        cumulative = 0.0
        for variant in variants:
            cumulative += variant.traffic_percentage
            if rand < cumulative:
                return variant

        return variants[0]

    async def record_experiment_result(
        self,
        experiment_id: str,
        variant_id: str,
        metric_values: Dict[MetricType, float],
    ) -> ExperimentResult:
        if experiment_id not in self.experiments:
            raise ValueError(f"Experiment {experiment_id} not found")

        experiment = self.experiments[experiment_id]
        metrics_config = experiment["metrics"]

        metrics: List[ExperimentMetrics] = []
        control_variant = next((v for v in experiment["variants"] if v.is_control), None)

        for metric_type in metrics_config:
            variant_value = metric_values.get(metric_type, 0.0)
            control_value = variant_value

            if control_variant and control_variant.variant_id != variant_id:
                control_results = [
                    r for r in self.experiment_results.get(experiment_id, [])
                    if r.variant_id == control_variant.variant_id
                ]
                if control_results:
                    latest = control_results[-1]
                    for m in latest.metrics:
                        if m.metric_type == metric_type:
                            control_value = m.variant_value
                            break

            diff = variant_value - control_value
            metrics.append(
                ExperimentMetrics(
                    metric_type=metric_type,
                    control_value=control_value,
                    variant_value=variant_value,
                    difference=diff,
                    is_statistically_significant=abs(diff) > 0.05,
                    p_value=0.05 if abs(diff) > 0.05 else 0.1,
                )
            )

        result = ExperimentResult(
            experiment_id=experiment_id,
            variant_id=variant_id,
            total_samples=len(self.experiment_results.get(experiment_id, [])) + 1,
            metrics=metrics,
            started_at=experiment.get("start_time") or utc_now(),
            updated_at=utc_now(),
        )

        if experiment_id not in self.experiment_results:
            self.experiment_results[experiment_id] = []
        self.experiment_results[experiment_id].append(result)

        return result

    async def compare_prompts(
        self, request: PromptComparisonRequest
    ) -> PromptComparisonResponse:
        results: List[Dict[str, Any]] = []
        scores: Dict[str, float] = {vid: 0.0 for vid in request.prompt_version_ids}

        for test_case in request.test_cases:
            case_result = {"test_case": test_case, "prompt_results": []}
            for vid in request.prompt_version_ids:
                prompt_result = {
                    "prompt_version_id": vid,
                    "metrics": {m.value: random.uniform(0.5, 1.0) for m in request.evaluation_metrics},
                }
                avg_score = sum(prompt_result["metrics"].values()) / len(request.evaluation_metrics)
                scores[vid] += avg_score
                case_result["prompt_results"].append(prompt_result)
            results.append(case_result)

        num_cases = len(request.test_cases)
        if num_cases > 0:
            for vid in scores:
                scores[vid] /= num_cases

        ranking = sorted(scores.keys(), key=lambda x: scores[x], reverse=True)

        return PromptComparisonResponse(
            comparison_id=generate_id("comp_"),
            prompt_version_ids=request.prompt_version_ids,
            results=results,
            overall_ranking=ranking,
            total_test_cases=num_cases,
            completed_at=utc_now(),
        )

    @staticmethod
    def _validate_variants(variants: List[ABVariant]):
        if not variants:
            raise ValueError("At least one variant is required")

        control_count = sum(1 for v in variants if v.is_control)
        if control_count != 1:
            raise ValueError(f"Exactly one control variant is required, found {control_count}")

        total_traffic = sum(v.traffic_percentage for v in variants)
        if abs(total_traffic - 100.0) > 0.01:
            raise ValueError(f"Total traffic must be 100%, got {total_traffic}%")

    def _build_experiment_response(self, experiment: Dict[str, Any]) -> ABExperimentResponse:
        exp_id = experiment["experiment_id"]
        return ABExperimentResponse(
            experiment_id=exp_id,
            name=experiment["name"],
            description=experiment.get("description"),
            status=experiment["status"],
            variants=experiment["variants"],
            traffic_allocation_mode=experiment["traffic_allocation_mode"],
            target_sample_size=experiment.get("target_sample_size"),
            metrics=experiment["metrics"],
            results=self.experiment_results.get(exp_id, None),
            start_time=experiment.get("start_time"),
            end_time=experiment.get("end_time"),
            created_by=experiment.get("created_by"),
            created_at=experiment["created_at"],
            updated_at=experiment["updated_at"],
        )


prompt_experiment_service = PromptExperimentService()
