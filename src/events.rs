use std::collections::HashMap;
use std::sync::Arc;

use tokio::sync::{broadcast, RwLock};

use crate::errors::AppResult;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum EventType {
    TaskStarted,
    TaskProgress,
    TaskCompleted,
    TaskFailed,
    TaskSkipped,
    BatchStarted,
    BatchProgress,
    BatchCompleted,
    ConfigLoaded,
    ConfigValidated,
    LogLoaded,
    ErrorOccurred,
    Custom(&'static str),
}

impl std::fmt::Display for EventType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EventType::TaskStarted => write!(f, "TaskStarted"),
            EventType::TaskProgress => write!(f, "TaskProgress"),
            EventType::TaskCompleted => write!(f, "TaskCompleted"),
            EventType::TaskFailed => write!(f, "TaskFailed"),
            EventType::TaskSkipped => write!(f, "TaskSkipped"),
            EventType::BatchStarted => write!(f, "BatchStarted"),
            EventType::BatchProgress => write!(f, "BatchProgress"),
            EventType::BatchCompleted => write!(f, "BatchCompleted"),
            EventType::ConfigLoaded => write!(f, "ConfigLoaded"),
            EventType::ConfigValidated => write!(f, "ConfigValidated"),
            EventType::LogLoaded => write!(f, "LogLoaded"),
            EventType::ErrorOccurred => write!(f, "ErrorOccurred"),
            EventType::Custom(name) => write!(f, "Custom:{}", name),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskStatus {
    Pending,
    Running,
    Success,
    Failed,
    Conflict,
    Skipped,
}

impl std::fmt::Display for TaskStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TaskStatus::Pending => write!(f, "等待中"),
            TaskStatus::Running => write!(f, "执行中"),
            TaskStatus::Success => write!(f, "成功"),
            TaskStatus::Failed => write!(f, "失败"),
            TaskStatus::Conflict => write!(f, "冲突"),
            TaskStatus::Skipped => write!(f, "跳过"),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Event {
    pub event_type: EventType,
    pub timestamp: chrono::DateTime<chrono::Local>,
    pub payload: EventPayload,
}

impl Event {
    pub fn new(event_type: EventType, payload: EventPayload) -> Self {
        Event {
            event_type,
            timestamp: chrono::Local::now(),
            payload,
        }
    }

    pub fn task_started(repository: String, operation: String) -> Self {
        Event::new(
            EventType::TaskStarted,
            EventPayload::TaskEvent(TaskEvent {
                repository,
                operation,
                status: TaskStatus::Running,
                message: Some("开始执行".to_string()),
                progress: None,
                duration_ms: None,
            }),
        )
    }

    pub fn task_progress(repository: String, operation: String, message: String, progress: f32) -> Self {
        Event::new(
            EventType::TaskProgress,
            EventPayload::TaskEvent(TaskEvent {
                repository,
                operation,
                status: TaskStatus::Running,
                message: Some(message),
                progress: Some(progress),
                duration_ms: None,
            }),
        )
    }

    pub fn task_completed(repository: String, operation: String, duration_ms: u64) -> Self {
        Event::new(
            EventType::TaskCompleted,
            EventPayload::TaskEvent(TaskEvent {
                repository,
                operation,
                status: TaskStatus::Success,
                message: Some("完成".to_string()),
                progress: Some(1.0),
                duration_ms: Some(duration_ms),
            }),
        )
    }

    pub fn task_failed(repository: String, operation: String, error: String) -> Self {
        Event::new(
            EventType::TaskFailed,
            EventPayload::TaskEvent(TaskEvent {
                repository,
                operation,
                status: TaskStatus::Failed,
                message: Some(error),
                progress: None,
                duration_ms: None,
            }),
        )
    }

    pub fn task_skipped(repository: String, operation: String, reason: String) -> Self {
        Event::new(
            EventType::TaskSkipped,
            EventPayload::TaskEvent(TaskEvent {
                repository,
                operation,
                status: TaskStatus::Skipped,
                message: Some(reason),
                progress: None,
                duration_ms: None,
            }),
        )
    }

    pub fn batch_started(operation: String, total_tasks: usize) -> Self {
        Event::new(
            EventType::BatchStarted,
            EventPayload::BatchEvent(BatchEvent {
                operation,
                total_tasks,
                completed_tasks: 0,
                failed_tasks: 0,
                skipped_tasks: 0,
                message: Some("批处理开始".to_string()),
            }),
        )
    }

    pub fn batch_progress(
        operation: String,
        total_tasks: usize,
        completed_tasks: usize,
        failed_tasks: usize,
        skipped_tasks: usize,
    ) -> Self {
        Event::new(
            EventType::BatchProgress,
            EventPayload::BatchEvent(BatchEvent {
                operation,
                total_tasks,
                completed_tasks,
                failed_tasks,
                skipped_tasks,
                message: None,
            }),
        )
    }

    pub fn batch_completed(
        operation: String,
        total_tasks: usize,
        completed_tasks: usize,
        failed_tasks: usize,
        skipped_tasks: usize,
    ) -> Self {
        Event::new(
            EventType::BatchCompleted,
            EventPayload::BatchEvent(BatchEvent {
                operation,
                total_tasks,
                completed_tasks,
                failed_tasks,
                skipped_tasks,
                message: Some("批处理完成".to_string()),
            }),
        )
    }

    pub fn error(error_message: String) -> Self {
        Event::new(
            EventType::ErrorOccurred,
            EventPayload::ErrorEvent(ErrorEvent {
                message: error_message,
            }),
        )
    }
}

#[derive(Debug, Clone)]
pub enum EventPayload {
    TaskEvent(TaskEvent),
    BatchEvent(BatchEvent),
    ErrorEvent(ErrorEvent),
    ConfigEvent(ConfigEvent),
    LogEvent(LogEvent),
    Custom(CustomEvent),
}

#[derive(Debug, Clone)]
pub struct TaskEvent {
    pub repository: String,
    pub operation: String,
    pub status: TaskStatus,
    pub message: Option<String>,
    pub progress: Option<f32>,
    pub duration_ms: Option<u64>,
}

#[derive(Debug, Clone)]
pub struct BatchEvent {
    pub operation: String,
    pub total_tasks: usize,
    pub completed_tasks: usize,
    pub failed_tasks: usize,
    pub skipped_tasks: usize,
    pub message: Option<String>,
}

impl BatchEvent {
    pub fn progress_percent(&self) -> f32 {
        if self.total_tasks == 0 {
            0.0
        } else {
            let done = self.completed_tasks + self.failed_tasks + self.skipped_tasks;
            done as f32 / self.total_tasks as f32
        }
    }
}

#[derive(Debug, Clone)]
pub struct ErrorEvent {
    pub message: String,
}

#[derive(Debug, Clone)]
pub struct ConfigEvent {
    pub action: String,
    pub path: Option<std::path::PathBuf>,
    pub valid: Option<bool>,
}

#[derive(Debug, Clone)]
pub struct LogEvent {
    pub repository: String,
    pub commit_count: usize,
}

#[derive(Debug, Clone)]
pub struct CustomEvent {
    pub name: String,
    pub data: HashMap<String, String>,
}

pub trait EventSubscriber: Send + Sync + 'static {
    fn on_event(&self, event: &Event) -> AppResult<()>;
    
    fn interested_in(&self) -> &'static [EventType] {
        &[]
    }
}

pub trait EventEmitter {
    fn emit(&self, event: Event) -> AppResult<()>;
}

pub type SubscriberId = u64;

pub struct EventBus {
    subscribers: Arc<RwLock<HashMap<SubscriberId, Box<dyn EventSubscriber>>>>,
    broadcast: broadcast::Sender<Event>,
    next_id: std::sync::atomic::AtomicU64,
}

impl EventBus {
    pub fn new() -> Self {
        let (tx, _) = broadcast::channel(1000);
        EventBus {
            subscribers: Arc::new(RwLock::new(HashMap::new())),
            broadcast: tx,
            next_id: std::sync::atomic::AtomicU64::new(1),
        }
    }

    pub fn subscribe(&self, subscriber: Box<dyn EventSubscriber>) -> SubscriberId {
        let id = self.next_id.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let mut subscribers = self.subscribers.blocking_write();
        subscribers.insert(id, subscriber);
        id
    }

    pub fn unsubscribe(&self, id: SubscriberId) {
        let mut subscribers = self.subscribers.blocking_write();
        subscribers.remove(&id);
    }

    pub async fn emit(&self, event: Event) -> AppResult<()> {
        let _ = self.broadcast.send(event.clone());

        let subscribers = self.subscribers.read().await;
        
        for subscriber in subscribers.values() {
            let interested = subscriber.interested_in();
            if interested.is_empty() || interested.contains(&event.event_type) {
                if let Err(e) = subscriber.on_event(&event) {
                    eprintln!("事件处理错误: {}", e);
                }
            }
        }

        Ok(())
    }

    pub fn emit_blocking(&self, event: Event) -> AppResult<()> {
        let _ = self.broadcast.send(event.clone());

        let subscribers = self.subscribers.blocking_read();
        
        for subscriber in subscribers.values() {
            let interested = subscriber.interested_in();
            if interested.is_empty() || interested.contains(&event.event_type) {
                if let Err(e) = subscriber.on_event(&event) {
                    eprintln!("事件处理错误: {}", e);
                }
            }
        }

        Ok(())
    }

    pub fn subscribe_broadcast(&self) -> broadcast::Receiver<Event> {
        self.broadcast.subscribe()
    }
}

impl Default for EventBus {
    fn default() -> Self {
        Self::new()
    }
}

impl Clone for EventBus {
    fn clone(&self) -> Self {
        EventBus {
            subscribers: self.subscribers.clone(),
            broadcast: self.broadcast.clone(),
            next_id: std::sync::atomic::AtomicU64::new(
                self.next_id.load(std::sync::atomic::Ordering::Relaxed),
            ),
        }
    }
}

pub struct ProgressBarSubscriber {
    pb: indicatif::ProgressBar,
    operation: String,
}

impl ProgressBarSubscriber {
    pub fn new(total: u64, operation: String) -> Self {
        let pb = indicatif::ProgressBar::new(total);
        pb.set_style(
            indicatif::ProgressStyle::default_bar()
                .template(
                    "{spinner:.green} [{elapsed_precise}] [{bar:40.cyan/blue}] {pos}/{len} ({eta}) {msg}",
                )
                .unwrap()
                .progress_chars("#>-"),
        );
        pb.set_message(operation.clone());

        ProgressBarSubscriber { pb, operation }
    }

    pub fn finish_with_message(&self, msg: String) {
        self.pb.finish_with_message(msg);
    }
}

impl EventSubscriber for ProgressBarSubscriber {
    fn on_event(&self, event: &Event) -> AppResult<()> {
        match &event.payload {
            EventPayload::BatchEvent(batch) => {
                let done = batch.completed_tasks + batch.failed_tasks + batch.skipped_tasks;
                self.pb.set_position(done as u64);

                match event.event_type {
                    EventType::BatchProgress => {
                        if let Some(repo) = self.get_current_repo(event) {
                            self.pb.set_message(repo);
                        }
                    }
                    EventType::BatchCompleted => {
                        self.pb.finish_with_message(format!("{} 完成", self.operation));
                    }
                    _ => {}
                }
            }
            EventPayload::TaskEvent(task) => match event.event_type {
                EventType::TaskStarted => {
                    self.pb.set_message(format!("{}: {}", self.operation, task.repository));
                }
                EventType::TaskProgress => {
                    if let Some(ref msg) = task.message {
                        self.pb.set_message(format!("{}: {}", task.repository, msg));
                    }
                }
                _ => {}
            },
            _ => {}
        }

        Ok(())
    }
}

impl ProgressBarSubscriber {
    fn get_current_repo(&self, _event: &Event) -> Option<String> {
        None
    }
}

pub struct ConsoleSubscriber {
    verbose: bool,
}

impl ConsoleSubscriber {
    pub fn new(verbose: bool) -> Self {
        ConsoleSubscriber { verbose }
    }
}

impl EventSubscriber for ConsoleSubscriber {
    fn on_event(&self, event: &Event) -> AppResult<()> {
        use colored::*;

        match &event.payload {
            EventPayload::TaskEvent(task) => {
                let status_str = match event.event_type {
                    EventType::TaskStarted => "● 开始".cyan(),
                    EventType::TaskProgress => "○ 执行中".blue(),
                    EventType::TaskCompleted => "✓ 成功".green(),
                    EventType::TaskFailed => "✗ 失败".red(),
                    EventType::TaskSkipped => "→ 跳过".yellow(),
                    _ => return Ok(()),
                };

                let duration_str = task
                    .duration_ms
                    .map(|d| format!(" ({:.2}s)", d as f64 / 1000.0))
                    .unwrap_or_default();

                println!(
                    "{} {}{}",
                    status_str,
                    task.repository.bold(),
                    duration_str
                );

                if self.verbose {
                    if let Some(ref msg) = task.message {
                        if !msg.is_empty() {
                            println!("      {}", msg.dimmed());
                        }
                    }
                }
            }
            EventPayload::BatchEvent(batch) => match event.event_type {
                EventType::BatchStarted => {
                    println!(
                        "\n{} {} 操作，共 {} 个任务",
                        "开始".cyan(),
                        batch.operation,
                        batch.total_tasks.to_string().yellow()
                    );
                }
                EventType::BatchCompleted => {
                    println!("\n{}:", "汇总".bold().cyan());
                    println!(
                        "  {}: {}",
                        "成功".green(),
                        batch.completed_tasks.to_string().green()
                    );
                    if batch.failed_tasks > 0 {
                        println!(
                            "  {}: {}",
                            "失败".red(),
                            batch.failed_tasks.to_string().red()
                        );
                    }
                    if batch.skipped_tasks > 0 {
                        println!(
                            "  {}: {}",
                            "跳过".yellow(),
                            batch.skipped_tasks.to_string().yellow()
                        );
                    }
                }
                _ => {}
            },
            EventPayload::ErrorEvent(err) => {
                eprintln!("{}: {}", "错误".red(), err.message);
            }
            _ => {}
        }

        Ok(())
    }

    fn interested_in(&self) -> &'static [EventType] {
        &[
            EventType::TaskStarted,
            EventType::TaskProgress,
            EventType::TaskCompleted,
            EventType::TaskFailed,
            EventType::TaskSkipped,
            EventType::BatchStarted,
            EventType::BatchCompleted,
            EventType::ErrorOccurred,
        ]
    }
}

pub struct SilentSubscriber;

impl EventSubscriber for SilentSubscriber {
    fn on_event(&self, _event: &Event) -> AppResult<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestSubscriber {
        events: std::sync::Mutex<Vec<Event>>,
    }

    impl TestSubscriber {
        fn new() -> Self {
            TestSubscriber {
                events: std::sync::Mutex::new(Vec::new()),
            }
        }

        fn event_count(&self) -> usize {
            self.events.lock().unwrap().len()
        }
    }

    impl EventSubscriber for TestSubscriber {
        fn on_event(&self, event: &Event) -> AppResult<()> {
            self.events.lock().unwrap().push(event.clone());
            Ok(())
        }
    }

    #[tokio::test]
    async fn test_event_bus() {
        let bus = EventBus::new();
        let subscriber = Box::new(TestSubscriber::new());
        let id = bus.subscribe(subscriber);

        let event = Event::task_started(
            "test-repo".to_string(),
            "pull".to_string(),
        );
        bus.emit(event).await.unwrap();

        let subscribers = bus.subscribers.read().await;
        let subscriber = subscribers.get(&id).unwrap();
        
        if let Some(test_sub) = subscriber.as_any().downcast_ref::<TestSubscriber>() {
            assert_eq!(test_sub.event_count(), 1);
        }
    }

    #[test]
    fn test_event_creation() {
        let event = Event::task_completed("repo".to_string(), "pull".to_string(), 1500);
        assert_eq!(event.event_type, EventType::TaskCompleted);
        
        if let EventPayload::TaskEvent(task) = event.payload {
            assert_eq!(task.repository, "repo");
            assert_eq!(task.status, TaskStatus::Success);
            assert_eq!(task.duration_ms, Some(1500));
        } else {
            panic!("Expected TaskEvent");
        }
    }
}
