use anyhow::Result;
use uuid::Uuid;
use std::sync::Arc;

use crate::dependency::DependencyGraph;
use crate::models::{
    DependencyRelation, PagedResult, ServiceEntry, ServiceSearchQuery,
};
use crate::registry::CatalogRegistry;
use crate::search::CatalogSearch;
use crate::hot_config::{HotConfigManager, ConfigUpdate, ConfigHistory};

pub fn register_service(registry: &CatalogRegistry, entry: ServiceEntry) -> Result<()> {
    registry.register(entry)
}

pub fn update_service(registry: &CatalogRegistry, id: Uuid, entry: ServiceEntry) -> Result<()> {
    registry.update(id, entry)
}

pub fn delete_service(registry: &CatalogRegistry, id: Uuid) -> Result<()> {
    registry.deregister(id)
}

pub fn get_service(registry: &CatalogRegistry, id: Uuid) -> Result<Option<ServiceEntry>> {
    registry.get_by_id(id)
}

pub fn list_services(registry: &CatalogRegistry) -> Result<Vec<ServiceEntry>> {
    registry.list_all()
}

pub fn search_services(
    search: &CatalogSearch,
    registry: &CatalogRegistry,
    query: ServiceSearchQuery,
) -> Result<PagedResult<ServiceEntry>> {
    search.search(registry, query)
}

pub fn add_dependency(graph: &DependencyGraph, rel: DependencyRelation) -> Result<()> {
    graph.add_dependency(rel)
}

pub fn remove_dependency(graph: &DependencyGraph, source: Uuid, target: Uuid) -> Result<()> {
    graph.remove_dependency(source, target)
}

pub fn get_dependencies(graph: &DependencyGraph, id: Uuid) -> Result<Vec<DependencyRelation>> {
    graph.get_dependencies(id)
}

pub fn get_dependents(graph: &DependencyGraph, id: Uuid) -> Result<Vec<DependencyRelation>> {
    graph.get_dependents(id)
}

pub fn check_cycles(graph: &DependencyGraph, config: Arc<HotConfigManager>) -> Result<Vec<Vec<Uuid>>> {
    if config.get::<bool>("enable_dependency_cycle_detection").unwrap_or(true) {
        graph.detect_cycles()
    } else {
        Ok(vec![])
    }
}

pub fn update_config(config: &HotConfigManager, update: ConfigUpdate) -> Result<()> {
    config.update(update)
}

pub fn get_config(config: &HotConfigManager) -> Result<crate::hot_config::CatalogConfig> {
    Ok(config.get_config())
}

pub fn get_config_history(config: &HotConfigManager, limit: usize) -> Result<Vec<ConfigHistory>> {
    Ok(config.get_history(limit))
}

pub fn reload_config_from_json(config: &HotConfigManager, json: &str) -> Result<()> {
    config.reload_from_json(json)
}

pub fn export_config_to_json(config: &HotConfigManager) -> Result<String> {
    config.export_to_json()
}

pub fn add_config_listener<F>(config: &HotConfigManager, listener: F) -> Uuid
where
    F: Fn(&ConfigUpdate) + Send + Sync + 'static,
{
    config.add_listener(listener)
}

