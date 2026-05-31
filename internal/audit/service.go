package audit

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/abstraction"
	"github.com/chaoslab/platform/internal/common"
	"go.uber.org/zap"
)

type AuditService struct {
	commands   map[string]*common.Command
	auditLogs  map[string]*common.AuditEntry
	mu         sync.RWMutex
}

func NewAuditService() abstraction.AuditService {
	return &AuditService{
		commands:  make(map[string]*common.Command),
		auditLogs: make(map[string]*common.AuditEntry),
	}
}

func (s *AuditService) PersistCommand(ctx context.Context, cmd *common.Command) error {
	if cmd == nil {
		return common.NewBadRequestError("command cannot be nil")
	}
	if cmd.CommandType == "" {
		return common.NewValidationError("command_type is required", "command_type")
	}
	if cmd.EntityID == "" {
		return common.NewValidationError("entity_id is required", "entity_id")
	}
	if cmd.IssuedBy == "" {
		cmd.IssuedBy = "system"
	}

	cmd.CommandID = fmt.Sprintf("cmd_%d", time.Now().UnixNano())
	cmd.IssuedAt = time.Now()
	cmd.Status = "pending"

	s.mu.Lock()
	s.commands[cmd.CommandID] = cmd
	s.mu.Unlock()

	common.Info("command persisted",
		zap.String("command_id", cmd.CommandID),
		zap.String("command_type", cmd.CommandType),
		zap.String("entity_id", cmd.EntityID),
		zap.String("issued_by", cmd.IssuedBy),
	)

	go s.generateAuditLogForCommand(cmd)

	return nil
}

func (s *AuditService) generateAuditLogForCommand(cmd *common.Command) {
	entry := &common.AuditEntry{
		EntryID:      fmt.Sprintf("audit_%d", time.Now().UnixNano()),
		Timestamp:    time.Now(),
		UserID:       cmd.IssuedBy,
		Action:       cmd.CommandType,
		ResourceType: "command",
		ResourceID:   cmd.EntityID,
		CommandID:    cmd.CommandID,
		EventIDs:     cmd.EventIDs,
		After:        cmd.Payload,
		Metadata: map[string]string{
			"command_id": cmd.CommandID,
			"status":     cmd.Status,
		},
	}

	s.mu.Lock()
	s.auditLogs[entry.EntryID] = entry
	s.mu.Unlock()
}

func (s *AuditService) GetCommand(ctx context.Context, commandID string) (*common.Command, error) {
	if commandID == "" {
		return nil, common.NewValidationError("command_id is required", "command_id")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	cmd, exists := s.commands[commandID]
	if !exists {
		return nil, common.NewNotFoundError(fmt.Sprintf("command %s not found", commandID))
	}
	return cmd, nil
}

func (s *AuditService) QueryCommands(ctx context.Context, filter *common.CommandFilter) ([]*common.Command, error) {
	if filter == nil {
		filter = &common.CommandFilter{}
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*common.Command, 0)
	for _, cmd := range s.commands {
		if filter.CommandType != "" && cmd.CommandType != filter.CommandType {
			continue
		}
		if filter.EntityID != "" && cmd.EntityID != filter.EntityID {
			continue
		}
		if filter.IssuedBy != "" && cmd.IssuedBy != filter.IssuedBy {
			continue
		}
		if filter.Status != "" && cmd.Status != filter.Status {
			continue
		}
		if filter.FromTime != nil && cmd.IssuedAt.Before(*filter.FromTime) {
			continue
		}
		if filter.ToTime != nil && cmd.IssuedAt.After(*filter.ToTime) {
			continue
		}
		result = append(result, cmd)
	}

	sort.Slice(result, func(i, j int) bool {
		return result[i].IssuedAt.After(result[j].IssuedAt)
	})

	if filter.Offset > 0 && filter.Offset < len(result) {
		result = result[filter.Offset:]
	}
	if filter.Limit > 0 && filter.Limit < len(result) {
		result = result[:filter.Limit]
	}

	return result, nil
}

func (s *AuditService) GenerateAuditLog(ctx context.Context, entry *common.AuditEntry) error {
	if entry == nil {
		return common.NewBadRequestError("audit entry cannot be nil")
	}
	if entry.Action == "" {
		return common.NewValidationError("action is required", "action")
	}
	if entry.UserID == "" {
		entry.UserID = "system"
	}

	entry.EntryID = fmt.Sprintf("audit_%d", time.Now().UnixNano())
	entry.Timestamp = time.Now()

	s.mu.Lock()
	s.auditLogs[entry.EntryID] = entry
	s.mu.Unlock()

	common.Debug("audit log generated",
		zap.String("entry_id", entry.EntryID),
		zap.String("action", entry.Action),
		zap.String("user_id", entry.UserID),
		zap.String("resource_type", entry.ResourceType),
	)

	return nil
}

func (s *AuditService) GetAuditLogs(ctx context.Context, filter *common.AuditFilter) ([]*common.AuditEntry, error) {
	if filter == nil {
		filter = &common.AuditFilter{}
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*common.AuditEntry, 0)
	for _, entry := range s.auditLogs {
		if filter.UserID != "" && entry.UserID != filter.UserID {
			continue
		}
		if filter.Action != "" && entry.Action != filter.Action {
			continue
		}
		if filter.ResourceType != "" && entry.ResourceType != filter.ResourceType {
			continue
		}
		if filter.ResourceID != "" && entry.ResourceID != filter.ResourceID {
			continue
		}
		if filter.FromTime != nil && entry.Timestamp.Before(*filter.FromTime) {
			continue
		}
		if filter.ToTime != nil && entry.Timestamp.After(*filter.ToTime) {
			continue
		}
		result = append(result, entry)
	}

	sort.Slice(result, func(i, j int) bool {
		return result[i].Timestamp.After(result[j].Timestamp)
	})

	if filter.Offset > 0 && filter.Offset < len(result) {
		result = result[filter.Offset:]
	}
	if filter.Limit > 0 && filter.Limit < len(result) {
		result = result[:filter.Limit]
	}

	return result, nil
}

func (s *AuditService) GenerateComplianceReport(ctx context.Context, req *common.ComplianceRequest) (*common.ComplianceReport, error) {
	if req == nil {
		return nil, common.NewBadRequestError("compliance request cannot be nil")
	}
	if req.ReportType == "" {
		req.ReportType = "general"
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	fromTime := time.Now().AddDate(0, 0, -30)
	toTime := time.Now()
	if req.FromTime != nil {
		fromTime = *req.FromTime
	}
	if req.ToTime != nil {
		toTime = *req.ToTime
	}

	commandsInRange := make([]*common.Command, 0)
	eventsInRange := 0
	auditLogsInRange := make([]*common.AuditEntry, 0)
	failedCommands := 0
	policyViolations := 0

	for _, cmd := range s.commands {
		if (req.Namespace == "" || cmd.Payload["namespace"] == req.Namespace) &&
			cmd.IssuedAt.After(fromTime) && cmd.IssuedAt.Before(toTime) {
			commandsInRange = append(commandsInRange, cmd)
			if cmd.Status == "failed" {
				failedCommands++
			}
			if req.IncludeEvents {
				eventsInRange += len(cmd.EventIDs)
			}
		}
	}

	for _, entry := range s.auditLogs {
		if (req.Namespace == "" || entry.Metadata["namespace"] == req.Namespace) &&
			entry.Timestamp.After(fromTime) && entry.Timestamp.Before(toTime) {
			auditLogsInRange = append(auditLogsInRange, entry)
			if entry.Metadata["violation"] == "true" {
				policyViolations++
			}
		}
	}

	findings := s.generateFindings(commandsInRange, auditLogsInRange)

	report := &common.ComplianceReport{
		ReportID:   fmt.Sprintf("report_%d", time.Now().UnixNano()),
		ReportType: req.ReportType,
		Framework:  req.Framework,
		GeneratedAt: time.Now(),
		PeriodStart: fromTime,
		PeriodEnd:   toTime,
		Summary: &common.ReportSummary{
			TotalCommands:    len(commandsInRange),
			TotalEvents:      eventsInRange,
			TotalAuditLogs:   len(auditLogsInRange),
			FailedCommands:   failedCommands,
			PolicyViolations: policyViolations,
		},
		Findings: findings,
		Data: map[string]interface{}{
			"namespace": req.Namespace,
		},
	}

	common.Info("compliance report generated",
		zap.String("report_id", report.ReportID),
		zap.String("report_type", req.ReportType),
		zap.String("framework", req.Framework),
		zap.Int("total_commands", report.Summary.TotalCommands),
		zap.Int("total_audit_logs", report.Summary.TotalAuditLogs),
		zap.Int("findings", len(report.Findings)),
	)

	return report, nil
}

func (s *AuditService) generateFindings(commands []*common.Command, logs []*common.AuditEntry) []*common.ReportFinding {
	findings := make([]*common.ReportFinding, 0)

	for _, cmd := range commands {
		if cmd.Status == "failed" {
			findings = append(findings, &common.ReportFinding{
				Severity: "medium",
				Rule:     "CMD-001",
				Message:  fmt.Sprintf("Command %s failed to execute", cmd.CommandID),
				Resource: cmd.EntityID,
				Evidence: map[string]interface{}{
					"command_id":   cmd.CommandID,
					"command_type": cmd.CommandType,
					"error":        cmd.Error,
				},
			})
		}

		if len(cmd.EventIDs) == 0 {
			findings = append(findings, &common.ReportFinding{
				Severity: "low",
				Rule:     "CMD-002",
				Message:  fmt.Sprintf("Command %s has no associated events", cmd.CommandID),
				Resource: cmd.EntityID,
				Evidence: map[string]interface{}{
					"command_id":   cmd.CommandID,
					"command_type": cmd.CommandType,
				},
			})
		}
	}

	return findings
}

func (s *AuditService) AssociateCommandWithEvents(ctx context.Context, commandID string, eventIDs []string) error {
	if commandID == "" {
		return common.NewValidationError("command_id is required", "command_id")
	}
	if len(eventIDs) == 0 {
		return common.NewValidationError("at least one event_id is required", "event_ids")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	cmd, exists := s.commands[commandID]
	if !exists {
		return common.NewNotFoundError(fmt.Sprintf("command %s not found", commandID))
	}

	cmd.EventIDs = append(cmd.EventIDs, eventIDs...)
	cmd.UpdatedAt = time.Now()

	common.Info("command associated with events",
		zap.String("command_id", commandID),
		zap.Int("event_count", len(eventIDs)),
	)

	return nil
}

func (s *AuditService) UpdateCommandStatus(ctx context.Context, commandID string, status string, result map[string]interface{}, err string) error {
	if commandID == "" {
		return common.NewValidationError("command_id is required", "command_id")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	cmd, exists := s.commands[commandID]
	if !exists {
		return common.NewNotFoundError(fmt.Sprintf("command %s not found", commandID))
	}

	cmd.Status = status
	cmd.Result = result
	cmd.Error = err

	common.Info("command status updated",
		zap.String("command_id", commandID),
		zap.String("status", status),
	)

	return nil
}
