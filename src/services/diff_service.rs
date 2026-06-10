use maud::{html, Markup, PreEscaped};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::models::repository::Repository;
use crate::providers::{GitProvider, MinioClient, ProviderDiff};
use crate::repositories::RepoRepository;
use crate::utils::{AppError, AppResult, DiffFile, DiffLine, DiffParser};

#[derive(Clone)]
pub struct DiffService {
    diff_parser: DiffParser,
    minio_client: MinioClient,
    repo_repo: RepoRepository,
}

impl DiffService {
    pub fn new(
        diff_parser: DiffParser,
        minio_client: MinioClient,
        repo_repo: RepoRepository,
    ) -> Self {
        Self {
            diff_parser,
            minio_client,
            repo_repo,
        }
    }

    pub fn parse_diff(&self, diff_text: &str) -> AppResult<Vec<DiffFile>> {
        self.diff_parser.parse(diff_text)
    }

    pub async fn fetch_diff(
        &self,
        provider: &dyn GitProvider,
        repo: &Repository,
        mr_number: i64,
    ) -> AppResult<Vec<DiffFile>> {
        let provider_diff: ProviderDiff = provider.get_diff(&repo.full_name, mr_number).await?;

        let mut all_diff_text = String::new();
        for diff_file in &provider_diff.files {
            all_diff_text.push_str(&diff_file.patch);
            all_diff_text.push('\n');
        }

        if all_diff_text.is_empty() {
            return Ok(Vec::new());
        }

        self.parse_diff(&all_diff_text)
    }

    pub async fn store_diff_snapshot(
        &self,
        merge_request_id: Uuid,
        diff_files: &[DiffFile],
    ) -> AppResult<String> {
        let diff_json = serde_json::to_string(diff_files)?;

        let storage_key = self
            .minio_client
            .store_diff_snapshot(merge_request_id, &diff_json)
            .await?;

        let checksum = format!("{:x}", Sha256::digest(diff_json.as_bytes()));
        let (additions, deletions) = self.diff_parser.get_changed_line_count(diff_files);
        let line_count = (additions + deletions) as i32;
        let changed_files = diff_files.len() as i32;

        self.repo_repo
            .create_diff_snapshot(merge_request_id, &storage_key, &checksum, line_count, changed_files)
            .await?;

        Ok(storage_key)
    }

    pub async fn load_diff_snapshot(&self, storage_key: &str) -> AppResult<Vec<DiffFile>> {
        let diff_json = self.minio_client.get_diff_snapshot(storage_key).await?;
        let diff_files: Vec<DiffFile> = serde_json::from_str(&diff_json)?;
        Ok(diff_files)
    }

    pub fn get_diff_summary(&self, diff_files: &[DiffFile]) -> DiffSummary {
        let (additions, deletions) = self.diff_parser.get_changed_line_count(diff_files);
        let file_count = diff_files.len() as i64;

        DiffSummary {
            additions,
            deletions,
            file_count,
        }
    }

    pub fn get_line_context<'a>(
        &'a self,
        diff_files: &'a [DiffFile],
        file_path: &str,
        line_no: i32,
        context_lines: i32,
    ) -> Option<LineContext<'a>> {
        let (lines, hunk_idx) = self
            .diff_parser
            .get_line_context(diff_files, file_path, line_no, context_lines)?;

        Some(LineContext {
            lines,
            hunk_index: hunk_idx,
        })
    }

    pub fn get_changed_files(&self, diff_files: &[DiffFile]) -> Vec<ChangedFile> {
        diff_files
            .iter()
            .map(|f| ChangedFile {
                old_path: f.old_path.clone(),
                new_path: f.new_path.clone(),
                status: f.status.clone(),
                additions: f
                    .hunks
                    .iter()
                    .flat_map(|h| h.lines.iter())
                    .filter(|l| l.line_type == "new")
                    .count() as i64,
                deletions: f
                    .hunks
                    .iter()
                    .flat_map(|h| h.lines.iter())
                    .filter(|l| l.line_type == "old")
                    .count() as i64,
                binary: f.binary,
            })
            .collect()
    }

    pub fn generate_diff_html(&self, diff_files: &[DiffFile]) -> Markup {
        html! {
            div class="diff-container" {
                @for file in diff_files {
                    div class="diff-file" {
                        div class="diff-file-header" {
                            span class="diff-file-path" { (file.new_path) }
                            span class="diff-file-status" { (file.status) }
                            span class="diff-file-stats" {
                                "+" (file.hunks.iter().flat_map(|h| &h.lines).filter(|l| l.line_type == "new").count())
                                " -" (file.hunks.iter().flat_map(|h| &h.lines).filter(|l| l.line_type == "old").count())
                            }
                        }
                        @for hunk in &file.hunks {
                            div class="diff-hunk" {
                                div class="diff-hunk-header" {
                                    (hunk.header)
                                }
                                table class="diff-table" {
                                    @for line in &hunk.lines {
                                        tr class={
                                            "diff-line "
                                            @match line.line_type.as_str() {
                                                "new" => "diff-line-add",
                                                "old" => "diff-line-del",
                                                _ => "diff-line-ctx",
                                            }
                                        } {
                                            td class="diff-line-num old" {
                                                @if let Some(n) = line.old_line_no { (n) }
                                            }
                                            td class="diff-line-num new" {
                                                @if let Some(n) = line.new_line_no { (n) }
                                            }
                                            td class="diff-line-content" {
                                                pre {
                                                    code {
                                                        (PreEscaped(self.escape_diff_line(&line.content, &line.line_type)))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pub fn compare_snapshots(
        &self,
        old_files: &[DiffFile],
        new_files: &[DiffFile],
    ) -> SnapshotComparison {
        let old_paths: std::collections::HashSet<_> =
            old_files.iter().map(|f| f.new_path.clone()).collect();
        let new_paths: std::collections::HashSet<_> =
            new_files.iter().map(|f| f.new_path.clone()).collect();

        let added_files: Vec<String> = new_paths
            .difference(&old_paths)
            .cloned()
            .collect();

        let removed_files: Vec<String> = old_paths
            .difference(&new_paths)
            .cloned()
            .collect();

        let mut modified_files = Vec::new();
        for path in old_paths.intersection(&new_paths) {
            let old_file = old_files.iter().find(|f| f.new_path == *path).unwrap();
            let new_file = new_files.iter().find(|f| f.new_path == *path).unwrap();

            let (old_add, old_del) = self.diff_parser.get_changed_line_count(&[old_file.clone()]);
            let (new_add, new_del) = self.diff_parser.get_changed_line_count(&[new_file.clone()]);

            if old_add != new_add || old_del != new_del {
                modified_files.push(ModifiedFile {
                    path: path.clone(),
                    old_additions: old_add,
                    old_deletions: old_del,
                    new_additions: new_add,
                    new_deletions: new_del,
                });
            }
        }

        SnapshotComparison {
            added_files,
            removed_files,
            modified_files,
        }
    }

    fn escape_diff_line(&self, content: &str, line_type: &str) -> String {
        let escaped = html_escape(content);
        match line_type {
            "new" => format!("<span class=\"diff-add\">+{}</span>", escaped),
            "old" => format!("<span class=\"diff-del\">-{}</span>", escaped),
            _ => format!(" {}", escaped),
        }
    }
}

fn html_escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DiffSummary {
    pub additions: i64,
    pub deletions: i64,
    pub file_count: i64,
}

#[derive(Debug, Clone)]
pub struct LineContext<'a> {
    pub lines: Vec<&'a DiffLine>,
    pub hunk_index: i32,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ChangedFile {
    pub old_path: String,
    pub new_path: String,
    pub status: String,
    pub additions: i64,
    pub deletions: i64,
    pub binary: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ModifiedFile {
    pub path: String,
    pub old_additions: i64,
    pub old_deletions: i64,
    pub new_additions: i64,
    pub new_deletions: i64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SnapshotComparison {
    pub added_files: Vec<String>,
    pub removed_files: Vec<String>,
    pub modified_files: Vec<ModifiedFile>,
}
