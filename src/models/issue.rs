use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct Issue {
    pub id: Uuid,
    pub merge_request_id: Option<Uuid>,
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub title: String,
    pub description: String,
    pub severity: String,
    pub status: String,
    pub reporter_id: Uuid,
    pub assignee_id: Option<Uuid>,
    pub code_snippet: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct IssueWithDetails {
    pub id: Uuid,
    pub merge_request_id: Option<Uuid>,
    pub merge_request_title: Option<String>,
    pub repo_name: Option<String>,
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub title: String,
    pub description: String,
    pub severity: String,
    pub status: String,
    pub reporter_id: Uuid,
    pub reporter_name: String,
    pub reporter_avatar: Option<String>,
    pub assignee_id: Option<Uuid>,
    pub assignee_name: Option<String>,
    pub assignee_avatar: Option<String>,
    pub code_snippet: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateIssueRequest {
    pub merge_request_id: Option<Uuid>,
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub title: String,
    pub description: String,
    pub severity: String,
    pub assignee_id: Option<Uuid>,
    pub code_snippet: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateIssueRequest {
    pub title: Option<String>,
    pub description: Option<String>,
    pub severity: Option<String>,
    pub assignee_id: Option<Uuid>,
    pub code_snippet: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateIssueStatusRequest {
    pub status: String,
}

#[derive(Debug, Deserialize)]
pub struct AssignIssueRequest {
    pub assignee_id: Uuid,
}

#[derive(Debug, Deserialize)]
pub struct IssueQuery {
    pub merge_request_id: Option<Uuid>,
    pub repo_id: Option<Uuid>,
    pub severity: Option<String>,
    pub status: Option<String>,
    pub reporter_id: Option<Uuid>,
    pub assignee_id: Option<Uuid>,
    pub page: Option<i32>,
    pub per_page: Option<i32>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum IssueSeverity {
    Critical,
    Major,
    Minor,
    Info,
}

impl IssueSeverity {
    pub fn as_str(&self) -> &str {
        match self {
            IssueSeverity::Critical => "critical",
            IssueSeverity::Major => "major",
            IssueSeverity::Minor => "minor",
            IssueSeverity::Info => "info",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "critical" => Some(IssueSeverity::Critical),
            "major" => Some(IssueSeverity::Major),
            "minor" => Some(IssueSeverity::Minor),
            "info" => Some(IssueSeverity::Info),
            _ => None,
        }
    }

    pub fn color(&self) -> &str {
        match self {
            IssueSeverity::Critical => "#EF4444",
            IssueSeverity::Major => "#F59E0B",
            IssueSeverity::Minor => "#3B82F6",
            IssueSeverity::Info => "#8B5CF6",
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone, Copy, PartialEq, Eq)]
pub enum IssueStatus {
    Open,
    InProgress,
    PendingReview,
    Resolved,
    Closed,
}

impl IssueStatus {
    pub fn as_str(&self) -> &str {
        match self {
            IssueStatus::Open => "open",
            IssueStatus::InProgress => "in_progress",
            IssueStatus::PendingReview => "pending_review",
            IssueStatus::Resolved => "resolved",
            IssueStatus::Closed => "closed",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "open" => Some(IssueStatus::Open),
            "in_progress" => Some(IssueStatus::InProgress),
            "pending_review" => Some(IssueStatus::PendingReview),
            "resolved" => Some(IssueStatus::Resolved),
            "closed" => Some(IssueStatus::Closed),
            _ => None,
        }
    }

    pub fn allowed_transitions(&self) -> &[IssueStatus] {
        static TRANSITIONS: &[&[IssueStatus]] = &[
            &[IssueStatus::InProgress, IssueStatus::Closed],
            &[IssueStatus::PendingReview, IssueStatus::Closed],
            &[IssueStatus::Resolved, IssueStatus::InProgress],
            &[IssueStatus::Closed, IssueStatus::Open],
            &[],
        ];

        const STATUS_ORDER: &[IssueStatus] = &[
            IssueStatus::Open,
            IssueStatus::InProgress,
            IssueStatus::PendingReview,
            IssueStatus::Resolved,
            IssueStatus::Closed,
        ];

        let idx = STATUS_ORDER.iter().position(|s| s == self).unwrap();
        TRANSITIONS[idx]
    }

    pub fn can_transition_to(&self, next: &IssueStatus) -> bool {
        self.allowed_transitions().contains(next)
    }

    pub fn transition_to(&self, next: IssueStatus) -> Result<IssueStatus, StatusTransitionError> {
        if self.can_transition_to(&next) {
            Ok(next)
        } else {
            Err(StatusTransitionError {
                from: *self,
                to: next,
            })
        }
    }

    pub fn all_statuses() -> &'static [IssueStatus] {
        &[
            IssueStatus::Open,
            IssueStatus::InProgress,
            IssueStatus::PendingReview,
            IssueStatus::Resolved,
            IssueStatus::Closed,
        ]
    }

    pub fn is_terminal(&self) -> bool {
        matches!(self, IssueStatus::Closed)
    }
}

#[derive(Debug, thiserror::Error)]
#[error("Invalid status transition from {from:?} to {to:?}")]
pub struct StatusTransitionError {
    pub from: IssueStatus,
    pub to: IssueStatus,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_valid_transitions() {
        assert!(IssueStatus::Open.can_transition_to(&IssueStatus::InProgress));
        assert!(IssueStatus::Open.can_transition_to(&IssueStatus::Closed));
        assert!(IssueStatus::InProgress.can_transition_to(&IssueStatus::PendingReview));
        assert!(IssueStatus::InProgress.can_transition_to(&IssueStatus::Closed));
        assert!(IssueStatus::PendingReview.can_transition_to(&IssueStatus::Resolved));
        assert!(IssueStatus::PendingReview.can_transition_to(&IssueStatus::InProgress));
        assert!(IssueStatus::Resolved.can_transition_to(&IssueStatus::Closed));
        assert!(IssueStatus::Resolved.can_transition_to(&IssueStatus::Open));
    }

    #[test]
    fn test_invalid_transitions() {
        assert!(!IssueStatus::Open.can_transition_to(&IssueStatus::Open));
        assert!(!IssueStatus::Open.can_transition_to(&IssueStatus::Resolved));
        assert!(!IssueStatus::Closed.can_transition_to(&IssueStatus::Open));
        assert!(!IssueStatus::Closed.can_transition_to(&IssueStatus::InProgress));
        assert!(!IssueStatus::InProgress.can_transition_to(&IssueStatus::Open));
        assert!(!IssueStatus::PendingReview.can_transition_to(&IssueStatus::Open));
    }

    #[test]
    fn test_transition_to_ok() {
        let result = IssueStatus::Open.transition_to(IssueStatus::InProgress);
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), IssueStatus::InProgress);
    }

    #[test]
    fn test_transition_to_err() {
        let result = IssueStatus::Closed.transition_to(IssueStatus::InProgress);
        assert!(result.is_err());
        let err = result.unwrap_err();
        assert_eq!(err.from, IssueStatus::Closed);
        assert_eq!(err.to, IssueStatus::InProgress);
    }

    #[test]
    fn test_is_terminal() {
        assert!(!IssueStatus::Open.is_terminal());
        assert!(!IssueStatus::InProgress.is_terminal());
        assert!(IssueStatus::Closed.is_terminal());
    }

    #[test]
    fn test_full_lifecycle() {
        let mut status = IssueStatus::Open;
        status = status.transition_to(IssueStatus::InProgress).unwrap();
        status = status.transition_to(IssueStatus::PendingReview).unwrap();
        status = status.transition_to(IssueStatus::Resolved).unwrap();
        status = status.transition_to(IssueStatus::Closed).unwrap();
        assert!(status.is_terminal());
    }

    #[test]
    fn test_reopen_lifecycle() {
        let mut status = IssueStatus::Open;
        status = status.transition_to(IssueStatus::InProgress).unwrap();
        status = status.transition_to(IssueStatus::PendingReview).unwrap();
        status = status.transition_to(IssueStatus::Resolved).unwrap();
        status = status.transition_to(IssueStatus::Open).unwrap();
        assert_eq!(status, IssueStatus::Open);
    }

    #[test]
    fn test_from_str_roundtrip() {
        for s in IssueStatus::all_statuses() {
            assert_eq!(IssueStatus::from_str(s.as_str()), Some(*s));
        }
    }

    #[test]
    fn test_allowed_transitions_returns_slice() {
        let transitions = IssueStatus::Open.allowed_transitions();
        assert_eq!(transitions.len(), 2);
        assert!(transitions.contains(&IssueStatus::InProgress));
        assert!(transitions.contains(&IssueStatus::Closed));
    }
}
