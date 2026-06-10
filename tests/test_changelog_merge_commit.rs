use tempfile::TempDir;
use std::sync::Arc;
use std::process::Command;
use gitflow_cli::git::{GitContext, CommitInfo};
use chrono::Utc;

fn create_test_repo_with_merge() -> (TempDir, Arc<GitContext>) {
    let tmp_dir = tempfile::tempdir().unwrap();
    let repo_path = tmp_dir.path();

    Command::new("git")
        .args(["init"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    Command::new("git")
        .args(["config", "user.name", "Test User"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    Command::new("git")
        .args(["config", "user.email", "test@example.com"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    std::fs::write(repo_path.join("README.md"), "# Test Repo\n").unwrap();
    Command::new("git")
        .args(["add", "."])
        .current_dir(repo_path)
        .output()
        .unwrap();
    Command::new("git")
        .args(["commit", "-m", "feat: initial commit"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    Command::new("git")
        .args(["checkout", "-b", "feature/new-feature"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    std::fs::write(repo_path.join("feature.rs"), "fn new_feature() {}\n").unwrap();
    Command::new("git")
        .args(["add", "."])
        .current_dir(repo_path)
        .output()
        .unwrap();
    Command::new("git")
        .args(["commit", "-m", "feat: add feature function"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    std::fs::write(repo_path.join("fix.rs"), "fn bugfix() {}\n").unwrap();
    Command::new("git")
        .args(["add", "."])
        .current_dir(repo_path)
        .output()
        .unwrap();
    Command::new("git")
        .args(["commit", "-m", "fix: fix bug in feature\n\nFixes #456"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    let checkout_result = Command::new("git")
        .args(["checkout", "main"])
        .current_dir(repo_path)
        .output();
    if !checkout_result.unwrap().status.success() {
        Command::new("git")
            .args(["checkout", "master"])
            .current_dir(repo_path)
            .output()
            .unwrap();
    }

    Command::new("git")
        .args(["merge", "--no-ff", "feature/new-feature", "-m", "Merge pull request #123 from feature/new-feature\n\nThis PR adds a new feature with bug fix."])
        .current_dir(repo_path)
        .output()
        .unwrap();

    let git = Arc::new(GitContext::open(Some(repo_path)).unwrap());
    (tmp_dir, git)
}

#[test]
fn test_merge_commit_detection() {
    let (_tmp, git) = create_test_repo_with_merge();

    let commits = git.get_commit_range(None, Some("HEAD")).unwrap();

    let merge_commit = commits.first().unwrap();
    assert!(merge_commit.is_merge(), "First commit should be a merge commit");
    assert!(merge_commit.parents.len() >= 2, "Merge commit should have at least 2 parents");
    assert_eq!(merge_commit.pr_number, Some(123), "Should extract PR #123");
    assert!(merge_commit.message.contains("Merge pull request #123"));
}

#[test]
fn test_get_merge_feature_commits() {
    let (_tmp, git) = create_test_repo_with_merge();

    let commits = git.get_commit_range(None, Some("HEAD")).unwrap();
    let merge_commit = commits.first().unwrap();

    let feature_commits = git.get_merge_feature_commits(merge_commit).unwrap();

    assert!(!feature_commits.is_empty(), "Should have feature commits");
    assert!(feature_commits.len() >= 2, "Should have at least 2 feature commits");

    let messages: Vec<String> = feature_commits.iter().map(|c| c.summary.clone()).collect();
    assert!(messages.iter().any(|m| m.contains("feat: add feature function")));
    assert!(messages.iter().any(|m| m.contains("fix: fix bug in feature")));
}

#[test]
fn test_non_merge_commit_returns_empty() {
    let (_tmp, git) = create_test_repo_with_merge();

    let commits = git.get_commit_range(None, Some("HEAD")).unwrap();
    let merge_commit = commits.first().unwrap();
    let feature_commits = git.get_merge_feature_commits(merge_commit).unwrap();
    let regular_commit = feature_commits.first().unwrap();

    assert!(!regular_commit.is_merge());
    let result = git.get_merge_feature_commits(regular_commit).unwrap();
    assert!(result.is_empty());
}

#[test]
fn test_commit_info_is_merge_method() {
    let merge_commit = CommitInfo {
        sha: "abc123".to_string(),
        short_sha: "abc123".to_string(),
        message: "Merge PR #1".to_string(),
        summary: "Merge PR #1".to_string(),
        body: None,
        author: "Test".to_string(),
        email: "test@test.com".to_string(),
        time: Utc::now(),
        parents: vec!["parent1".to_string(), "parent2".to_string()],
        is_merge_commit: true,
        pr_number: Some(1),
    };

    let regular_commit = CommitInfo {
        sha: "def456".to_string(),
        short_sha: "def456".to_string(),
        message: "feat: add feature".to_string(),
        summary: "feat: add feature".to_string(),
        body: None,
        author: "Test".to_string(),
        email: "test@test.com".to_string(),
        time: Utc::now(),
        parents: vec!["parent1".to_string()],
        is_merge_commit: false,
        pr_number: None,
    };

    assert!(merge_commit.is_merge());
    assert_eq!(merge_commit.parent_count(), 2);
    assert!(!regular_commit.is_merge());
    assert_eq!(regular_commit.parent_count(), 1);
}
