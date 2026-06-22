package dao

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/featureflag/platform/internal/model"
	"github.com/featureflag/platform/pkg/logger"
)

type HistoryDAO struct{}

func NewHistoryDAO() *HistoryDAO {
	return &HistoryDAO{}
}

func (d *HistoryDAO) Create(ctx context.Context, h *model.SwitchHistory) error {
	sql := `
		INSERT INTO switch_history (id, switch_id, event_type, old_value, new_value, operator_user, remark, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
	`
	_, err := DB.Exec(ctx, sql, h.ID, h.SwitchID, h.EventType, h.OldValue, h.NewValue, h.OperatorUser, h.Remark, h.CreatedAt)
	if err != nil {
		logger.Errorf("create history error: %v", err)
		return err
	}
	return nil
}

func (d *HistoryDAO) ListBySwitchID(ctx context.Context, switchID string, page, pageSize int) ([]*model.SwitchHistory, int64, error) {
	countSQL := `SELECT COUNT(*) FROM switch_history WHERE switch_id=$1`
	var total int64
	err := DB.QueryRow(ctx, countSQL, switchID).Scan(&total)
	if err != nil {
		logger.Errorf("count history error: %v", err)
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	sql := `
		SELECT id, switch_id, event_type, old_value, new_value, operator_user, remark, created_at
		FROM switch_history WHERE switch_id=$1 ORDER BY created_at DESC LIMIT $2 OFFSET $3
	`
	rows, err := DB.Query(ctx, sql, switchID, pageSize, offset)
	if err != nil {
		logger.Errorf("list history error: %v", err)
		return nil, 0, err
	}
	defer rows.Close()

	histories := make([]*model.SwitchHistory, 0)
	for rows.Next() {
		var h model.SwitchHistory
		err := rows.Scan(&h.ID, &h.SwitchID, &h.EventType, &h.OldValue, &h.NewValue, &h.OperatorUser, &h.Remark, &h.CreatedAt)
		if err != nil {
			logger.Errorf("scan history error: %v", err)
			return nil, 0, err
		}
		histories = append(histories, &h)
	}
	return histories, total, nil
}

type ApprovalDAO struct{}

func NewApprovalDAO() *ApprovalDAO {
	return &ApprovalDAO{}
}

func (d *ApprovalDAO) Create(ctx context.Context, a *model.Approval) error {
	sql := `
		INSERT INTO approvals (id, switch_id, title, description, requester, approver, status,
			change_content, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
	`
	_, err := DB.Exec(ctx, sql, a.ID, a.SwitchID, a.Title, a.Description, a.Requester, a.Approver,
		a.Status, a.ChangeContent, a.CreatedAt, a.UpdatedAt)
	if err != nil {
		logger.Errorf("create approval error: %v", err)
		return err
	}
	return nil
}

func (d *ApprovalDAO) Update(ctx context.Context, a *model.Approval) error {
	sql := `
		UPDATE approvals SET status=$1, approved_at=$2, rejected_at=$3, reject_reason=$4, updated_at=$5
		WHERE id=$6
	`
	_, err := DB.Exec(ctx, sql, a.Status, a.ApprovedAt, a.RejectedAt, a.RejectReason, time.Now(), a.ID)
	if err != nil {
		logger.Errorf("update approval error: %v", err)
		return err
	}
	return nil
}

func (d *ApprovalDAO) GetByID(ctx context.Context, id string) (*model.Approval, error) {
	sql := `
		SELECT a.id, a.switch_id, a.title, a.description, a.requester, a.approver, a.status,
			a.change_content, a.approved_at, a.rejected_at, a.reject_reason, a.created_at, a.updated_at,
			s.key, s.name
		FROM approvals a
		LEFT JOIN switches s ON a.switch_id = s.id
		WHERE a.id=$1
	`
	var a model.Approval
	err := DB.QueryRow(ctx, sql, id).Scan(
		&a.ID, &a.SwitchID, &a.Title, &a.Description, &a.Requester, &a.Approver, &a.Status,
		&a.ChangeContent, &a.ApprovedAt, &a.RejectedAt, &a.RejectReason, &a.CreatedAt, &a.UpdatedAt,
		&a.SwitchKey, &a.SwitchName,
	)
	if err != nil {
		logger.Errorf("get approval by id error: %v", err)
		return nil, err
	}
	return &a, nil
}

func (d *ApprovalDAO) List(ctx context.Context, status, requester, approver string, page, pageSize int) ([]*model.Approval, int64, error) {
	where := []string{"1=1"}
	args := []interface{}{}
	argIdx := 1

	if status != "" {
		where = append(where, fmt.Sprintf("a.status = $%d", argIdx))
		args = append(args, status)
		argIdx++
	}
	if requester != "" {
		where = append(where, fmt.Sprintf("a.requester = $%d", argIdx))
		args = append(args, requester)
		argIdx++
	}
	if approver != "" {
		where = append(where, fmt.Sprintf("a.approver = $%d", argIdx))
		args = append(args, approver)
		argIdx++
	}

	whereSQL := "WHERE " + fmt.Sprintf(strings.Join(where, " AND "))

	countSQL := fmt.Sprintf(`SELECT COUNT(*) FROM approvals a %s`, whereSQL)
	var total int64
	err := DB.QueryRow(ctx, countSQL, args...).Scan(&total)
	if err != nil {
		logger.Errorf("count approvals error: %v", err)
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	listSQL := fmt.Sprintf(`
		SELECT a.id, a.switch_id, a.title, a.description, a.requester, a.approver, a.status,
			a.change_content, a.approved_at, a.rejected_at, a.reject_reason, a.created_at, a.updated_at,
			s.key, s.name
		FROM approvals a
		LEFT JOIN switches s ON a.switch_id = s.id
		%s
		ORDER BY a.created_at DESC
		LIMIT $%d OFFSET $%d
	`, whereSQL, argIdx, argIdx+1)
	args = append(args, pageSize, offset)

	rows, err := DB.Query(ctx, listSQL, args...)
	if err != nil {
		logger.Errorf("list approvals error: %v", err)
		return nil, 0, err
	}
	defer rows.Close()

	approvals := make([]*model.Approval, 0)
	for rows.Next() {
		var a model.Approval
		err := rows.Scan(
			&a.ID, &a.SwitchID, &a.Title, &a.Description, &a.Requester, &a.Approver, &a.Status,
			&a.ChangeContent, &a.ApprovedAt, &a.RejectedAt, &a.RejectReason, &a.CreatedAt, &a.UpdatedAt,
			&a.SwitchKey, &a.SwitchName,
		)
		if err != nil {
			logger.Errorf("scan approval error: %v", err)
			return nil, 0, err
		}
		approvals = append(approvals, &a)
	}
	return approvals, total, nil
}

type ScheduledTaskDAO struct{}

func NewScheduledTaskDAO() *ScheduledTaskDAO {
	return &ScheduledTaskDAO{}
}

func (d *ScheduledTaskDAO) Create(ctx context.Context, t *model.ScheduledTask) error {
	sql := `
		INSERT INTO scheduled_tasks (id, switch_id, task_type, target_enabled, execute_at, status, created_by, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
	`
	_, err := DB.Exec(ctx, sql, t.ID, t.SwitchID, t.TaskType, t.TargetEnabled, t.ExecuteAt, t.Status, t.CreatedBy, t.CreatedAt)
	if err != nil {
		logger.Errorf("create scheduled task error: %v", err)
		return err
	}
	return nil
}

func (d *ScheduledTaskDAO) GetPendingTasks(ctx context.Context, limit int) ([]*model.ScheduledTask, error) {
	sql := `
		SELECT id, switch_id, task_type, target_enabled, execute_at, status, created_by, created_at
		FROM scheduled_tasks
		WHERE status = 'PENDING' AND execute_at <= NOW()
		ORDER BY execute_at ASC
		LIMIT $1
	`
	rows, err := DB.Query(ctx, sql, limit)
	if err != nil {
		logger.Errorf("get pending tasks error: %v", err)
		return nil, err
	}
	defer rows.Close()

	tasks := make([]*model.ScheduledTask, 0)
	for rows.Next() {
		var t model.ScheduledTask
		err := rows.Scan(&t.ID, &t.SwitchID, &t.TaskType, &t.TargetEnabled, &t.ExecuteAt, &t.Status, &t.CreatedBy, &t.CreatedAt)
		if err != nil {
			logger.Errorf("scan scheduled task error: %v", err)
			return nil, err
		}
		tasks = append(tasks, &t)
	}
	return tasks, nil
}

func (d *ScheduledTaskDAO) MarkExecuted(ctx context.Context, id string, success bool, errMsg string) error {
	sql := `
		UPDATE scheduled_tasks SET status=$1, executed_at=NOW(), error_message=$2 WHERE id=$3
	`
	status := "SUCCESS"
	if !success {
		status = "FAILED"
	}
	_, err := DB.Exec(ctx, sql, status, errMsg, id)
	if err != nil {
		logger.Errorf("mark task executed error: %v", err)
		return err
	}
	return nil
}

func (d *ScheduledTaskDAO) ListBySwitchID(ctx context.Context, switchID string, page, pageSize int) ([]*model.ScheduledTask, int64, error) {
	countSQL := `SELECT COUNT(*) FROM scheduled_tasks WHERE switch_id=$1`
	var total int64
	err := DB.QueryRow(ctx, countSQL, switchID).Scan(&total)
	if err != nil {
		logger.Errorf("count scheduled tasks error: %v", err)
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	sql := `
		SELECT id, switch_id, task_type, target_enabled, execute_at, executed_at, status, error_message, created_by, created_at
		FROM scheduled_tasks WHERE switch_id=$1 ORDER BY execute_at DESC LIMIT $2 OFFSET $3
	`
	rows, err := DB.Query(ctx, sql, switchID, pageSize, offset)
	if err != nil {
		logger.Errorf("list scheduled tasks error: %v", err)
		return nil, 0, err
	}
	defer rows.Close()

	tasks := make([]*model.ScheduledTask, 0)
	for rows.Next() {
		var t model.ScheduledTask
		err := rows.Scan(&t.ID, &t.SwitchID, &t.TaskType, &t.TargetEnabled, &t.ExecuteAt, &t.ExecutedAt, &t.Status, &t.ErrorMessage, &t.CreatedBy, &t.CreatedAt)
		if err != nil {
			logger.Errorf("scan scheduled task error: %v", err)
			return nil, 0, err
		}
		tasks = append(tasks, &t)
	}
	return tasks, total, nil
}

type StatsDAO struct{}

func NewStatsDAO() *StatsDAO {
	return &StatsDAO{}
}

func (d *StatsDAO) Upsert(ctx context.Context, stat *model.SwitchStats) error {
	sql := `
		INSERT INTO switch_stats (id, switch_id, date, total_evaluations, true_count, false_count,
			error_count, avg_latency_ms, p99_latency_ms, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
		ON CONFLICT (switch_id, date) DO UPDATE SET
			total_evaluations = switch_stats.total_evaluations + EXCLUDED.total_evaluations,
			true_count = switch_stats.true_count + EXCLUDED.true_count,
			false_count = switch_stats.false_count + EXCLUDED.false_count,
			error_count = switch_stats.error_count + EXCLUDED.error_count,
			avg_latency_ms = (switch_stats.avg_latency_ms + EXCLUDED.avg_latency_ms) / 2,
			p99_latency_ms = GREATEST(switch_stats.p99_latency_ms, EXCLUDED.p99_latency_ms),
			updated_at = NOW()
	`
	_, err := DB.Exec(ctx, sql, stat.ID, stat.SwitchID, stat.Date, stat.TotalEvaluations, stat.TrueCount,
		stat.FalseCount, stat.ErrorCount, stat.AvgLatencyMs, stat.P99LatencyMs, stat.CreatedAt, stat.UpdatedAt)
	if err != nil {
		logger.Errorf("upsert stats error: %v", err)
		return err
	}
	return nil
}

func (d *StatsDAO) GetBySwitchID(ctx context.Context, switchID string, startDate, endDate string) ([]*model.SwitchStats, error) {
	sql := `
		SELECT id, switch_id, date, total_evaluations, true_count, false_count,
			error_count, avg_latency_ms, p99_latency_ms, created_at, updated_at
		FROM switch_stats
		WHERE switch_id=$1 AND date >= $2 AND date <= $3
		ORDER BY date ASC
	`
	rows, err := DB.Query(ctx, sql, switchID, startDate, endDate)
	if err != nil {
		logger.Errorf("get stats by switch id error: %v", err)
		return nil, err
	}
	defer rows.Close()

	stats := make([]*model.SwitchStats, 0)
	for rows.Next() {
		var s model.SwitchStats
		err := rows.Scan(&s.ID, &s.SwitchID, &s.Date, &s.TotalEvaluations, &s.TrueCount,
			&s.FalseCount, &s.ErrorCount, &s.AvgLatencyMs, &s.P99LatencyMs, &s.CreatedAt, &s.UpdatedAt)
		if err != nil {
			logger.Errorf("scan stats error: %v", err)
			return nil, err
		}
		stats = append(stats, &s)
	}
	return stats, nil
}

func (d *StatsDAO) GetSummary(ctx context.Context, switchID string) (*model.StatsSummary, error) {
	sql := `
		SELECT
			COALESCE(SUM(total_evaluations), 0) as total,
			COALESCE(SUM(true_count), 0) as true_count,
			COALESCE(SUM(false_count), 0) as false_count,
			COALESCE(SUM(error_count), 0) as error_count,
			COALESCE(AVG(avg_latency_ms), 0) as avg_latency,
			COALESCE(MAX(p99_latency_ms), 0) as p99_latency
		FROM switch_stats
		WHERE switch_id=$1 AND date >= NOW() - INTERVAL '30 days'
	`
	var summary model.StatsSummary
	err := DB.QueryRow(ctx, sql, switchID).Scan(
		&summary.TotalEvaluations, &summary.TrueCount, &summary.FalseCount,
		&summary.ErrorCount, &summary.AvgLatencyMs, &summary.P99LatencyMs,
	)
	if err != nil {
		logger.Errorf("get stats summary error: %v", err)
		return nil, err
	}
	return &summary, nil
}

func (d *StatsDAO) GetRecentStats(ctx context.Context, switchID string, minutes int) (*model.StatsSummary, error) {
	sql := `
		SELECT
			COALESCE(SUM(total_evaluations), 0) as total,
			COALESCE(SUM(true_count), 0) as true_count,
			COALESCE(SUM(false_count), 0) as false_count,
			COALESCE(SUM(error_count), 0) as error_count,
			COALESCE(AVG(avg_latency_ms), 0) as avg_latency,
			COALESCE(MAX(p99_latency_ms), 0) as p99_latency
		FROM switch_stats
		WHERE switch_id=$1 AND date >= NOW() - INTERVAL '$2 minutes'
	`
	var summary model.StatsSummary
	err := DB.QueryRow(ctx, sql, switchID, minutes).Scan(
		&summary.TotalEvaluations, &summary.TrueCount, &summary.FalseCount,
		&summary.ErrorCount, &summary.AvgLatencyMs, &summary.P99LatencyMs,
	)
	if err != nil {
		logger.Errorf("get recent stats error: %v", err)
		return nil, err
	}
	return &summary, nil
}

type ServiceDAO struct{}

func NewServiceDAO() *ServiceDAO {
	return &ServiceDAO{}
}

func (d *ServiceDAO) Create(ctx context.Context, svc *model.Service) error {
	sql := `
		INSERT INTO services (id, name, description, owner, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6)
	`
	_, err := DB.Exec(ctx, sql, svc.ID, svc.Name, svc.Description, svc.Owner, svc.CreatedAt, svc.UpdatedAt)
	if err != nil {
		logger.Errorf("create service error: %v", err)
		return err
	}
	return nil
}

func (d *ServiceDAO) List(ctx context.Context) ([]*model.Service, error) {
	sql := `
		SELECT id, name, description, owner, created_at, updated_at
		FROM services WHERE deleted_at IS NULL ORDER BY name ASC
	`
	rows, err := DB.Query(ctx, sql)
	if err != nil {
		logger.Errorf("list services error: %v", err)
		return nil, err
	}
	defer rows.Close()

	services := make([]*model.Service, 0)
	for rows.Next() {
		var svc model.Service
		err := rows.Scan(&svc.ID, &svc.Name, &svc.Description, &svc.Owner, &svc.CreatedAt, &svc.UpdatedAt)
		if err != nil {
			logger.Errorf("scan service error: %v", err)
			return nil, err
		}
		services = append(services, &svc)
	}
	return services, nil
}

func (d *ServiceDAO) GetByID(ctx context.Context, id string) (*model.Service, error) {
	sql := `
		SELECT id, name, description, owner, created_at, updated_at
		FROM services WHERE id=$1 AND deleted_at IS NULL
	`
	var svc model.Service
	err := DB.QueryRow(ctx, sql, id).Scan(&svc.ID, &svc.Name, &svc.Description, &svc.Owner, &svc.CreatedAt, &svc.UpdatedAt)
	if err != nil {
		logger.Errorf("get service by id error: %v", err)
		return nil, err
	}
	return &svc, nil
}

type IntegrationDAO struct{}

func NewIntegrationDAO() *IntegrationDAO {
	return &IntegrationDAO{}
}

func (d *IntegrationDAO) Upsert(ctx context.Context, integration *model.SwitchIntegration) error {
	sql := `
		INSERT INTO switch_integrations (id, switch_id, service_name, sdk_version, last_poll_at, created_at, updated_at)
		VALUES ($1, $2, $3, $4, NOW(), NOW(), NOW())
		ON CONFLICT (switch_id, service_name) DO UPDATE SET
			sdk_version = EXCLUDED.sdk_version,
			last_poll_at = NOW(),
			updated_at = NOW()
	`
	_, err := DB.Exec(ctx, sql, integration.ID, integration.SwitchID, integration.ServiceName, integration.SDKVersion)
	if err != nil {
		logger.Errorf("upsert integration error: %v", err)
		return err
	}
	return nil
}

func (d *IntegrationDAO) GetBySwitchID(ctx context.Context, switchID string) ([]*model.SwitchIntegration, error) {
	sql := `
		SELECT id, switch_id, service_name, sdk_version, last_poll_at, created_at, updated_at
		FROM switch_integrations WHERE switch_id=$1 ORDER BY service_name ASC
	`
	rows, err := DB.Query(ctx, sql, switchID)
	if err != nil {
		logger.Errorf("get integrations by switch id error: %v", err)
		return nil, err
	}
	defer rows.Close()

	integrations := make([]*model.SwitchIntegration, 0)
	for rows.Next() {
		var i model.SwitchIntegration
		err := rows.Scan(&i.ID, &i.SwitchID, &i.ServiceName, &i.SDKVersion, &i.LastPollAt, &i.CreatedAt, &i.UpdatedAt)
		if err != nil {
			logger.Errorf("scan integration error: %v", err)
			return nil, err
		}
		integrations = append(integrations, &i)
	}
	return integrations, nil
}

type AuditLogDAO struct{}

func NewAuditLogDAO() *AuditLogDAO {
	return &AuditLogDAO{}
}

func (d *AuditLogDAO) Create(ctx context.Context, log *model.AuditLog) error {
	sql := `
		INSERT INTO audit_logs (id, user_id, action, resource_type, resource_id, details, ip_address, user_agent, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`
	_, err := DB.Exec(ctx, sql, log.ID, log.UserID, log.Action, log.ResourceType, log.ResourceID, log.Details, log.IPAddress, log.UserAgent, log.CreatedAt)
	if err != nil {
		logger.Errorf("create audit log error: %v", err)
		return err
	}
	return nil
}

func (d *AuditLogDAO) List(ctx context.Context, userID, action, resourceType string, page, pageSize int) ([]*model.AuditLog, int64, error) {
	where := []string{"1=1"}
	args := []interface{}{}
	argIdx := 1

	if userID != "" {
		where = append(where, fmt.Sprintf("user_id = $%d", argIdx))
		args = append(args, userID)
		argIdx++
	}
	if action != "" {
		where = append(where, fmt.Sprintf("action = $%d", argIdx))
		args = append(args, action)
		argIdx++
	}
	if resourceType != "" {
		where = append(where, fmt.Sprintf("resource_type = $%d", argIdx))
		args = append(args, resourceType)
		argIdx++
	}

	whereSQL := "WHERE " + strings.Join(where, " AND ")

	countSQL := fmt.Sprintf(`SELECT COUNT(*) FROM audit_logs %s`, whereSQL)
	var total int64
	err := DB.QueryRow(ctx, countSQL, args...).Scan(&total)
	if err != nil {
		logger.Errorf("count audit logs error: %v", err)
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	listSQL := fmt.Sprintf(`
		SELECT id, user_id, action, resource_type, resource_id, details, ip_address, user_agent, created_at
		FROM audit_logs %s ORDER BY created_at DESC LIMIT $%d OFFSET $%d
	`, whereSQL, argIdx, argIdx+1)
	args = append(args, pageSize, offset)

	rows, err := DB.Query(ctx, listSQL, args...)
	if err != nil {
		logger.Errorf("list audit logs error: %v", err)
		return nil, 0, err
	}
	defer rows.Close()

	logs := make([]*model.AuditLog, 0)
	for rows.Next() {
		var log model.AuditLog
		err := rows.Scan(&log.ID, &log.UserID, &log.Action, &log.ResourceType, &log.ResourceID, &log.Details, &log.IPAddress, &log.UserAgent, &log.CreatedAt)
		if err != nil {
			logger.Errorf("scan audit log error: %v", err)
			return nil, 0, err
		}
		logs = append(logs, &log)
	}
	return logs, total, nil
}
