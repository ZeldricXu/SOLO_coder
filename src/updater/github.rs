use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

pub const APP_VERSION: &str = env!("CARGO_PKG_VERSION");
pub const GITHUB_REPO: &str = "marknote-app/marknote";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReleaseInfo {
    pub tag_name: String,
    pub name: String,
    pub body: String,
    pub prerelease: bool,
    pub published_at: String,
    pub assets: Vec<ReleaseAsset>,
    pub html_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReleaseAsset {
    pub name: String,
    pub size: u64,
    pub browser_download_url: String,
    pub content_type: String,
}

#[derive(Debug, Clone)]
pub enum UpdateStatus {
    Checking,
    NoUpdate,
    UpdateAvailable(ReleaseInfo),
    Downloading(f32),
    ReadyToInstall,
    Error(String),
}

pub struct Updater {
    pub status: UpdateStatus,
    pub last_check: Option<u64>,
    pub auto_check_enabled: bool,
    pub skipped_version: Option<String>,
    check_interval_seconds: u64,
}

impl Default for Updater {
    fn default() -> Self {
        Self {
            status: UpdateStatus::NoUpdate,
            last_check: None,
            auto_check_enabled: true,
            skipped_version: None,
            check_interval_seconds: 24 * 60 * 60,
        }
    }
}

impl Updater {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn should_check(&self) -> bool {
        if !self.auto_check_enabled {
            return false;
        }

        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        match self.last_check {
            None => true,
            Some(last) => now - last >= self.check_interval_seconds,
        }
    }

    pub fn check_for_updates(&self, include_prerelease: bool) -> Result<Option<ReleaseInfo>, Box<dyn std::error::Error>> {
        let url = format!("https://api.github.com/repos/{}/releases", GITHUB_REPO);
        
        let client = reqwest::blocking::Client::builder()
            .user_agent("marknote-app")
            .build()?;
        
        let response = client.get(&url).send()?;
        let releases: Vec<ReleaseInfo> = response.json()?;

        for release in releases {
            if release.prerelease && !include_prerelease {
                continue;
            }

            if let Some(skipped) = &self.skipped_version {
                if &release.tag_name == skipped {
                    continue;
                }
            }

            if is_newer_version(&release.tag_name, APP_VERSION) {
                return Ok(Some(release));
            }
        }

        Ok(None)
    }

    pub async fn check_for_updates_async(&self, include_prerelease: bool) -> Result<Option<ReleaseInfo>, Box<dyn std::error::Error + Send + Sync>> {
        let url = format!("https://api.github.com/repos/{}/releases", GITHUB_REPO);
        
        let client = reqwest::Client::builder()
            .user_agent("marknote-app")
            .build()?;
        
        let response = client.get(&url).send().await?;
        let releases: Vec<ReleaseInfo> = response.json().await?;

        for release in releases {
            if release.prerelease && !include_prerelease {
                continue;
            }

            if let Some(skipped) = &self.skipped_version {
                if &release.tag_name == skipped {
                    continue;
                }
            }

            if is_newer_version(&release.tag_name, APP_VERSION) {
                return Ok(Some(release));
            }
        }

        Ok(None)
    }

    pub fn get_asset_for_platform<'a>(&self, release: &'a ReleaseInfo) -> Option<&'a ReleaseAsset> {
        let target = current_platform();
        release.assets.iter().find(|asset| {
            asset.name.to_lowercase().contains(&target)
        })
    }

    pub fn mark_checked(&mut self) {
        self.last_check = Some(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
        );
    }

    pub fn skip_version(&mut self, version: String) {
        self.skipped_version = Some(version);
    }
}

fn is_newer_version(tag: &str, current: &str) -> bool {
    let tag = tag.trim_start_matches('v');
    let current = current.trim_start_matches('v');

    let tag_parts: Vec<u32> = tag
        .split('.')
        .filter_map(|s| s.parse().ok())
        .collect();
    let current_parts: Vec<u32> = current
        .split('.')
        .filter_map(|s| s.parse().ok())
        .collect();

    for i in 0..std::cmp::max(tag_parts.len(), current_parts.len()) {
        let tag_part = tag_parts.get(i).copied().unwrap_or(0);
        let current_part = current_parts.get(i).copied().unwrap_or(0);
        
        if tag_part > current_part {
            return true;
        } else if tag_part < current_part {
            return false;
        }
    }

    false
}

fn current_platform() -> String {
    if cfg!(target_os = "windows") {
        "windows".to_string()
    } else if cfg!(target_os = "macos") {
        "macos".to_string()
    } else {
        "linux".to_string()
    }
}

pub fn render_update_dialog(
    ctx: &egui::Context,
    updater: &mut Updater,
    release: &ReleaseInfo,
    open: &mut bool,
) {
    if !*open {
        return;
    }

    let mut should_close = false;
    
    egui::Window::new("发现新版本")
        .collapsible(false)
        .resizable(true)
        .default_size([500.0, 400.0])
        .open(open)
        .show(ctx, |ui| {
            ui.vertical(|ui| {
                ui.heading(&release.name);
                ui.label(format!("发布时间: {}", release.published_at));
                
                ui.separator();
                
                ui.label("更新内容:");
                egui::ScrollArea::vertical()
                    .max_height(200.0)
                    .show(ui, |ui| {
                        ui.label(&release.body);
                    });
                
                ui.separator();
                
                ui.horizontal(|ui| {
                    if ui.button("立即下载").clicked() {
                        if let Ok(()) = webbrowser::open(&release.html_url) {
                            should_close = true;
                        }
                    }
                    
                    if ui.button("跳过此版本").clicked() {
                        updater.skip_version(release.tag_name.clone());
                        should_close = true;
                    }
                    
                    if ui.button("稍后提醒").clicked() {
                        should_close = true;
                    }
                });
            });
        });
    
    if should_close {
        *open = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_version_comparison() {
        assert!(is_newer_version("v1.0.1", "v1.0.0"));
        assert!(is_newer_version("v1.1.0", "v1.0.0"));
        assert!(is_newer_version("v2.0.0", "v1.9.9"));
        assert!(!is_newer_version("v1.0.0", "v1.0.0"));
        assert!(!is_newer_version("v0.9.0", "v1.0.0"));
        assert!(!is_newer_version("v1.0.0-beta", "v1.0.0"));
    }
}
