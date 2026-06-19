use chrono::Utc;
use common::types::{Experiment, MetricDefinition};
use std::collections::HashMap;
use tracing::debug;
use uuid::Uuid;

use crate::stats::Statistics;
use crate::types::{ExperimentReport, GroupResult, MetricValue, StatSignificance};

const ALPHA: f64 = 0.05;
const CONFIDENCE_LEVEL: f64 = 0.95;
const MIN_SAMPLE_SIZE: usize = 30;

pub struct ReportGenerator;

impl ReportGenerator {
    pub fn generate(
        experiment: &Experiment,
        group_results: &[GroupResult],
        total_users: u64,
        duration_days: f64,
    ) -> ExperimentReport {
        debug!("Generating report for experiment: {}", experiment.id);

        let mut stat_significance = Vec::new();
        let mut conclusions = Vec::new();

        let control_group_name = experiment.control_group.name.clone();
        let control_result = group_results
            .iter()
            .find(|g| g.group_name == control_group_name);

        for metric_def in &experiment.metrics {
            let control_metric = control_result.and_then(|c| c.metrics.get(&metric_def.name));

            for exp_grp in group_results.iter().filter(|g| g.group_name != control_group_name) {
                let exp_metric = exp_grp.metrics.get(&metric_def.name);

                if let (Some(ctrl), Some(exp)) = (control_metric, exp_metric) {
                    let sig = Self::analyze_significance(
                        experiment.id,
                        metric_def,
                        &exp_grp.group_name,
                        &control_group_name,
                        ctrl,
                        exp,
                    );

                    let conclusion = Self::generate_conclusion(
                        metric_def,
                        &exp_grp.group_name,
                        &control_group_name,
                        &sig,
                    );
                    if !conclusion.is_empty() {
                        conclusions.push(conclusion);
                    }

                    stat_significance.push(sig);
                }
            }
        }

        let summary = Self::generate_summary(experiment, group_results, &stat_significance);
        conclusions.extend(summary);

        ExperimentReport {
            experiment_id: experiment.id,
            name: experiment.name.clone(),
            total_users,
            duration_days,
            groups: group_results.to_vec(),
            stat_significance,
            conclusions,
            generated_at: Utc::now(),
        }
    }

    fn analyze_significance(
        _experiment_id: Uuid,
        metric_def: &MetricDefinition,
        group_name: &str,
        control_group: &str,
        ctrl: &MetricValue,
        exp: &MetricValue,
    ) -> StatSignificance {
        let is_proportion = metric_def.metric_type == "proportion"
            || metric_def.name.contains("rate")
            || metric_def.name.contains("conversion")
            || metric_def.name.contains("ctr")
            || metric_def.name.contains("click");

        let (p_value, t_stat, z_stat) = if is_proportion {
            let count_ctrl = (ctrl.mean * ctrl.sample_count as f64).round() as u64;
            let count_exp = (exp.mean * exp.sample_count as f64).round() as u64;
            let (z, p) = Statistics::z_test_proportions(
                count_exp,
                exp.sample_count,
                count_ctrl,
                ctrl.sample_count,
            );
            (p, None, Some(z))
        } else {
            let n_ctrl = ctrl.sample_count as usize;
            let n_exp = exp.sample_count as usize;
            if n_ctrl >= MIN_SAMPLE_SIZE && n_exp >= MIN_SAMPLE_SIZE {
                let (t, p) = Statistics::t_test_from_aggregated(
                    exp.mean, exp.std, n_exp, ctrl.mean, ctrl.std, n_ctrl,
                );
                (p, Some(t), None)
            } else {
                (1.0, None, None)
            }
        };

        let mean_diff = exp.mean - ctrl.mean;
        let n_ctrl = ctrl.sample_count as usize;
        let n_exp = exp.sample_count as usize;
        let pooled_std =
            Statistics::pooled_std_from_aggregated(exp.std, n_exp, ctrl.std, n_ctrl);

        let effect_size = Statistics::cohens_d(exp.mean, ctrl.mean, pooled_std);

        let diff_se = if n_ctrl > 0 && n_exp > 0 {
            pooled_std * (1.0 / n_ctrl as f64 + 1.0 / n_exp as f64).sqrt()
        } else {
            0.0
        };
        let n_min = n_ctrl.min(n_exp).max(2);
        let confidence_interval =
            Statistics::confidence_interval(mean_diff, diff_se, n_min, CONFIDENCE_LEVEL);

        let uplift_percent = if ctrl.mean.abs() > 0.0 {
            ((exp.mean - ctrl.mean) / ctrl.mean.abs()) * 100.0
        } else if exp.mean > 0.0 {
            100.0
        } else {
            0.0
        };

        let relative_change = if ctrl.mean.abs() > 0.0 {
            (exp.mean - ctrl.mean) / ctrl.mean.abs()
        } else {
            0.0
        };

        let ci_contains_zero = confidence_interval.0 <= 0.0 && confidence_interval.1 >= 0.0;
        let is_significant =
            Statistics::is_statistically_significant(p_value, ALPHA) && !ci_contains_zero;

        StatSignificance {
            metric_name: metric_def.name.clone(),
            group_name: group_name.to_string(),
            control_group: control_group.to_string(),
            p_value,
            t_stat,
            z_stat,
            is_significant,
            effect_size,
            confidence_interval,
            confidence_level: CONFIDENCE_LEVEL,
            uplift_percent,
            relative_change,
        }
    }

    fn generate_conclusion(
        metric_def: &MetricDefinition,
        group_name: &str,
        control_group: &str,
        sig: &StatSignificance,
    ) -> String {
        let direction = if sig.relative_change > 0.0 {
            "提升"
        } else if sig.relative_change < 0.0 {
            "下降"
        } else {
            "持平"
        };

        let effect_desc = Statistics::interpret_effect_size(sig.effect_size);

        if sig.is_significant {
            format!(
                "指标 [{}]：实验组 [{}] 相对对照组 [{}] {} {:.2}%，具有统计显著性 (p={:.4}, 95% CI=[{:.4}, {:.4}], 效应量={} d={:.3})",
                metric_def.name,
                group_name,
                control_group,
                direction,
                sig.uplift_percent.abs(),
                sig.p_value,
                sig.confidence_interval.0,
                sig.confidence_interval.1,
                effect_desc,
                sig.effect_size.abs()
            )
        } else {
            let mut reasons = Vec::new();
            if sig.p_value >= ALPHA {
                reasons.push(format!("p={:.4} >= 0.05", sig.p_value));
            }
            let ci_contains_zero =
                sig.confidence_interval.0 <= 0.0 && sig.confidence_interval.1 >= 0.0;
            if ci_contains_zero {
                reasons.push("CI包含0".to_string());
            }
            format!(
                "指标 [{}]：实验组 [{}] 相对对照组 [{}] {} {:.2}%，差异不显著 ({})",
                metric_def.name,
                group_name,
                control_group,
                direction,
                sig.uplift_percent.abs(),
                reasons.join("; ")
            )
        }
    }

    fn generate_summary(
        experiment: &Experiment,
        results: &[GroupResult],
        significance: &[StatSignificance],
    ) -> Vec<String> {
        let mut conclusions = Vec::new();

        for exp_grp in experiment.experiment_groups.iter() {
            let group_sigs: Vec<&StatSignificance> = significance
                .iter()
                .filter(|s| s.group_name == exp_grp.name)
                .collect();

            if group_sigs.is_empty() {
                continue;
            }

            let significant_positive = group_sigs
                .iter()
                .filter(|s| s.is_significant && s.relative_change > 0.0)
                .count();
            let significant_negative = group_sigs
                .iter()
                .filter(|s| s.is_significant && s.relative_change < 0.0)
                .count();
            let not_significant =
                group_sigs.len() - significant_positive - significant_negative;

            conclusions.push(format!(
                "实验组 [{}] 总结：显著提升指标 {} 个，显著下降指标 {} 个，无显著差异 {} 个",
                exp_grp.name, significant_positive, significant_negative, not_significant
            ));

            if significant_positive > significant_negative {
                let avg_uplift: f64 = group_sigs
                    .iter()
                    .filter(|s| s.is_significant && s.relative_change > 0.0)
                    .map(|s| s.uplift_percent)
                    .sum::<f64>()
                    / significant_positive.max(1) as f64;

                conclusions.push(format!(
                    "→ 实验组 [{}] 整体表现优于对照组，平均显著提升 {:.2}%，建议考虑全量",
                    exp_grp.name, avg_uplift
                ));
            } else if significant_negative > significant_positive {
                let avg_drop: f64 = group_sigs
                    .iter()
                    .filter(|s| s.is_significant && s.relative_change < 0.0)
                    .map(|s| s.uplift_percent.abs())
                    .sum::<f64>()
                    / significant_negative.max(1) as f64;

                conclusions.push(format!(
                    "→ 实验组 [{}] 整体表现劣于对照组，平均显著下降 {:.2}%，不建议全量",
                    exp_grp.name, avg_drop
                ));
            } else if significant_positive == 0 && significant_negative == 0 {
                conclusions.push(format!(
                    "→ 实验组 [{}] 各项指标均无显著差异，建议延长实验周期以积累更多样本",
                    exp_grp.name
                ));
            } else {
                conclusions.push(format!(
                    "→ 实验组 [{}] 表现混合，建议重点关注核心指标再做决策",
                    exp_grp.name
                ));
            }
        }

        let grp_result: HashMap<&str, &GroupResult> = results
            .iter()
            .map(|g| (g.group_name.as_str(), g))
            .collect();

        for (name, result) in &grp_result {
            let total_samples: u64 = result
                .metrics
                .values()
                .map(|m| m.sample_count)
                .max()
                .unwrap_or(0);
            conclusions.push(format!("组 [{}]：累计观测 {} 个样本", name, total_samples));
        }

        conclusions
    }

    pub fn to_markdown(report: &ExperimentReport) -> String {
        let mut md = String::new();

        md.push_str(&format!("# A/B 实验报告: {}\n\n", report.name));
        md.push_str(&format!("**实验ID**: `{}`  \n", report.experiment_id));
        md.push_str(&format!("**生成时间**: {}  \n", report.generated_at));
        md.push_str(&format!(
            "**累计用户**: {} | **运行天数**: {:.1}\n\n",
            report.total_users, report.duration_days
        ));

        md.push_str("## 一、各组统计结果\n\n");
        md.push_str(
            "| 组名 | 模型版本ID | 指标名称 | 样本数 | 均值 | 标准差 | 最小值 | 最大值 |\n",
        );
        md.push_str(
            "|------|-----------|---------|--------|------|--------|--------|--------|\n",
        );

        for g in &report.groups {
            for (metric_name, mv) in &g.metrics {
                md.push_str(&format!(
                    "| {} | {} | {} | {} | {:.4} | {:.4} | {:.4} | {:.4} |\n",
                    g.group_name,
                    g.model_version_id,
                    metric_name,
                    mv.sample_count,
                    mv.mean,
                    mv.std,
                    mv.min,
                    mv.max
                ));
            }
        }

        md.push_str("\n## 二、统计显著性分析\n\n");
        md.push_str(
            "| 指标 | 实验组 | p值 | 是否显著 | 提升比例 | 效应量 | 95%置信区间 |\n",
        );
        md.push_str(
            "|------|--------|-----|----------|----------|--------|------------|\n",
        );

        for s in &report.stat_significance {
            let sig_flag = if s.is_significant { "✅ 是" } else { "❌ 否" };
            md.push_str(&format!(
                "| {} | {} | {:.4} | {} | {:+.2}% | {} ({:.3}) | [{:.4}, {:.4}] |\n",
                s.metric_name,
                s.group_name,
                s.p_value,
                sig_flag,
                s.uplift_percent,
                Statistics::interpret_effect_size(s.effect_size),
                s.effect_size.abs(),
                s.confidence_interval.0,
                s.confidence_interval.1
            ));
        }

        md.push_str("\n## 三、实验结论\n\n");
        for (i, c) in report.conclusions.iter().enumerate() {
            md.push_str(&format!("{}. {}\n", i + 1, c));
        }

        md
    }

    pub fn to_json(report: &ExperimentReport) -> serde_json::Value {
        serde_json::json!({
            "experiment_id": report.experiment_id.to_string(),
            "name": report.name,
            "total_users": report.total_users,
            "duration_days": report.duration_days,
            "generated_at": report.generated_at.to_rfc3339(),
            "groups": report.groups.iter().map(|g| {
                serde_json::json!({
                    "group_name": g.group_name,
                    "model_version_id": g.model_version_id.to_string(),
                    "metrics": g.metrics
                })
            }).collect::<Vec<_>>(),
            "stat_significance": report.stat_significance.iter().map(|s| {
                serde_json::json!({
                    "metric_name": s.metric_name,
                    "group_name": s.group_name,
                    "control_group": s.control_group,
                    "p_value": s.p_value,
                    "t_stat": s.t_stat,
                    "z_stat": s.z_stat,
                    "is_significant": s.is_significant,
                    "effect_size": s.effect_size,
                    "effect_size_label": Statistics::interpret_effect_size(s.effect_size),
                    "confidence_interval": [s.confidence_interval.0, s.confidence_interval.1],
                    "confidence_level": s.confidence_level,
                    "uplift_percent": s.uplift_percent,
                    "relative_change": s.relative_change,
                })
            }).collect::<Vec<_>>(),
            "conclusions": report.conclusions,
        })
    }
}
