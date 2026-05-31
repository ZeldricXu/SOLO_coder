use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use std::sync::{Arc, Mutex};
use chrono::{DateTime, Utc, Duration};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum CommandType {
    Create,
    Read,
    Update,
    Delete,
    Execute,
    Query,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum CommandStatus {
    Pending,
    Executing,
    Completed,
    Failed,
    RolledBack,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum AuditEventType {
    CommandExecuted,
    AccessGranted,
    AccessDenied,
    ConfigChanged,
    Login,
    Logout,
    DataAccessed,
    DataModified,
    Exception,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CqrsCommand {
    pub id: String,
    pub command_type: CommandType,
    pub aggregate_id: String,
    pub aggregate_type: String,
    pub payload: serde_json::Value,
    pub metadata: HashMap<String, String>,
    pub status: CommandStatus,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub executed_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_message: Option<String>,
    pub version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditLogEntry {
    pub id: String,
    pub event_type: AuditEventType,
    pub user_id: String,
    pub user_name: String,
    pub resource_type: String,
    pub resource_id: Option<String>,
    pub action: String,
    pub result: String,
    pub ip_address: Option<String>,
    pub user_agent: Option<String>,
    pub command_id: Option<String>,
    pub timestamp: DateTime<Utc>,
    pub details: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ComplianceReport {
    pub id: String,
    pub name: String,
    pub report_type: String,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    pub generated_at: DateTime<Utc>,
    pub generated_by: String,
    pub total_commands: u32,
    pub successful_commands: u32,
    pub failed_commands: u32,
    pub total_audit_logs: u32,
    pub access_denied_count: u32,
    pub data_modified_count: u32,
    pub summary: String,
    pub format: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditFilter {
    pub user_id: Option<String>,
    pub event_type: Option<AuditEventType>,
    pub resource_type: Option<String>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub action: Option<String>,
    pub result: Option<String>,
}

#[derive(Debug, Clone)]
pub struct AuditManager {
    commands: Arc<Mutex<HashMap<String, CqrsCommand>>>,
    audit_logs: Arc<Mutex<Vec<AuditLogEntry>>>,
    reports: Arc<Mutex<HashMap<String, ComplianceReport>>>,
    command_events: Arc<Mutex<HashMap<String, Vec<AuditLogEntry>>>>,
}

impl AuditManager {
    pub fn new() -> Self {
        Self {
            commands: Arc::new(Mutex::new(HashMap::new())),
            audit_logs: Arc::new(Mutex::new(Vec::new())),
            reports: Arc::new(Mutex::new(HashMap::new())),
            command_events: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn create_command(
        &self,
        command_type: CommandType,
        aggregate_id: &str,
        aggregate_type: &str,
        payload: serde_json::Value,
        created_by: &str,
    ) -> CqrsCommand {
        let id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let command = CqrsCommand {
            id: id.clone(),
            command_type,
            aggregate_id: aggregate_id.to_string(),
            aggregate_type: aggregate_type.to_string(),
            payload,
            metadata: HashMap::new(),
            status: CommandStatus::Pending,
            created_by: created_by.to_string(),
            created_at: now,
            executed_at: None,
            completed_at: None,
            error_message: None,
            version: 1,
        };

        let mut commands = self.commands.lock().unwrap();
        commands.insert(id.clone(), command.clone());

        self.log_audit(
            AuditEventType::CommandExecuted,
            created_by,
            created_by,
            "command",
            Some(&id),
            "create",
            "success",
            None,
            None,
            Some(id.clone()),
            HashMap::new(),
        );

        command
    }

    pub fn start_command(&self, command_id: &str) -> Result<CqrsCommand, String> {
        let mut commands = self.commands.lock().unwrap();
        let command = commands.get_mut(command_id)
            .ok_or_else(|| "Command not found".to_string())?;

        if command.status != CommandStatus::Pending {
            return Err("Command already started".to_string());
        }

        command.status = CommandStatus::Executing;
        command.executed_at = Some(Utc::now());
        Ok(command.clone())
    }

    pub fn complete_command(&self, command_id: &str, result_metadata: HashMap<String, String>) -> Result<CqrsCommand, String> {
        let mut commands = self.commands.lock().unwrap();
        let command = commands.get_mut(command_id)
            .ok_or_else(|| "Command not found".to_string())?;

        if command.status != CommandStatus::Executing {
            return Err("Command not executing".to_string());
        }

        command.status = CommandStatus::Completed;
        command.completed_at = Some(Utc::now());
        command.metadata.extend(result_metadata);
        Ok(command.clone())
    }

    pub fn fail_command(&self, command_id: &str, error_message: &str) -> Result<CqrsCommand, String> {
        let mut commands = self.commands.lock().unwrap();
        let command = commands.get_mut(command_id)
            .ok_or_else(|| "Command not found".to_string())?;

        command.status = CommandStatus::Failed;
        command.completed_at = Some(Utc::now());
        command.error_message = Some(error_message.to_string());
        Ok(command.clone())
    }

    pub fn rollback_command(&self, command_id: &str, reason: &str) -> Result<CqrsCommand, String> {
        let mut commands = self.commands.lock().unwrap();
        let command = commands.get_mut(command_id)
            .ok_or_else(|| "Command not found".to_string())?;

        command.status = CommandStatus::RolledBack;
        command.error_message = Some(format!("Rolled back: {}", reason));
        Ok(command.clone())
    }

    pub fn get_command(&self, command_id: &str) -> Option<CqrsCommand> {
        let commands = self.commands.lock().unwrap();
        commands.get(command_id).cloned()
    }

    pub fn list_commands(&self, aggregate_id: Option<&str>) -> Vec<CqrsCommand> {
        let commands = self.commands.lock().unwrap();
        commands.values()
            .filter(|c| aggregate_id.map_or(true, |aid| c.aggregate_id == aid))
            .cloned()
            .collect()
    }

    pub fn get_command_history(&self, aggregate_id: &str) -> Vec<CqrsCommand> {
        let commands = self.commands.lock().unwrap();
        let mut history: Vec<CqrsCommand> = commands.values()
            .filter(|c| c.aggregate_id == aggregate_id)
            .cloned()
            .collect();
        
        history.sort_by_key(|c| c.created_at);
        history
    }

    pub fn log_audit(
        &self,
        event_type: AuditEventType,
        user_id: &str,
        user_name: &str,
        resource_type: &str,
        resource_id: Option<&str>,
        action: &str,
        result: &str,
        ip_address: Option<&str>,
        user_agent: Option<&str>,
        command_id: Option<String>,
        details: HashMap<String, String>,
    ) -> AuditLogEntry {
        let id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let entry = AuditLogEntry {
            id: id.clone(),
            event_type,
            user_id: user_id.to_string(),
            user_name: user_name.to_string(),
            resource_type: resource_type.to_string(),
            resource_id: resource_id.map(|s| s.to_string()),
            action: action.to_string(),
            result: result.to_string(),
            ip_address: ip_address.map(|s| s.to_string()),
            user_agent: user_agent.map(|s| s.to_string()),
            command_id: command_id.clone(),
            timestamp: now,
            details,
        };

        let mut logs = self.audit_logs.lock().unwrap();
        logs.push(entry.clone());

        if let Some(cid) = command_id {
            let mut command_events = self.command_events.lock().unwrap();
            command_events.entry(cid).or_insert_with(Vec::new).push(entry.clone());
        }

        entry
    }

    pub fn query_audit_logs(&self, filter: AuditFilter) -> Vec<AuditLogEntry> {
        let logs = self.audit_logs.lock().unwrap();
        logs.iter()
            .filter(|entry| {
                if let Some(ref user_id) = filter.user_id {
                    if entry.user_id != *user_id {
                        return false;
                    }
                }
                if let Some(ref event_type) = filter.event_type {
                    if entry.event_type != *event_type {
                        return false;
                    }
                }
                if let Some(ref resource_type) = filter.resource_type {
                    if entry.resource_type != *resource_type {
                        return false;
                    }
                }
                if let Some(ref start_time) = filter.start_time {
                    if entry.timestamp < *start_time {
                        return false;
                    }
                }
                if let Some(ref end_time) = filter.end_time {
                    if entry.timestamp > *end_time {
                        return false;
                    }
                }
                if let Some(ref action) = filter.action {
                    if entry.action != *action {
                        return false;
                    }
                }
                if let Some(ref result) = filter.result {
                    if entry.result != *result {
                        return false;
                    }
                }
                true
            })
            .cloned()
            .collect()
    }

    pub fn get_audit_logs_for_command(&self, command_id: &str) -> Vec<AuditLogEntry> {
        let command_events = self.command_events.lock().unwrap();
        command_events.get(command_id).cloned().unwrap_or_default()
    }

    pub fn generate_compliance_report(
        &self,
        name: &str,
        report_type: &str,
        start_time: DateTime<Utc>,
        end_time: DateTime<Utc>,
        generated_by: &str,
    ) -> ComplianceReport {
        let (filtered_commands, filtered_logs) = {
            let commands = self.commands.lock().unwrap();
            let logs = self.audit_logs.lock().unwrap();

            let filtered_commands: Vec<CqrsCommand> = commands.values()
                .filter(|c| c.created_at >= start_time && c.created_at <= end_time)
                .cloned()
                .collect();

            let filtered_logs: Vec<AuditLogEntry> = logs.iter()
                .filter(|l| l.timestamp >= start_time && l.timestamp <= end_time)
                .cloned()
                .collect();
            
            (filtered_commands, filtered_logs)
        };

        let total_commands = filtered_commands.len() as u32;
        let successful_commands = filtered_commands.iter()
            .filter(|c| c.status == CommandStatus::Completed).count() as u32;
        let failed_commands = filtered_commands.iter()
            .filter(|c| c.status == CommandStatus::Failed).count() as u32;

        let total_audit_logs = filtered_logs.len() as u32;
        let access_denied_count = filtered_logs.iter()
            .filter(|l| l.event_type == AuditEventType::AccessDenied).count() as u32;
        let data_modified_count = filtered_logs.iter()
            .filter(|l| l.event_type == AuditEventType::DataModified).count() as u32;

        let summary = format!(
            "Report covering {} to {}. {} commands executed ({} successful, {} failed). {} audit events recorded.",
            start_time, end_time, total_commands, successful_commands, failed_commands, total_audit_logs
        );

        let id = Uuid::new_v4().to_string();
        let report = ComplianceReport {
            id: id.clone(),
            name: name.to_string(),
            report_type: report_type.to_string(),
            start_time,
            end_time,
            generated_at: Utc::now(),
            generated_by: generated_by.to_string(),
            total_commands,
            successful_commands,
            failed_commands,
            total_audit_logs,
            access_denied_count,
            data_modified_count,
            summary,
            format: "json".to_string(),
        };

        let mut reports = self.reports.lock().unwrap();
        reports.insert(id.clone(), report.clone());
        drop(reports);

        self.log_audit(
            AuditEventType::DataAccessed,
            generated_by,
            generated_by,
            "compliance_report",
            Some(&id),
            "generate",
            "success",
            None,
            None,
            None,
            HashMap::new(),
        );

        report
    }

    pub fn get_report(&self, report_id: &str) -> Option<ComplianceReport> {
        let reports = self.reports.lock().unwrap();
        reports.get(report_id).cloned()
    }

    pub fn list_reports(&self) -> Vec<ComplianceReport> {
        let reports = self.reports.lock().unwrap();
        reports.values().cloned().collect()
    }

    pub fn get_user_activity_summary(&self, user_id: &str, days: i64) -> HashMap<String, u32> {
        let end_time = Utc::now();
        let start_time = end_time - Duration::days(days);

        let filter = AuditFilter {
            user_id: Some(user_id.to_string()),
            event_type: None,
            resource_type: None,
            start_time: Some(start_time),
            end_time: Some(end_time),
            action: None,
            result: None,
        };

        let logs = self.query_audit_logs(filter);
        let mut summary = HashMap::new();

        for log in logs {
            let key = format!("{:?}", log.event_type);
            *summary.entry(key).or_insert(0) += 1;
        }

        summary
    }

    pub fn export_audit_logs(&self, filter: AuditFilter, format: &str) -> Result<String, String> {
        let logs = self.query_audit_logs(filter);

        match format.to_lowercase().as_str() {
            "json" => serde_json::to_string(&logs).map_err(|e| e.to_string()),
            "csv" => {
                let mut csv = String::from("id,event_type,user_id,user_name,resource_type,resource_id,action,result,timestamp\n");
                for log in logs {
                    csv.push_str(&format!(
                        "{},{},{},{},{},{},{},{},{}\n",
                        log.id,
                        format!("{:?}", log.event_type),
                        log.user_id,
                        log.user_name,
                        log.resource_type,
                        log.resource_id.unwrap_or_default(),
                        log.action,
                        log.result,
                        log.timestamp
                    ));
                }
                Ok(csv)
            }
            _ => Err(format!("Unsupported format: {}", format)),
        }
    }
}

impl Default for AuditManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_command() {
        let manager = AuditManager::new();
        let payload = serde_json::json!({"field": "value"});

        let command = manager.create_command(
            CommandType::Create,
            "agg_123",
            "workflow",
            payload,
            "user_001",
        );

        assert_eq!(command.command_type, CommandType::Create);
        assert_eq!(command.aggregate_id, "agg_123");
        assert_eq!(command.aggregate_type, "workflow");
        assert_eq!(command.status, CommandStatus::Pending);
        assert_eq!(command.created_by, "user_001");
    }

    #[test]
    fn test_execute_command() {
        let manager = AuditManager::new();
        let payload = serde_json::json!({});
        let command = manager.create_command(
            CommandType::Update,
            "agg_123",
            "workflow",
            payload,
            "user_001",
        );

        let started = manager.start_command(&command.id);
        assert!(started.is_ok());
        assert_eq!(started.unwrap().status, CommandStatus::Executing);

        let mut metadata = HashMap::new();
        metadata.insert("result".to_string(), "success".to_string());
        
        let completed = manager.complete_command(&command.id, metadata);
        assert!(completed.is_ok());
        assert_eq!(completed.unwrap().status, CommandStatus::Completed);
    }

    #[test]
    fn test_fail_command() {
        let manager = AuditManager::new();
        let payload = serde_json::json!({});
        let command = manager.create_command(
            CommandType::Delete,
            "agg_123",
            "workflow",
            payload,
            "user_001",
        );

        manager.start_command(&command.id).unwrap();
        let failed = manager.fail_command(&command.id, "Something went wrong");

        assert!(failed.is_ok());
        let failed = failed.unwrap();
        assert_eq!(failed.status, CommandStatus::Failed);
        assert_eq!(failed.error_message, Some("Something went wrong".to_string()));
    }

    #[test]
    fn test_audit_logging() {
        let manager = AuditManager::new();
        
        let mut details = HashMap::new();
        details.insert("key".to_string(), "value".to_string());

        let entry = manager.log_audit(
            AuditEventType::Login,
            "user_001",
            "John Doe",
            "session",
            None,
            "login",
            "success",
            Some("192.168.1.1"),
            Some("Mozilla/5.0"),
            None,
            details,
        );

        assert_eq!(entry.event_type, AuditEventType::Login);
        assert_eq!(entry.user_id, "user_001");
        assert_eq!(entry.ip_address, Some("192.168.1.1".to_string()));
    }

    #[test]
    fn test_query_audit_logs() {
        let manager = AuditManager::new();

        manager.log_audit(
            AuditEventType::Login,
            "user_001",
            "John",
            "session",
            None,
            "login",
            "success",
            None,
            None,
            None,
            HashMap::new(),
        );

        manager.log_audit(
            AuditEventType::AccessDenied,
            "user_001",
            "John",
            "resource",
            None,
            "access",
            "denied",
            None,
            None,
            None,
            HashMap::new(),
        );

        let filter = AuditFilter {
            user_id: Some("user_001".to_string()),
            event_type: None,
            resource_type: None,
            start_time: None,
            end_time: None,
            action: None,
            result: None,
        };

        let logs = manager.query_audit_logs(filter);
        assert_eq!(logs.len(), 2);

        let filter = AuditFilter {
            user_id: None,
            event_type: Some(AuditEventType::Login),
            resource_type: None,
            start_time: None,
            end_time: None,
            action: None,
            result: None,
        };

        let logs = manager.query_audit_logs(filter);
        assert_eq!(logs.len(), 1);
    }

    #[test]
    fn test_generate_compliance_report() {
        let manager = AuditManager::new();
        let start_time = Utc::now() - Duration::days(7);

        let payload = serde_json::json!({});
        let command = manager.create_command(
            CommandType::Create,
            "agg_1",
            "workflow",
            payload,
            "admin",
        );
        manager.start_command(&command.id).unwrap();
        manager.complete_command(&command.id, HashMap::new()).unwrap();

        let end_time = Utc::now();
        let report = manager.generate_compliance_report(
            "Weekly Report",
            "weekly",
            start_time,
            end_time,
            "admin",
        );

        assert_eq!(report.name, "Weekly Report");
        assert_eq!(report.report_type, "weekly");
        assert!(report.total_commands > 0);
        assert!(report.successful_commands > 0);
    }

    #[test]
    fn test_get_command_history() {
        let manager = AuditManager::new();
        let aggregate_id = "agg_123";

        for i in 0..3 {
            let payload = serde_json::json!({"version": i});
            manager.create_command(
                CommandType::Update,
                aggregate_id,
                "workflow",
                payload,
                "user_001",
            );
        }

        let history = manager.get_command_history(aggregate_id);
        assert_eq!(history.len(), 3);
    }

    #[test]
    fn test_export_audit_logs() {
        let manager = AuditManager::new();

        manager.log_audit(
            AuditEventType::DataAccessed,
            "user_001",
            "John",
            "document",
            Some("doc_123"),
            "read",
            "success",
            None,
            None,
            None,
            HashMap::new(),
        );

        let filter = AuditFilter {
            user_id: None,
            event_type: None,
            resource_type: None,
            start_time: None,
            end_time: None,
            action: None,
            result: None,
        };

        let json = manager.export_audit_logs(filter.clone(), "json");
        assert!(json.is_ok());

        let csv = manager.export_audit_logs(filter, "csv");
        assert!(csv.is_ok());
    }

    #[test]
    fn test_get_user_activity_summary() {
        let manager = AuditManager::new();

        for _ in 0..5 {
            manager.log_audit(
                AuditEventType::DataAccessed,
                "user_001",
                "John",
                "document",
                None,
                "read",
                "success",
                None,
                None,
                None,
                HashMap::new(),
            );
        }

        for _ in 0..3 {
            manager.log_audit(
                AuditEventType::DataModified,
                "user_001",
                "John",
                "document",
                None,
                "write",
                "success",
                None,
                None,
                None,
                HashMap::new(),
            );
        }

        let summary = manager.get_user_activity_summary("user_001", 1);
        assert!(summary.get("DataAccessed").unwrap_or(&0) > &0);
        assert!(summary.get("DataModified").unwrap_or(&0) > &0);
    }
}
