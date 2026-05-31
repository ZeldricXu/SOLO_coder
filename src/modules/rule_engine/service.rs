use std::sync::Arc;
use dashmap::DashMap;
use serde_json::json;
use tracing::{info, warn, debug, error};
use chrono::{Utc, Duration};

use crate::common::error::{AppError, AppResult};
use crate::common::context::RequestContext;
use crate::common::event::DomainEvent;
use crate::common::context::AuditLogger;
use crate::common::metrics::MetricsCollector;
use crate::ports::mod::EventPublisherPort;
use crate::ports::mod::NotificationPort;
use super::model::{
    Rule, CreateRuleRequest, UpdateRuleRequest, DataPoint, TriggerEvent,
    ActionExecutionRecord, RuleTriggerHistory, RuleResponse, RuleStatus,
    EvaluationResult, ActionType, PendingRecovery, RecoveryEvent,
    CircuitBreakerStatus, RecoveryStats, FailureRecoveryStrategy,
};

pub struct RuleEngineService {
    rules: Arc<DashMap<String, Rule>>,
    trigger_history: Arc<DashMap<String, RuleTriggerHistory>>,
    pending_recoveries: Arc<DashMap<String, PendingRecovery>>,
    recovery_events: Arc<DashMap<String, RecoveryEvent>>,
    recovery_times: Arc<DashMap<String, Vec<u64>>>,
    event_publisher: Arc<dyn EventPublisherPort>,
    notification_port: Arc<dyn NotificationPort>,
    audit_logger: Arc<AuditLogger>,
    metrics: MetricsCollector,
}

impl RuleEngineService {
    pub fn new(
        event_publisher: Arc<dyn EventPublisherPort>,
        notification_port: Arc<dyn NotificationPort>,
        audit_logger: Arc<AuditLogger>,
    ) -> Arc<Self> {
        Arc::new(Self {
            rules: Arc::new(DashMap::new()),
            trigger_history: Arc::new(DashMap::new()),
            pending_recoveries: Arc::new(DashMap::new()),
            recovery_events: Arc::new(DashMap::new()),
            recovery_times: Arc::new(DashMap::new()),
            event_publisher,
            notification_port,
            audit_logger,
            metrics: MetricsCollector::new().with_dimension("module", "rule_engine"),
        })
    }

    pub async fn create_rule(&self, ctx: &RequestContext, req: CreateRuleRequest) -> AppResult<RuleResponse> {
        let start = std::time::Instant::now();
        debug!(name = %req.name, source = %req.source, "Creating rule");

        self.validate_create_request(&req)?;

        let created_by = ctx.auth.as_ref()
            .map(|a| a.device_id.clone())
            .unwrap_or_else(|| "system".into());

        let rule = Rule::new(req, created_by);
        let rule_id = rule.rule_id.clone();
        let rule_response: RuleResponse = rule.clone().into();

        self.rules.insert(rule_id.clone(), rule);

        let event = DomainEvent::new(
            "rule.created",
            &rule_id,
            json!({
                "rule_id": rule_id,
                "name": rule_response.name,
                "source": rule_response.source,
                "priority": rule_response.priority,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "rule.create",
            "rule_engine",
            &rule_id,
            true,
            json!({ "name": rule_response.name, "source": rule_response.source }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        info!(rule_id = %rule_id, name = %rule_response.name, "Rule created successfully");
        Ok(rule_response)
    }

    pub async fn get_rule(&self, ctx: &RequestContext, rule_id: &str) -> AppResult<RuleResponse> {
        let start = std::time::Instant::now();
        debug!(rule_id = %rule_id, "Getting rule");

        let rule = self.rules.get(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "rule.get",
            "rule_engine",
            rule_id,
            true,
            json!({ "name": rule.name }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(rule.clone().into())
    }

    pub async fn list_rules(
        &self,
        page: u32,
        page_size: u32,
        source: Option<String>,
        status: Option<RuleStatus>,
    ) -> AppResult<(Vec<RuleResponse>, u64)> {
        let start = std::time::Instant::now();

        let mut items: Vec<RuleResponse> = self.rules.iter()
            .filter(|r| {
                let source_match = source.as_ref()
                    .map(|s| r.source == *s)
                    .unwrap_or(true);
                let status_match = status.as_ref()
                    .map(|s| r.status == *s)
                    .unwrap_or(true);
                source_match && status_match
            })
            .map(|r| r.clone().into())
            .collect();

        items.sort_by(|a, b| b.priority.cmp(&a.priority));

        let total = items.len() as u64;
        let start_idx = ((page - 1) * page_size) as usize;
        let end_idx = (start_idx + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start_idx).take(end_idx - start_idx).collect();

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok((paginated, total))
    }

    pub async fn update_rule(
        &self,
        ctx: &RequestContext,
        rule_id: &str,
        req: UpdateRuleRequest,
    ) -> AppResult<RuleResponse> {
        let start = std::time::Instant::now();
        debug!(rule_id = %rule_id, "Updating rule");

        let mut rule = self.rules.get_mut(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;

        if let Some(name) = req.name {
            if name.is_empty() {
                return Err(AppError::Validation("规则名称不能为空".into()));
            }
            rule.name = name;
        }
        if let Some(description) = req.description {
            rule.description = Some(description);
        }
        if let Some(condition) = req.condition {
            rule.condition = condition;
        }
        if let Some(actions) = req.actions {
            if actions.is_empty() {
                return Err(AppError::Validation("规则动作不能为空".into()));
            }
            rule.actions = actions;
        }
        if let Some(priority) = req.priority {
            rule.priority = priority;
        }
        if let Some(trigger_limit) = req.trigger_limit {
            rule.trigger_limit = Some(trigger_limit);
        }
        if let Some(cooldown_seconds) = req.cooldown_seconds {
            rule.cooldown_seconds = Some(cooldown_seconds);
        }
        if let Some(recovery_config) = req.recovery_config {
            rule.recovery_config = recovery_config;
        }
        rule.updated_at = Utc::now();

        let response: RuleResponse = rule.clone().into();

        let event = DomainEvent::new(
            "rule.updated",
            rule_id,
            json!({
                "rule_id": rule_id,
                "name": response.name,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "rule.update",
            "rule_engine",
            rule_id,
            true,
            json!({ "name": response.name }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        info!(rule_id = %rule_id, "Rule updated successfully");
        Ok(response)
    }

    pub async fn enable_rule(&self, ctx: &RequestContext, rule_id: &str) -> AppResult<RuleResponse> {
        let start = std::time::Instant::now();
        debug!(rule_id = %rule_id, "Enabling rule");

        let mut rule = self.rules.get_mut(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;

        rule.status = RuleStatus::Enabled;
        rule.updated_at = Utc::now();
        let response: RuleResponse = rule.clone().into();

        let event = DomainEvent::new(
            "rule.enabled",
            rule_id,
            json!({ "rule_id": rule_id, "name": response.name }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "rule.enable",
            "rule_engine",
            rule_id,
            true,
            json!({}),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);
        Ok(response)
    }

    pub async fn disable_rule(&self, ctx: &RequestContext, rule_id: &str) -> AppResult<RuleResponse> {
        let start = std::time::Instant::now();
        debug!(rule_id = %rule_id, "Disabling rule");

        let mut rule = self.rules.get_mut(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;

        rule.status = RuleStatus::Disabled;
        rule.updated_at = Utc::now();
        let response: RuleResponse = rule.clone().into();

        let event = DomainEvent::new(
            "rule.disabled",
            rule_id,
            json!({ "rule_id": rule_id, "name": response.name }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "rule.disable",
            "rule_engine",
            rule_id,
            true,
            json!({}),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);
        Ok(response)
    }

    pub async fn delete_rule(&self, ctx: &RequestContext, rule_id: &str) -> AppResult<()> {
        let start = std::time::Instant::now();
        debug!(rule_id = %rule_id, "Deleting rule");

        let (_, rule) = self.rules.remove(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;

        let event = DomainEvent::new(
            "rule.deleted",
            rule_id,
            json!({ "rule_id": rule_id, "name": rule.name }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "rule.delete",
            "rule_engine",
            rule_id,
            true,
            json!({ "name": rule.name }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        info!(rule_id = %rule_id, "Rule deleted successfully");
        Ok(())
    }

    pub async fn process_data_point(
        &self,
        ctx: &RequestContext,
        data_point: DataPoint,
    ) -> AppResult<Vec<TriggerEvent>> {
        let start = std::time::Instant::now();
        debug!(source = %data_point.source, "Processing data point");

        let mut triggered_events = Vec::new();
        let mut evaluation_results = Vec::new();

        let mut applicable_rules: Vec<Rule> = self.rules.iter()
            .filter(|r| r.source == data_point.source)
            .map(|r| r.clone())
            .collect();
        applicable_rules.sort_by(|a, b| b.priority.cmp(&a.priority));

        for mut rule in applicable_rules {
            if rule.try_half_open() {
                info!(rule_id = %rule.rule_id, "Circuit breaker transitioning to half-open state");
            }

            let (can_trigger, skip_reason) = rule.can_trigger();
            if !can_trigger {
                evaluation_results.push(EvaluationResult {
                    rule_id: rule.rule_id.clone(),
                    rule_name: rule.name.clone(),
                    matched: false,
                    matched_conditions: Vec::new(),
                    skipped_reason: skip_reason,
                });
                continue;
            }

            let (matched, matched_conditions) = rule.condition.evaluate(&data_point.data);
            if matched {
                rule.mark_triggered();

                let trigger_event = TriggerEvent::new(&rule, data_point.clone(), matched_conditions.clone());
                triggered_events.push(trigger_event.clone());

                let event = DomainEvent::new(
                    "rule.triggered",
                    &rule.rule_id,
                    json!({
                        "rule_id": rule.rule_id,
                        "rule_name": rule.name,
                        "source": data_point.source,
                        "matched_conditions": matched_conditions,
                        "event_id": trigger_event.event_id,
                    }),
                    &ctx.trace_id,
                );
                self.event_publisher.publish(event).await?;

                let action_results = self.execute_actions_with_recovery(ctx, &mut rule, &trigger_event).await;
                self.record_trigger_history(trigger_event.clone(), action_results);

                self.rules.insert(rule.rule_id.clone(), rule);

                evaluation_results.push(EvaluationResult {
                    rule_id: rule.rule_id.clone(),
                    rule_name: rule.name.clone(),
                    matched: true,
                    matched_conditions,
                    skipped_reason: None,
                });

                info!(
                    rule_id = %rule.rule_id,
                    rule_name = %rule.name,
                    event_id = %trigger_event.event_id,
                    "Rule triggered successfully"
                );
            } else {
                evaluation_results.push(EvaluationResult {
                    rule_id: rule.rule_id.clone(),
                    rule_name: rule.name.clone(),
                    matched: false,
                    matched_conditions: Vec::new(),
                    skipped_reason: Some("条件不匹配".into()),
                });
            }
        }

        self.audit_logger.log_operation(
            ctx,
            "rule.evaluate",
            "rule_engine",
            &data_point.source,
            true,
            json!({
                "source": data_point.source,
                "triggered_count": triggered_events.len(),
                "evaluated_count": evaluation_results.len(),
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(triggered_events)
    }

    async fn execute_actions_with_recovery(
        &self,
        ctx: &RequestContext,
        rule: &mut Rule,
        event: &TriggerEvent,
    ) -> Vec<ActionExecutionRecord> {
        let mut results = Vec::new();

        for action in &event.actions {
            let result = self.execute_single_action_with_recovery(ctx, rule, event, action).await;
            results.push(result);
        }

        results
    }

    async fn execute_single_action_with_recovery(
        &self,
        ctx: &RequestContext,
        rule: &mut Rule,
        event: &TriggerEvent,
        action: &super::model::RuleAction,
    ) -> ActionExecutionRecord {
        let start = std::time::Instant::now();
        debug!(
            event_id = %event.event_id,
            rule_id = %event.rule_id,
            action_type = ?action.action_type,
            "Executing action with recovery"
        );

        let result = match action.action_type {
            ActionType::SendCommand => {
                self.execute_send_command(&action.target, &action.parameters).await
            }
            ActionType::Alert => {
                self.execute_alert(&action.target, &action.parameters).await
            }
            ActionType::TriggerWorkflow => {
                self.execute_trigger_workflow(&action.target, &action.parameters).await
            }
        };

        match result {
            Ok(res) => {
                rule.record_success();
                
                let action_executed_event = DomainEvent::new(
                    "rule.action.executed",
                    &event.rule_id,
                    json!({
                        "event_id": event.event_id,
                        "action_type": format!("{:?}", action.action_type),
                        "target": action.target,
                        "success": true,
                        "result": res,
                    }),
                    &ctx.trace_id,
                );
                if let Err(e) = self.event_publisher.publish(action_executed_event).await {
                    error!(error = %e, "Failed to publish action executed event");
                }

                info!(
                    event_id = %event.event_id,
                    action_type = ?action.action_type,
                    latency_ms = start.elapsed().as_millis(),
                    "Action executed successfully"
                );

                ActionExecutionRecord::success(&event.event_id, &event.rule_id, action.clone(), res)
            }
            Err(e) => {
                error!(
                    event_id = %event.event_id,
                    rule_id = %event.rule_id,
                    action_type = ?action.action_type,
                    error = %e,
                    "Action execution failed, attempting recovery"
                );

                let recovery_start = std::time::Instant::now();
                let recovery_result = self.try_recover(ctx, rule, event, action, &e.to_string()).await;
                let recovery_time_ms = recovery_start.elapsed().as_millis() as u64;
                self.record_recovery_time(&event.rule_id, recovery_time_ms);

                match recovery_result {
                    RecoveryOutcome::Success(record) => {
                        info!(
                            event_id = %event.event_id,
                            recovery_time_ms = recovery_time_ms,
                            "Action recovered successfully"
                        );
                        record
                    }
                    RecoveryOutcome::Fallback(record) => {
                        warn!(
                            event_id = %event.event_id,
                            recovery_time_ms = recovery_time_ms,
                            "Action executed fallback action"
                        );
                        record
                    }
                    RecoveryOutcome::Failed(record) => {
                        error!(
                            event_id = %event.event_id,
                            recovery_time_ms = recovery_time_ms,
                            "Action recovery failed"
                        );
                        record
                    }
                }
            }
        }
    }

    async fn try_recover(
        &self,
        ctx: &RequestContext,
        rule: &mut Rule,
        event: &TriggerEvent,
        action: &super::model::RuleAction,
        error: &str,
    ) -> RecoveryOutcome {
        rule.record_failure(error);

        match rule.recovery_config.strategy {
            FailureRecoveryStrategy::None => {
                RecoveryOutcome::Failed(
                    ActionExecutionRecord::failure(&event.event_id, &event.rule_id, action.clone(), error)
                )
            }
            FailureRecoveryStrategy::Retry => {
                self.try_retry(ctx, rule, event, action, error, 1).await
            }
            FailureRecoveryStrategy::CircuitBreaker => {
                self.handle_circuit_breaker(ctx, rule, event, action, error).await
            }
            FailureRecoveryStrategy::FallbackAction => {
                self.try_fallback(ctx, rule, event, action, error).await
            }
            FailureRecoveryStrategy::RetryWithFallback => {
                match self.try_retry(ctx, rule, event, action, error, 1).await {
                    RecoveryOutcome::Success(record) => RecoveryOutcome::Success(record),
                    _ => self.try_fallback(ctx, rule, event, action, error).await,
                }
            }
        }
    }

    async fn try_retry(
        &self,
        ctx: &RequestContext,
        rule: &mut Rule,
        event: &TriggerEvent,
        action: &super::model::RuleAction,
        original_error: &str,
        attempt: u32,
    ) -> RecoveryOutcome {
        let max_attempts = rule.recovery_config.max_retry_attempts.unwrap_or(3);
        
        if attempt > max_attempts {
            warn!(
                rule_id = %rule.rule_id,
                event_id = %event.event_id,
                max_attempts = max_attempts,
                "Max retry attempts reached"
            );
            return RecoveryOutcome::Failed(
                ActionExecutionRecord::failure(&event.event_id, &event.rule_id, action.clone(), 
                    format!("重试 {} 次后仍然失败: {}", max_attempts, original_error))
            );
        }

        if let Some(delay) = rule.recovery_config.retry_delay_seconds {
            tokio::time::sleep(tokio::time::Duration::from_secs(delay)).await;
        }

        rule.failure_info.retry_attempts = attempt;

        debug!(
            rule_id = %rule.rule_id,
            event_id = %event.event_id,
            attempt = attempt,
            max_attempts = max_attempts,
            "Retrying action execution"
        );

        let result = match action.action_type {
            ActionType::SendCommand => {
                self.execute_send_command(&action.target, &action.parameters).await
            }
            ActionType::Alert => {
                self.execute_alert(&action.target, &action.parameters).await
            }
            ActionType::TriggerWorkflow => {
                self.execute_trigger_workflow(&action.target, &action.parameters).await
            }
        };

        match result {
            Ok(res) => {
                rule.record_success();
                self.record_recovery_event(rule, event, "retry", original_error, "success", attempt);
                
                let mut record = ActionExecutionRecord::success(&event.event_id, &event.rule_id, action.clone(), res);
                record = record.with_recovery("retry", attempt);
                RecoveryOutcome::Success(record)
            }
            Err(e) => {
                Box::pin(self.try_retry(ctx, rule, event, action, &e.to_string(), attempt + 1)).await
            }
        }
    }

    async fn handle_circuit_breaker(
        &self,
        ctx: &RequestContext,
        rule: &mut Rule,
        event: &TriggerEvent,
        action: &super::model::RuleAction,
        error: &str,
    ) -> RecoveryOutcome {
        if rule.failure_info.circuit_breaker_state == super::model::CircuitBreakerState::Open {
            return RecoveryOutcome::Failed(
                ActionExecutionRecord::failure(&event.event_id, &event.rule_id, action.clone(), 
                    "断路器已打开，请求被阻止")
            );
        }

        if let (Some(threshold), Some(reset_seconds)) = (
            rule.recovery_config.circuit_breaker_threshold,
            rule.recovery_config.circuit_breaker_reset_seconds,
        ) {
            if rule.failure_info.consecutive_failures >= threshold {
                rule.open_circuit_breaker();
                info!(
                    rule_id = %rule.rule_id,
                    threshold = threshold,
                    reset_seconds = reset_seconds,
                    "Circuit breaker opened"
                );
                self.record_recovery_event(rule, event, "circuit_breaker_open", error, "opened", 0);
            }
        }

        RecoveryOutcome::Failed(
            ActionExecutionRecord::failure(&event.event_id, &event.rule_id, action.clone(), error)
        )
    }

    async fn try_fallback(
        &self,
        ctx: &RequestContext,
        rule: &mut Rule,
        event: &TriggerEvent,
        action: &super::model::RuleAction,
        original_error: &str,
    ) -> RecoveryOutcome {
        if let Some(fallback_action) = &rule.recovery_config.fallback_action {
            debug!(
                rule_id = %rule.rule_id,
                event_id = %event.event_id,
                "Executing fallback action"
            );

            let result = match fallback_action.action_type {
                ActionType::SendCommand => {
                    self.execute_send_command(&fallback_action.target, &fallback_action.parameters).await
                }
                ActionType::Alert => {
                    self.execute_alert(&fallback_action.target, &fallback_action.parameters).await
                }
                ActionType::TriggerWorkflow => {
                    self.execute_trigger_workflow(&fallback_action.target, &fallback_action.parameters).await
                }
            };

            match result {
                Ok(res) => {
                    self.record_recovery_event(rule, event, "fallback", original_error, "executed", 0);
                    
                    let mut record = ActionExecutionRecord::success(
                        &event.event_id, 
                        &event.rule_id, 
                        action.clone(), 
                        res
                    );
                    record = record.with_recovery("fallback", 0);
                    RecoveryOutcome::Fallback(record)
                }
                Err(e) => {
                    error!(
                        rule_id = %rule.rule_id,
                        fallback_error = %e,
                        "Fallback action also failed"
                    );
                    RecoveryOutcome::Failed(
                        ActionExecutionRecord::failure(&event.event_id, &event.rule_id, action.clone(), 
                            format!("原始错误: {}, 回退也失败: {}", original_error, e))
                    )
                }
            }
        } else {
            RecoveryOutcome::Failed(
                ActionExecutionRecord::failure(&event.event_id, &event.rule_id, action.clone(), 
                    format!("未配置回退动作: {}", original_error))
            )
        }
    }

    fn record_recovery_event(
        &self,
        rule: &mut Rule,
        event: &TriggerEvent,
        recovery_type: &str,
        original_error: &str,
        recovery_result: &str,
        retry_attempt: u32,
    ) {
        let recovery_event = RecoveryEvent {
            event_id: event.event_id.clone(),
            rule_id: rule.rule_id.clone(),
            rule_name: rule.name.clone(),
            recovery_type: recovery_type.to_string(),
            original_error: original_error.to_string(),
            recovery_result: recovery_result.to_string(),
            retry_attempt,
            recovered_at: Utc::now(),
        };
        self.recovery_events.insert(event.event_id.clone(), recovery_event);
    }

    fn record_recovery_time(&self, rule_id: &str, time_ms: u64) {
        let mut times = self.recovery_times
            .entry(rule_id.to_string())
            .or_insert_with(Vec::new);
        times.push(time_ms);
        if times.len() > 100 {
            times.remove(0);
        }
    }

    async fn execute_send_command(
        &self,
        target: &str,
        parameters: &serde_json::Value,
    ) -> AppResult<Option<serde_json::Value>> {
        self.notification_port
            .send_device_command(target, parameters.clone())
            .await?;
        Ok(Some(json!({ "target": target, "status": "sent" })))
    }

    async fn execute_alert(
        &self,
        target: &str,
        parameters: &serde_json::Value,
    ) -> AppResult<Option<serde_json::Value>> {
        let level = parameters.get("level")
            .and_then(|v| v.as_str())
            .unwrap_or("info");
        let title = parameters.get("title")
            .and_then(|v| v.as_str())
            .unwrap_or("规则告警");
        let message = parameters.get("message")
            .and_then(|v| v.as_str())
            .unwrap_or(target);

        self.notification_port
            .send_alert(level, title, message)
            .await?;
        Ok(Some(json!({ "level": level, "title": title, "target": target })))
    }

    async fn execute_trigger_workflow(
        &self,
        target: &str,
        parameters: &serde_json::Value,
    ) -> AppResult<Option<serde_json::Value>> {
        let event = DomainEvent::new(
            "workflow.triggered",
            target,
            json!({
                "workflow_id": target,
                "parameters": parameters,
            }),
            "system",
        );
        self.event_publisher.publish(event).await?;
        Ok(Some(json!({ "workflow_id": target, "status": "triggered" })))
    }

    fn record_trigger_history(
        &self,
        event: TriggerEvent,
        action_results: Vec<ActionExecutionRecord>,
    ) {
        let history = RuleTriggerHistory {
            event: event.clone(),
            action_results,
        };
        self.trigger_history.insert(event.event_id.clone(), history);
    }

    pub async fn get_trigger_history(
        &self,
        rule_id: Option<String>,
        page: u32,
        page_size: u32,
    ) -> AppResult<(Vec<RuleTriggerHistory>, u64)> {
        let start = std::time::Instant::now();

        let mut items: Vec<RuleTriggerHistory> = self.trigger_history.iter()
            .filter(|h| {
                rule_id.as_ref()
                    .map(|rid| h.event.rule_id == *rid)
                    .unwrap_or(true)
            })
            .map(|h| h.clone())
            .collect();

        items.sort_by(|a, b| b.event.triggered_at.cmp(&a.event.triggered_at));

        let total = items.len() as u64;
        let start_idx = ((page - 1) * page_size) as usize;
        let end_idx = (start_idx + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start_idx).take(end_idx - start_idx).collect();

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok((paginated, total))
    }

    pub async fn evaluate_data_point(
        &self,
        data_point: DataPoint,
    ) -> AppResult<Vec<EvaluationResult>> {
        let start = std::time::Instant::now();

        let mut results = Vec::new();
        let mut applicable_rules: Vec<Rule> = self.rules.iter()
            .filter(|r| r.source == data_point.source)
            .map(|r| r.clone())
            .collect();
        applicable_rules.sort_by(|a, b| b.priority.cmp(&a.priority));

        for rule in applicable_rules {
            let (can_trigger, skip_reason) = rule.can_trigger();
            if !can_trigger {
                results.push(EvaluationResult {
                    rule_id: rule.rule_id.clone(),
                    rule_name: rule.name.clone(),
                    matched: false,
                    matched_conditions: Vec::new(),
                    skipped_reason: skip_reason,
                });
                continue;
            }

            let (matched, matched_conditions) = rule.condition.evaluate(&data_point.data);
            results.push(EvaluationResult {
                rule_id: rule.rule_id.clone(),
                rule_name: rule.name.clone(),
                matched,
                matched_conditions,
                skipped_reason: None,
            });
        }

        self.metrics.record_success(start.elapsed().as_millis() as u64);
        Ok(results)
    }

    fn validate_create_request(&self, req: &CreateRuleRequest) -> AppResult<()> {
        if req.name.is_empty() {
            return Err(AppError::Validation("规则名称不能为空".into()));
        }
        if req.source.is_empty() {
            return Err(AppError::Validation("数据源不能为空".into()));
        }
        let has_conditions = !req.condition.conditions.is_empty()
            || req.condition.groups.as_ref().map(|g| !g.is_empty()).unwrap_or(false);
        if !has_conditions {
            return Err(AppError::Validation("至少需要一个条件".into()));
        }
        if req.actions.is_empty() {
            return Err(AppError::Validation("至少需要一个动作".into()));
        }
        Ok(())
    }

    pub fn get_metrics(&self) -> crate::common::metrics::StatsSnapshot {
        self.metrics.snapshot()
    }

    pub fn get_stats(&self) -> serde_json::Value {
        let total_rules = self.rules.len();
        let enabled_rules = self.rules.iter().filter(|r| r.status == RuleStatus::Enabled).count();
        let disabled_rules = total_rules - enabled_rules;
        let total_triggers = self.trigger_history.len();
        let open_circuit_breakers = self.rules.iter()
            .filter(|r| r.failure_info.circuit_breaker_state == super::model::CircuitBreakerState::Open)
            .count();
        let pending_recoveries = self.pending_recoveries.len();

        json!({
            "total_rules": total_rules,
            "enabled_rules": enabled_rules,
            "disabled_rules": disabled_rules,
            "total_triggers": total_triggers,
            "open_circuit_breakers": open_circuit_breakers,
            "pending_recoveries": pending_recoveries,
        })
    }

    pub fn get_circuit_breaker_status(&self, rule_id: &str) -> AppResult<CircuitBreakerStatus> {
        let rule = self.rules.get(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;
        
        Ok(rule.get_circuit_breaker_status())
    }

    pub fn get_all_circuit_breaker_statuses(&self) -> Vec<CircuitBreakerStatus> {
        self.rules.iter()
            .map(|r| r.get_circuit_breaker_status())
            .collect()
    }

    pub fn get_recovery_stats(&self, rule_id: Option<&str>) -> RecoveryStats {
        let mut stats = RecoveryStats::default();
        let recovery_times: Vec<u64> = match rule_id {
            Some(rid) => self.recovery_times.get(rid).map(|t| t.clone()).unwrap_or_default(),
            None => self.recovery_times.iter().flat_map(|t| t.value().clone()).collect(),
        };

        if !recovery_times.is_empty() {
            stats.avg_recovery_time_ms = Some(
                recovery_times.iter().sum::<u64>() as f64 / recovery_times.len() as f64
            );
        }

        let events: Vec<RecoveryEvent> = match rule_id {
            Some(rid) => self.recovery_events.iter()
                .filter(|e| e.rule_id == rid)
                .map(|e| e.clone())
                .collect(),
            None => self.recovery_events.iter().map(|e| e.clone()).collect(),
        };

        for event in &events {
            match event.recovery_type.as_str() {
                "retry" => {
                    stats.total_retries += 1;
                    if event.recovery_result == "success" {
                        stats.successful_recoveries += 1;
                    }
                }
                "fallback" => {
                    stats.fallback_executions += 1;
                    if event.recovery_result == "executed" {
                        stats.successful_recoveries += 1;
                    }
                }
                "circuit_breaker_open" => {
                    stats.circuit_breaker_ops += 1;
                }
                _ => {}
            }
            stats.total_recoveries += 1;
        }

        let failures: Vec<_> = match rule_id {
            Some(rid) => self.rules.get(rid).map(|r| vec![r.failure_info.failure_count]).unwrap_or_default(),
            None => self.rules.iter().map(|r| r.failure_info.failure_count).collect(),
        };
        stats.total_failures = failures.iter().sum();

        stats
    }

    pub async fn reset_circuit_breaker(&self, rule_id: &str) -> AppResult<()> {
        let mut rule = self.rules.get_mut(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;
        
        rule.failure_info.circuit_breaker_state = super::model::CircuitBreakerState::Closed;
        rule.failure_info.circuit_breaker_opened_at = None;
        rule.failure_info.consecutive_failures = 0;
        rule.updated_at = Utc::now();
        
        info!(rule_id = %rule_id, "Circuit breaker manually reset");
        Ok(())
    }

    pub fn get_pending_recoveries(&self, rule_id: Option<&str>) -> Vec<PendingRecovery> {
        self.pending_recoveries.iter()
            .filter(|p| {
                rule_id.map(|rid| p.rule_id == rid).unwrap_or(true)
            })
            .map(|p| p.clone())
            .collect()
    }

    pub async fn process_pending_recoveries(&self) {
        let now = Utc::now();
        let to_process: Vec<PendingRecovery> = self.pending_recoveries.iter()
            .filter(|p| p.next_retry_at <= now)
            .map(|p| p.clone())
            .collect();

        for pending in to_process {
            self.pending_recoveries.remove(&pending.event_id);
            debug!(event_id = %pending.event_id, "Processing pending recovery");
        }
    }
}

enum RecoveryOutcome {
    Success(ActionExecutionRecord),
    Fallback(ActionExecutionRecord),
    Failed(ActionExecutionRecord),
}

