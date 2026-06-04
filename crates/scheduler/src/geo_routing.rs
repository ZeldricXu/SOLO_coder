use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;

use arc_swap::ArcSwap;
use common::error::{CdnResult, CdnError};
use common::models::GeoLocation;
use tokio::task::JoinHandle;

const PRIVATE_RANGES: [(u32, u32); 3] = [
    (167772160, 4278190080),
    (2886729728, 4293918720),
    (3232235520, 4294901760),
];

pub struct GeoLocationResolver {
    db: Arc<ArcSwap<GeoIpDatabase>>,
    db_path: Option<String>,
    default_region: String,
    reload_task: Option<JoinHandle<()>>,
}

struct GeoIpDatabase {
    entries: HashMap<u32, GeoLocation>,
    prefix_len: u8,
}

impl GeoLocationResolver {
    pub fn new() -> Self {
        GeoLocationResolver {
            db: Arc::new(ArcSwap::from_pointee(GeoIpDatabase::empty())),
            db_path: None,
            default_region: "global".to_string(),
            reload_task: None,
        }
    }

    pub fn with_db_path(db_path: String) -> Self {
        let mut resolver = GeoLocationResolver::new();
        resolver.db_path = Some(db_path);
        resolver
    }

    pub fn with_default_region(mut self, region: String) -> Self {
        self.default_region = region;
        self
    }

    pub async fn load_from_file(&self, path: &str) -> CdnResult<()> {
        let db = GeoIpDatabase::load_from_file(path).await?;
        self.db.store(Arc::new(db));
        Ok(())
    }

    pub async fn start_reload_task(&mut self) -> CdnResult<()> {
        let Some(db_path) = self.db_path.clone() else {
            return Ok(());
        };

        let db = Arc::clone(&self.db);

        let handle = tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(86400)).await;
                match GeoIpDatabase::load_from_file(&db_path).await {
                    Ok(new_db) => {
                        db.store(Arc::new(new_db));
                        tracing::info!("GeoIP database reloaded successfully");
                    }
                    Err(e) => {
                        tracing::warn!("Failed to reload GeoIP database: {}", e);
                    }
                }
            }
        });

        self.reload_task = Some(handle);
        Ok(())
    }

    pub async fn resolve(&self, ip: &str) -> CdnResult<GeoLocation> {
        let ipv4 = match Ipv4Addr::from_str(ip) {
            Ok(addr) => addr,
            Err(_) => return Ok(self.fallback_location()),
        };

        let ip_u32 = u32::from(ipv4);

        if Self::is_private_ip(ip_u32) {
            return Ok(self.fallback_location());
        }

        let db = self.db.load();
        if let Some(loc) = db.lookup(ip_u32) {
            return Ok(loc.clone());
        }

        Ok(self.fallback_location())
    }

    pub fn get_region_for_ip(&self, ip: &str) -> String {
        let ipv4 = match Ipv4Addr::from_str(ip) {
            Ok(addr) => addr,
            Err(_) => return self.default_region.clone(),
        };

        let ip_u32 = u32::from(ipv4);

        if Self::is_private_ip(ip_u32) {
            return self.default_region.clone();
        }

        let db = self.db.load();
        db.lookup(ip_u32)
            .map(|loc| loc.region.clone())
            .unwrap_or_else(|| self.default_region.clone())
    }

    fn is_private_ip(ip: u32) -> bool {
        for (network, mask) in PRIVATE_RANGES.iter() {
            if ip & mask == *network {
                return true;
            }
        }
        false
    }

    fn fallback_location(&self) -> GeoLocation {
        GeoLocation {
            country: "Unknown".to_string(),
            region: self.default_region.clone(),
            city: "Unknown".to_string(),
            latitude: 0.0,
            longitude: 0.0,
            timezone: "UTC".to_string(),
        }
    }
}

impl GeoIpDatabase {
    fn empty() -> Self {
        GeoIpDatabase {
            entries: HashMap::new(),
            prefix_len: 24,
        }
    }

    async fn load_from_file(path: &str) -> CdnResult<Self> {
        let content = tokio::fs::read_to_string(path).await
            .map_err(|e| CdnError::GeoIpError(format!("Failed to read GeoIP file: {}", e)))?;

        let mut entries = HashMap::new();
        let mut max_prefix = 0;

        for line in content.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }

            let parts: Vec<&str> = line.split(',').collect();
            if parts.len() < 6 {
                continue;
            }

            let cidr = parts[0].trim();
            let (network_addr, prefix_len) = Self::parse_cidr(cidr)?;

            if prefix_len > max_prefix {
                max_prefix = prefix_len;
            }

            let location = GeoLocation {
                country: parts[1].trim().to_string(),
                region: parts[2].trim().to_string(),
                city: parts[3].trim().to_string(),
                latitude: parts[4].trim().parse().unwrap_or(0.0),
                longitude: parts[5].trim().parse().unwrap_or(0.0),
                timezone: if parts.len() > 6 { parts[6].trim().to_string() } else { "UTC".to_string() },
            };

            entries.insert(network_addr, location);
        }

        Ok(GeoIpDatabase {
            entries,
            prefix_len: max_prefix,
        })
    }

    fn parse_cidr(cidr: &str) -> CdnResult<(u32, u8)> {
        let parts: Vec<&str> = cidr.split('/').collect();
        if parts.len() != 2 {
            return Err(CdnError::GeoIpError(format!("Invalid CIDR: {}", cidr)));
        }

        let ip = Ipv4Addr::from_str(parts[0])
            .map_err(|_| CdnError::GeoIpError(format!("Invalid IP in CIDR: {}", cidr)))?;
        let prefix_len: u8 = parts[1].parse()
            .map_err(|_| CdnError::GeoIpError(format!("Invalid prefix length: {}", cidr)))?;

        let mask = if prefix_len == 0 {
            0
        } else {
            u32::MAX << (32 - prefix_len)
        };
        let network_addr = u32::from(ip) & mask;

        Ok((network_addr, prefix_len))
    }

    fn lookup(&self, ip: u32) -> Option<&GeoLocation> {
        for prefix_len in (0..=self.prefix_len).rev() {
            let mask = if prefix_len == 0 {
                0
            } else {
                u32::MAX << (32 - prefix_len)
            };
            let network_addr = ip & mask;
            if let Some(loc) = self.entries.get(&network_addr) {
                return Some(loc);
            }
        }
        None
    }
}

impl Default for GeoLocationResolver {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for GeoLocationResolver {
    fn drop(&mut self) {
        if let Some(handle) = self.reload_task.take() {
            handle.abort();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::NamedTempFile;
    use std::io::{Write, Seek};

    fn create_test_geoip_file() -> NamedTempFile {
        let mut file = NamedTempFile::new().unwrap();
        writeln!(file, "1.0.0.0/24,CN,asia-east,Beijing,39.9,116.4,Asia/Shanghai").unwrap();
        writeln!(file, "2.0.0.0/16,US,na-west,San Francisco,37.8,-122.4,America/Los_Angeles").unwrap();
        writeln!(file, "3.0.0.0/8,GB,eu-west,London,51.5,-0.1,Europe/London").unwrap();
        file
    }

    #[tokio::test]
    async fn test_private_ip_fallback() {
        let resolver = GeoLocationResolver::new().with_default_region("test-region".to_string());

        assert_eq!(resolver.get_region_for_ip("10.0.0.1"), "test-region");
        assert_eq!(resolver.get_region_for_ip("172.16.0.1"), "test-region");
        assert_eq!(resolver.get_region_for_ip("172.31.255.255"), "test-region");
        assert_eq!(resolver.get_region_for_ip("192.168.0.1"), "test-region");
        assert_eq!(resolver.get_region_for_ip("192.168.255.255"), "test-region");

        let loc = resolver.resolve("10.0.0.1").await.unwrap();
        assert_eq!(loc.region, "test-region");
    }

    #[tokio::test]
    async fn test_memory_query() {
        let file = create_test_geoip_file();
        let resolver = GeoLocationResolver::with_db_path(file.path().to_str().unwrap().to_string());
        resolver.load_from_file(file.path().to_str().unwrap()).await.unwrap();

        let loc = resolver.resolve("1.0.0.100").await.unwrap();
        assert_eq!(loc.country, "CN");
        assert_eq!(loc.region, "asia-east");
        assert_eq!(loc.city, "Beijing");

        let loc = resolver.resolve("2.0.100.200").await.unwrap();
        assert_eq!(loc.country, "US");
        assert_eq!(loc.region, "na-west");

        let loc = resolver.resolve("3.255.255.255").await.unwrap();
        assert_eq!(loc.country, "GB");
        assert_eq!(loc.region, "eu-west");

        assert_eq!(resolver.get_region_for_ip("1.0.0.50"), "asia-east");
        assert_eq!(resolver.get_region_for_ip("2.0.0.1"), "na-west");
    }

    #[tokio::test]
    async fn test_unknown_ip_fallback() {
        let file = create_test_geoip_file();
        let resolver = GeoLocationResolver::with_db_path(file.path().to_str().unwrap().to_string())
            .with_default_region("fallback".to_string());
        resolver.load_from_file(file.path().to_str().unwrap()).await.unwrap();

        let loc = resolver.resolve("100.0.0.1").await.unwrap();
        assert_eq!(loc.region, "fallback");
        assert_eq!(resolver.get_region_for_ip("100.0.0.1"), "fallback");
    }

    #[tokio::test]
    async fn test_reload_updates_data() {
        let mut file = NamedTempFile::new().unwrap();
        writeln!(file, "1.0.0.0/24,CN,asia-east,Beijing,39.9,116.4,Asia/Shanghai").unwrap();
        let path = file.path().to_str().unwrap().to_string();

        let resolver = GeoLocationResolver::with_db_path(path.clone());
        resolver.load_from_file(&path).await.unwrap();

        let loc = resolver.resolve("1.0.0.1").await.unwrap();
        assert_eq!(loc.city, "Beijing");

        file.as_file_mut().set_len(0).unwrap();
        file.as_file_mut().seek(std::io::SeekFrom::Start(0)).unwrap();
        writeln!(file, "1.0.0.0/24,CN,asia-east,Shanghai,31.2,121.5,Asia/Shanghai").unwrap();

        resolver.load_from_file(&path).await.unwrap();

        let loc = resolver.resolve("1.0.0.1").await.unwrap();
        assert_eq!(loc.city, "Shanghai");
    }

    #[test]
    fn test_private_ip_detection() {
        assert!(GeoLocationResolver::is_private_ip(u32::from(Ipv4Addr::new(10, 0, 0, 1))));
        assert!(GeoLocationResolver::is_private_ip(u32::from(Ipv4Addr::new(10, 255, 255, 255))));
        assert!(GeoLocationResolver::is_private_ip(u32::from(Ipv4Addr::new(172, 16, 0, 1))));
        assert!(GeoLocationResolver::is_private_ip(u32::from(Ipv4Addr::new(172, 31, 255, 255))));
        assert!(GeoLocationResolver::is_private_ip(u32::from(Ipv4Addr::new(192, 168, 0, 1))));
        assert!(GeoLocationResolver::is_private_ip(u32::from(Ipv4Addr::new(192, 168, 255, 255))));

        assert!(!GeoLocationResolver::is_private_ip(u32::from(Ipv4Addr::new(8, 8, 8, 8))));
        assert!(!GeoLocationResolver::is_private_ip(u32::from(Ipv4Addr::new(1, 0, 0, 1))));
    }

    #[test]
    fn test_cidr_parsing() {
        assert_eq!(GeoIpDatabase::parse_cidr("192.168.0.0/24").unwrap(), (3232235520, 24));
        assert_eq!(GeoIpDatabase::parse_cidr("10.0.0.0/8").unwrap(), (167772160, 8));
        assert_eq!(GeoIpDatabase::parse_cidr("172.16.0.0/12").unwrap(), (2886729728, 12));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn test_performance() {
        let file = create_test_geoip_file();
        let resolver = GeoLocationResolver::with_db_path(file.path().to_str().unwrap().to_string());
        resolver.load_from_file(file.path().to_str().unwrap()).await.unwrap();

        let resolver = Arc::new(resolver);
        let start = std::time::Instant::now();

        let mut handles = Vec::new();
        for _i in 0..10 {
            let resolver = resolver.clone();
            handles.push(tokio::spawn(async move {
                for j in 0..1000 {
                    let ip = format!("1.0.0.{}", j % 256);
                    let _ = resolver.get_region_for_ip(&ip);
                }
            }));
        }

        for handle in handles {
            handle.await.unwrap();
        }

        let elapsed = start.elapsed();
        assert!(elapsed.as_millis() < 100, "10000 queries should complete under 100ms");
    }

    #[tokio::test]
    async fn test_invalid_ip() {
        let resolver = GeoLocationResolver::new().with_default_region("default".to_string());

        assert_eq!(resolver.get_region_for_ip("invalid"), "default");
        assert_eq!(resolver.get_region_for_ip("256.0.0.1"), "default");
        assert_eq!(resolver.get_region_for_ip(""), "default");

        let loc = resolver.resolve("invalid").await.unwrap();
        assert_eq!(loc.region, "default");
    }
}
