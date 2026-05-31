use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use chrono::Utc;
use serde::{Deserialize, Serialize};
use crate::feature_flags::models::{
    FeatureFlag, UserSegment, UserContext, Rule,
    CreateFlagRequest, UpdateFlagRequest, EvaluateResponse,
};
use crate::feature_flags::evaluator::RuleEvaluator;
use crate::utils::error::{Result, PlatformError};
use crate::utils::id::generate_id;
use tracing::{info, warn, error};

#[derive(Debug, Clone, Default)]
struct FeatureStoreInner {
    flags: HashMap<String, FeatureFlag>,
    segments: HashMap<String, UserSegment>,
}

#[derive(Debug, Clone, Default)]
pub struct FeatureFlagManager {
    inner: Arc<RwLock<FeatureStoreInner>>,
}

impl FeatureFlagManager {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(RwLock::new(FeatureStoreInner::default())),
        }
    }

    pub async fn create_flag(&self, req: CreateFlagRequest) -> Result<FeatureFlag> {
        info!(flag_id = %req.flag_id, "creating_feature_flag");

        let mut inner = self.inner.write().await;
        
        if inner.flags.contains_key(&req.flag_id) {
            return Err(PlatformError::Conflict(format!(
                "flag {} already exists", req.flag_id
            )));
        }

        let now = Utc::now();
        let flag = FeatureFlag {
            flag_id: req.flag_id,
            name: req.name,
            description: req.description,
            enabled: req.enabled,
            target_percentage: req.target_percentage,
            rules: req.rules,
            user_segments: req.user_segments,
            created_at: now,
            updated_at: now,
            metadata: HashMap::new(),
        };

        inner.flags.insert(flag.flag_id.clone(), flag.clone());
        
        info!(flag_id = %flag.flag_id, "feature_flag_created");
        Ok(flag)
    }

    pub async fn get_flag(&self, flag_id: &str) -> Result<FeatureFlag> {
        let inner = self.inner.read().await;
        inner.flags.get(flag_id)
            .cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("flag {} not found", flag_id)))
    }

    pub async fn list_flags(&self) -> Result<Vec<FeatureFlag>> {
        let inner = self.inner.read().await;
        Ok(inner.flags.values().cloned().collect())
    }

    pub async fn update_flag(&self, flag_id: &str, req: UpdateFlagRequest) -> Result<FeatureFlag> {
        info!(flag_id = %flag_id, "updating_feature_flag");

        let mut inner = self.inner.write().await;
        
        let flag = inner.flags.get_mut(flag_id)
            .ok_or_else(|| PlatformError::NotFound(format!("flag {} not found", flag_id)))?;

        if let Some(name) = req.name {
            flag.name = name;
        }
        if let Some(description) = req.description {
            flag.description = description;
        }
        if let Some(enabled) = req.enabled {
            flag.enabled = enabled;
        }
        if let Some(percentage) = req.target_percentage {
            flag.target_percentage = percentage.clamp(0.0, 100.0);
        }
        if let Some(rules) = req.rules {
            flag.rules = rules;
        }
        if let Some(segments) = req.user_segments {
            flag.user_segments = segments;
        }
        
        flag.updated_at = Utc::now();

        info!(flag_id = %flag_id, "feature_flag_updated");
        Ok(flag.clone())
    }

    pub async fn delete_flag(&self, flag_id: &str) -> Result<()> {
        info!(flag_id = %flag_id, "deleting_feature_flag");

        let mut inner = self.inner.write().await;
        
        if inner.flags.remove(flag_id).is_none() {
            return Err(PlatformError::NotFound(format!("flag {} not found", flag_id)));
        }

        info!(flag_id = %flag_id, "feature_flag_deleted");
        Ok(())
    }

    pub async fn evaluate(&self, flag_id: &str, user: &UserContext) -> Result<EvaluateResponse> {
        let inner = self.inner.read().await;
        
        let flag = inner.flags.get(flag_id)
            .ok_or_else(|| PlatformError::NotFound(format!("flag {} not found", flag_id)))?;

        let (enabled, matched_rules) = RuleEvaluator::evaluate_matched_rules(flag, user);

        Ok(EvaluateResponse {
            flag_id: flag_id.to_string(),
            enabled,
            value: Some(serde_json::json!({
                "enabled": enabled
            })),
            matched_rules,
        })
    }

    pub async fn evaluate_all(&self, user: &UserContext) -> Result<HashMap<String, bool>> {
        let inner = self.inner.read().await;
        
        let mut results = HashMap::new();
        for (flag_id, flag) in &inner.flags {
            let enabled = RuleEvaluator::evaluate(flag, user);
            results.insert(flag_id.clone(), enabled);
        }

        Ok(results)
    }

    pub async fn create_segment(&self, segment_id: String, name: String) -> Result<UserSegment> {
        info!(segment_id = %segment_id, "creating_user_segment");

        let mut inner = self.inner.write().await;
        
        if inner.segments.contains_key(&segment_id) {
            return Err(PlatformError::Conflict(format!(
                "segment {} already exists", segment_id
            )));
        }

        let segment = UserSegment::new(segment_id, name);
        inner.segments.insert(segment.segment_id.clone(), segment.clone());

        info!(segment_id = %segment.segment_id, "user_segment_created");
        Ok(segment)
    }

    pub async fn get_segment(&self, segment_id: &str) -> Result<UserSegment> {
        let inner = self.inner.read().await;
        inner.segments.get(segment_id)
            .cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("segment {} not found", segment_id)))
    }

    pub async fn list_segments(&self) -> Result<Vec<UserSegment>> {
        let inner = self.inner.read().await;
        Ok(inner.segments.values().cloned().collect())
    }

    pub async fn add_user_to_segment(&self, segment_id: &str, user_id: &str) -> Result<()> {
        info!(segment_id = %segment_id, user_id = %user_id, "adding_user_to_segment");

        let mut inner = self.inner.write().await;
        
        let segment = inner.segments.get_mut(segment_id)
            .ok_or_else(|| PlatformError::NotFound(format!("segment {} not found", segment_id)))?;

        if !segment.user_ids.contains(&user_id.to_string()) {
            segment.user_ids.push(user_id.to_string());
        }

        info!(segment_id = %segment_id, user_id = %user_id, "user_added_to_segment");
        Ok(())
    }

    pub async fn remove_user_from_segment(&self, segment_id: &str, user_id: &str) -> Result<()> {
        info!(segment_id = %segment_id, user_id = %user_id, "removing_user_from_segment");

        let mut inner = self.inner.write().await;
        
        let segment = inner.segments.get_mut(segment_id)
            .ok_or_else(|| PlatformError::NotFound(format!("segment {} not found", segment_id)))?;

        segment.user_ids.retain(|id| id != user_id);

        info!(segment_id = %segment_id, user_id = %user_id, "user_removed_from_segment");
        Ok(())
    }

    pub async fn delete_segment(&self, segment_id: &str) -> Result<()> {
        info!(segment_id = %segment_id, "deleting_user_segment");

        let mut inner = self.inner.write().await;
        
        if inner.segments.remove(segment_id).is_none() {
            return Err(PlatformError::NotFound(format!("segment {} not found", segment_id)));
        }

        info!(segment_id = %segment_id, "user_segment_deleted");
        Ok(())
    }
}
