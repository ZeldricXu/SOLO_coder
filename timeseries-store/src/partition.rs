use anyhow::{Context, Result};
use chrono::{DateTime, Duration, Timelike, Utc};
use std::fs;
use std::path::{Path, PathBuf};

pub fn get_partition_key(timestamp: &DateTime<Utc>) -> String {
    timestamp.format("dt=%Y-%m-%d/%H").to_string()
}

pub fn get_partition_key_for_date(timestamp: &DateTime<Utc>) -> String {
    timestamp.format("dt=%Y-%m-%d").to_string()
}

pub struct PartitionManager {
    base_path: PathBuf,
}

impl PartitionManager {
    pub fn new(base_path: PathBuf) -> Self {
        Self { base_path }
    }

    pub fn ensure_partition(&self, partition_key: &str) -> Result<PathBuf> {
        let partition_path = self.base_path.join(partition_key);
        fs::create_dir_all(&partition_path)
            .with_context(|| format!("Failed to create partition: {}", partition_path.display()))?;
        Ok(partition_path)
    }

    pub fn list_partitions(&self) -> Result<Vec<String>> {
        let mut partitions = Vec::new();
        self.walk_partitions(&self.base_path, &mut partitions, 0)?;
        Ok(partitions)
    }

    fn walk_partitions(&self, path: &Path, partitions: &mut Vec<String>, depth: usize) -> Result<()> {
        if !path.exists() {
            return Ok(());
        }

        for entry in fs::read_dir(path)? {
            let entry = entry?;
            let entry_path = entry.path();
            if entry_path.is_dir() {
                if depth < 2 {
                    self.walk_partitions(&entry_path, partitions, depth + 1)?;
                } else {
                    if let Some(rel_path) = entry_path.strip_prefix(&self.base_path).ok() {
                        partitions.push(rel_path.to_string_lossy().to_string());
                    }
                }
            }
        }
        Ok(())
    }

    pub fn list_partitions_in_range(&self, start: DateTime<Utc>, end: DateTime<Utc>) -> Result<Vec<String>> {
        let mut partitions = Vec::new();
        let mut current = start.with_minute(0).unwrap().with_second(0).unwrap().with_nanosecond(0).unwrap();

        while current <= end {
            let key = get_partition_key(&current);
            partitions.push(key);
            current = current + Duration::hours(1);
        }

        Ok(partitions)
    }

    pub fn list_files_in_partition(&self, partition_key: &str) -> Result<Vec<PathBuf>> {
        let partition_path = self.base_path.join(partition_key);
        if !partition_path.exists() {
            return Ok(Vec::new());
        }

        let mut files = Vec::new();
        for entry in fs::read_dir(partition_path)? {
            let entry = entry?;
            let path = entry.path();
            if path.is_file() && path.extension().and_then(|e| e.to_str()) == Some("parquet") {
                files.push(path);
            }
        }

        files.sort();
        Ok(files)
    }

    pub fn cleanup_old_partitions(&self, retention_days: i64) -> Result<()> {
        let cutoff = Utc::now() - Duration::days(retention_days);
        let partitions = self.list_partitions()?;

        for partition in partitions {
            if let Some(partition_dt) = self.parse_partition_key(&partition) {
                if partition_dt < cutoff {
                    let partition_path = self.base_path.join(&partition);
                    fs::remove_dir_all(&partition_path).ok();
                }
            }
        }

        Ok(())
    }

    fn parse_partition_key(&self, key: &str) -> Option<DateTime<Utc>> {
        let parts: Vec<&str> = key.split('/').collect();
        if parts.len() >= 2 {
            let dt_str = parts[0].trim_start_matches("dt=");
            let hour = parts[1].parse::<u32>().ok()?;
            let dt = chrono::NaiveDate::parse_from_str(dt_str, "%Y-%m-%d").ok()?;
            let naive = dt.and_hms_opt(hour, 0, 0)?;
            Some(DateTime::from_naive_utc_and_offset(naive, Utc))
        } else {
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_get_partition_key() {
        let dt = chrono::DateTime::parse_from_rfc3339("2024-01-15T14:30:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let key = get_partition_key(&dt);
        assert_eq!(key, "dt=2024-01-15/14");
    }

    #[test]
    fn test_partition_manager() {
        let dir = tempdir().unwrap();
        let manager = PartitionManager::new(dir.path().to_path_buf());

        let dt = Utc::now();
        let key = get_partition_key(&dt);
        let path = manager.ensure_partition(&key).unwrap();
        assert!(path.exists());
    }
}
