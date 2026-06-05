use thiserror::Error;

#[derive(Error, Debug)]
pub enum GitFlowError {
    #[error("Git操作错误: {0}")]
    GitError(#[from] git2::Error),

    #[error("配置错误: {0}")]
    ConfigError(String),

    #[error("IO错误: {0}")]
    IoError(#[from] std::io::Error),

    #[error("序列化错误: {0}")]
    SerializeError(#[from] toml::ser::Error),

    #[error("反序列化错误: {0}")]
    DeserializeError(#[from] toml::de::Error),

    #[error("JSON错误: {0}")]
    JsonError(#[from] serde_json::Error),

    #[error("HTTP请求错误: {0}")]
    RequestError(#[from] reqwest::Error),

    #[error("正则表达式错误: {0}")]
    RegexError(#[from] regex::Error),

    #[error("分支名无效: {0}")]
    InvalidBranchName(String),

    #[error("提交消息格式无效: {0}")]
    InvalidCommitMessage(String),

    #[error("未找到Git仓库")]
    RepositoryNotFound,

    #[error("JIRA API错误: {0}")]
    JiraError(String),

    #[error("GitHub/GitLab API错误: {0}")]
    GitPlatformError(String),

    #[error("配置文件未找到")]
    ConfigNotFound,

    #[error("验证失败: {0}")]
    ValidationError(String),

    #[error("用户取消操作")]
    UserCancelled,

    #[error("{0}")]
    Other(String),
}

pub type Result<T> = std::result::Result<T, GitFlowError>;

impl From<dialoguer::Error> for GitFlowError {
    fn from(e: dialoguer::Error) -> Self {
        GitFlowError::Other(e.to_string())
    }
}

impl From<anyhow::Error> for GitFlowError {
    fn from(e: anyhow::Error) -> Self {
        GitFlowError::Other(e.to_string())
    }
}
