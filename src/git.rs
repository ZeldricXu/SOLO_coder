use std::path::Path;
use std::process::Stdio;
use std::sync::Arc;
use std::time::Instant;

use tokio::sync::Mutex;
use tokio::task;

use crate::config::RepositoryConfig;
use crate::errors::{AppError, AppResult, ErrorContext, ErrorKind};
use crate::events::{Event, EventBus, TaskStatus};
use crate::scheduler::{Scheduler, SchedulerConfig, TaskPriority};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ExecutionStatus {
    Pending,
    Running,
    Success,
    Failed,
    Conflict,
    Skipped,
}

impl From<TaskStatus> for ExecutionStatus {
    fn from(status: TaskStatus) -> Self {
        match status {
            TaskStatus::Pending => ExecutionStatus::Pending,
            TaskStatus::Running => ExecutionStatus::Running,
            TaskStatus::Success => ExecutionStatus::Success,
            TaskStatus::Failed => ExecutionStatus::Failed,
            TaskStatus::Conflict => ExecutionStatus::Conflict,
            TaskStatus::Skipped => ExecutionStatus::Skipped,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ExecutionResult {
    pub repository_name: String,
    pub repository_path: String,
    pub status: ExecutionStatus,
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
    pub changed_files: usize,
    pub duration_ms: u64,
    pub error_message: Option<String>,
}

impl ExecutionResult {
    pub fn new(repository_name: String, repository_path: String) -> Self {
        ExecutionResult {
            repository_name,
            repository_path,
            status: ExecutionStatus::Pending,
            stdout: String::new(),
            stderr: String::new(),
            exit_code: 0,
            changed_files: 0,
            duration_ms: 0,
            error_message: None,
        }
    }

    pub fn success(
        repository_name: String,
        repository_path: String,
        stdout: String,
        stderr: String,
        duration_ms: u64,
    ) -> Self {
        ExecutionResult {
            repository_name,
            repository_path,
            status: ExecutionStatus::Success,
            stdout,
            stderr,
            exit_code: 0,
            changed_files: 0,
            duration_ms,
            error_message: None,
        }
    }

    pub fn failed(
        repository_name: String,
        repository_path: String,
        stdout: String,
        stderr: String,
        exit_code: i32,
        error_message: String,
        duration_ms: u64,
    ) -> Self {
        ExecutionResult {
            repository_name,
            repository_path,
            status: ExecutionStatus::Failed,
            stdout,
            stderr,
            exit_code,
            changed_files: 0,
            duration_ms,
            error_message: Some(error_message),
        }
    }

    pub fn conflict(
        repository_name: String,
        repository_path: String,
        stdout: String,
        stderr: String,
        duration_ms: u64,
    ) -> Self {
        ExecutionResult {
            repository_name,
            repository_path,
            status: ExecutionStatus::Conflict,
            stdout,
            stderr,
            exit_code: 1,
            changed_files: 0,
            duration_ms,
            error_message: Some("存在合并冲突".to_string()),
        }
    }

    pub fn skipped(
        repository_name: String,
        repository_path: String,
        reason: String,
    ) -> Self {
        ExecutionResult {
            repository_name,
            repository_path,
            status: ExecutionStatus::Skipped,
            stdout: String::new(),
            stderr: reason.clone(),
            exit_code: 0,
            changed_files: 0,
            duration_ms: 0,
            error_message: Some(reason),
        }
    }
}

#[derive(Debug, Clone)]
pub struct BatchResult {
    pub results: Vec<ExecutionResult>,
    pub total: usize,
    pub success_count: usize,
    pub failed_count: usize,
    pub conflict_count: usize,
    pub skipped_count: usize,
    pub total_duration_ms: u64,
}

impl BatchResult {
    pub fn new(results: Vec<ExecutionResult>, total_duration_ms: u64) -> Self {
        let total = results.len();
        let success_count = results
            .iter()
            .filter(|r| r.status == ExecutionStatus::Success)
            .count();
        let failed_count = results
            .iter()
            .filter(|r| r.status == ExecutionStatus::Failed)
            .count();
        let conflict_count = results
            .iter()
            .filter(|r| r.status == ExecutionStatus::Conflict)
            .count();
        let skipped_count = results
            .iter()
            .filter(|r| r.status == ExecutionStatus::Skipped || r.status == ExecutionStatus::Pending)
            .count();

        BatchResult {
            results,
            total,
            success_count,
            failed_count,
            conflict_count,
            skipped_count,
            total_duration_ms,
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct GitOptions {
    pub force: bool,
    pub fetch_only: bool,
    pub prune: bool,
    pub fetch_depth: Option<u32>,
    pub retry_count: u32,
    pub timeout_seconds: u64,
    pub verbose: bool,
    pub priority: TaskPriority,
}

pub struct GitEngine {
    scheduler: Arc<Mutex<Option<Scheduler<ExecutionResult, AppError>>>>,
    event_bus: Option<EventBus>,
    scheduler_config: SchedulerConfig,
}

impl GitEngine {
    pub fn new(max_concurrency: Option<usize>) -> Self {
        let mut config = SchedulerConfig::default();
        if let Some(concurrency) = max_concurrency {
            config.max_concurrent = concurrency;
        }

        GitEngine {
            scheduler: Arc::new(Mutex::new(None)),
            event_bus: None,
            scheduler_config: config,
        }
    }

    pub fn with_event_bus(mut self, event_bus: EventBus) -> Self {
        self.event_bus = Some(event_bus);
        self
    }

    pub fn set_event_bus(&mut self, event_bus: EventBus) {
        self.event_bus = Some(event_bus);
    }

    fn emit_event(&self, event: Event) {
        if let Some(ref bus) = self.event_bus {
            let _ = bus.emit_blocking(event);
        }
    }

    async fn ensure_scheduler(
        &self,
    ) -> (
        Arc<Mutex<Option<Scheduler<ExecutionResult, AppError>>>>,
        tokio::sync::mpsc::UnboundedReceiver<(u64, Result<ExecutionResult, AppError>)>,
    ) {
        let mut scheduler_guard = self.scheduler.lock().await;
        if scheduler_guard.is_none() {
            let (scheduler, result_rx) = Scheduler::new(self.scheduler_config.clone());
            *scheduler_guard = Some(scheduler);
            
            let scheduler_clone = self.scheduler.clone();
            tokio::spawn(async move {
                if let Some(s) = scheduler_clone.lock().await.as_mut() {
                    s.run().await;
                }
            });
        }

        let (scheduler, result_rx) = Scheduler::new(self.scheduler_config.clone());
        let scheduler_arc = Arc::new(Mutex::new(Some(scheduler)));
        
        let scheduler_clone = scheduler_arc.clone();
        tokio::spawn(async move {
            if let Some(s) = scheduler_clone.lock().await.as_mut() {
                s.run().await;
            }
        });

        (scheduler_arc, result_rx)
    }

    async fn execute_git_command_blocking(
        working_dir: String,
        args: Vec<String>,
        _timeout_seconds: u64,
    ) -> AppResult<(String, String, i32)> {
        let working_dir_path = Path::new(&working_dir);

        if !working_dir_path.exists() {
            return Err(AppError::not_found(format!("路径不存在: {}", working_dir)));
        }

        let output = task::spawn_blocking(move || {
            let mut command = std::process::Command::new("git");
            command
                .args(&args)
                .current_dir(&working_dir)
                .stdout(Stdio::piped())
                .stderr(Stdio::piped());

            command.output()
        })
        .await??;

        let stdout = String::from_utf8(output.stdout)?;
        let stderr = String::from_utf8(output.stderr)?;
        let exit_code = output.status.code().unwrap_or(-1);

        Ok((stdout, stderr, exit_code))
    }

    pub async fn execute_git_command(
        working_dir: impl AsRef<Path>,
        args: &[&str],
        timeout_seconds: Option<u64>,
    ) -> AppResult<(String, String, i32)> {
        let working_dir_str = working_dir.as_ref().to_string_lossy().to_string();
        let args_vec: Vec<String> = args.iter().map(|s| s.to_string()).collect();
        let timeout = timeout_seconds.unwrap_or(300);

        Self::execute_git_command_blocking(working_dir_str, args_vec, timeout).await
    }

    fn validate_repository(repo: &RepositoryConfig) -> Result<(), String> {
        let local_path = Path::new(&repo.local_path);

        if !local_path.exists() {
            return Err(format!("本地路径不存在: {}", repo.local_path));
        }

        let git_dir = local_path.join(".git");
        if !git_dir.exists() {
            return Err(format!("不是一个Git仓库: {}", repo.local_path));
        }

        Ok(())
    }

    async fn pull_single(
        repo: RepositoryConfig,
        options: GitOptions,
        event_bus: Option<EventBus>,
    ) -> AppResult<ExecutionResult> {
        let start_time = Instant::now();
        let repo_name = repo.name.clone();
        let repo_path = repo.local_path.clone();

        let emit = |event: Event| {
            if let Some(ref bus) = event_bus {
                let _ = bus.emit_blocking(event);
            }
        };

        emit(Event::task_started(
            repo_name.clone(),
            if options.fetch_only { "fetch" } else { "pull" }.to_string(),
        ));

        if let Err(reason) = Self::validate_repository(&repo) {
            emit(Event::task_skipped(
                repo_name.clone(),
                if options.fetch_only { "fetch" } else { "pull" }.to_string(),
                reason.clone(),
            ));
            return Ok(ExecutionResult::skipped(repo_name, repo_path, reason));
        }

        let local_path = Path::new(&repo.local_path);

        let mut args = vec!["pull"];
        if options.force {
            args.push("--force");
        }

        let fetch_args = if options.fetch_only {
            let mut fetch = vec!["fetch"];
            if options.prune {
                fetch.push("--prune");
            }
            if let Some(depth) = options.fetch_depth {
                fetch.push("--depth");
                fetch.push(&depth.to_string());
            }
            Some(fetch)
        } else {
            None
        };

        let actual_args = fetch_args.unwrap_or(args);

        let mut attempts = 0;
        let max_attempts = options.retry_count + 1;

        loop {
            match Self::execute_git_command(local_path, &actual_args, Some(options.timeout_seconds)).await {
                Ok((stdout, stderr, exit_code)) => {
                    let duration_ms = start_time.elapsed().as_millis() as u64;

                    if exit_code == 0 {
                        emit(Event::task_completed(
                            repo_name.clone(),
                            if options.fetch_only { "fetch" } else { "pull" }.to_string(),
                            duration_ms,
                        ));

                        return Ok(ExecutionResult::success(
                            repo_name,
                            repo_path,
                            stdout,
                            stderr,
                            duration_ms,
                        ));
                    } else {
                        if stderr.contains("CONFLICT") || stderr.contains("conflict") {
                            emit(Event::task_failed(
                                repo_name.clone(),
                                if options.fetch_only { "fetch" } else { "pull" }.to_string(),
                                "存在合并冲突".to_string(),
                            ));

                            return Ok(ExecutionResult::conflict(
                                repo_name,
                                repo_path,
                                stdout,
                                stderr,
                                duration_ms,
                            ));
                        } else if attempts < max_attempts - 1 {
                            attempts += 1;
                            emit(Event::task_progress(
                                repo_name.clone(),
                                if options.fetch_only { "fetch" } else { "pull" }.to_string(),
                                format!("重试中 ({}/{})", attempts + 1, max_attempts),
                                0.5,
                            ));
                            continue;
                        } else {
                            let error_msg = format!("Git命令执行失败，退出码: {}", exit_code);
                            emit(Event::task_failed(
                                repo_name.clone(),
                                if options.fetch_only { "fetch" } else { "pull" }.to_string(),
                                error_msg.clone(),
                            ));

                            return Ok(ExecutionResult::failed(
                                repo_name,
                                repo_path,
                                stdout,
                                stderr,
                                exit_code,
                                error_msg,
                                duration_ms,
                            ));
                        }
                    }
                }
                Err(e) => {
                    let duration_ms = start_time.elapsed().as_millis() as u64;

                    if attempts < max_attempts - 1 {
                        attempts += 1;
                        emit(Event::task_progress(
                            repo_name.clone(),
                            if options.fetch_only { "fetch" } else { "pull" }.to_string(),
                            format!("重试中 ({}/{}): {}", attempts + 1, max_attempts, e),
                            0.5,
                        ));
                        continue;
                    }

                    emit(Event::task_failed(
                        repo_name.clone(),
                        if options.fetch_only { "fetch" } else { "pull" }.to_string(),
                        e.to_string(),
                    ));

                    return Ok(ExecutionResult::failed(
                        repo_name,
                        repo_path,
                        String::new(),
                        e.to_string(),
                        -1,
                        e.to_string(),
                        duration_ms,
                    ));
                }
            }
        }
    }

    pub async fn batch_pull(
        &self,
        repositories: &[RepositoryConfig],
        options: GitOptions,
    ) -> AppResult<BatchResult> {
        let start_time = Instant::now();
        let operation = if options.fetch_only { "fetch" } else { "pull" };
        let total = repositories.len();

        self.emit_event(Event::batch_started(operation.to_string(), total));

        let (scheduler_arc, mut result_rx) = self.ensure_scheduler().await;

        let mut completed = 0usize;
        let mut failed = 0usize;
        let mut skipped = 0usize;

        for repo in repositories.iter().cloned() {
            let options_clone = options.clone();
            let event_bus_clone = self.event_bus.clone();

            let scheduler_guard = scheduler_arc.lock().await;
            if let Some(scheduler) = scheduler_guard.as_ref() {
                let task_name = format!("{}:{}", operation, repo.name);
                let _task_id = scheduler
                    .schedule(
                        task_name,
                        options_clone.priority,
                        Self::pull_single(repo, options_clone, event_bus_clone),
                    )
                    .await?;
            }
        }

        let mut results = Vec::new();

        while results.len() < total {
            if let Some((_task_id, result)) = result_rx.recv().await {
                match result {
                    Ok(exec_result) => {
                        match exec_result.status {
                            ExecutionStatus::Success => completed += 1,
                            ExecutionStatus::Failed => failed += 1,
                            ExecutionStatus::Skipped => skipped += 1,
                            ExecutionStatus::Conflict => failed += 1,
                            _ => {}
                        }

                        self.emit_event(Event::batch_progress(
                            operation.to_string(),
                            total,
                            completed,
                            failed,
                            skipped,
                        ));

                        results.push(exec_result);
                    }
                    Err(e) => {
                        failed += 1;
                        self.emit_event(Event::error(e.to_string()));
                    }
                }
            } else {
                break;
            }
        }

        let total_duration_ms = start_time.elapsed().as_millis() as u64;

        self.emit_event(Event::batch_completed(
            operation.to_string(),
            total,
            completed,
            failed,
            skipped,
        ));

        Ok(BatchResult::new(results, total_duration_ms))
    }

    pub async fn batch_fetch(
        &self,
        repositories: &[RepositoryConfig],
        mut options: GitOptions,
    ) -> AppResult<BatchResult> {
        options.fetch_only = true;
        self.batch_pull(repositories, options).await
    }

    pub async fn get_status(repo: &RepositoryConfig) -> AppResult<(String, String, i32)> {
        let local_path = Path::new(&repo.local_path);
        Self::execute_git_command(local_path, &["status", "--porcelain"], Some(60)).await
    }

    pub async fn get_log(
        repo: &RepositoryConfig,
        author: Option<&str>,
        since: Option<&str>,
        number: Option<usize>,
    ) -> AppResult<String> {
        let local_path = Path::new(&repo.local_path);
        let mut args = vec!["log", "--pretty=format:%H|%an|%ae|%ad|%s", "--date=iso"];

        if let Some(a) = author {
            args.push("--author");
            args.push(a);
        }

        if let Some(s) = since {
            args.push("--since");
            args.push(s);
        }

        if let Some(n) = number {
            args.push("-n");
            args.push(&n.to_string());
        }

        let (stdout, _, _) = Self::execute_git_command(local_path, &args, Some(60)).await?;
        Ok(stdout)
    }

    async fn get_log_single(
        repo: RepositoryConfig,
        author: Option<String>,
        since: Option<String>,
        number: Option<usize>,
        event_bus: Option<EventBus>,
    ) -> AppResult<String> {
        let repo_name = repo.name.clone();

        let emit = |event: Event| {
            if let Some(ref bus) = event_bus {
                let _ = bus.emit_blocking(event);
            }
        };

        emit(Event::task_started(repo_name.clone(), "log".to_string()));

        match Self::validate_repository(&repo) {
            Ok(_) => {}
            Err(reason) => {
                emit(Event::task_skipped(
                    repo_name.clone(),
                    "log".to_string(),
                    reason.clone(),
                ));
                return Err(AppError::not_found(reason));
            }
        }

        let result = Self::get_log(
            &repo,
            author.as_deref(),
            since.as_deref(),
            number,
        )
        .await;

        match &result {
            Ok(logs) => {
                let commit_count = logs.lines().count();
                emit(Event::task_completed(repo_name, "log".to_string(), 0));
            }
            Err(e) => {
                emit(Event::task_failed(repo_name, "log".to_string(), e.to_string()));
            }
        }

        result
    }

    pub async fn batch_log(
        &self,
        repositories: &[RepositoryConfig],
        author: Option<&str>,
        since: Option<&str>,
        number: Option<usize>,
    ) -> AppResult<std::collections::HashMap<String, AppResult<String>>> {
        let operation = "log";
        let total = repositories.len();

        self.emit_event(Event::batch_started(operation.to_string(), total));

        let (scheduler_arc, mut result_rx) = self.ensure_scheduler().await;

        for repo in repositories.iter().cloned() {
            let author_clone = author.map(|s| s.to_string());
            let since_clone = since.map(|s| s.to_string());
            let event_bus_clone = self.event_bus.clone();

            let scheduler_guard = scheduler_arc.lock().await;
            if let Some(scheduler) = scheduler_guard.as_ref() {
                let task_name = format!("log:{}", repo.name);
                let _task_id = scheduler
                    .schedule(
                        task_name,
                        TaskPriority::Normal,
                        Self::get_log_single(repo, author_clone, since_clone, number, event_bus_clone),
                    )
                    .await?;
            }
        }

        let mut results = std::collections::HashMap::new();
        let mut completed = 0usize;
        let mut failed = 0usize;
        let mut skipped = 0usize;

        while results.len() < total {
            if let Some((_task_id, result)) = result_rx.recv().await {
                match result {
                    Ok(log_output) => {
                        completed += 1;
                        self.emit_event(Event::batch_progress(
                            operation.to_string(),
                            total,
                            completed,
                            failed,
                            skipped,
                        ));
                        results.insert("unknown".to_string(), Ok(log_output));
                    }
                    Err(e) => {
                        failed += 1;
                        self.emit_event(Event::error(e.to_string()));
                        results.insert("unknown".to_string(), Err(e));
                    }
                }
            } else {
                break;
            }
        }

        self.emit_event(Event::batch_completed(
            operation.to_string(),
            total,
            completed,
            failed,
            skipped,
        ));

        Ok(results)
    }
}

impl Default for GitEngine {
    fn default() -> Self {
        Self::new(None)
    }
}
