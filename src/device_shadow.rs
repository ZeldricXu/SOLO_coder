use crate::config::DeviceShadowConfig;
use crate::error::SystemError;
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceState {
    pub device_id: String,
    pub desired: HashMap<String, Value>,
    pub reported: HashMap<String, Value>,
    pub timestamp: DateTime<Utc>,
    pub version: u64,
    pub online: bool,
    pub last_connection: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShadowDelta {
    pub device_id: String,
    pub desired_changes: HashMap<String, Value>,
    pub reported_changes: HashMap<String, Value>,
    pub timestamp: DateTime<Utc>,
    pub version: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncStatus {
    pub device_id: String,
    pub synced: bool,
    pub last_sync: Option<DateTime<Utc>>,
    pub pending_changes: usize,
    pub sync_error: Option<String>,
}

type ChangeCallback = Arc<dyn Fn(ShadowDelta) + Send + Sync>;

#[async_trait]
pub trait CloudSyncClient: Send + Sync {
    async fn sync(&self, state: &DeviceState) -> Result<(), SystemError>;
}

pub struct HttpCloudSyncClient {
    client: reqwest::Client,
    endpoint: String,
}

impl HttpCloudSyncClient {
    pub fn new(endpoint: String, timeout: std::time::Duration) -> Result<Self, SystemError> {
        Ok(Self {
            client: reqwest::Client::builder()
                .timeout(timeout)
                .build()
                .map_err(SystemError::NetworkError)?,
            endpoint,
        })
    }
}

#[async_trait]
impl CloudSyncClient for HttpCloudSyncClient {
    async fn sync(&self, state: &DeviceState) -> Result<(), SystemError> {
        let payload = serde_json::json!({
            "deviceId": state.device_id,
            "desired": state.desired,
            "reported": state.reported,
            "version": state.version,
            "timestamp": state.timestamp,
        });

        let response = self
            .client
            .post(&self.endpoint)
            .json(&payload)
            .send()
            .await
            .map_err(SystemError::NetworkError)?;

        if response.status().is_success() {
            Ok(())
        } else {
            Err(SystemError::DeviceShadowError(format!(
                "云端同步失败: HTTP {}",
                response.status()
            )))
        }
    }
}

pub struct DeviceStateStore {
    devices: Arc<DashMap<String, DeviceState>>,
}

impl DeviceStateStore {
    pub fn new() -> Self {
        Self {
            devices: Arc::new(DashMap::new()),
        }
    }

    pub fn register(&self, device_id: String) -> bool {
        if self.devices.contains_key(&device_id) {
            return false;
        }

        let state = DeviceState {
            device_id: device_id.clone(),
            desired: HashMap::new(),
            reported: HashMap::new(),
            timestamp: Utc::now(),
            version: 1,
            online: false,
            last_connection: None,
        };

        self.devices.insert(device_id, state);
        true
    }

    pub fn exists(&self, device_id: &str) -> bool {
        self.devices.contains_key(device_id)
    }

    pub fn get(&self, device_id: &str) -> Option<DeviceState> {
        self.devices.get(device_id).map(|r| r.clone())
    }

    pub fn get_all(&self) -> Vec<DeviceState> {
        self.devices.iter().map(|d| d.clone()).collect()
    }

    pub fn update<F>(&self, device_id: &str, updater: F) -> Result<(), SystemError>
    where
        F: FnOnce(&mut DeviceState),
    {
        let mut state = self
            .devices
            .get_mut(device_id)
            .ok_or_else(|| SystemError::NotFoundError(format!("设备不存在: {}", device_id)))?;

        updater(&mut state);
        Ok(())
    }

    pub fn apply_delta(&self, device_id: &str, delta: &ShadowDelta) -> Result<(), SystemError> {
        let mut state = self
            .devices
            .get_mut(device_id)
            .ok_or_else(|| SystemError::NotFoundError(format!("设备不存在: {}", device_id)))?;

        for (key, value) in &delta.desired_changes {
            state.desired.insert(key.clone(), value.clone());
        }

        for (key, value) in &delta.reported_changes {
            state.reported.insert(key.clone(), value.clone());
        }

        state.timestamp = delta.timestamp;
        state.version = delta.version;
        state.online = true;
        state.last_connection = Some(Utc::now());

        Ok(())
    }

    pub fn set_online(&self, device_id: &str, online: bool) -> Result<(), SystemError> {
        let mut state = self
            .devices
            .get_mut(device_id)
            .ok_or_else(|| SystemError::NotFoundError(format!("设备不存在: {}", device_id)))?;

        state.online = online;
        if online {
            state.last_connection = Some(Utc::now());
        }

        Ok(())
    }

    pub fn device_ids(&self) -> Vec<String> {
        self.devices.iter().map(|d| d.key().clone()).collect()
    }
}

impl Clone for DeviceStateStore {
    fn clone(&self) -> Self {
        Self {
            devices: self.devices.clone(),
        }
    }
}

pub struct StateChangeNotifier {
    callbacks: Arc<RwLock<Vec<ChangeCallback>>>,
}

impl StateChangeNotifier {
    pub fn new() -> Self {
        Self {
            callbacks: Arc::new(RwLock::new(Vec::new())),
        }
    }

    pub async fn register<F>(&self, callback: F)
    where
        F: Fn(ShadowDelta) + Send + Sync + 'static,
    {
        let mut callbacks = self.callbacks.write().await;
        callbacks.push(Arc::new(callback));
    }

    pub async fn notify(&self, delta: ShadowDelta) {
        let callbacks = self.callbacks.read().await;
        for callback in callbacks.iter() {
            callback(delta.clone());
        }
    }
}

impl Clone for StateChangeNotifier {
    fn clone(&self) -> Self {
        Self {
            callbacks: self.callbacks.clone(),
        }
    }
}

pub struct SyncCoordinator {
    sync_client: Arc<dyn CloudSyncClient>,
    sync_status: Arc<DashMap<String, SyncStatus>>,
    pending_updates: Arc<DashMap<String, Vec<ShadowDelta>>>,
    sync_tx: mpsc::Sender<String>,
    config: DeviceShadowConfig,
}

impl SyncCoordinator {
    pub fn new(
        config: DeviceShadowConfig,
        sync_client: Arc<dyn CloudSyncClient>,
    ) -> Result<(Self, mpsc::Receiver<String>), SystemError> {
        let (sync_tx, sync_rx) = mpsc::channel(1000);

        let coordinator = Self {
            sync_client,
            sync_status: Arc::new(DashMap::new()),
            pending_updates: Arc::new(DashMap::new()),
            sync_tx,
            config,
        };

        Ok((coordinator, sync_rx))
    }

    pub fn init_device(&self, device_id: String) {
        self.sync_status.insert(
            device_id.clone(),
            SyncStatus {
                device_id: device_id.clone(),
                synced: true,
                last_sync: None,
                pending_changes: 0,
                sync_error: None,
            },
        );
        self.pending_updates.insert(device_id, Vec::new());
    }

    pub async fn queue_sync(&self, device_id: String) -> Result<(), SystemError> {
        self.sync_tx
            .send(device_id)
            .await
            .map_err(|e| SystemError::DeviceShadowError(format!("同步队列错误: {}", e)))?;
        Ok(())
    }

    pub fn get_sync_status(&self, device_id: &str) -> Option<SyncStatus> {
        self.sync_status.get(device_id).map(|r| r.clone())
    }

    pub fn mark_synced(&self, device_id: &str) {
        if let Some(mut status) = self.sync_status.get_mut(device_id) {
            status.synced = true;
            status.last_sync = Some(Utc::now());
            status.sync_error = None;
        }
    }

    pub fn mark_failed(&self, device_id: &str, error: String) {
        if let Some(mut status) = self.sync_status.get_mut(device_id) {
            status.synced = false;
            status.sync_error = Some(error);
        }
    }

    pub fn start_sync_listener(
        &self,
        rx: mpsc::Receiver<String>,
        state_store: DeviceStateStore,
    ) {
        let sync_client = self.sync_client.clone();
        let sync_status = self.sync_status.clone();
        let config = self.config.clone();

        tokio::spawn(async move {
            let mut rx = rx;
            while let Some(device_id) = rx.recv().await {
                if let Some(state) = state_store.get(&device_id) {
                    let sync_client = sync_client.clone();
                    let sync_status = sync_status.clone();
                    let device_id = device_id.clone();

                    tokio::spawn(async move {
                        match sync_client.sync(&state).await {
                            Ok(_) => {
                                if let Some(mut status) = sync_status.get_mut(&device_id) {
                                    status.synced = true;
                                    status.last_sync = Some(Utc::now());
                                    status.sync_error = None;
                                }
                                debug!("设备 {} 同步成功", device_id);
                            }
                            Err(e) => {
                                if let Some(mut status) = sync_status.get_mut(&device_id) {
                                    status.synced = false;
                                    status.sync_error = Some(e.to_string());
                                }
                                warn!("设备 {} 同步失败: {}", device_id, e);
                            }
                        }
                    });
                }
            }
        });
    }

    pub fn start_sync_worker(&self, state_store: DeviceStateStore) -> Result<(), SystemError> {
        let interval = self.config.sync_interval();
        let sync_tx = self.sync_tx.clone();

        tokio::spawn(async move {
            loop {
                tokio::time::sleep(interval).await;

                for device_id in state_store.device_ids() {
                    if let Err(e) = sync_tx.send(device_id.clone()).await {
                        error!("同步调度失败: {}", e);
                    }
                    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                }
            }
        });

        Ok(())
    }
}

impl Clone for SyncCoordinator {
    fn clone(&self) -> Self {
        Self {
            sync_client: self.sync_client.clone(),
            sync_status: self.sync_status.clone(),
            pending_updates: self.pending_updates.clone(),
            sync_tx: self.sync_tx.clone(),
            config: self.config.clone(),
        }
    }
}

#[derive(Clone)]
pub struct DeviceShadow {
    state_store: DeviceStateStore,
    notifier: StateChangeNotifier,
    sync_coordinator: SyncCoordinator,
    config: DeviceShadowConfig,
}

impl DeviceShadow {
    pub fn new(config: &DeviceShadowConfig) -> Result<Self, SystemError> {
        let sync_client = HttpCloudSyncClient::new(
            config.cloud_endpoint.clone(),
            std::time::Duration::from_secs(30),
        )?;

        let (sync_coordinator, sync_rx) = SyncCoordinator::new(
            config.clone(),
            Arc::new(sync_client),
        )?;

        let state_store = DeviceStateStore::new();
        let notifier = StateChangeNotifier::new();

        sync_coordinator.start_sync_listener(sync_rx, state_store.clone());

        Ok(Self {
            state_store,
            notifier,
            sync_coordinator,
            config: config.clone(),
        })
    }

    pub async fn register_device(&self, device_id: String) -> Result<(), SystemError> {
        if self.state_store.register(device_id.clone()) {
            self.sync_coordinator.init_device(device_id);
        }
        Ok(())
    }

    pub async fn update_desired_state(
        &self,
        device_id: &str,
        updates: HashMap<String, Value>,
    ) -> Result<(), SystemError> {
        self.ensure_device_exists(device_id)?;

        let state = self.state_store.get(device_id).ok_or_else(|| {
            SystemError::NotFoundError(format!("设备不存在: {}", device_id))
        })?;

        let delta = ShadowDelta {
            device_id: device_id.to_string(),
            desired_changes: updates,
            reported_changes: HashMap::new(),
            timestamp: Utc::now(),
            version: state.version + 1,
        };

        self.state_store.apply_delta(device_id, &delta)?;
        self.notifier.notify(delta.clone()).await;
        self.sync_coordinator.queue_sync(device_id.to_string()).await?;

        Ok(())
    }

    pub async fn update_reported_state(
        &self,
        device_id: &str,
        updates: HashMap<String, Value>,
    ) -> Result<(), SystemError> {
        self.ensure_device_exists(device_id)?;

        let state = self.state_store.get(device_id).ok_or_else(|| {
            SystemError::NotFoundError(format!("设备不存在: {}", device_id))
        })?;

        let delta = ShadowDelta {
            device_id: device_id.to_string(),
            desired_changes: HashMap::new(),
            reported_changes: updates,
            timestamp: Utc::now(),
            version: state.version + 1,
        };

        self.state_store.apply_delta(device_id, &delta)?;
        self.notifier.notify(delta.clone()).await;
        self.sync_coordinator.queue_sync(device_id.to_string()).await?;

        Ok(())
    }

    pub async fn get_device_state(&self, device_id: &str) -> Result<DeviceState, SystemError> {
        self.state_store
            .get(device_id)
            .ok_or_else(|| SystemError::NotFoundError(format!("设备不存在: {}", device_id)))
    }

    pub async fn get_all_devices(&self) -> Vec<DeviceState> {
        self.state_store.get_all()
    }

    pub async fn set_device_online(&self, device_id: &str, online: bool) -> Result<(), SystemError> {
        self.ensure_device_exists(device_id)?;
        self.state_store.set_online(device_id, online)
    }

    pub async fn get_sync_status(&self, device_id: &str) -> Result<SyncStatus, SystemError> {
        self.sync_coordinator
            .get_sync_status(device_id)
            .ok_or_else(|| SystemError::NotFoundError(format!("设备不存在: {}", device_id)))
    }

    pub async fn register_callback<F>(&self, callback: F)
    where
        F: Fn(ShadowDelta) + Send + Sync + 'static,
    {
        self.notifier.register(callback).await;
    }

    fn ensure_device_exists(&self, device_id: &str) -> Result<(), SystemError> {
        if !self.state_store.exists(device_id) {
            return Err(SystemError::DeviceShadowError(format!("设备不存在: {}", device_id)));
        }
        Ok(())
    }

    pub async fn start_sync_worker(&self) -> Result<(), SystemError> {
        self.sync_coordinator.start_sync_worker(self.state_store.clone())
    }

    pub async fn force_sync(&self, device_id: &str) -> Result<(), SystemError> {
        self.ensure_device_exists(device_id)?;
        self.sync_coordinator.queue_sync(device_id.to_string()).await?;
        Ok(())
    }

    pub async fn get_state_diff(&self, device_id: &str) -> Result<HashMap<String, (Option<Value>, Option<Value>)>, SystemError> {
        let state = self.get_device_state(device_id).await?;
        let mut diff = HashMap::new();

        let all_keys: std::collections::HashSet<String> = state
            .desired
            .keys()
            .chain(state.reported.keys())
            .cloned()
            .collect();

        for key in all_keys {
            let desired = state.desired.get(&key).cloned();
            let reported = state.reported.get(&key).cloned();

            if desired != reported {
                diff.insert(key, (desired, reported));
            }
        }

        Ok(diff)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::atomic::{AtomicU32, Ordering};
    use tokio::sync::Barrier;

    fn create_test_config() -> DeviceShadowConfig {
        DeviceShadowConfig {
            sync_interval_secs: 60,
            cloud_endpoint: "http://localhost:8080".to_string(),
            report_interval_secs: 60,
        }
    }

    struct MockSuccessCloudSyncClient;

    #[async_trait]
    impl CloudSyncClient for MockSuccessCloudSyncClient {
        async fn sync(&self, _state: &DeviceState) -> Result<(), SystemError> {
            Ok(())
        }
    }

    struct MockFailingCloudSyncClient {
        fail_count: AtomicU32,
    }

    impl MockFailingCloudSyncClient {
        fn new() -> Self {
            Self {
                fail_count: AtomicU32::new(0),
            }
        }
    }

    #[async_trait]
    impl CloudSyncClient for MockFailingCloudSyncClient {
        async fn sync(&self, _state: &DeviceState) -> Result<(), SystemError> {
            self.fail_count.fetch_add(1, Ordering::SeqCst);
            Err(SystemError::DeviceShadowError("模拟同步失败".to_string()))
        }
    }

    struct MockFlakyCloudSyncClient {
        call_count: AtomicU32,
    }

    impl MockFlakyCloudSyncClient {
        fn new() -> Self {
            Self {
                call_count: AtomicU32::new(0),
            }
        }
    }

    #[async_trait]
    impl CloudSyncClient for MockFlakyCloudSyncClient {
        async fn sync(&self, _state: &DeviceState) -> Result<(), SystemError> {
            let count = self.call_count.fetch_add(1, Ordering::SeqCst);
            if count % 2 == 0 {
                Err(SystemError::DeviceShadowError("间歇性同步失败".to_string()))
            } else {
                Ok(())
            }
        }
    }

    fn create_test_shadow_with_client(
        config: &DeviceShadowConfig,
        client: Arc<dyn CloudSyncClient>,
    ) -> Result<DeviceShadow, SystemError> {
        let (sync_coordinator, sync_rx) = SyncCoordinator::new(config.clone(), client)?;
        let state_store = DeviceStateStore::new();
        let notifier = StateChangeNotifier::new();
        sync_coordinator.start_sync_listener(sync_rx, state_store.clone());

        Ok(DeviceShadow {
            state_store,
            notifier,
            sync_coordinator,
            config: config.clone(),
        })
    }

    // ==================== 边界条件测试 ====================

    #[tokio::test]
    async fn test_boundary_empty_device_id() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let result = shadow.register_device("".to_string()).await;
        assert!(result.is_ok());

        let state = shadow.get_device_state("").await;
        assert!(state.is_ok());
        assert_eq!(state.unwrap().device_id, "");
    }

    #[tokio::test]
    async fn test_boundary_very_long_device_id() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let long_id: String = (0..10000).map(|_| 'x').collect();
        let result = shadow.register_device(long_id.clone()).await;
        assert!(result.is_ok());

        let state = shadow.get_device_state(&long_id).await;
        assert!(state.is_ok());
        assert_eq!(state.unwrap().device_id.len(), 10000);
    }

    #[tokio::test]
    async fn test_boundary_special_chars_device_id() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let special_id = "device_!@#$%^&*()_+{}|:<>?[];',./`~中文测试🎉".to_string();
        let result = shadow.register_device(special_id.clone()).await;
        assert!(result.is_ok());

        let state = shadow.get_device_state(&special_id).await;
        assert!(state.is_ok());
        assert_eq!(state.unwrap().device_id, special_id);
    }

    #[tokio::test]
    async fn test_boundary_empty_state_update() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        shadow.register_device("device001".to_string()).await.unwrap();

        let result = shadow
            .update_desired_state("device001", HashMap::new())
            .await;
        assert!(result.is_ok());

        let state = shadow.get_device_state("device001").await.unwrap();
        assert!(state.desired.is_empty());
        assert_eq!(state.version, 2);
    }

    #[tokio::test]
    async fn test_boundary_very_large_state() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        shadow.register_device("device001".to_string()).await.unwrap();

        let mut large_state = HashMap::new();
        for i in 0..1000 {
            large_state.insert(
                format!("key_{}", i),
                json!(format!("value_{}", i)),
            );
        }

        let result = shadow
            .update_desired_state("device001", large_state)
            .await;
        assert!(result.is_ok());

        let state = shadow.get_device_state("device001").await.unwrap();
        assert_eq!(state.desired.len(), 1000);
    }

    #[tokio::test]
    async fn test_boundary_extreme_values() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        shadow.register_device("device001".to_string()).await.unwrap();

        let mut extreme_values = HashMap::new();
        extreme_values.insert("zero".to_string(), json!(0));
        extreme_values.insert("max_int".to_string(), json!(i64::MAX));
        extreme_values.insert("min_int".to_string(), json!(i64::MIN));
        extreme_values.insert("max_float".to_string(), json!(f64::MAX));
        extreme_values.insert("min_float".to_string(), json!(f64::MIN));
        extreme_values.insert("nan".to_string(), json!(serde_json::Value::Null));
        extreme_values.insert("empty_string".to_string(), json!(""));
        extreme_values.insert("very_long_string".to_string(), json!((0..10000).map(|_| 'a').collect::<String>()));
        extreme_values.insert("nested".to_string(), json!({"a": {"b": {"c": {"d": "deep"}}}}));

        let result = shadow
            .update_desired_state("device001", extreme_values)
            .await;
        assert!(result.is_ok());

        let state = shadow.get_device_state("device001").await.unwrap();
        assert_eq!(state.desired.len(), 9);
    }

    #[tokio::test]
    async fn test_boundary_zero_version() {
        let store = DeviceStateStore::new();
        store.register("device001".to_string());

        let mut state = store.get("device001").unwrap();
        state.version = 0;

        store.update("device001", |s| s.version = 0).unwrap();

        let delta = ShadowDelta {
            device_id: "device001".to_string(),
            desired_changes: HashMap::new(),
            reported_changes: HashMap::new(),
            timestamp: Utc::now(),
            version: 0,
        };

        let result = store.apply_delta("device001", &delta);
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_boundary_state_diff_empty() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        shadow.register_device("device001".to_string()).await.unwrap();

        let diff = shadow.get_state_diff("device001").await.unwrap();
        assert!(diff.is_empty());
    }

    #[tokio::test]
    async fn test_boundary_many_keys_diff() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        shadow.register_device("device001".to_string()).await.unwrap();

        let mut desired = HashMap::new();
        for i in 0..100 {
            desired.insert(format!("key_{}", i), json!(i));
        }
        shadow.update_desired_state("device001", desired).await.unwrap();

        let diff = shadow.get_state_diff("device001").await.unwrap();
        assert_eq!(diff.len(), 100);
    }

    // ==================== 并发场景测试 ====================

    #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
    async fn test_concurrent_device_registration() {
        let config = create_test_config();
        let client = Arc::new(MockSuccessCloudSyncClient);
        let shadow = create_test_shadow_with_client(&config, client).unwrap();
        let shadow = Arc::new(shadow);

        let num_tasks = 100;
        let mut handles = Vec::new();

        for i in 0..num_tasks {
            let shadow = shadow.clone();
            let handle = tokio::spawn(async move {
                shadow
                    .register_device(format!("concurrent_device_{}", i))
                    .await
                    .unwrap();
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.await.unwrap();
        }

        let all_devices = shadow.get_all_devices().await;
        assert_eq!(all_devices.len(), num_tasks);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
    async fn test_concurrent_state_updates() {
        let config = create_test_config();
        let client = Arc::new(MockSuccessCloudSyncClient);
        let shadow = create_test_shadow_with_client(&config, client).unwrap();
        let shadow = Arc::new(shadow);

        shadow.register_device("concurrent_device".to_string()).await.unwrap();

        let num_updates = 100;
        let mut handles = Vec::new();

        for i in 0..num_updates {
            let shadow = shadow.clone();
            let handle = tokio::spawn(async move {
                let mut updates = HashMap::new();
                updates.insert(format!("key_{:06}", i), json!(i));
                shadow
                    .update_desired_state("concurrent_device", updates)
                    .await
                    .unwrap();
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.await.unwrap();
        }

        let state = shadow.get_device_state("concurrent_device").await.unwrap();
        assert_eq!(state.desired.len(), num_updates);
        assert!(state.version >= 1);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
    async fn test_concurrent_read_write() {
        let config = create_test_config();
        let client = Arc::new(MockSuccessCloudSyncClient);
        let shadow = create_test_shadow_with_client(&config, client).unwrap();
        let shadow = Arc::new(shadow);

        shadow.register_device("rw_device".to_string()).await.unwrap();

        let barrier = Arc::new(Barrier::new(10));
        let mut handles = Vec::new();

        for i in 0..10 {
            let shadow = shadow.clone();
            let barrier = barrier.clone();
            let handle = tokio::spawn(async move {
                barrier.wait().await;
                if i % 2 == 0 {
                    let mut updates = HashMap::new();
                    updates.insert(format!("key_{}", i), json!(i));
                    shadow
                        .update_desired_state("rw_device", updates)
                        .await
                        .unwrap();
                } else {
                    let state = shadow.get_device_state("rw_device").await;
                    assert!(state.is_ok());
                }
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.await.unwrap();
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
    async fn test_concurrent_callbacks() {
        let config = create_test_config();
        let client = Arc::new(MockSuccessCloudSyncClient);
        let shadow = create_test_shadow_with_client(&config, client).unwrap();
        let shadow = Arc::new(shadow);

        let callback_count = Arc::new(AtomicU32::new(0));

        for _ in 0..10 {
            let count = callback_count.clone();
            shadow
                .register_callback(move |_delta| {
                    count.fetch_add(1, Ordering::SeqCst);
                })
                .await;
        }

        shadow.register_device("callback_device".to_string()).await.unwrap();

        let mut updates = HashMap::new();
        updates.insert("test".to_string(), json!(123));
        shadow
            .update_desired_state("callback_device", updates)
            .await
            .unwrap();

        tokio::time::sleep(std::time::Duration::from_millis(100)).await;

        assert_eq!(callback_count.load(Ordering::SeqCst), 10);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 16)]
    async fn test_concurrent_force_sync() {
        let config = create_test_config();
        let client = Arc::new(MockSuccessCloudSyncClient);
        let shadow = create_test_shadow_with_client(&config, client).unwrap();
        let shadow = Arc::new(shadow);

        for i in 0..10 {
            shadow
                .register_device(format!("sync_device_{}", i))
                .await
                .unwrap();
        }

        let mut handles = Vec::new();
        for i in 0..50 {
            let shadow = shadow.clone();
            let handle = tokio::spawn(async move {
                let device_id = format!("sync_device_{}", i % 10);
                let _ = shadow.force_sync(&device_id).await;
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.await.unwrap();
        }
    }

    // ==================== 异常路径测试 ====================

    #[tokio::test]
    async fn test_error_get_nonexistent_device() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let result = shadow.get_device_state("nonexistent").await;
        assert!(result.is_err());
        match result.unwrap_err() {
            SystemError::NotFoundError(msg) => {
                assert!(msg.contains("设备不存在"));
            }
            _ => panic!("预期 NotFoundError"),
        }
    }

    #[tokio::test]
    async fn test_error_update_nonexistent_device() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let mut updates = HashMap::new();
        updates.insert("test".to_string(), json!(123));

        let result = shadow
            .update_desired_state("nonexistent", updates)
            .await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_reported_state_nonexistent() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let mut updates = HashMap::new();
        updates.insert("test".to_string(), json!(123));

        let result = shadow
            .update_reported_state("nonexistent", updates)
            .await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_sync_status_nonexistent() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let result = shadow.get_sync_status("nonexistent").await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_force_sync_nonexistent() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let result = shadow.force_sync("nonexistent").await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_set_online_nonexistent() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let result = shadow.set_device_online("nonexistent", true).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_state_diff_nonexistent() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let result = shadow.get_state_diff("nonexistent").await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_duplicate_device_registration() {
        let config = create_test_config();
        let shadow = DeviceShadow::new(&config).unwrap();

        let result1 = shadow.register_device("device001".to_string()).await;
        assert!(result1.is_ok());

        let result2 = shadow.register_device("device001".to_string()).await;
        assert!(result2.is_ok());

        let all_devices = shadow.get_all_devices().await;
        assert_eq!(all_devices.len(), 1);
    }

    #[tokio::test]
    async fn test_error_store_update_nonexistent() {
        let store = DeviceStateStore::new();

        let result = store.update("nonexistent", |_s| {});
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_store_apply_delta_nonexistent() {
        let store = DeviceStateStore::new();

        let delta = ShadowDelta {
            device_id: "nonexistent".to_string(),
            desired_changes: HashMap::new(),
            reported_changes: HashMap::new(),
            timestamp: Utc::now(),
            version: 1,
        };

        let result = store.apply_delta("nonexistent", &delta);
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_store_set_online_nonexistent() {
        let store = DeviceStateStore::new();

        let result = store.set_online("nonexistent", true);
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_cloud_sync_failure() {
        let config = create_test_config();
        let client = Arc::new(MockFailingCloudSyncClient::new());
        let shadow = create_test_shadow_with_client(&config, client.clone()).unwrap();

        shadow.register_device("failing_device".to_string()).await.unwrap();

        let mut updates = HashMap::new();
        updates.insert("test".to_string(), json!(123));
        shadow
            .update_desired_state("failing_device", updates)
            .await
            .unwrap();

        tokio::time::sleep(std::time::Duration::from_millis(200)).await;

        let sync_status = shadow.get_sync_status("failing_device").await.unwrap();
        assert!(sync_status.sync_error.is_some());
        assert_eq!(
            sync_status.sync_error.unwrap(),
            "设备影子错误: 模拟同步失败"
        );
    }

    #[tokio::test]
    async fn test_error_flaky_cloud_sync() {
        let config = create_test_config();
        let client = Arc::new(MockFlakyCloudSyncClient::new());
        let shadow = create_test_shadow_with_client(&config, client).unwrap();

        shadow.register_device("flaky_device".to_string()).await.unwrap();

        for i in 0..4 {
            let mut updates = HashMap::new();
            updates.insert(format!("key_{}", i), json!(i));
            shadow
                .update_desired_state("flaky_device", updates)
                .await
                .unwrap();
        }

        tokio::time::sleep(std::time::Duration::from_millis(300)).await;

        let sync_status = shadow.get_sync_status("flaky_device").await.unwrap();
        assert_eq!(sync_status.synced, true);
    }

    #[tokio::test]
    async fn test_error_callback_exception_handling() {
        let config = create_test_config();
        let client = Arc::new(MockSuccessCloudSyncClient);
        let shadow = create_test_shadow_with_client(&config, client).unwrap();

        let call_count = Arc::new(AtomicU32::new(0));
        let callback_panicked = Arc::new(AtomicU32::new(0));

        shadow
            .register_callback({
                let count = call_count.clone();
                move |_delta| {
                    count.fetch_add(1, Ordering::SeqCst);
                }
            })
            .await;

        shadow
            .register_callback({
                let panicked = callback_panicked.clone();
                move |_delta| {
                    panicked.fetch_add(1, Ordering::SeqCst);
                    panic!("模拟回调函数异常");
                }
            })
            .await;

        shadow.register_device("callback_test".to_string()).await.unwrap();

        let mut updates = HashMap::new();
        updates.insert("test".to_string(), json!(123));

        let shadow_clone = shadow.clone();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            futures::executor::block_on(async {
                shadow_clone
                    .update_desired_state("callback_test", updates)
                    .await
            })
        }));

        assert!(result.is_err());
        assert_eq!(call_count.load(Ordering::SeqCst), 1);
        assert_eq!(callback_panicked.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn test_error_sync_coordinator_queue_sync() {
        let config = create_test_config();
        let client = Arc::new(MockSuccessCloudSyncClient);
        let (coordinator, mut rx) = SyncCoordinator::new(config, client).unwrap();

        coordinator.queue_sync("test_device".to_string()).await.unwrap();

        let received = tokio::time::timeout(
            std::time::Duration::from_secs(1),
            rx.recv(),
        )
        .await
        .unwrap();

        assert_eq!(received, Some("test_device".to_string()));
    }

    #[tokio::test]
    async fn test_error_sync_coordinator_mark_synced() {
        let config = create_test_config();
        let client = Arc::new(MockSuccessCloudSyncClient);
        let (coordinator, _rx) = SyncCoordinator::new(config, client).unwrap();

        coordinator.init_device("device001".to_string());
        coordinator.mark_synced("device001");

        let status = coordinator.get_sync_status("device001").unwrap();
        assert!(status.synced);
        assert!(status.last_sync.is_some());
        assert!(status.sync_error.is_none());
    }

    #[tokio::test]
    async fn test_error_sync_coordinator_mark_failed() {
        let config = create_test_config();
        let client = Arc::new(MockSuccessCloudSyncClient);
        let (coordinator, _rx) = SyncCoordinator::new(config, client).unwrap();

        coordinator.init_device("device001".to_string());
        coordinator.mark_failed("device001", "网络错误".to_string());

        let status = coordinator.get_sync_status("device001").unwrap();
        assert!(!status.synced);
        assert_eq!(status.sync_error, Some("网络错误".to_string()));
    }

    // ==================== 设备状态存储独立测试 ====================

    #[tokio::test]
    async fn test_device_state_store_full_crud() {
        let store = DeviceStateStore::new();

        assert!(!store.exists("device001"));
        assert!(store.register("device001".to_string()));
        assert!(!store.register("device001".to_string()));
        assert!(store.exists("device001"));

        let state = store.get("device001").unwrap();
        assert_eq!(state.device_id, "device001");
        assert_eq!(state.version, 1);
        assert!(!state.online);

        store
            .update("device001", |s| {
                s.desired.insert("test".to_string(), json!(123));
            })
            .unwrap();

        let updated = store.get("device001").unwrap();
        assert_eq!(updated.desired.get("test"), Some(&json!(123)));

        let all = store.get_all();
        assert_eq!(all.len(), 1);
    }

    // ==================== 状态变更通知器独立测试 ====================

    #[tokio::test]
    async fn test_state_change_notifier() {
        let notifier = StateChangeNotifier::new();
        let called = Arc::new(AtomicU32::new(0));

        notifier
            .register({
                let called = called.clone();
                move |_delta| {
                    called.fetch_add(1, Ordering::SeqCst);
                }
            })
            .await;

        let delta = ShadowDelta {
            device_id: "device001".to_string(),
            desired_changes: HashMap::new(),
            reported_changes: HashMap::new(),
            timestamp: Utc::now(),
            version: 1,
        };

        notifier.notify(delta).await;
        assert_eq!(called.load(Ordering::SeqCst), 1);
    }
}
