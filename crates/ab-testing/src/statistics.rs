use crate::models::{Experiment, ExperimentGroup, StatisticalResult};

fn mean(values: &[f64]) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    values.iter().sum::<f64>() / values.len() as f64
}

fn std_dev(values: &[f64], mean_val: f64) -> f64 {
    if values.len() < 2 {
        return 0.0;
    }
    let variance = values
        .iter()
        .map(|x| (x - mean_val).powi(2))
        .sum::<f64>()
        / (values.len() - 1) as f64;
    variance.sqrt()
}

fn normal_cdf(x: f64) -> f64 {
    let a1 = 0.254829592;
    let a2 = -0.284496736;
    let a3 = 1.421413741;
    let a4 = -1.453152027;
    let a5 = 1.061405429;
    let p = 0.3275911;

    let sign = if x < 0.0 { -1.0 } else { 1.0 };
    let x = x.abs() / std::f64::consts::SQRT_2;

    let t = 1.0 / (1.0 + p * x);
    let y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * (-x * x).exp();

    0.5 * (1.0 + sign * y)
}

pub fn significance_test(
    control_values: &[f64],
    treatment_values: &[f64],
    confidence_level: f64,
) -> StatisticalResult {
    let control_mean = mean(control_values);
    let treatment_mean = mean(treatment_values);
    let control_std = std_dev(control_values, control_mean);
    let treatment_std = std_dev(treatment_values, treatment_mean);

    let n1 = control_values.len() as f64;
    let n2 = treatment_values.len() as f64;

    if n1 < 2.0 || n2 < 2.0 {
        return StatisticalResult {
            metric_name: String::new(),
            control_mean,
            treatment_mean,
            control_std,
            treatment_std,
            p_value: 1.0,
            is_significant: false,
            confidence_level,
        };
    }

    let se1 = control_std.powi(2) / n1;
    let se2 = treatment_std.powi(2) / n2;
    let se_sum = se1 + se2;

    let t_stat = if se_sum == 0.0 {
        0.0
    } else {
        (treatment_mean - control_mean) / se_sum.sqrt()
    };

    let df = if se_sum == 0.0 {
        1.0
    } else {
        (se_sum).powi(2) / (se1.powi(2) / (n1 - 1.0) + se2.powi(2) / (n2 - 1.0))
    };

    let z = t_stat * (df / (df - 2.0)).sqrt().max(0.0);
    let p_value = 2.0 * (1.0 - normal_cdf(z.abs()));

    let alpha = 1.0 - confidence_level;
    let is_significant = p_value < alpha;

    StatisticalResult {
        metric_name: String::new(),
        control_mean,
        treatment_mean,
        control_std,
        treatment_std,
        p_value,
        is_significant,
        confidence_level,
    }
}

pub fn analyze_experiment(experiment: &Experiment, metric_name: &str) -> StatisticalResult {
    let extractor: Box<dyn Fn(&crate::models::ExperimentMetrics) -> f64> = match metric_name {
        "cache_hit_rate" => Box::new(|m| m.cache_hit_rate),
        "avg_latency_ms" => Box::new(|m| m.avg_latency_ms),
        "origin_fetch_rate" => Box::new(|m| m.origin_fetch_rate),
        "user_qoe_score" => Box::new(|m| m.user_qoe_score),
        _ => Box::new(|m| m.cache_hit_rate),
    };

    let control_values: Vec<f64> = experiment
        .metrics
        .iter()
        .filter(|m| m.group == ExperimentGroup::Control)
        .map(|m| extractor(m))
        .collect();

    let treatment_values: Vec<f64> = experiment
        .metrics
        .iter()
        .filter(|m| m.group == ExperimentGroup::Treatment)
        .map(|m| extractor(m))
        .collect();

    let mut result = significance_test(&control_values, &treatment_values, 0.95);
    result.metric_name = metric_name.to_string();
    result
}
