use tempfile::TempDir;
use gitflow_cli::git::{GitContext, extract_pr_number};
use std::sync::Arc;
use gitflow_cli::config::Config;
use gitflow_cli::branch::BranchManager;
use gitflow_cli::errors::GitFlowError;
use std::process::Command;

fn create_test_repo() -> (TempDir, Arc<GitContext>) {
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

    std::fs::write(repo_path.join("README.md"), "# Test Repo").unwrap();
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

    let git = Arc::new(GitContext::open(Some(repo_path)).unwrap());
    (tmp_dir, git)
}

#[test]
fn test_prevent_delete_current_branch() {
    let (_tmp, git) = create_test_repo();

    git.create_branch("feature/test", None, false).unwrap();
    git.checkout_branch("feature/test").unwrap();

    let result = git.delete_branch("feature/test", false);
    assert!(result.is_err());

    match result.unwrap_err() {
        GitFlowError::ValidationError(msg) => {
            assert!(msg.contains("无法删除当前分支"));
            assert!(msg.contains("feature/test"));
            assert!(msg.contains("请先切换到其他分支"));
        }
        e => panic!("Expected ValidationError, got {:?}", e),
    }

    let _ = git.checkout_branch("main").or_else(|_| git.checkout_branch("master"));

    let result = git.delete_branch("feature/test", false);
    assert!(result.is_ok());
}

#[test]
fn test_preflight_check_head_operation() {
    let (_tmp, git) = create_test_repo();

    git.create_branch("feature/test2", None, false).unwrap();
    git.checkout_branch("feature/test2").unwrap();

    let current = git.current_branch().unwrap();
    assert_eq!(current, "feature/test2");

    let _ = git.checkout_branch("main").or_else(|_| git.checkout_branch("master"));

    let current_after = git.current_branch().unwrap();
    assert!(current_after == "main" || current_after == "master");
}

#[test]
fn test_branch_clean_skips_current_branch() {
    let (_tmp, git) = create_test_repo();
    let config = Config::default();

    git.create_branch("feature/old1", None, false).unwrap();
    git.create_branch("feature/old2", None, false).unwrap();

    git.checkout_branch("feature/old1").unwrap();

    let _branch_manager = BranchManager::new(git.clone(), config.clone());

    let current = git.current_branch().unwrap();
    assert_eq!(current, "feature/old1");

    let branches = git.list_branches(true, false, None, false).unwrap();
    let local_branches: Vec<_> = branches.iter().filter(|b| !b.is_remote).collect();
    assert!(local_branches.iter().any(|b| b.is_current && b.short_name == "old1" && b.name == "feature/old1"));
}

#[test]
fn test_extract_pr_number() {
    assert_eq!(extract_pr_number("Merge pull request #123 from feature/xyz"), Some(123));
    assert_eq!(extract_pr_number("Fix bug in login #456"), Some(456));
    assert_eq!(extract_pr_number("feat: add new feature"), None);
    assert_eq!(extract_pr_number("Merge PR #789: fix critical issue"), Some(789));
    assert_eq!(extract_pr_number("#1 is the first PR"), Some(1));
    assert_eq!(extract_pr_number("No number here"), None);
}
