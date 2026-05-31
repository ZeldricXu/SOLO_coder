use anyhow::Result;
use std::sync::Arc;

use crate::models::{PagedResult, ServiceEntry, ServiceSearchQuery};
use crate::registry::CatalogRegistry;
use crate::hot_config::HotConfigManager;

pub struct CatalogSearch {
    config: Arc<HotConfigManager>,
}

impl CatalogSearch {
    pub fn new(config: Arc<HotConfigManager>) -> Self {
        Self { config }
    }

    pub fn with_default_config() -> Self {
        Self { config: Arc::new(HotConfigManager::new()) }
    }

    pub fn search(&self, registry: &CatalogRegistry, query: ServiceSearchQuery) -> Result<PagedResult<ServiceEntry>> {
        let cfg = self.config.get_config();
        let all = registry.list_all()?;
        
        let mut scored: Vec<(f32, ServiceEntry)> = all
            .into_iter()
            .filter(|entry| {
                if let Some(ref keyword) = query.keyword {
                    let kw = keyword.to_lowercase();
                    if !entry.name.to_lowercase().contains(&kw)
                        && !entry.description.to_lowercase().contains(&kw)
                    {
                        return false;
                    }
                }
                if let Some(ref language) = query.language {
                    if entry.language != *language {
                        return false;
                    }
                }
                if let Some(ref team) = query.team {
                    if entry.team != *team {
                        return false;
                    }
                }
                if let Some(ref status) = query.status {
                    if entry.status != *status {
                        return false;
                    }
                }
                if !query.tags.is_empty() {
                    if !query.tags.iter().all(|t| entry.tags.contains(t)) {
                        return false;
                    }
                }
                true
            })
            .map(|entry| {
                let mut score = 0.0;
                if let Some(ref keyword) = query.keyword {
                    let kw = keyword.to_lowercase();
                    if entry.name.to_lowercase().contains(&kw) {
                        score += cfg.search_weight_name;
                    }
                    if entry.description.to_lowercase().contains(&kw) {
                        score += cfg.search_weight_description;
                    }
                    for tag in &entry.tags {
                        if tag.to_lowercase().contains(&kw) {
                            score += cfg.search_weight_tags;
                        }
                    }
                }
                (score, entry)
            })
            .collect();

        scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));

        let total = scored.len();
        let page = query.page.max(1);
        let page_size = query.page_size.clamp(1, cfg.max_page_size);
        let start = (page - 1) * page_size;
        let items: Vec<ServiceEntry> = scored
            .into_iter()
            .skip(start)
            .take(page_size)
            .map(|(_, entry)| entry)
            .collect();

        Ok(PagedResult {
            items,
            total,
            page,
            page_size,
        })
    }

    pub fn config_manager(&self) -> Arc<HotConfigManager> {
        self.config.clone()
    }
}

impl Default for CatalogSearch {
    fn default() -> Self {
        Self::with_default_config()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_search_with_hot_config() {
        let config = Arc::new(HotConfigManager::new());
        let search = CatalogSearch::new(config.clone());
        
        config.update(crate::hot_config::ConfigUpdate {
            key: "max_page_size".to_string(),
            value: serde_json::json!(50),
            updated_by: "test".to_string(),
        }).unwrap();
        
        assert_eq!(config.get::<usize>("max_page_size"), Some(50));
    }
}
