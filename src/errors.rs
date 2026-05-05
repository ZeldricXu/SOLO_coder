use std::error::Error;
use std::fmt;
use std::path::PathBuf;

use crate::scheduler::{SchedulerError, TaskId};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    Config,
    Git,
    Scheduler,
    Io,
    Parse,
    Network,
    Timeout,
    Conflict,
    Validation,
    NotFound,
    Permission,
    Internal,
}

impl fmt::Display for ErrorKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ErrorKind::Config => write!(f, "配置错误"),
            ErrorKind::Git => write!(f, "Git操作错误"),
            ErrorKind::Scheduler => write!(f, "调度器错误"),
            ErrorKind::Io => write!(f, "IO错误"),
            ErrorKind::Parse => write!(f, "解析错误"),
            ErrorKind::Network => write!(f, "网络错误"),
            ErrorKind::Timeout => write!(f, "超时错误"),
            ErrorKind::Conflict => write!(f, "冲突错误"),
            ErrorKind::Validation => write!(f, "验证错误"),
            ErrorKind::NotFound => write!(f, "未找到错误"),
            ErrorKind::Permission => write!(f, "权限错误"),
            ErrorKind::Internal => write!(f, "内部错误"),
        }
    }
}

#[derive(Debug, Clone)]
pub struct ErrorContext {
    pub file: Option<&'static str>,
    pub line: Option<u32>,
    pub operation: Option<String>,
    pub repository: Option<String>,
    pub additional: Option<String>,
}

impl ErrorContext {
    pub fn new() -> Self {
        ErrorContext {
            file: None,
            line: None,
            operation: None,
            repository: None,
            additional: None,
        }
    }

    pub fn with_operation(mut self, op: impl Into<String>) -> Self {
        self.operation = Some(op.into());
        self
    }

    pub fn with_repository(mut self, repo: impl Into<String>) -> Self {
        self.repository = Some(repo.into());
        self
    }

    pub fn with_additional(mut self, info: impl Into<String>) -> Self {
        self.additional = Some(info.into());
        self
    }
}

impl Default for ErrorContext {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Display for ErrorContext {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut parts = Vec::new();

        if let Some(ref op) = self.operation {
            parts.push(format!("操作: {}", op));
        }
        if let Some(ref repo) = self.repository {
            parts.push(format!("仓库: {}", repo));
        }
        if let Some(ref add) = self.additional {
            parts.push(format!("详情: {}", add));
        }

        if !parts.is_empty() {
            write!(f, "{}", parts.join(", "))?;
        }

        Ok(())
    }
}

#[derive(Debug)]
pub struct AppError {
    kind: ErrorKind,
    message: String,
    context: ErrorContext,
    source: Option<Box<dyn Error + Send + Sync + 'static>>,
}

impl AppError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        AppError {
            kind,
            message: message.into(),
            context: ErrorContext::new(),
            source: None,
        }
    }

    pub fn with_context(mut self, context: ErrorContext) -> Self {
        self.context = context;
        self
    }

    pub fn with_source<E: Error + Send + Sync + 'static>(mut self, source: E) -> Self {
        self.source = Some(Box::new(source));
        self
    }

    pub fn kind(&self) -> ErrorKind {
        self.kind
    }

    pub fn message(&self) -> &str {
        &self.message
    }

    pub fn context(&self) -> &ErrorContext {
        &self.context
    }

    pub fn config(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Config, message)
    }

    pub fn git(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Git, message)
    }

    pub fn scheduler(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Scheduler, message)
    }

    pub fn io(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Io, message)
    }

    pub fn parse(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Parse, message)
    }

    pub fn network(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Network, message)
    }

    pub fn timeout(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Timeout, message)
    }

    pub fn conflict(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Conflict, message)
    }

    pub fn validation(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Validation, message)
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::NotFound, message)
    }

    pub fn permission(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Permission, message)
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(ErrorKind::Internal, message)
    }

    pub fn chain(&self) -> Vec<&(dyn Error + 'static)> {
        let mut chain = Vec::new();
        let mut current: Option<&dyn Error> = Some(self);

        while let Some(err) = current {
            chain.push(err);
            current = err.source();
        }

        chain
    }
}

impl fmt::Display for AppError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "[{}] {}", self.kind, self.message)?;

        let context_str = self.context.to_string();
        if !context_str.is_empty() {
            write!(f, " ({})", context_str)?;
        }

        if let Some(ref source) = self.source {
            write!(f, "\n  原因: {}", source)?;
        }

        Ok(())
    }
}

impl Error for AppError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        self.source.as_deref()
    }
}

pub type AppResult<T> = Result<T, AppError>;

impl From<std::io::Error> for AppError {
    fn from(err: std::io::Error) -> Self {
        let kind = match err.kind() {
            std::io::ErrorKind::NotFound => ErrorKind::NotFound,
            std::io::ErrorKind::PermissionDenied => ErrorKind::Permission,
            std::io::ErrorKind::TimedOut => ErrorKind::Timeout,
            _ => ErrorKind::Io,
        };

        AppError::new(kind, err.to_string()).with_source(err)
    }
}

impl From<std::string::FromUtf8Error> for AppError {
    fn from(err: std::string::FromUtf8Error) -> Self {
        AppError::parse("UTF-8解码失败").with_source(err)
    }
}

impl From<serde_yaml::Error> for AppError {
    fn from(err: serde_yaml::Error) -> Self {
        AppError::parse("YAML解析失败").with_source(err)
    }
}

impl From<std::num::ParseIntError> for AppError {
    fn from(err: std::num::ParseIntError) -> Self {
        AppError::parse("整数解析失败").with_source(err)
    }
}

impl From<std::num::ParseFloatError> for AppError {
    fn from(err: std::num::ParseFloatError) -> Self {
        AppError::parse("浮点数解析失败").with_source(err)
    }
}

impl From<regex::Error> for AppError {
    fn from(err: regex::Error) -> Self {
        AppError::parse("正则表达式解析失败").with_source(err)
    }
}

impl From<SchedulerError> for AppError {
    fn from(err: SchedulerError) -> Self {
        let message = err.to_string();
        AppError::scheduler(message).with_source(err)
    }
}

impl From<tokio::task::JoinError> for AppError {
    fn from(err: tokio::task::JoinError) -> Self {
        if err.is_cancelled() {
            AppError::internal("任务被取消").with_source(err)
        } else if err.is_panic() {
            AppError::internal("任务发生panic").with_source(err)
        } else {
            AppError::internal("任务执行失败").with_source(err)
        }
    }
}

#[derive(Error, Debug, Clone)]
pub enum ConfigErrorKind {
    #[error("配置文件不存在: {0}")]
    FileNotFound(PathBuf),

    #[error("YAML解析错误")]
    YamlParse,

    #[error("仓库组不存在: {0}")]
    GroupNotFound(String),

    #[error("仓库配置无效")]
    InvalidRepository,

    #[error("必填字段缺失: {0}")]
    MissingField(String),

    #[error("字段值无效: {0}")]
    InvalidFieldValue(String),

    #[error("路径不存在: {0}")]
    PathNotFound(String),

    #[error("Git URL格式无效: {0}")]
    InvalidGitUrl(String),

    #[error("多个验证错误")]
    MultipleErrors,
}

#[derive(Error, Debug, Clone)]
pub enum GitErrorKind {
    #[error("本地路径不存在: {0}")]
    PathNotFound(String),

    #[error("Git命令执行失败")]
    CommandFailed,

    #[error("Git命令输出解析失败")]
    ParseError,

    #[error("网络超时")]
    NetworkTimeout,

    #[error("存在冲突需要手动解决")]
    ConflictDetected,

    #[error("Git未安装或不在PATH中")]
    GitNotInstalled,

    #[error("不是一个Git仓库: {0}")]
    NotARepository(String),
}

#[macro_export]
macro_rules! context {
    ($op:expr) => {
        $crate::errors::ErrorContext::new().with_operation($op)
    };
    ($op:expr, $repo:expr) => {
        $crate::errors::ErrorContext::new()
            .with_operation($op)
            .with_repository($repo)
    };
}

#[macro_export]
macro_rules! bail {
    ($kind:ident, $msg:expr) => {
        return Err($crate::errors::AppError::$kind($msg))
    };
    ($kind:ident, $msg:expr, $ctx:expr) => {
        return Err($crate::errors::AppError::$kind($msg).with_context($ctx))
    };
}

#[macro_export]
macro_rules! ensure {
    ($cond:expr, $kind:ident, $msg:expr) => {
        if !$cond {
            $crate::bail!($kind, $msg);
        }
    };
    ($cond:expr, $kind:ident, $msg:expr, $ctx:expr) => {
        if !$cond {
            $crate::bail!($kind, $msg, $ctx);
        }
    };
}

pub trait ResultExt<T> {
    fn context(self, kind: ErrorKind, message: impl Into<String>) -> AppResult<T>;
    fn with_operation(self, op: impl Into<String>) -> AppResult<T>;
    fn with_repository(self, repo: impl Into<String>) -> AppResult<T>;
}

impl<T, E: Error + Send + Sync + 'static> ResultExt<T> for Result<T, E> {
    fn context(self, kind: ErrorKind, message: impl Into<String>) -> AppResult<T> {
        self.map_err(|e| AppError::new(kind, message).with_source(e))
    }

    fn with_operation(self, op: impl Into<String>) -> AppResult<T> {
        self.map_err(|e| {
            AppError::new(ErrorKind::Internal, e.to_string())
                .with_context(ErrorContext::new().with_operation(op))
                .with_source(e)
        })
    }

    fn with_repository(self, repo: impl Into<String>) -> AppResult<T> {
        self.map_err(|e| {
            AppError::new(ErrorKind::Internal, e.to_string())
                .with_context(ErrorContext::new().with_repository(repo))
                .with_source(e)
        })
    }
}
