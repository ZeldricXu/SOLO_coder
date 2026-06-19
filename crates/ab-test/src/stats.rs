use statrs::distribution::{StudentsT, Normal, Continuous};
use statrs::function::erf;

pub struct Statistics;

impl Statistics {
    pub fn mean(data: &[f64]) -> f64 {
        if data.is_empty() {
            return 0.0;
        }
        data.iter().sum::<f64>() / data.len() as f64
    }

    pub fn variance(data: &[f64]) -> f64 {
        if data.len() < 2 {
            return 0.0;
        }
        let mean = Self::mean(data);
        data.iter()
            .map(|x| (x - mean).powi(2))
            .sum::<f64>()
            / (data.len() - 1) as f64
    }

    pub fn std_dev(data: &[f64]) -> f64 {
        Self::variance(data).sqrt()
    }

    pub fn standard_error(std: f64, n: usize) -> f64 {
        if n == 0 {
            return 0.0;
        }
        std / (n as f64).sqrt()
    }

    pub fn confidence_interval(
        mean: f64,
        std: f64,
        n: usize,
        confidence_level: f64,
    ) -> (f64, f64) {
        if n < 2 {
            return (mean, mean);
        }
        let df = (n - 1) as f64;
        let alpha = 1.0 - confidence_level;
        let t_dist = match StudentsT::new(0.0, 1.0, df) {
            Ok(d) => d,
            Err(_) => return (mean, mean),
        };
        let t_critical = t_dist.inverse_cdf(1.0 - alpha / 2.0);
        if !t_critical.is_finite() {
            return (mean, mean);
        }
        let se = Self::standard_error(std, n);
        let margin = t_critical * se;
        (mean - margin, mean + margin)
    }

    pub fn confidence_interval_95(mean: f64, std: f64, n: usize) -> (f64, f64) {
        Self::confidence_interval(mean, std, n, 0.95)
    }

    pub fn t_test_independent(sample_a: &[f64], sample_b: &[f64]) -> (f64, f64) {
        let n_a = sample_a.len();
        let n_b = sample_b.len();

        if n_a < 2 || n_b < 2 {
            return (0.0, 1.0);
        }

        let mean_a = Self::mean(sample_a);
        let mean_b = Self::mean(sample_b);
        let var_a = Self::variance(sample_a);
        let var_b = Self::variance(sample_b);

        let pooled_var = (((n_a - 1) as f64 * var_a) + ((n_b - 1) as f64 * var_b))
            / ((n_a + n_b - 2) as f64);
        let pooled_std = pooled_var.sqrt();

        let se_diff = pooled_std * (1.0 / n_a as f64 + 1.0 / n_b as f64).sqrt();

        if se_diff == 0.0 {
            return (0.0, 1.0);
        }

        let t_stat = (mean_a - mean_b) / se_diff;
        let df = (n_a + n_b - 2) as f64;

        let t_dist = match StudentsT::new(0.0, 1.0, df) {
            Ok(d) => d,
            Err(_) => return (t_stat, 1.0),
        };

        let abs_t = t_stat.abs();
        let cdf_val = t_dist.cdf(abs_t);
        let p_value = if cdf_val.is_finite() {
            2.0 * (1.0 - cdf_val)
        } else {
            1.0
        };

        (t_stat, p_value.max(0.0).min(1.0))
    }

    pub fn t_test_from_aggregated(
        mean_a: f64,
        std_a: f64,
        n_a: usize,
        mean_b: f64,
        std_b: f64,
        n_b: usize,
    ) -> (f64, f64) {
        if n_a < 2 || n_b < 2 {
            return (0.0, 1.0);
        }

        let var_a = std_a * std_a;
        let var_b = std_b * std_b;
        let pooled_var = (((n_a - 1) as f64 * var_a) + ((n_b - 1) as f64 * var_b))
            / ((n_a + n_b - 2) as f64);
        let pooled_std = pooled_var.sqrt();

        let se_diff = pooled_std * (1.0 / n_a as f64 + 1.0 / n_b as f64).sqrt();

        if se_diff == 0.0 {
            return (0.0, 1.0);
        }

        let t_stat = (mean_a - mean_b) / se_diff;
        let df = (n_a + n_b - 2) as f64;

        let t_dist = match StudentsT::new(0.0, 1.0, df) {
            Ok(d) => d,
            Err(_) => return (t_stat, 1.0),
        };

        let abs_t = t_stat.abs();
        let cdf_val = t_dist.cdf(abs_t);
        let p_value = if cdf_val.is_finite() {
            2.0 * (1.0 - cdf_val)
        } else {
            1.0
        };

        (t_stat, p_value.max(0.0).min(1.0))
    }

    pub fn z_test_proportions(
        count_a: u64,
        total_a: u64,
        count_b: u64,
        total_b: u64,
    ) -> (f64, f64) {
        if total_a == 0 || total_b == 0 {
            return (0.0, 1.0);
        }

        let p_a = count_a as f64 / total_a as f64;
        let p_b = count_b as f64 / total_b as f64;
        let p_pooled = (count_a + count_b) as f64 / (total_a + total_b) as f64;

        let se =
            (p_pooled * (1.0 - p_pooled) * (1.0 / total_a as f64 + 1.0 / total_b as f64)).sqrt();

        if se == 0.0 {
            return (0.0, 1.0);
        }

        let z_stat = (p_a - p_b) / se;
        let abs_z = z_stat.abs();
        let p_value = 2.0 * (1.0 - Self::normal_cdf(abs_z));

        (z_stat, p_value.max(0.0).min(1.0))
    }

    fn normal_cdf(x: f64) -> f64 {
        0.5 * (1.0 + erf::erf(x / 2.0_f64.sqrt()))
    }

    pub fn cohens_d(mean_a: f64, mean_b: f64, std_pooled: f64) -> f64 {
        if std_pooled == 0.0 {
            return 0.0;
        }
        (mean_a - mean_b) / std_pooled
    }

    pub fn pooled_std_from_aggregated(
        std_a: f64,
        n_a: usize,
        std_b: f64,
        n_b: usize,
    ) -> f64 {
        if n_a < 2 || n_b < 2 {
            return ((std_a.powi(2) + std_b.powi(2)) / 2.0).sqrt();
        }

        let var_a = std_a * std_a;
        let var_b = std_b * std_b;

        let pooled_var = (((n_a - 1) as f64 * var_a) + ((n_b - 1) as f64 * var_b))
            / ((n_a + n_b - 2) as f64);

        pooled_var.sqrt()
    }

    pub fn is_statistically_significant(p_value: f64, alpha: f64) -> bool {
        p_value < alpha
    }

    pub fn interpret_effect_size(d: f64) -> &'static str {
        let abs_d = d.abs();
        if abs_d < 0.1 {
            "negligible"
        } else if abs_d < 0.3 {
            "small"
        } else if abs_d < 0.5 {
            "medium"
        } else if abs_d < 0.8 {
            "large"
        } else {
            "very large"
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mean() {
        let data = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        assert!((Statistics::mean(&data) - 3.0).abs() < 1e-10);
    }

    #[test]
    fn test_std_dev() {
        let data = vec![2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0];
        assert!((Statistics::std_dev(&data) - 2.138).abs() < 0.01);
    }

    #[test]
    fn test_confidence_interval_95() {
        let data = vec![2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0];
        let mean = Statistics::mean(&data);
        let std = Statistics::std_dev(&data);
        let (low, high) = Statistics::confidence_interval_95(mean, std, data.len());
        assert!(low < mean);
        assert!(high > mean);
    }

    #[test]
    fn test_t_test_significant() {
        let a: Vec<f64> = (0..50).map(|i| i as f64 + 5.0).collect();
        let b: Vec<f64> = (0..50).map(|i| i as f64).collect();
        let (_, p) = Statistics::t_test_independent(&a, &b);
        assert!(p < 0.05);
    }

    #[test]
    fn test_t_test_not_significant() {
        let a: Vec<f64> = (0..10).map(|i| i as f64).collect();
        let b: Vec<f64> = (0..10).map(|i| i as f64 + 0.1).collect();
        let (_, p) = Statistics::t_test_independent(&a, &b);
        assert!(p > 0.05);
    }

    #[test]
    fn test_z_test_proportions() {
        let (z, p) = Statistics::z_test_proportions(80, 100, 50, 100);
        assert!(z > 0.0);
        assert!(p < 0.05);
    }

    #[test]
    fn test_is_statistically_significant() {
        assert!(Statistics::is_statistically_significant(0.03, 0.05));
        assert!(!Statistics::is_statistically_significant(0.07, 0.05));
    }
}
