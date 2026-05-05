use chrono::{DateTime, Local, NaiveDate, TimeZone};
use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct CommitRecord {
    pub hash: String,
    pub author_name: String,
    pub author_email: String,
    pub date: DateTime<Local>,
    pub message: String,
    pub repository: String,
}

#[derive(Debug, Clone)]
pub struct AuthorStats {
    pub author_name: String,
    pub author_email: String,
    pub commit_count: usize,
    pub repositories: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct DailyStats {
    pub date: NaiveDate,
    pub commit_count: usize,
}

#[derive(Debug, Clone)]
pub struct AggregatedLogs {
    pub commits: Vec<CommitRecord>,
    pub author_stats: HashMap<String, AuthorStats>,
    pub daily_stats: Vec<DailyStats>,
}

impl CommitRecord {
    pub fn from_git_log_line(line: &str, repository: &str) -> Option<Self> {
        let parts: Vec<&str> = line.split('|').collect();
        if parts.len() < 5 {
            return None;
        }

        let hash = parts[0].to_string();
        let author_name = parts[1].to_string();
        let author_email = parts[2].to_string();
        let date_str = parts[3];
        let message = parts[4..].join("|");

        let date = match DateTime::parse_from_str(date_str, "%Y-%m-%d %H:%M:%S %z") {
            Ok(dt) => dt.with_timezone(&Local),
            Err(_) => return None,
        };

        Some(CommitRecord {
            hash,
            author_name,
            author_email,
            date,
            message,
            repository: repository.to_string(),
        })
    }
}

impl AggregatedLogs {
    pub fn new() -> Self {
        AggregatedLogs {
            commits: Vec::new(),
            author_stats: HashMap::new(),
            daily_stats: Vec::new(),
        }
    }

    pub fn from_git_outputs(outputs: &HashMap<String, Result<String, super::git::GitError>>) -> Self {
        let mut aggregated = AggregatedLogs::new();

        for (repo_name, result) in outputs {
            match result {
                Ok(output) => {
                    for line in output.lines() {
                        if let Some(commit) = CommitRecord::from_git_log_line(line, repo_name) {
                            aggregated.commits.push(commit);
                        }
                    }
                }
                Err(e) => {
                    eprintln!("获取仓库 {} 的日志失败: {}", repo_name, e);
                }
            }
        }

        aggregated.commits.sort_by(|a, b| b.date.cmp(&a.date));
        aggregated.calculate_author_stats();
        aggregated.calculate_daily_stats();

        aggregated
    }

    fn calculate_author_stats(&mut self) {
        let mut stats: HashMap<String, AuthorStats> = HashMap::new();

        for commit in &self.commits {
            let key = commit.author_email.clone();
            let entry = stats.entry(key).or_insert_with(|| AuthorStats {
                author_name: commit.author_name.clone(),
                author_email: commit.author_email.clone(),
                commit_count: 0,
                repositories: Vec::new(),
            });

            entry.commit_count += 1;
            if !entry.repositories.contains(&commit.repository) {
                entry.repositories.push(commit.repository.clone());
            }
        }

        self.author_stats = stats;
    }

    fn calculate_daily_stats(&mut self) {
        let mut daily: HashMap<NaiveDate, usize> = HashMap::new();

        for commit in &self.commits {
            let date = commit.date.date_naive();
            *daily.entry(date).or_insert(0) += 1;
        }

        let mut daily_stats: Vec<DailyStats> = daily
            .into_iter()
            .map(|(date, count)| DailyStats {
                date,
                commit_count: count,
            })
            .collect();

        daily_stats.sort_by(|a, b| b.date.cmp(&a.date));
        self.daily_stats = daily_stats;
    }

    pub fn get_top_authors(&self, limit: usize) -> Vec<&AuthorStats> {
        let mut authors: Vec<&AuthorStats> = self.author_stats.values().collect();
        authors.sort_by(|a, b| b.commit_count.cmp(&a.commit_count));
        authors.into_iter().take(limit).collect()
    }

    pub fn total_commits(&self) -> usize {
        self.commits.len()
    }

    pub fn unique_authors(&self) -> usize {
        self.author_stats.len()
    }

    pub fn unique_repositories(&self) -> usize {
        let mut repos = std::collections::HashSet::new();
        for commit in &self.commits {
            repos.insert(&commit.repository);
        }
        repos.len()
    }
}

impl Default for AggregatedLogs {
    fn default() -> Self {
        Self::new()
    }
}

pub fn format_commit_table(commits: &[CommitRecord], max_count: Option<usize>) -> String {
    let display_count = max_count.unwrap_or(commits.len()).min(commits.len());
    let mut output = String::new();

    output.push_str(&format!(
        "{:<12} {:<20} {:<12} {}\n",
        "日期", "作者", "仓库", "提交信息"
    ));
    output.push_str(&"-".repeat(100));
    output.push('\n');

    for commit in commits.iter().take(display_count) {
        let date = commit.date.format("%Y-%m-%d").to_string();
        let author = if commit.author_name.len() > 18 {
            format!("{}...", &commit.author_name[..17])
        } else {
            commit.author_name.clone()
        };
        let repo = if commit.repository.len() > 10 {
            format!("{}...", &commit.repository[..9])
        } else {
            commit.repository.clone()
        };
        let message = if commit.message.len() > 40 {
            format!("{}...", &commit.message[..39])
        } else {
            commit.message.clone()
        };

        output.push_str(&format!(
            "{:<12} {:<20} {:<12} {}\n",
            date, author, repo, message
        ));
    }

    if display_count < commits.len() {
        output.push_str(&format!(
            "\n... 还有 {} 条提交记录未显示\n",
            commits.len() - display_count
        ));
    }

    output
}

pub fn format_author_stats(stats: &HashMap<String, AuthorStats>, max_count: Option<usize>) -> String {
    let mut authors: Vec<&AuthorStats> = stats.values().collect();
    authors.sort_by(|a, b| b.commit_count.cmp(&a.commit_count));

    let display_count = max_count.unwrap_or(authors.len()).min(authors.len());
    let mut output = String::new();

    output.push_str(&format!(
        "{:<4} {:<25} {:<10} {}\n",
        "排名", "作者", "提交次数", "涉及仓库"
    ));
    output.push_str(&"-".repeat(80));
    output.push('\n');

    for (idx, author) in authors.iter().enumerate().take(display_count) {
        let name = if author.author_name.len() > 23 {
            format!("{}...", &author.author_name[..22])
        } else {
            author.author_name.clone()
        };

        let repos = author.repositories.join(", ");
        let repos_display = if repos.len() > 30 {
            format!("{}...", &repos[..29])
        } else {
            repos
        };

        output.push_str(&format!(
            "{:<4} {:<25} {:<10} {}\n",
            idx + 1,
            name,
            author.commit_count,
            repos_display
        ));
    }

    output
}

pub fn format_daily_stats(stats: &[DailyStats]) -> String {
    let mut output = String::new();
    output.push_str("每日提交统计:\n\n");

    for day in stats.iter().take(14) {
        let bar = "█".repeat(day.commit_count.min(50));
        output.push_str(&format!(
            "{}: {:>3} 次 {}\n",
            day.date.format("%Y-%m-%d"),
            day.commit_count,
            bar
        ));
    }

    if stats.len() > 14 {
        output.push_str(&format!("\n... 还有 {} 天的数据\n", stats.len() - 14));
    }

    output
}
