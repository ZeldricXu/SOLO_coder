use tempfile::TempDir;
use std::sync::Arc;
use std::process::Command;
use gitflow_cli::git::{GitContext, CommitInfo};
use gitflow_cli::config::Config;
use gitflow_cli::commit::CommitManager;
use chrono::Utc;

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

    let git = Arc::new(GitContext::open(Some(repo_path)).unwrap());
    (tmp_dir, git)
}

fn create_test_repo_with_multiple_commits() -> (TempDir, Arc<GitContext>) {
    let (tmp, git) = create_test_repo();
    let repo_path = tmp.path();

    std::fs::write(repo_path.join("file1.rs"), "fn test1() {}\n").unwrap();
    Command::new("git")
        .args(["add", "."])
        .current_dir(repo_path)
        .output()
        .unwrap();
    Command::new("git")
        .args(["commit", "-m", "feat: add test1 function"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    std::fs::write(repo_path.join("file2.rs"), "fn test2() {}\n").unwrap();
    Command::new("git")
        .args(["add", "."])
        .current_dir(repo_path)
        .output()
        .unwrap();
    Command::new("git")
        .args(["commit", "-m", "fix: fix bug in test2\n\nSigned-off-by: Test User <test@example.com>"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    std::fs::write(repo_path.join("file3.rs"), "fn test3() {}\n").unwrap();
    Command::new("git")
        .args(["add", "."])
        .current_dir(repo_path)
        .output()
        .unwrap();
    Command::new("git")
        .args(["commit", "-m", "bad commit message without conventional format"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    (tmp, git)
}

#[test]
fn test_validate_message_conventional() {
    let (_tmp, git) = create_test_repo();
    let config = Config::default();
    let commit_manager = CommitManager::new(git, config);

    let result = commit_manager.validate_message("feat: add new feature", false).unwrap();
    assert!(result.passed);
    assert!(result.errors.is_empty());

    let result = commit_manager.validate_message("bad message", false).unwrap();
    assert!(!result.passed);
    assert!(!result.errors.is_empty());
    assert!(result.errors.iter().any(|e| e.contains("Conventional Commits")));
}

#[test]
fn test_validate_message_strict_mode() {
    let (_tmp, git) = create_test_repo();
    let mut config = Config::default();
    config.commit.require_body = true;
    let commit_manager = CommitManager::new(git, config);

    let result = commit_manager.validate_message("feat: add feature\n\nDetailed body", true).unwrap();
    assert!(result.passed);

    let result = commit_manager.validate_message("feat: add feature without body", true).unwrap();
    assert!(!result.passed);
    assert!(result.errors.iter().any(|e| e.contains("需要包含详细描述")));
}

#[test]
fn test_validate_commit_author() {
    let (_tmp, git) = create_test_repo();
    let mut config = Config::default();
    config.commit.require_author_name = true;
    config.commit.require_author_email = true;
    let commit_manager = CommitManager::new(git.clone(), config);

    let valid_commit = CommitInfo {
        sha: "abc123".to_string(),
        short_sha: "abc123".to_string(),
        message: "feat: test".to_string(),
        summary: "feat: test".to_string(),
        body: None,
        author: "Test User".to_string(),
        email: "test@example.com".to_string(),
        time: Utc::now(),
        parents: vec![],
        is_merge_commit: false,
        pr_number: None,
    };

    let result = commit_manager.validate_commit_author(&valid_commit, false).unwrap();
    assert!(result.passed);

    let mut config2 = Config::default();
    config2.commit.require_author_name = true;
    config2.commit.require_author_email = true;
    let commit_manager2 = CommitManager::new(git, config2);

    let invalid_commit = CommitInfo {
        sha: "def456".to_string(),
        short_sha: "def456".to_string(),
        message: "feat: test".to_string(),
        summary: "feat: test".to_string(),
        body: None,
        author: "".to_string(),
        email: "".to_string(),
        time: Utc::now(),
        parents: vec![],
        is_merge_commit: false,
        pr_number: None,
    };

    let result = commit_manager2.validate_commit_author(&invalid_commit, false).unwrap();
    assert!(!result.passed);
    assert!(result.errors.iter().any(|e| e.contains("作者名称不能为空")));
    assert!(result.errors.iter().any(|e| e.contains("作者邮箱不能为空")));
}

#[test]
fn test_validate_email_domain() {
    let (_tmp, git) = create_test_repo();
    let mut config = Config::default();
    config.commit.valid_email_domains = Some(vec!["company.com".to_string()]);
    let commit_manager = CommitManager::new(git, config);

    let valid_commit = CommitInfo {
        sha: "abc123".to_string(),
        short_sha: "abc123".to_string(),
        message: "feat: test".to_string(),
        summary: "feat: test".to_string(),
        body: None,
        author: "Test User".to_string(),
        email: "dev@company.com".to_string(),
        time: Utc::now(),
        parents: vec![],
        is_merge_commit: false,
        pr_number: None,
    };

    let result = commit_manager.validate_commit_author(&valid_commit, true).unwrap();
    assert!(result.passed);

    let invalid_commit = CommitInfo {
        sha: "def456".to_string(),
        short_sha: "def456".to_string(),
        message: "feat: test".to_string(),
        summary: "feat: test".to_string(),
        body: None,
        author: "Test User".to_string(),
        email: "dev@gmail.com".to_string(),
        time: Utc::now(),
        parents: vec![],
        is_merge_commit: false,
        pr_number: None,
    };

    let result = commit_manager.validate_commit_author(&invalid_commit, true).unwrap();
    assert!(!result.passed);
    assert!(result.warnings.iter().any(|w| w.contains("不在允许的域名列表中")));
}

#[test]
fn test_validate_signoff() {
    let (_tmp, git) = create_test_repo();
    let mut config = Config::default();
    config.commit.require_signoff = true;
    let commit_manager = CommitManager::new(git, config);

    let signed_commit = CommitInfo {
        sha: "abc123".to_string(),
        short_sha: "abc123".to_string(),
        message: "feat: test\n\nSigned-off-by: Test User <test@example.com>".to_string(),
        summary: "feat: test".to_string(),
        body: Some("Signed-off-by: Test User <test@example.com>".to_string()),
        author: "Test User".to_string(),
        email: "test@example.com".to_string(),
        time: Utc::now(),
        parents: vec![],
        is_merge_commit: false,
        pr_number: None,
    };

    let result = commit_manager.validate_commit_author(&signed_commit, false).unwrap();
    assert!(result.passed);

    let unsigned_commit = CommitInfo {
        sha: "def456".to_string(),
        short_sha: "def456".to_string(),
        message: "feat: test".to_string(),
        summary: "feat: test".to_string(),
        body: None,
        author: "Test User".to_string(),
        email: "test@example.com".to_string(),
        time: Utc::now(),
        parents: vec![],
        is_merge_commit: false,
        pr_number: None,
    };

    let result = commit_manager.validate_commit_author(&unsigned_commit, false).unwrap();
    assert!(!result.passed);
    assert!(result.errors.iter().any(|e| e.contains("缺少 Signed-off-by 签名")));
}

#[test]
fn test_check_range_parsing() {
    let (_tmp, git) = create_test_repo_with_multiple_commits();
    let config = Config::default();
    let commit_manager = CommitManager::new(git.clone(), config);

    let commits = commit_manager.git().get_commit_range(None, Some("HEAD")).unwrap();
    assert!(commits.len() >= 3);

    let head_commit = commits.first().unwrap();
    assert!(head_commit.message.contains("bad commit message"));
}

#[test]
fn test_pre_push_hook_content() {
    let (_tmp, git) = create_test_repo();
    let config = Config::default();
    let commit_manager = CommitManager::new(git.clone(), config.clone());

    let hook_types = ["pre-commit", "commit-msg", "pre-push"];
    for hook_type in hook_types.iter() {
        let result = commit_manager.install_hook(false, hook_type);
        assert!(result.is_ok(), "Should install {} hook", hook_type);

        let hook_path = git.git_dir().join("hooks").join(hook_type);
        assert!(hook_path.exists(), "Hook file should exist for {}", hook_type);

        let content = std::fs::read_to_string(&hook_path).unwrap();
        assert!(content.starts_with("#!/bin/sh"), "Should have shebang");
        assert!(content.contains("GitFlow") || content.contains("gitflow") || content.contains("commit check"), 
                "Should contain gitflow-related command");

        if *hook_type == "pre-push" {
            assert!(content.contains("pre-push hook"), "Should mention pre-push hook");
            assert!(content.contains("commit check --range"), "Should use range check");
        }
    }
}

#[test]
fn test_amend_bypass_scenario() {
    let (tmp, git) = create_test_repo();
    let repo_path = tmp.path();
    let config = Config::default();
    let commit_manager = CommitManager::new(git.clone(), config.clone());

    std::fs::write(repo_path.join("test.rs"), "fn test() {}\n").unwrap();
    Command::new("git")
        .args(["add", "."])
        .current_dir(repo_path)
        .output()
        .unwrap();
    Command::new("git")
        .args(["commit", "-m", "feat: good message that passes"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    let commits = git.get_commit_range(None, Some("HEAD")).unwrap();
    let last_commit = commits.first().unwrap();
    assert!(last_commit.message.contains("feat: good message"));

    Command::new("git")
        .args(["commit", "--amend", "-m", "bad message that should not pass"])
        .current_dir(repo_path)
        .output()
        .unwrap();

    let commits_after = git.get_commit_range(None, Some("HEAD")).unwrap();
    let amended_commit = commits_after.first().unwrap();
    assert!(amended_commit.message.contains("bad message"));

    let result = commit_manager.validate_message(&amended_commit.message, false).unwrap();
    assert!(!result.passed, "Amended commit message should fail validation");
    assert!(result.errors.iter().any(|e| e.contains("Conventional Commits")));
}
