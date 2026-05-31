import asyncio
import uuid
import math
import statistics
from typing import Dict, List, Optional, Any
from collections import defaultdict
from datetime import datetime
from scipy import stats
from app.logging_module import get_logger
from .models import (
    PromptVersion, PromptCreateRequest, PromptStatus,
    ABExperiment, ExperimentConfig, ExperimentStatus,
    VariantMetrics, ExperimentResult, ComparisonReport
)


logger = get_logger(__name__)


class PromptVersionManager:
    def __init__(self):
        self._prompts: Dict[str, PromptVersion] = {}
        self._prompts_by_name: Dict[str, List[str]] = defaultdict(list)
        self._next_versions: Dict[str, int] = defaultdict(lambda: 1)
    
    def create_prompt(self, request: PromptCreateRequest, created_by: Optional[str] = None) -> PromptVersion:
        version_id = f"prompt_{uuid.uuid4().hex[:12]}"
        version = self._next_versions[request.name]
        
        prompt = PromptVersion(
            version_id=version_id,
            name=request.name,
            content=request.content,
            version=version,
            status=PromptStatus.DRAFT,
            description=request.description,
            tags=request.tags.copy(),
            variables=request.variables.copy(),
            created_by=created_by,
            metadata=request.metadata.copy()
        )
        
        self._prompts[version_id] = prompt
        self._prompts_by_name[request.name].append(version_id)
        self._next_versions[request.name] = version + 1
        
        logger.info(f"Created prompt version", name=request.name, version=version, version_id=version_id)
        return prompt
    
    def update_status(self, version_id: str, new_status: PromptStatus) -> Optional[PromptVersion]:
        prompt = self._prompts.get(version_id)
        if not prompt:
            return None
        
        prompt.status = new_status
        prompt.updated_at = datetime.utcnow()
        
        logger.info(f"Updated prompt status", version_id=version_id, new_status=new_status)
        return prompt
    
    def create_variant(
        self,
        base_version_id: str,
        new_content: str,
        changes_description: str,
        created_by: Optional[str] = None
    ) -> Optional[PromptVersion]:
        base = self._prompts.get(base_version_id)
        if not base:
            return None
        
        new_version = self.create_prompt(
            PromptCreateRequest(
                name=base.name,
                content=new_content,
                description=f"Variant of v{base.version}: {changes_description}",
                tags=base.tags.copy(),
                variables=base.variables.copy(),
                metadata={"parent_version_id": base_version_id, "changes": changes_description}
            ),
            created_by=created_by
        )
        
        new_version.parent_version_id = base_version_id
        return new_version
    
    def get_prompt(self, version_id: str) -> Optional[PromptVersion]:
        return self._prompts.get(version_id)
    
    def get_latest_version(self, name: str, status: Optional[PromptStatus] = None) -> Optional[PromptVersion]:
        version_ids = self._prompts_by_name.get(name, [])
        if not version_ids:
            return None
        
        candidates = []
        for vid in version_ids:
            prompt = self._prompts[vid]
            if status is None or prompt.status == status:
                candidates.append(prompt)
        
        if not candidates:
            return None
        
        candidates.sort(key=lambda p: p.version, reverse=True)
        return candidates[0]
    
    def list_prompts(
        self,
        name_filter: Optional[str] = None,
        status_filter: Optional[PromptStatus] = None,
        tag_filter: Optional[str] = None
    ) -> List[PromptVersion]:
        results = []
        
        for prompt in self._prompts.values():
            if name_filter and name_filter not in prompt.name:
                continue
            if status_filter and prompt.status != status_filter:
                continue
            if tag_filter and tag_filter not in prompt.tags:
                continue
            results.append(prompt)
        
        results.sort(key=lambda p: (p.name, -p.version))
        return results
    
    def compare_versions(
        self,
        base_version_id: str,
        comparison_version_ids: List[str],
        metrics: Dict[str, Dict[str, float]]
    ) -> ComparisonReport:
        base = self._prompts.get(base_version_id)
        if not base:
            raise ValueError(f"Base version not found: {base_version_id}")
        
        analysis = {}
        recommendations = []
        
        for metric_name, values in metrics.items():
            base_value = values.get(base_version_id)
            if base_value is None:
                continue
            
            metric_analysis = {
                "base_value": base_value,
                "comparisons": {}
            }
            
            for comp_vid in comparison_version_ids:
                comp_value = values.get(comp_vid)
                if comp_value is None:
                    continue
                
                improvement = comp_value - base_value
                improvement_pct = (improvement / abs(base_value)) * 100 if base_value != 0 else 0
                
                metric_analysis["comparisons"][comp_vid] = {
                    "value": comp_value,
                    "improvement": improvement,
                    "improvement_pct": improvement_pct,
                    "is_better": improvement > 0
                }
                
                if improvement_pct > 5:
                    recommendations.append(
                        f"Version {comp_vid} shows {improvement_pct:.1f}% improvement in {metric_name}"
                    )
        
        if not recommendations:
            recommendations.append("No significant differences found between versions")
        else:
            recommendations.append("Consider running a formal A/B test to validate improvements")
        
        return ComparisonReport(
            report_id=f"comp_{uuid.uuid4().hex[:12]}",
            base_version_id=base_version_id,
            comparison_version_ids=comparison_version_ids,
            metrics=analysis,
            recommendations=recommendations
        )


class ABExperimentManager:
    def __init__(self):
        self._experiments: Dict[str, ABExperiment] = {}
        self._experiment_data: Dict[str, Dict[str, List[float]]] = defaultdict(
            lambda: defaultdict(list)
        )
    
    def create_experiment(
        self,
        config: ExperimentConfig,
        created_by: Optional[str] = None
    ) -> ABExperiment:
        total_weight = sum(v.traffic_weight for v in config.variants)
        if abs(total_weight - 1.0) > 0.001:
            raise ValueError(f"Variant weights must sum to 1.0, got {total_weight}")
        
        experiment = ABExperiment(
            experiment_id=f"exp_{uuid.uuid4().hex[:12]}",
            name=config.name,
            config=config,
            created_by=created_by
        )
        
        self._experiments[experiment.experiment_id] = experiment
        self._experiment_data[experiment.experiment_id] = defaultdict(list)
        
        logger.info(f"Created A/B experiment", experiment_id=experiment.experiment_id, name=config.name)
        return experiment
    
    def start_experiment(self, experiment_id: str) -> bool:
        experiment = self._experiments.get(experiment_id)
        if not experiment or experiment.status != ExperimentStatus.CREATED:
            return False
        
        experiment.status = ExperimentStatus.RUNNING
        experiment.started_at = datetime.utcnow()
        
        logger.info(f"Started A/B experiment", experiment_id=experiment_id)
        return True
    
    def stop_experiment(self, experiment_id: str) -> bool:
        experiment = self._experiments.get(experiment_id)
        if not experiment or experiment.status != ExperimentStatus.RUNNING:
            return False
        
        experiment.status = ExperimentStatus.COMPLETED
        experiment.ended_at = datetime.utcnow()
        
        logger.info(f"Stopped A/B experiment", experiment_id=experiment_id)
        return True
    
    def assign_variant(self, experiment_id: str, user_id: str) -> Optional[str]:
        experiment = self._experiments.get(experiment_id)
        if not experiment or experiment.status != ExperimentStatus.RUNNING:
            return None
        
        import hashlib
        hash_value = int(hashlib.md5(f"{experiment_id}:{user_id}".encode()).hexdigest(), 16) / (2**128)
        
        if hash_value > experiment.config.traffic_allocation:
            return None
        
        cumulative = 0.0
        for variant in experiment.config.variants:
            cumulative += variant.traffic_weight
            if hash_value / experiment.config.traffic_allocation <= cumulative:
                return variant.variant_id
        
        return experiment.config.variants[-1].variant_id
    
    def record_metric(
        self,
        experiment_id: str,
        variant_id: str,
        metric_name: str,
        value: float
    ):
        if experiment_id in self._experiment_data:
            key = f"{variant_id}:{metric_name}"
            self._experiment_data[experiment_id][key].append(value)
    
    def get_experiment_result(self, experiment_id: str) -> Optional[ExperimentResult]:
        experiment = self._experiments.get(experiment_id)
        if not experiment:
            return None
        
        data = self._experiment_data[experiment_id]
        variants_metrics = []
        all_values = {}
        
        for variant in experiment.config.variants:
            variant_data = VariantMetrics(
                variant_id=variant.variant_id,
                prompt_version_id=variant.prompt_version_id
            )
            
            metric_key = f"{variant.variant_id}:{experiment.config.primary_metric}"
            values = data.get(metric_key, [])
            
            if values:
                variant_data.total_samples = len(values)
                variant_data.metrics[experiment.config.primary_metric] = statistics.mean(values)
                variant_data.metric_stats[experiment.config.primary_metric] = {
                    "mean": statistics.mean(values),
                    "median": statistics.median(values),
                    "std": statistics.stdev(values) if len(values) > 1 else 0,
                    "min": min(values),
                    "max": max(values)
                }
                all_values[variant.variant_id] = values
            
            variants_metrics.append(variant_data)
        
        winner = None
        is_significant = False
        p_value = None
        effect_size = None
        
        if len(variants_metrics) >= 2 and all(v.total_samples > 0 for v in variants_metrics):
            best_variant = max(
                variants_metrics,
                key=lambda v: v.metrics.get(experiment.config.primary_metric, 0)
            )
            
            if len(variants_metrics) == 2:
                v1 = variants_metrics[0]
                v2 = variants_metrics[1]
                
                values1 = all_values.get(v1.variant_id, [])
                values2 = all_values.get(v2.variant_id, [])
                
                if len(values1) >= 10 and len(values2) >= 10:
                    t_stat, p_val = stats.ttest_ind(values1, values2, equal_var=False)
                    p_value = p_val
                    is_significant = p_val < (1 - experiment.config.confidence_level)
                    
                    mean1 = statistics.mean(values1)
                    mean2 = statistics.mean(values2)
                    pooled_std = math.sqrt(
                        (statistics.variance(values1) * (len(values1)-1) + 
                         statistics.variance(values2) * (len(values2)-1)) / 
                        (len(values1) + len(values2) - 2)
                    ) if (len(values1) + len(values2)) > 2 else 0
                    
                    effect_size = abs(mean1 - mean2) / pooled_std if pooled_std > 0 else 0
                    
                    if is_significant:
                        winner = best_variant.variant_id
            else:
                winner = best_variant.variant_id
        
        return ExperimentResult(
            experiment_id=experiment_id,
            status=experiment.status,
            variants=variants_metrics,
            winner=winner,
            is_statistically_significant=is_significant,
            p_value=p_value,
            effect_size=effect_size
        )
    
    def list_experiments(
        self,
        status_filter: Optional[ExperimentStatus] = None
    ) -> List[ABExperiment]:
        results = []
        for exp in self._experiments.values():
            if status_filter and exp.status != status_filter:
                continue
            results.append(exp)
        
        results.sort(key=lambda e: e.created_at, reverse=True)
        return results
    
    def get_experiment(self, experiment_id: str) -> Optional[ABExperiment]:
        return self._experiments.get(experiment_id)
