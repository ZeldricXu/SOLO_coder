use gix::Repository;

use crate::error::{DiffError, DiffResult};
use crate::models::{ChangedFile, FileChangeStatus};

pub struct GixRepository {
    repo: Repository,
}

impl GixRepository {
    pub fn open(path: &str) -> DiffResult<Self> {
        let repo = gix::open(path).map_err(|e| DiffError::Repository(e.to_string()))?;
        Ok(Self { repo })
    }

    pub fn open_or_init(path: &str) -> DiffResult<Self> {
        let repo = gix::open(path)
            .or_else(|_| gix::init(path))
            .map_err(|e| DiffError::Repository(e.to_string()))?;
        Ok(Self { repo })
    }

    pub fn diff_commits(&self, old_rev: &str, new_rev: &str) -> DiffResult<Vec<ChangedFile>> {
        let old_id = self
            .repo
            .rev_parse_single(old_rev)
            .map_err(|e| DiffError::Repository(format!("failed to parse rev '{}': {}", old_rev, e)))?;
        let new_id = self
            .repo
            .rev_parse_single(new_rev)
            .map_err(|e| DiffError::Repository(format!("failed to parse rev '{}': {}", new_rev, e)))?;

        let old_commit = old_id
            .object()
            .map_err(|e| DiffError::NotFound(format!("object '{}': {}", old_rev, e)))?
            .try_into_commit()
            .map_err(|_| DiffError::Parse(format!("'{}' is not a commit", old_rev)))?;
        let new_commit = new_id
            .object()
            .map_err(|e| DiffError::NotFound(format!("object '{}': {}", new_rev, e)))?
            .try_into_commit()
            .map_err(|_| DiffError::Parse(format!("'{}' is not a commit", new_rev)))?;

        let old_tree = old_commit
            .tree()
            .map_err(|e| DiffError::Repository(format!("old tree: {}", e)))?;
        let new_tree = new_commit
            .tree()
            .map_err(|e| DiffError::Repository(format!("new tree: {}", e)))?;

        let mut changes = old_tree
            .changes()
            .map_err(|e| DiffError::Repository(format!("create diff tracker: {}", e)))?;

        let mut result = Vec::new();

        changes
            .options(|opts| {
                opts.track_path();
            })
            .for_each_to_obtain_tree(&new_tree, |change| {
                let changed_file = match change {
                    gix::object::tree::diff::Change::Addition {
                        location,
                        relation: _,
                        entry_mode: _,
                        id: _,
                    } => ChangedFile {
                        old_path: None,
                        new_path: Some(location.to_string()),
                        status: FileChangeStatus::Added,
                    },
                    gix::object::tree::diff::Change::Deletion {
                        location,
                        relation: _,
                        entry_mode: _,
                        id: _,
                    } => ChangedFile {
                        old_path: Some(location.to_string()),
                        new_path: None,
                        status: FileChangeStatus::Deleted,
                    },
                    gix::object::tree::diff::Change::Modification {
                        location,
                        previous_entry_mode: _,
                        previous_id: _,
                        entry_mode: _,
                        id: _,
                    } => ChangedFile {
                        old_path: Some(location.to_string()),
                        new_path: Some(location.to_string()),
                        status: FileChangeStatus::Modified,
                    },
                    gix::object::tree::diff::Change::Rewrite {
                        source_location,
                        source_relation: _,
                        source_entry_mode: _,
                        source_id: _,
                        diff: _,
                        entry_mode: _,
                        location,
                        id: _,
                        relation: _,
                        copy,
                    } => ChangedFile {
                        old_path: Some(source_location.to_string()),
                        new_path: Some(location.to_string()),
                        status: if copy {
                            FileChangeStatus::Copied
                        } else {
                            FileChangeStatus::Renamed
                        },
                    },
                };

                result.push(changed_file);
                Ok::<_, std::convert::Infallible>(gix::object::tree::diff::Action::Continue)
            })
            .map_err(|e| DiffError::Repository(format!("compute tree diff: {}", e)))?;

        Ok(result)
    }

    pub fn get_blob_content(&self, rev: &str, path: &str) -> DiffResult<String> {
        let rev_path = format!("{}:{}", rev, path);
        let id = self
            .repo
            .rev_parse_single(rev_path.as_str())
            .map_err(|e| DiffError::NotFound(format!("'{}': {}", rev_path, e)))?;

        let blob = id
            .object()
            .map_err(|e| DiffError::Repository(format!("read blob '{}': {}", rev_path, e)))?
            .try_into_blob()
            .map_err(|_| DiffError::Parse(format!("'{}' is not a blob", rev_path)))?;

        let content = String::from_utf8(blob.data.clone())
            .map_err(|e| DiffError::Parse(format!("blob is not valid utf-8: {}", e)))?;

        Ok(content)
    }
}
