use anyhow::{Context, Result};
use chrono::{DateTime, Local, TimeZone};
use git2::{Repository, Signature, Oid, Tree, FileMode};
use std::path::{Path, PathBuf};
use std::fs;

pub struct GitBackend {
    pub repo: Repository,
    pub repo_path: PathBuf,
}

pub struct VersionCommit {
    pub id: String,
    pub message: String,
    pub author: String,
    pub timestamp: DateTime<Local>,
    pub files_changed: Vec<String>,
}

impl GitBackend {
    pub fn init(repo_path: &Path) -> Result<Self> {
        fs::create_dir_all(repo_path)?;
        let repo = Repository::init(repo_path)?;
        let backend = Self {
            repo,
            repo_path: repo_path.to_path_buf(),
        };
        backend.create_gitignore()?;
        Ok(backend)
    }

    pub fn open(repo_path: &Path) -> Result<Self> {
        let repo = Repository::open(repo_path)?;
        Ok(Self {
            repo,
            repo_path: repo_path.to_path_buf(),
        })
    }

    pub fn ensure_repo(repo_path: &Path) -> Result<Self> {
        if Repository::open(repo_path).is_ok() {
            Self::open(repo_path)
        } else {
            Self::init(repo_path)
        }
    }

    fn create_gitignore(&self) -> Result<()> {
        let gitignore_path = self.repo_path.join(".gitignore");
        if !gitignore_path.exists() {
            fs::write(gitignore_path, ".notebook/\n")?;
        }
        Ok(())
    }

    pub fn commit(&mut self, message: &str) -> Result<String> {
        let mut index = self.repo.index()?;
        index.add_all(["."].iter(), git2::IndexAddOption::DEFAULT, None)?;
        index.write()?;

        let tree_id = index.write_tree()?;
        let tree = self.repo.find_tree(tree_id)?;

        let parent_commit = self.repo.head().ok().and_then(|head| head.peel_to_commit().ok());
        let parents = parent_commit.as_ref().map(|c| vec![c]).unwrap_or_default();

        let signature = Signature::now("Notebook User", "user@notebook.local")?;
        
        let commit_oid = self.repo.commit(
            Some("HEAD"),
            &signature,
            &signature,
            message,
            &tree,
            parents.as_slice(),
        )?;

        Ok(commit_oid.to_string())
    }

    pub fn get_history(&self, limit: usize) -> Result<Vec<VersionCommit>> {
        let mut revwalk = self.repo.revwalk()?;
        revwalk.push_head()?;
        revwalk.set_sorting(git2::Sort::TIME)?;

        let mut commits = Vec::new();
        for oid in revwalk.take(limit) {
            let oid = oid?;
            let commit = self.repo.find_commit(oid)?;
            
            let files_changed = self.get_files_changed_in_commit(&commit)?;
            
            let timestamp = Local.timestamp_opt(commit.time().seconds(), 0).single().unwrap_or_else(|| Local::now());
            
            commits.push(VersionCommit {
                id: oid.to_string(),
                message: commit.message().unwrap_or("").to_string(),
                author: commit.author().name().unwrap_or("Unknown").to_string(),
                timestamp,
                files_changed,
            });
        }

        Ok(commits)
    }

    fn get_files_changed_in_commit(&self, commit: &git2::Commit) -> Result<Vec<String>> {
        let tree = commit.tree()?;
        let parent_tree = commit.parents().next().map(|p| p.tree()).transpose()?;

        let diff = self.repo.diff_tree_to_tree(parent_tree.as_ref(), Some(&tree), None)?;
        
        let mut files = Vec::new();
        for delta in diff.deltas() {
            if let Some(path) = delta.new_file().path() {
                files.push(path.to_string_lossy().to_string());
            }
        }

        Ok(files)
    }

    pub fn get_file_content_at_commit(&self, file_path: &str, commit_id: &str) -> Result<String> {
        let oid = Oid::from_str(commit_id)?;
        let commit = self.repo.find_commit(oid)?;
        let tree = commit.tree()?;
        
        let entry = tree.get_path(Path::new(file_path))?;
        let object = entry.to_object(&self.repo)?;
        let blob = object.as_blob().context("Object is not a blob")?;
        
        let content = String::from_utf8_lossy(blob.content()).to_string();
        Ok(content)
    }

    pub fn restore_to_commit(&mut self, commit_id: &str, file_path: &str) -> Result<()> {
        let content = self.get_file_content_at_commit(file_path, commit_id)?;
        let full_path = self.repo_path.join(file_path);
        fs::write(full_path, content)?;
        Ok(())
    }
}
