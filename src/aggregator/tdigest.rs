use ordered_float::OrderedFloat;
use std::cmp::Ordering;

#[derive(Debug, Clone)]
struct Centroid {
    mean: OrderedFloat<f64>,
    weight: f64,
}

impl Centroid {
    fn new(value: f64, weight: f64) -> Self {
        Self {
            mean: OrderedFloat(value),
            weight,
        }
    }
}

#[derive(Debug, Clone)]
pub struct TDigest {
    centroids: Vec<Centroid>,
    compression: f64,
    total_weight: f64,
    min: f64,
    max: f64,
    buffer: Vec<f64>,
    buffer_size: usize,
}

impl TDigest {
    pub fn new(compression: f64) -> Self {
        let buffer_size = (compression.ceil() as usize).max(50);
        Self {
            centroids: Vec::new(),
            compression,
            total_weight: 0.0,
            min: f64::INFINITY,
            max: f64::NEG_INFINITY,
            buffer: Vec::with_capacity(buffer_size),
            buffer_size,
        }
    }

    pub fn add(&mut self, value: f64) {
        self.add_weighted(value, 1.0);
    }

    pub fn add_weighted(&mut self, value: f64, weight: f64) {
        if !value.is_finite() || weight <= 0.0 {
            return;
        }
        if value < self.min {
            self.min = value;
        }
        if value > self.max {
            self.max = value;
        }
        self.buffer.push(value);
        self.total_weight += weight;
        if self.buffer.len() >= self.buffer_size {
            self.flush_buffer();
        }
    }

    fn flush_buffer(&mut self) {
        if self.buffer.is_empty() {
            return;
        }
        let vals: Vec<(f64, f64)> = self.buffer.drain(..).map(|v| (v, 1.0)).collect();
        self.merge_centroids(vals);
    }

    fn merge_centroids(&mut self, new_points: Vec<(f64, f64)>) {
        let mut points: Vec<(f64, f64)> = self
            .centroids
            .iter()
            .map(|c| (c.mean.0, c.weight))
            .chain(new_points.into_iter())
            .collect();
        points.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(Ordering::Equal));

        if points.is_empty() {
            return;
        }

        let total: f64 = points.iter().map(|p| p.1).sum();
        let mut merged: Vec<Centroid> = Vec::with_capacity(points.len().min(self.centroids.len().max(50)));

        let mut weight_so_far = 0.0;
        let mut current_mean = points[0].0;
        let mut current_weight = points[0].1;

        for &(val, w) in points.iter().skip(1) {
            let potential_weight = current_weight + w;
            let q1 = (weight_so_far + current_weight / 2.0) / total;
            let k_limit = self.k_limit(q1) * total / self.compression;

            if potential_weight <= k_limit {
                let new_mean =
                    (current_mean * current_weight + val * w) / potential_weight;
                current_mean = new_mean;
                current_weight = potential_weight;
            } else {
                merged.push(Centroid::new(current_mean, current_weight));
                weight_so_far += current_weight;
                current_mean = val;
                current_weight = w;
            }
        }
        merged.push(Centroid::new(current_mean, current_weight));
        self.centroids = merged;
    }

    fn k_limit(&self, q: f64) -> f64 {
        let q = q.max(0.0).min(1.0);
        self.compression
            * (q * std::f64::consts::PI).sin().asin()
            / std::f64::consts::PI
    }

    pub fn quantile(&mut self, q: f64) -> f64 {
        self.flush_buffer();
        if self.centroids.is_empty() || self.total_weight == 0.0 {
            return 0.0;
        }
        let q = q.max(0.0).min(1.0);
        if q == 0.0 {
            return self.min;
        }
        if q == 1.0 {
            return self.max;
        }

        let target = q * self.total_weight;
        let mut cum = 0.0;

        for i in 0..self.centroids.len() {
            let c = &self.centroids[i];
            let prev_cum = cum;
            cum += c.weight;

            if cum >= target {
                if i == 0 {
                    let next = self.centroids.get(i + 1).map(|cc| cc.mean.0).unwrap_or(self.max);
                    let frac = (target - prev_cum) / c.weight.max(1e-9);
                    return c.mean.0 + frac * (next - c.mean.0);
                }
                let prev = self.centroids[i - 1].mean.0;
                let next = self
                    .centroids
                    .get(i + 1)
                    .map(|cc| cc.mean.0)
                    .unwrap_or(self.max);
                let mid = (prev + next) / 2.0;
                let frac = (target - prev_cum) / c.weight.max(1e-9);
                return c.mean.0 + frac * (mid - c.mean.0) * 0.5;
            }
        }
        self.max
    }

    pub fn count(&self) -> u64 {
        self.total_weight as u64
    }

    pub fn min(&self) -> f64 {
        if self.total_weight == 0.0 {
            0.0
        } else {
            self.min
        }
    }

    pub fn max(&self) -> f64 {
        if self.total_weight == 0.0 {
            0.0
        } else {
            self.max
        }
    }

    pub fn merge(&mut self, other: &TDigest) {
        if other.total_weight == 0.0 {
            return;
        }
        let other_points: Vec<(f64, f64)> = other
            .centroids
            .iter()
            .map(|c| (c.mean.0, c.weight))
            .collect();
        if other.min < self.min {
            self.min = other.min;
        }
        if other.max > self.max {
            self.max = other.max;
        }
        self.total_weight += other.total_weight;
        self.merge_centroids(other_points);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_basic_quantiles() {
        let mut td = TDigest::new(100.0);
        for i in 1..=100 {
            td.add(i as f64);
        }
        let p50 = td.quantile(0.5);
        let p95 = td.quantile(0.95);
        let p99 = td.quantile(0.99);
        assert!((p50 - 50.0).abs() < 5.0, "p50={}", p50);
        assert!((p95 - 95.0).abs() < 5.0, "p95={}", p95);
        assert!((p99 - 99.0).abs() < 3.0, "p99={}", p99);
        assert_eq!(td.min(), 1.0);
        assert_eq!(td.max(), 100.0);
    }

    #[test]
    fn test_merge() {
        let mut td1 = TDigest::new(100.0);
        for i in 1..=50 {
            td1.add(i as f64);
        }
        let mut td2 = TDigest::new(100.0);
        for i in 51..=100 {
            td2.add(i as f64);
        }
        td1.merge(&td2);
        assert_eq!(td1.count(), 100);
        assert_eq!(td1.min(), 1.0);
        assert_eq!(td1.max(), 100.0);
        let p50 = td1.quantile(0.5);
        assert!((p50 - 50.0).abs() < 5.0);
    }
}
