use chrono::{DateTime, Utc};
use git2::{
    Branch, BranchType, Commit, Cred, FetchOptions, PushOptions, RemoteCallbacks,
    Repository, Sort,
};
use regex::Regex;
use std::path::{Path, PathBuf};
use tracing::{debug, info};

use crate::errors::{GitFlowError, Result};

#[derive(Debug, Clone)]
pub struct BranchInfo {
    pub name: String,
    pub short_name: String,
    pub is_remote: bool,
    pub is_current: bool,
    pub last_commit_sha: String,
    pub last_commit_message: String,
    pub last_commit_author: String,
    pub last_commit_time: DateTime<Utc>,
    pub is_merged: bool,
    pub upstream: Option<String>,
}

#[derive(Debug, Clone)]
pub struct CommitInfo {
    pub sha: String,
    pub short_sha: String,
    pub message: String,
    pub summary: String,
    pub body: Option<String>,
    pub author: String,
    pub email: String,
    pub time: DateTime<Utc>,
    pub parents: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct TagInfo {
    pub name: String,
    pub sha: String,
    pub message: Option<String>,
    pub time: Option<DateTime<Utc>>,
}

pub struct GitRepository {
    repo: Repository,
    path: PathBuf,
}

impl GitRepository {
    pub fn open(path: Option<&Path>) -> Result<Self> {
        let repo_path = match path {
            Some(p) => p.to_path_buf(),
            None => std::env::current_dir()?,
        };

        let repo = Repository::discover(&repo_path).map_err(|_| GitFlowError::RepositoryNotFound)?;
        let workdir = repo
            .workdir()
            .ok_or_else(|| GitFlowError::RepositoryNotFound)?
            .to_path_buf();

        Ok(Self {
            repo,
            path: workdir,
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn repo(&self) -> &Repository {
        &self.repo
    }

    pub fn current_branch(&self) -> Result<String> {
        let head = self.repo.head()?;
        if head.is_branch() {
            let name = head
                .shorthand()
                .ok_or_else(|| GitFlowError::GitError(git2::Error::from_str("无法获取分支名")))?;
            Ok(name.to_string())
        } else {
            Err(GitFlowError::Other("当前处于detached HEAD状态".into()))
        }
    }

    pub fn current_commit_sha(&self) -> Result<String> {
        let head = self.repo.head()?;
        let oid = head.target().ok_or_else(|| {
            GitFlowError::GitError(git2::Error::from_str("无法获取当前commit SHA"))
        })?;
        Ok(oid.to_string())
    }

    pub fn list_branches(
        &self,
        local: bool,
        remote: bool,
        pattern: Option<&str>,
        merged_only: bool,
    ) -> Result<Vec<BranchInfo>> {
        let mut branches = Vec::new();
        let regex = pattern
            .map(|p| Regex::new(p))
            .transpose()?;

        let current_branch = self.current_branch().ok();

        let mut branch_types = Vec::new();
        if local {
            branch_types.push(BranchType::Local);
        }
        if remote {
            branch_types.push(BranchType::Remote);
        }
        if branch_types.is_empty() {
            branch_types.push(BranchType::Local);
            branch_types.push(BranchType::Remote);
        }

        for branch_type in branch_types {
            let git_branches = self.repo.branches(Some(branch_type))?;

            for br in git_branches {
                let (branch, _) = br?;
                let name = branch.name()?.unwrap_or("").to_string();

                if name.is_empty() || name.starts_with("HEAD") {
                    continue;
                }

                if let Some(ref re) = regex {
                    if !re.is_match(&name) {
                        continue;
                    }
                }

                let is_merged = if merged_only {
                    self.is_branch_merged(&branch)?
                } else {
                    false
                };

                let branch_info = self.get_branch_info(&branch, &name, &current_branch, is_merged)?;
                branches.push(branch_info);
            }
        }

        Ok(branches)
    }

    fn get_branch_info(
        &self,
        branch: &Branch,
        name: &str,
        current_branch: &Option<String>,
        is_merged: bool,
    ) -> Result<BranchInfo> {
        let commit = branch.get().peel_to_commit()?;
        let sha = commit.id().to_string();
        let message = commit.summary().unwrap_or("").to_string();
        let author = commit.author().name().unwrap_or("").to_string();
        let time = DateTime::from_timestamp(commit.time().seconds(), 0).unwrap_or_else(|| Utc::now());

        let upstream = branch.upstream().ok().and_then(|b| b.name().ok().flatten().map(|s| s.to_string()));

        let short_name = if name.starts_with("origin/") {
            name.trim_start_matches("origin/").to_string()
        } else {
            name.to_string()
        };

        let is_current = current_branch
            .as_ref()
            .map(|cb| cb == &short_name)
            .unwrap_or(false);

        Ok(BranchInfo {
            name: name.to_string(),
            short_name,
            is_remote: name.starts_with("origin/"),
            is_current,
            last_commit_sha: sha,
            last_commit_message: message,
            last_commit_author: author,
            last_commit_time: time,
            is_merged,
            upstream,
        })
    }

    pub fn is_branch_merged(&self, branch: &Branch) -> Result<bool> {
        let branch_commit = branch.get().peel_to_commit()?;
        let head_commit = self.repo.head()?.peel_to_commit()?;

        Ok(self.repo.graph_descendant_of(branch_commit.id(), head_commit.id())?)
    }

    pub fn is_branch_name_merged(&self, branch_name: &str, into: &str) -> Result<bool> {
        let branch = self.repo.find_branch(branch_name, BranchType::Local)?;
        let into_branch = self.repo.find_branch(into, BranchType::Local)?;

        let branch_commit = branch.get().peel_to_commit()?;
        let into_commit = into_branch.get().peel_to_commit()?;

        Ok(self.repo.graph_descendant_of(branch_commit.id(), into_commit.id())?)
    }

    pub fn create_branch(
        &self,
        name: &str,
        base: Option<&str>,
        force: bool,
    ) -> Result<BranchInfo> {
        let base_commit = match base {
            Some(base_name) => {
                let base_branch = self.repo.find_branch(base_name, BranchType::Local)
                    .or_else(|_| self.repo.find_branch(&format!("origin/{}", base_name), BranchType::Remote))?;
                base_branch.get().peel_to_commit()?
            }
            None => self.repo.head()?.peel_to_commit()?,
        };

        let branch = if force {
            match self.repo.find_branch(name, BranchType::Local) {
                Ok(mut existing) => {
                    existing.delete()?;
                    self.repo.branch(name, &base_commit, false)?
                }
                Err(_) => self.repo.branch(name, &base_commit, false)?,
            }
        } else {
            if self.repo.find_branch(name, BranchType::Local).is_ok() {
                return Err(GitFlowError::InvalidBranchName(format!(
                    "分支 '{}' 已存在，使用 --force 强制覆盖",
                    name
                )));
            }
            self.repo.branch(name, &base_commit, false)?
        };

        info!("创建分支: {}", name);
        self.get_branch_info(&branch, name, &Some(self.current_branch()?), false)
    }

    pub fn delete_branch(&self, name: &str, force: bool) -> Result<()> {
        let mut branch = self.repo.find_branch(name, BranchType::Local)?;

        if !force {
            let is_current = self
                .current_branch()
                .map(|cb| cb == name)
                .unwrap_or(false);
            if is_current {
                return Err(GitFlowError::InvalidBranchName(format!(
                    "无法删除当前分支 '{}'，请先切换到其他分支",
                    name
                )));
            }

            if !self.is_branch_merged(&branch)? {
                return Err(GitFlowError::InvalidBranchName(format!(
                    "分支 '{}' 未合并，使用 --force 强制删除",
                    name
                )));
            }
        }

        branch.delete()?;
        info!("删除分支: {}", name);
        Ok(())
    }

    pub fn checkout_branch(&self, name: &str) -> Result<()> {
        let (object, reference) = self.repo.revparse_ext(name)?;
        self.repo.checkout_tree(&object, None)?;

        match reference {
            Some(gref) => self.repo.set_head(gref.name().unwrap())?,
            None => self.repo.set_head_detached(object.id())?,
        }

        info!("切换到分支: {}", name);
        Ok(())
    }

    pub fn sync_branch(&self, name: &str, remote_name: &str, use_rebase: bool) -> Result<()> {
        let remote_branch_name = format!("{}/{}", remote_name, name);
        let remote_branch = self
            .repo
            .find_branch(&remote_branch_name, BranchType::Remote)?;
        let remote_commit = remote_branch.get().peel_to_commit()?;

        let analysis = self.repo.merge_analysis(&[&remote_commit])?;

        if analysis.0.is_up_to_date() {
            info!("分支 {} 已是最新", name);
            return Ok(());
        }

        if use_rebase {
            let mut rebase = self.repo.rebase(None, Some(&remote_branch.get()), None, None)?;
            while let Some(op) = rebase.next() {
                op?;
            }
            rebase.commit(None, &self.repo.signature()?, None)?;
            rebase.finish(None)?;
            info!("Rebase 完成: {} -> {}", name, remote_branch_name);
        } else {
            let opts = git2::MergeOptions::new();
            self.repo.merge(&[&remote_commit], Some(&opts), None)?;

            if self.repo.index()?.has_conflicts() {
                return Err(GitFlowError::Other(
                    "合并冲突，请手动解决冲突后提交".into(),
                ));
            }

            info!("合并完成: {} -> {}", remote_branch_name, name);
        }

        Ok(())
    }

    pub fn fetch(&self, remote_name: &str) -> Result<()> {
        let mut remote = self.repo.find_remote(remote_name)?;

        let mut callbacks = RemoteCallbacks::new();
        callbacks.credentials(|_url, username_from_url, _allowed_types| {
            Cred::ssh_key_from_agent(username_from_url.unwrap_or("git"))
                .or_else(|_| Cred::default())
        });

        let mut fetch_opts = FetchOptions::new();
        fetch_opts.remote_callbacks(callbacks);

        remote.fetch::<&str>(&[], Some(&mut fetch_opts), None)?;
        info!("拉取远程更新: {}", remote_name);
        Ok(())
    }

    pub fn push(&self, remote_name: &str, branch_name: &str, set_upstream: bool) -> Result<()> {
        let mut remote = self.repo.find_remote(remote_name)?;

        let mut callbacks = RemoteCallbacks::new();
        callbacks.credentials(|_url, username_from_url, _allowed_types| {
            Cred::ssh_key_from_agent(username_from_url.unwrap_or("git"))
                .or_else(|_| Cred::default())
        });

        let mut push_opts = PushOptions::new();
        push_opts.remote_callbacks(callbacks);

        let refspec = if set_upstream {
            format!("refs/heads/{}:refs/heads/{}", branch_name, branch_name)
        } else {
            format!("refs/heads/{}", branch_name)
        };

        remote.push(&[&refspec], Some(&mut push_opts))?;
        info!("推送分支 {} 到 {}", branch_name, remote_name);
        Ok(())
    }

    pub fn get_commit(&self, sha_or_ref: &str) -> Result<CommitInfo> {
        let commit = if sha_or_ref == "HEAD" {
            self.repo.head()?.peel_to_commit()?
        } else {
            let oid = git2::Oid::from_str(sha_or_ref)
                .or_else(|_| self.repo.revparse_single(sha_or_ref)?.id())?;
            self.repo.find_commit(oid)?
        };

        let message = commit.message().unwrap_or("").to_string();
        let (summary, body) = message.split_once('\n').map(|(s, b)| (s.trim().to_string(), Some(b.trim().to_string())))
            .unwrap_or_else(|| (message.trim().to_string(), None));

        let parents = commit
            .parent_ids()
            .map(|oid| oid.to_string())
            .collect();

        let time = DateTime::from_timestamp(commit.time().seconds(), 0).unwrap_or_else(|| Utc::now());

        Ok(CommitInfo {
            sha: commit.id().to_string(),
            short_sha: commit.id().to_string()[..8].to_string(),
            message,
            summary,
            body,
            author: commit.author().name().unwrap_or("").to_string(),
            email: commit.author().email().unwrap_or("").to_string(),
            time,
            parents,
        })
    }

    pub fn get_commit_range(&self, from: Option<&str>, to: Option<&str>) -> Result<Vec<CommitInfo>> {
        let mut revwalk = self.repo.revwalk()?;
        revwalk.set_sorting(Sort::TIME | Sort::TOPOLOGICAL)?;

        match to {
            Some(to_ref) => {
                let to_oid = self.repo.revparse_single(to_ref)?.id();
                revwalk.push(to_oid)?;
            }
            None => {
                revwalk.push_head()?;
            }
        }

        if let Some(from_ref) = from {
            let from_oid = self.repo.revparse_single(from_ref)?.id();
            revwalk.hide(from_oid)?;
        }

        let mut commits = Vec::new();
        for oid in revwalk {
            let oid = oid?;
            let commit = self.repo.find_commit(oid)?;
            let message = commit.message().unwrap_or("").to_string();
            let (summary, body) = message.split_once('\n').map(|(s, b)| (s.trim().to_string(), Some(b.trim().to_string())))
                .unwrap_or_else(|| (message.trim().to_string(), None));

            let parents = commit
                .parent_ids()
                .map(|p| p.to_string())
                .collect();

            let time = DateTime::from_timestamp(commit.time().seconds(), 0).unwrap_or_else(|| Utc::now());

            commits.push(CommitInfo {
                sha: oid.to_string(),
                short_sha: oid.to_string()[..8].to_string(),
                message,
                summary,
                body,
                author: commit.author().name().unwrap_or("").to_string(),
                email: commit.author().email().unwrap_or("").to_string(),
                time,
                parents,
            });
        }

        Ok(commits)
    }

    pub fn list_tags(&self, pattern: Option<&str>) -> Result<Vec<TagInfo>> {
        let regex = pattern.map(|p| Regex::new(p)).transpose()?;
        let tags = self.repo.tag_names(None)?;

        let mut tag_infos = Vec::new();
        for name in tags.iter().flatten() {
            if let Some(ref re) = regex {
                if !re.is_match(name) {
                    continue;
                }
            }

            let obj = self.repo.revparse_single(name)?;
            let oid = obj.id();

            let (message, time) = if let Some(tag) = obj.as_tag() {
                let t = tag.tagger().map(|sig| {
                    DateTime::from_timestamp(sig.when().seconds(), 0).unwrap_or_else(|| Utc::now())
                });
                (tag.message().map(|s| s.to_string()), t)
            } else {
                (None, None)
            };

            tag_infos.push(TagInfo {
                name: name.to_string(),
                sha: oid.to_string(),
                message,
                time,
            });
        }

        tag_infos.sort_by(|a, b| b.time.cmp(&a.time));
        Ok(tag_infos)
    }

    pub fn get_config_value(&self, key: &str) -> Result<Option<String>> {
        let config = self.repo.config()?;
        Ok(config.get_string(key).ok())
    }

    pub fn set_config_value(&self, key: &str, value: &str) -> Result<()> {
        let mut config = self.repo.config()?;
        config.set_str(key, value)?;
        Ok(())
    }

    pub fn git_dir(&self) -> &Path {
        self.repo.path()
    }

    pub fn has_remote(&self, name: &str) -> bool {
        self.repo.find_remote(name).is_ok()
    }

    pub fn get_remotes(&self) -> Result<Vec<String>> {
        Ok(self
            .repo
            .remotes()?
            .iter()
            .flatten()
            .map(|s| s.to_string())
            .collect())
    }

    pub fn is_clean(&self) -> Result<bool> {
        let statuses = self.repo.statuses(None)?;
        Ok(statuses.is_empty())
    }

    pub fn stash(&self, message: &str) -> Result<()> {
        let signature = self.repo.signature()?;
        let mut opts = git2::StashSaveOptions::new();
        opts.include_untracked(true);
        self.repo
            .stash_save(&signature, message, Some(&mut opts))?;
        info!("暂存修改: {}", message);
        Ok(())
    }

    pub fn stash_pop(&self) -> Result<()> {
        let mut stash = git2::StashApplyOptions::new();
        self.repo.stash_apply(0, Some(&mut stash))?;
        self.repo.stash_drop(0)?;
        info!("恢复暂存的修改");
        Ok(())
    }

    pub fn add_all(&self) -> Result<()> {
        let mut index = self.repo.index()?;
        index.add_all(["."].iter(), git2::IndexAddOption::DEFAULT, None)?;
        index.write()?;
        info!("添加所有变更到暂存区");
        Ok(())
    }

    pub fn commit(&self, message: &str, sign: bool) -> Result<String> {
        let mut index = self.repo.index()?;
        let tree_id = index.write_tree()?;
        let tree = self.repo.find_tree(tree_id)?;

        let parents: Vec<Commit> = match self.repo.head() {
            Ok(head) => vec![self.repo.find_commit(head.target().unwrap())?],
            Err(_) => Vec::new(),
        };

        let parent_refs: Vec<&Commit> = parents.iter().collect();
        let signature = self.repo.signature()?;

        let oid = if sign {
            self.repo.commit(
                Some("HEAD"),
                &signature,
                &signature,
                message,
                &tree,
                &parent_refs,
            )?
        } else {
            self.repo.commit(
                Some("HEAD"),
                &signature,
                &signature,
                message,
                &tree,
                &parent_refs,
            )?
        };

        info!("创建提交: {}", oid);
        Ok(oid.to_string())
    }

    pub fn get_branch_last_commit_time(&self, branch_name: &str) -> Result<DateTime<Utc>> {
        let branch = self.repo.find_branch(branch_name, BranchType::Local)
            .or_else(|_| self.repo.find_branch(&format!("origin/{}", branch_name), BranchType::Remote))?;
        let commit = branch.get().peel_to_commit()?;
        Ok(DateTime::from_timestamp(commit.time().seconds(), 0).unwrap_or_else(|| Utc::now()))
    }

    pub fn merge(&self, source_branch: &str) -> Result<()> {
        let source = self.repo.find_branch(source_branch, BranchType::Local)?;
        let source_commit = source.get().peel_to_commit()?;

        let (analysis, _) = self.repo.merge_analysis(&[&source_commit])?;

        if analysis.is_up_to_date() {
            info!("已是最新，无需合并");
            return Ok(());
        }

        if analysis.is_fast_forward() {
            debug!("Fast-forward 合并");
            let head = self.repo.head()?;
            let target = head.target().unwrap();
            self.repo.set_head_target(target, None)?;
        }

        let opts = git2::MergeOptions::new();
        self.repo.merge(&[&source_commit], Some(&opts), None)?;

        if self.repo.index()?.has_conflicts() {
            return Err(GitFlowError::Other(
                "合并冲突，请手动解决冲突后提交".into(),
            ));
        }

        info!("合并 {} 完成", source_branch);
        Ok(())
    }

    pub fn list_files(&self) -> Result<Vec<PathBuf>> {
        let mut files = Vec::new();
        let index = self.repo.index()?;

        for entry in index.iter() {
            let path = PathBuf::from(String::from_utf8_lossy(&entry.path).into_owned());
            files.push(path);
        }

        Ok(files)
    }

    pub fn get_file_size(&self, path: &Path) -> Result<u64> {
        let full_path = self.path.join(path);
        Ok(std::fs::metadata(&full_path)?.len())
    }

    pub fn get_file_blob_size(&self, sha: &str) -> Result<u64> {
        let oid = git2::Oid::from_str(sha)?;
        let blob = self.repo.find_blob(oid)?;
        Ok(blob.size() as u64)
    }
}

pub fn parse_commit_for_conventional(message: &str) -> Option<ConventionalCommit> {
    let re = Regex::new(
        r"^(?P<type>\w+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<subject>.+)$"
    ).ok()?;

    let captures = re.captures(message.split('\n').next().unwrap_or(""))?;

    let r#type = captures.name("type")?.as_str().to_string();
    let scope = captures.name("scope").map(|m| m.as_str().to_string());
    let is_breaking = captures.name("breaking").is_some();
    let subject = captures.name("subject")?.as_str().to_string();

    let body = message.splitn(2, '\n').nth(1).map(|s| s.trim().to_string());

    let footer_re = Regex::new(r"(?P<key>BREAKING CHANGE|[A-Z-]+):\s*(?P<value>.+)").ok()?;
    let mut footers = Vec::new();
    if let Some(ref b) = body {
        for line in b.lines() {
            if let Some(cap) = footer_re.captures(line) {
                footers.push((
                    cap.name("key")?.as_str().to_string(),
                    cap.name("value")?.as_str().to_string(),
                ));
            }
        }
    }

    let is_breaking = is_breaking || footers.iter().any(|(k, _)| k == "BREAKING CHANGE");

    Some(ConventionalCommit {
        r#type,
        scope,
        is_breaking,
        subject,
        body,
        footers,
    })
}

#[derive(Debug, Clone)]
pub struct ConventionalCommit {
    pub r#type: String,
    pub scope: Option<String>,
    pub is_breaking: bool,
    pub subject: String,
    pub body: Option<String>,
    pub footers: Vec<(String, String)>,
}

pub fn get_jira_issue_from_branch(branch_name: &str, pattern: &str) -> Option<String> {
    let re = Regex::new(pattern).ok()?;
    re.captures(branch_name)
        .and_then(|c| c.get(0))
        .map(|m| m.as_str().to_string())
}

pub fn extract_jira_issues(text: &str, pattern: &str) -> Vec<String> {
    let re = match Regex::new(pattern) {
        Ok(r) => r,
        Err(_) => return Vec::new(),
    };

    re.find_iter(text)
        .map(|m| m.as_str().to_string())
        .collect()
}
