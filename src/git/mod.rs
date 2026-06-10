use chrono::{DateTime, Utc};
use git2::{
    Branch, BranchType, Commit, Cred, FetchOptions, MergeOptions, PushOptions,
    RemoteCallbacks, Repository, Sort, StashApplyOptions, StashFlags,
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
    pub is_merge_commit: bool,
    pub pr_number: Option<u32>,
}

impl CommitInfo {
    pub fn parent_count(&self) -> usize {
        self.parents.len()
    }

    pub fn is_merge(&self) -> bool {
        self.parent_count() > 1
    }
}

#[derive(Debug, Clone)]
pub struct TagInfo {
    pub name: String,
    pub sha: String,
    pub message: Option<String>,
    pub time: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone)]
pub struct SignatureInfo {
    pub signature_type: String,
    pub signed_by: Option<String>,
    pub verified: bool,
}

#[derive(Debug, Clone)]
pub struct FileDiff {
    pub old_path: Option<String>,
    pub new_path: Option<String>,
    pub status: String,
    pub additions: usize,
    pub deletions: usize,
}

pub struct GitContext {
    repo: Repository,
    path: PathBuf,
}

unsafe impl Send for GitContext {}
unsafe impl Sync for GitContext {}

pub type GitRepository = GitContext;

impl GitContext {
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

    pub fn open_repo(&self) -> &Repository {
        &self.repo
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn git_dir(&self) -> &Path {
        self.repo.path()
    }

    pub fn repo(&self) -> &Repository {
        &self.repo
    }

    pub fn commit(&self, message: &str, _sign: bool) -> Result<String> {
        let mut index = self.repo.index()?;
        let tree_id = index.write_tree()?;
        let tree = self.repo.find_tree(tree_id)?;

        let parent_commit = self.repo.head()?.peel_to_commit()?;

        let signature = self.repo.signature()?;
        let oid = self.repo.commit(
            Some("HEAD"),
            &signature,
            &signature,
            message,
            &tree,
            &[&parent_commit],
        )?;

        Ok(oid.to_string())
    }

    pub fn resolve_ref(&self, spec: &str) -> Result<git2::Oid> {
        let obj = self.repo.revparse_single(spec)?;
        Ok(obj.id())
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

    pub fn walk_commits<F>(&self, start: Option<&str>, mut callback: F) -> Result<()>
    where
        F: FnMut(CommitInfo) -> Result<bool>,
    {
        let mut revwalk = self.repo.revwalk()?;
        revwalk.set_sorting(Sort::TIME | Sort::TOPOLOGICAL)?;

        match start {
            Some(spec) => {
                let oid = self.resolve_ref(spec)?;
                revwalk.push(oid)?;
            }
            None => {
                revwalk.push_head()?;
            }
        }

        for oid in revwalk {
            let oid = oid?;
            let commit = self.repo.find_commit(oid)?;
            let info = self.commit_to_info(&commit)?;
            if !callback(info)? {
                break;
            }
        }

        Ok(())
    }

    pub fn get_commit_count_in_range(
        &self,
        start: Option<DateTime<Utc>>,
        end: Option<DateTime<Utc>>,
    ) -> Result<usize> {
        let mut revwalk = self.repo.revwalk()?;
        revwalk.set_sorting(Sort::TIME | Sort::TOPOLOGICAL)?;
        revwalk.push_head()?;

        let mut count = 0;
        for oid in revwalk {
            let oid = oid?;
            let commit = self.repo.find_commit(oid)?;
            let commit_time =
                DateTime::from_timestamp(commit.time().seconds(), 0).unwrap_or_else(|| Utc::now());

            let in_range = match (start, end) {
                (Some(s), Some(e)) => commit_time >= s && commit_time <= e,
                (Some(s), None) => commit_time >= s,
                (None, Some(e)) => commit_time <= e,
                (None, None) => true,
            };

            if in_range {
                count += 1;
            } else if let Some(s) = start {
                if commit_time < s {
                    break;
                }
            }
        }

        Ok(count)
    }

    pub fn diff_files(&self, old: Option<&str>, new: Option<&str>) -> Result<Vec<FileDiff>> {
        let old_oid = match old {
            Some(spec) => Some(self.resolve_ref(spec)?),
            None => None,
        };
        let new_oid = match new {
            Some(spec) => Some(self.resolve_ref(spec)?),
            None => None,
        };

        let old_tree = match old_oid {
            Some(oid) => Some(self.repo.find_commit(oid)?.tree()?),
            None => None,
        };
        let new_tree = match new_oid {
            Some(oid) => Some(self.repo.find_commit(oid)?.tree()?),
            None => None,
        };

        let mut diff =
            self.repo.diff_tree_to_tree(old_tree.as_ref(), new_tree.as_ref(), None)?;

        let mut diffs = Vec::new();
        for delta in diff.deltas() {
            let old_path = delta.old_file().path().map(|p| p.to_string_lossy().to_string());
            let new_path = delta.new_file().path().map(|p| p.to_string_lossy().to_string());
            let status = match delta.status() {
                git2::Delta::Added => "added".to_string(),
                git2::Delta::Deleted => "deleted".to_string(),
                git2::Delta::Modified => "modified".to_string(),
                git2::Delta::Renamed => "renamed".to_string(),
                _ => "unknown".to_string(),
            };

            let mut additions = 0;
            let mut deletions = 0;

            if let Ok(stats) = diff.stats() {
                additions = stats.insertions() as usize;
                deletions = stats.deletions() as usize;
            }

            diffs.push(FileDiff {
                old_path,
                new_path,
                status,
                additions,
                deletions,
            });
        }

        Ok(diffs)
    }

    pub fn list_branches(
        &self,
        local: bool,
        remote: bool,
        pattern: Option<&str>,
        merged_only: bool,
    ) -> Result<Vec<BranchInfo>> {
        let mut branches = Vec::new();
        let current_branch = self.current_branch().ok();

        let branch_types = if local && !remote {
            vec![BranchType::Local]
        } else if remote && !local {
            vec![BranchType::Remote]
        } else {
            vec![BranchType::Local, BranchType::Remote]
        };

        for branch_type in branch_types {
            for b in self.repo.branches(Some(branch_type))? {
                let (branch, _) = b?;
                let info = self.branch_to_info(&branch, current_branch.as_deref())?;

                if let Some(pat) = pattern {
                    if !info.name.contains(pat) && !info.short_name.contains(pat) {
                        continue;
                    }
                }

                if merged_only && !info.is_merged {
                    continue;
                }

                branches.push(info);
            }
        }

        Ok(branches)
    }

    pub fn has_remote(&self, name: &str) -> bool {
        self.repo.find_remote(name).is_ok()
    }

    pub fn find_branch(&self, name: &str, branch_type: BranchType) -> Result<Branch> {
        self.repo
            .find_branch(name, branch_type)
            .map_err(|e| GitFlowError::GitError(e))
    }

    pub fn create_branch(
        &self,
        name: &str,
        base: Option<&str>,
        force: bool,
    ) -> Result<Branch> {
        let base_commit = match base {
            Some(base_spec) => {
                let oid = self.resolve_ref(base_spec)?;
                self.repo.find_commit(oid)?
            }
            None => {
                let head = self.repo.head()?;
                head.peel_to_commit()?
            }
        };

        let branch = self.repo.branch(name, &base_commit, force)?;
        info!("创建分支: {}", name);
        Ok(branch)
    }

    pub fn delete_branch(&self, name: &str, force: bool) -> Result<()> {
        self.preflight_check_head_operation(name, "删除")?;

        let mut branch = self.repo.find_branch(name, BranchType::Local)?;
        branch.delete()?;
        info!("删除分支: {}", name);
        Ok(())
    }

    fn preflight_check_head_operation(&self, target_branch: &str, operation: &str) -> Result<()> {
        if let Ok(current_branch) = self.current_branch() {
            if current_branch == target_branch {
                return Err(GitFlowError::ValidationError(format!(
                    "无法{}当前分支 '{}'，请先切换到其他分支",
                    operation, target_branch
                )));
            }
        }
        Ok(())
    }

    pub fn checkout_branch(&self, name: &str) -> Result<()> {
        let branch = self.repo.find_branch(name, BranchType::Local)?;
        let ref_name = branch.get().name().ok_or_else(|| {
            GitFlowError::GitError(git2::Error::from_str("无法获取分支引用名"))
        })?;

        let mut opts = git2::build::CheckoutBuilder::new();
        opts.safe();
        self.repo.checkout_tree(
            branch.get().peel_to_commit()?.as_object(),
            Some(&mut opts),
        )?;
        self.repo.set_head(ref_name)?;
        info!("切换到分支: {}", name);
        Ok(())
    }

    pub fn sync_branch(&self, name: &str, remote_name: &str, use_rebase: bool) -> Result<()> {
        let remote_branch_name = format!("{}/{}", remote_name, name);
        let remote_branch = self
            .repo
            .find_branch(&remote_branch_name, BranchType::Remote)?;
        let remote_commit = remote_branch.get().peel_to_commit()?;
        let annotated_commit = self.repo.find_annotated_commit(remote_commit.id())?;

        let analysis = self.repo.merge_analysis(&[&annotated_commit])?;

        if analysis.0.is_up_to_date() {
            info!("分支 {} 已是最新", name);
            return Ok(());
        }

        if use_rebase {
            let annotated = self
                .repo
                .find_annotated_commit(remote_branch.get().target().unwrap())?;
            let mut rebase = self.repo.rebase(None, Some(&annotated), None, None)?;
            while let Some(op) = rebase.next() {
                op?;
            }
            rebase.commit(None, &self.repo.signature()?, None)?;
            rebase.finish(None)?;
            info!("Rebase 完成: {} -> {}", name, remote_branch_name);
        } else {
            let mut opts = MergeOptions::new();
            self.repo.merge(&[&annotated_commit], Some(&mut opts), None)?;

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

        remote.fetch(&[] as &[&str], Some(&mut fetch_opts), None)?;
        info!("从 {} 拉取最新代码", remote_name);
        Ok(())
    }

    pub fn push(&self, branch_name: &str, remote_name: &str, force: bool) -> Result<()> {
        let mut remote = self.repo.find_remote(remote_name)?;

        let mut callbacks = RemoteCallbacks::new();
        callbacks.credentials(|_url, username_from_url, _allowed_types| {
            Cred::ssh_key_from_agent(username_from_url.unwrap_or("git"))
                .or_else(|_| Cred::default())
        });

        let mut push_opts = PushOptions::new();
        push_opts.remote_callbacks(callbacks);

        let refspec = format!(
            "{}refs/heads/{}:refs/heads/{}",
            if force { "+" } else { "" },
            branch_name,
            branch_name
        );

        remote.push(&[refspec.as_str()], Some(&mut push_opts))?;
        info!("推送分支 {} 到 {}", branch_name, remote_name);
        Ok(())
    }

    pub fn get_commit(&self, sha_or_ref: &str) -> Result<CommitInfo> {
        let commit = if sha_or_ref == "HEAD" {
            self.repo.head()?.peel_to_commit()?
        } else {
            let oid = git2::Oid::from_str(sha_or_ref)
                .or_else(|_| -> Result<git2::Oid> { Ok(self.repo.revparse_single(sha_or_ref)?.id()) })?;
            self.repo.find_commit(oid)?
        };

        self.commit_to_info(&commit)
    }

    pub fn get_commit_signature(&self, sha_or_ref: &str) -> Result<Option<crate::git::SignatureInfo>> {
        let commit = if sha_or_ref == "HEAD" {
            self.repo.head()?.peel_to_commit()?
        } else {
            let oid = git2::Oid::from_str(sha_or_ref)?;
            self.repo.find_commit(oid)?
        };

        let raw = commit.as_object();
        let odb = self.repo.odb()?;
        let obj = odb.read(raw.id())?;

        if obj.kind() == git2::ObjectType::Commit {
            let data = obj.data();
            if let Some(sig_start) = data.windows(8).position(|w| w == b"gpgsig ") {
                return Ok(Some(crate::git::SignatureInfo {
                    signature_type: "gpg".to_string(),
                    signed_by: None,
                    verified: false,
                }));
            }
        }

        Ok(None)
    }

    pub fn get_commit_range(
        &self,
        from: Option<&str>,
        to: Option<&str>,
    ) -> Result<Vec<CommitInfo>> {
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
            commits.push(self.commit_to_info(&commit)?);
        }

        Ok(commits)
    }

    pub fn get_remote_url(&self, remote_name: &str) -> Result<String> {
        let remote = self.repo.find_remote(remote_name)?;
        let url = remote
            .url()
            .ok_or_else(|| GitFlowError::GitError(git2::Error::from_str("无法获取远程URL")))?;
        Ok(url.to_string())
    }

    pub fn get_remotes(&self) -> Result<Vec<String>> {
        let remotes = self.repo.remotes()?;
        Ok(remotes
            .iter()
            .flatten()
            .map(|s| s.to_string())
            .collect())
    }

    pub fn is_clean(&self) -> Result<bool> {
        let statuses = self.repo.statuses(None)?;
        Ok(statuses.is_empty())
    }

    pub fn stash(&mut self, message: &str) -> Result<()> {
        let signature = self.repo.signature()?;
        let flags = StashFlags::DEFAULT | StashFlags::INCLUDE_UNTRACKED;
        self.repo
            .stash_save(&signature, message, Some(flags))?;
        info!("暂存修改: {}", message);
        Ok(())
    }

    pub fn stash_pop(&mut self) -> Result<()> {
        let mut stash = StashApplyOptions::new();
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

    pub fn create_commit(
        &self,
        message: &str,
        parents: Option<&[&git2::Commit]>,
    ) -> Result<git2::Oid> {
        let mut index = self.repo.index()?;
        let tree_id = index.write_tree()?;
        let tree = self.repo.find_tree(tree_id)?;

        let signature = self.repo.signature()?;

        let head_commit;
        let parent_commits: Vec<&Commit> = match parents {
            Some(p) => p.to_vec(),
            None => {
                head_commit = self.repo.head()?.peel_to_commit()?;
                vec![&head_commit]
            }
        };

        let oid = self.repo.commit(
            Some("HEAD"),
            &signature,
            &signature,
            message,
            &tree,
            &parent_commits,
        )?;

        info!("创建commit: {}", oid);
        Ok(oid)
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
        let annotated_commit = self.repo.find_annotated_commit(source_commit.id())?;

        let (analysis, _) = self.repo.merge_analysis(&[&annotated_commit])?;

        if analysis.is_up_to_date() {
            info!("已是最新，无需合并");
            return Ok(());
        }

        if analysis.is_fast_forward() {
            debug!("Fast-forward 合并");
            let head_ref = self.repo.head()?;
            let head_refname = head_ref.name().unwrap();
            self.repo.set_head(head_refname)?;
            self.repo.checkout_head(None)?;
        }

        let mut opts = MergeOptions::new();
        self.repo.merge(&[&annotated_commit], Some(&mut opts), None)?;

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
        Ok(fs::metadata(&full_path)?.len())
    }

    pub fn get_file_blob_size(&self, sha: &str) -> Result<u64> {
        let oid = git2::Oid::from_str(sha)?;
        let blob = self.repo.find_blob(oid)?;
        Ok(blob.size() as u64)
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

    pub fn is_merged(&self, branch: &str, base: Option<&str>) -> Result<bool> {
        let branch_oid = self.resolve_ref(branch)?;
        let base_oid = match base {
            Some(b) => self.resolve_ref(b)?,
            None => self.resolve_ref("HEAD")?,
        };

        Ok(self.repo.graph_descendant_of(base_oid, branch_oid)?)
    }

    pub fn list_tags(&self, pattern: Option<&str>) -> Result<Vec<TagInfo>> {
        let mut tags = Vec::new();
        let tag_names = self.repo.tag_names(pattern)?;

        for tag_name in tag_names.iter().flatten() {
            let obj = self.repo.revparse_single(tag_name)?;
            let sha = obj.id().to_string();

            let (message, time) = if let Some(tag) = obj.as_tag() {
                (
                    tag.message().map(|s| s.to_string()),
                    tag.tagger().and_then(|t| {
                        DateTime::from_timestamp(t.when().seconds(), 0)
                    }),
                )
            } else {
                (None, None)
            };

            tags.push(TagInfo {
                name: tag_name.to_string(),
                sha,
                message,
                time,
            });
        }

        tags.sort_by(|a, b| {
            b.time.cmp(&a.time)
        });

        Ok(tags)
    }

    fn commit_to_info(&self, commit: &Commit) -> Result<CommitInfo> {
        let message = commit.message().unwrap_or("").to_string();
        let (summary, body) = message
            .split_once('\n')
            .map(|(s, b)| (s.trim().to_string(), Some(b.trim().to_string())))
            .unwrap_or_else(|| (message.trim().to_string(), None));

        let parents: Vec<String> = commit
            .parent_ids()
            .map(|oid| oid.to_string())
            .collect();

        let is_merge_commit = parents.len() > 1;
        let pr_number = extract_pr_number(&message);

        let time = DateTime::from_timestamp(commit.time().seconds(), 0).unwrap_or_else(|| Utc::now());
        let author = commit.author().name().unwrap_or("").to_string();
        let email = commit.author().email().unwrap_or("").to_string();

        let result = CommitInfo {
            sha: commit.id().to_string(),
            short_sha: commit.id().to_string()[..8].to_string(),
            message,
            summary,
            body,
            author,
            email,
            time,
            parents,
            is_merge_commit,
            pr_number,
        };
        Ok(result)
    }

    pub fn get_merge_feature_commits(&self, merge_commit: &CommitInfo) -> Result<Vec<CommitInfo>> {
        if !merge_commit.is_merge() {
            return Ok(Vec::new());
        }

        if merge_commit.parents.len() < 2 {
            return Ok(Vec::new());
        }

        let first_parent = &merge_commit.parents[0];
        let second_parent = &merge_commit.parents[1];

        let merge_base_oid = self.repo.merge_base(
            git2::Oid::from_str(first_parent)?,
            git2::Oid::from_str(second_parent)?,
        )?;

        let commits = self.get_commit_range(
            Some(&merge_base_oid.to_string()),
            Some(second_parent),
        )?;

        Ok(commits)
    }

    fn branch_to_info(&self, branch: &Branch, current_branch: Option<&str>) -> Result<BranchInfo> {
        let name = branch.name()?.unwrap_or("").to_string();
        let is_remote = name.starts_with("origin/") || name.starts_with("remotes/");
        let short_name = if is_remote {
            name.splitn(3, '/').nth(2).unwrap_or(&name).to_string()
        } else {
            name
                .splitn(2, '/')
                .nth(1)
                .unwrap_or(&name)
                .to_string()
        };
        let is_current = Some(name.as_str()) == current_branch && !is_remote;

        let upstream = branch.upstream().ok().and_then(|b| b.name().ok().flatten().map(|s| s.to_string()));

        let commit = branch.get().peel_to_commit()?;
        let last_commit_sha = commit.id().to_string();
        let last_commit_message = commit.summary().unwrap_or("").to_string();
        let last_commit_author = commit.author().name().unwrap_or("").to_string();
        let last_commit_time =
            DateTime::from_timestamp(commit.time().seconds(), 0).unwrap_or_else(|| Utc::now());

        let is_merged = self.is_merged(&name, None).unwrap_or(false);

        Ok(BranchInfo {
            name,
            short_name,
            is_remote,
            is_current,
            last_commit_sha,
            last_commit_message,
            last_commit_author,
            last_commit_time,
            is_merged,
            upstream,
        })
    }
}

pub fn get_jira_issue_from_branch(branch_name: &str, pattern: &str) -> Option<String> {
    let re = Regex::new(pattern).ok()?;
    re.captures(branch_name)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().to_string())
}

pub fn extract_pr_number(message: &str) -> Option<u32> {
    let re = regex::Regex::new(r"#(\d+)").ok()?;
    re.captures(message)
        .and_then(|c| c.get(1))
        .and_then(|m| m.as_str().parse::<u32>().ok())
}

#[derive(Debug, Clone)]
pub struct ConventionalCommit {
    pub r#type: String,
    pub scope: Option<String>,
    pub is_breaking: bool,
    pub subject: String,
    pub body: Option<String>,
    pub breaking_description: Option<String>,
    pub issues: Vec<String>,
    pub footers: Vec<(String, String)>,
}

pub fn parse_commit_for_conventional(message: &str) -> Option<ConventionalCommit> {
    let re = regex::Regex::new(
        r"^(?P<type>\w+)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<subject>.+)$"
    ).ok()?;

    let lines: Vec<&str> = message.split('\n').collect();
    let first_line = lines.first()?;

    let caps = re.captures(first_line)?;

    let r#type = caps.name("type")?.as_str().to_string();
    let scope = caps.name("scope").map(|m| m.as_str().to_string());
    let is_breaking = caps.name("breaking").is_some();
    let subject = caps.name("subject")?.as_str().to_string();

    let mut body = None;
    let mut breaking_description = None;
    let issues = Vec::new();
    let mut footers = Vec::new();

    for line in lines.iter().skip(1) {
        let line = line.trim();
        if line.starts_with("BREAKING CHANGE:") {
            breaking_description = Some(line.trim_start_matches("BREAKING CHANGE:").trim().to_string());
        } else if line.starts_with("BREAKING-CHANGE:") {
            breaking_description = Some(line.trim_start_matches("BREAKING-CHANGE:").trim().to_string());
        } else if !line.is_empty() {
            body = Some(match body {
                Some(b) => format!("{}\n{}", b, line),
                None => line.to_string(),
            });
        }
    }

    Some(ConventionalCommit {
        r#type,
        scope,
        is_breaking: is_breaking || breaking_description.is_some(),
        subject,
        body,
        breaking_description,
        issues,
        footers,
    })
}

pub fn extract_jira_issues(message: &str, pattern: &str) -> Vec<String> {
    let re = match regex::Regex::new(pattern) {
        Ok(re) => re,
        Err(_) => return Vec::new(),
    };

    let mut issues = std::collections::HashSet::new();
    for cap in re.captures_iter(message) {
        if let Some(issue) = cap.get(1) {
            issues.insert(issue.as_str().to_string());
        }
    }

    issues.into_iter().collect()
}

use std::fs;
