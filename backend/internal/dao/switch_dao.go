package dao

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/featureflag/platform/internal/model"
	"github.com/featureflag/platform/pkg/logger"
)

type SwitchDAO struct{}

func NewSwitchDAO() *SwitchDAO {
	return &SwitchDAO{}
}

func (d *SwitchDAO) Create(ctx context.Context, s *model.Switch) error {
	sql := `
		INSERT INTO switches (id, key, name, description, type, scope, service_id, owner, status,
			enabled, boolean_value, percentage_value, environment, tenant_id, require_approval,
			auto_rollback_enabled, auto_rollback_threshold, created_by, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20)
	`
	_, err := DB.Exec(ctx, sql,
		s.ID, s.Key, s.Name, s.Description, s.Type, s.Scope, s.ServiceID, s.Owner, s.Status,
		s.Enabled, s.BooleanValue, s.PercentageValue, s.Environment, s.TenantID, s.RequireApproval,
		s.AutoRollbackEnabled, s.AutoRollbackThreshold, s.CreatedBy, s.CreatedAt, s.UpdatedAt,
	)
	if err != nil {
		logger.Errorf("create switch error: %v", err)
		return err
	}
	return nil
}

func (d *SwitchDAO) Update(ctx context.Context, s *model.Switch) error {
	sql := `
		UPDATE switches SET name=$1, description=$2, type=$3, scope=$4, service_id=$5, owner=$6,
			status=$7, enabled=$8, boolean_value=$9, percentage_value=$10, environment=$11,
			tenant_id=$12, require_approval=$13, auto_rollback_enabled=$14, auto_rollback_threshold=$15,
			updated_at=$16
		WHERE id=$17 AND deleted_at IS NULL
	`
	_, err := DB.Exec(ctx, sql,
		s.Name, s.Description, s.Type, s.Scope, s.ServiceID, s.Owner,
		s.Status, s.Enabled, s.BooleanValue, s.PercentageValue, s.Environment,
		s.TenantID, s.RequireApproval, s.AutoRollbackEnabled, s.AutoRollbackThreshold,
		time.Now(), s.ID,
	)
	if err != nil {
		logger.Errorf("update switch error: %v", err)
		return err
	}
	return nil
}

func (d *SwitchDAO) Delete(ctx context.Context, id string, operator string) error {
	sql := `UPDATE switches SET deleted_at=$1, status=$2 WHERE id=$3 AND deleted_at IS NULL`
	_, err := DB.Exec(ctx, sql, time.Now(), model.StatusInactive, id)
	if err != nil {
		logger.Errorf("delete switch error: %v", err)
		return err
	}
	return nil
}

func (d *SwitchDAO) GetByID(ctx context.Context, id string) (*model.Switch, error) {
	sql := `
		SELECT s.id, s.key, s.name, s.description, s.type, s.scope, s.service_id, s.owner,
			s.status, s.enabled, s.boolean_value, s.percentage_value, s.environment, s.tenant_id,
			s.require_approval, s.auto_rollback_enabled, s.auto_rollback_threshold,
			s.created_by, s.created_at, s.updated_at, sv.name as service_name
		FROM switches s
		LEFT JOIN services sv ON s.service_id = sv.id
		WHERE s.id=$1 AND s.deleted_at IS NULL
	`
	var s model.Switch
	var serviceName *string
	err := DB.QueryRow(ctx, sql, id).Scan(
		&s.ID, &s.Key, &s.Name, &s.Description, &s.Type, &s.Scope, &s.ServiceID, &s.Owner,
		&s.Status, &s.Enabled, &s.BooleanValue, &s.PercentageValue, &s.Environment, &s.TenantID,
		&s.RequireApproval, &s.AutoRollbackEnabled, &s.AutoRollbackThreshold,
		&s.CreatedBy, &s.CreatedAt, &s.UpdatedAt, &serviceName,
	)
	if err != nil {
		logger.Errorf("get switch by id error: %v", err)
		return nil, err
	}
	if serviceName != nil {
		s.ServiceName = *serviceName
	}
	return &s, nil
}

func (d *SwitchDAO) GetByKey(ctx context.Context, key string) (*model.Switch, error) {
	sql := `
		SELECT s.id, s.key, s.name, s.description, s.type, s.scope, s.service_id, s.owner,
			s.status, s.enabled, s.boolean_value, s.percentage_value, s.environment, s.tenant_id,
			s.require_approval, s.auto_rollback_enabled, s.auto_rollback_threshold,
			s.created_by, s.created_at, s.updated_at, sv.name as service_name
		FROM switches s
		LEFT JOIN services sv ON s.service_id = sv.id
		WHERE s.key=$1 AND s.deleted_at IS NULL
	`
	var s model.Switch
	var serviceName *string
	err := DB.QueryRow(ctx, sql, key).Scan(
		&s.ID, &s.Key, &s.Name, &s.Description, &s.Type, &s.Scope, &s.ServiceID, &s.Owner,
		&s.Status, &s.Enabled, &s.BooleanValue, &s.PercentageValue, &s.Environment, &s.TenantID,
		&s.RequireApproval, &s.AutoRollbackEnabled, &s.AutoRollbackThreshold,
		&s.CreatedBy, &s.CreatedAt, &s.UpdatedAt, &serviceName,
	)
	if err != nil {
		logger.Errorf("get switch by key error: %v", err)
		return nil, err
	}
	if serviceName != nil {
		s.ServiceName = *serviceName
	}
	return &s, nil
}

func (d *SwitchDAO) List(ctx context.Context, req *model.ListRequest) ([]*model.Switch, int64, error) {
	where := []string{"s.deleted_at IS NULL"}
	args := []interface{}{}
	argIdx := 1

	if req.Keyword != "" {
		where = append(where, fmt.Sprintf("(s.name ILIKE $%d OR s.key ILIKE $%d OR s.description ILIKE $%d)", argIdx, argIdx, argIdx))
		args = append(args, "%"+req.Keyword+"%")
		argIdx++
	}
	if req.ServiceID != "" {
		where = append(where, fmt.Sprintf("s.service_id = $%d", argIdx))
		args = append(args, req.ServiceID)
		argIdx++
	}
	if req.Environment != "" {
		where = append(where, fmt.Sprintf("(s.environment = $%d OR s.scope = 'GLOBAL')", argIdx))
		args = append(args, req.Environment)
		argIdx++
	}
	if req.Status != "" {
		where = append(where, fmt.Sprintf("s.status = $%d", argIdx))
		args = append(args, req.Status)
		argIdx++
	}
	if req.Owner != "" {
		where = append(where, fmt.Sprintf("s.owner = $%d", argIdx))
		args = append(args, req.Owner)
		argIdx++
	}
	if req.Type != "" {
		where = append(where, fmt.Sprintf("s.type = $%d", argIdx))
		args = append(args, req.Type)
		argIdx++
	}
	if req.Scope != "" {
		where = append(where, fmt.Sprintf("s.scope = $%d", argIdx))
		args = append(args, req.Scope)
		argIdx++
	}

	whereSQL := strings.Join(where, " AND ")

	countSQL := fmt.Sprintf(`SELECT COUNT(*) FROM switches s WHERE %s`, whereSQL)
	var total int64
	err := DB.QueryRow(ctx, countSQL, args...).Scan(&total)
	if err != nil {
		logger.Errorf("count switches error: %v", err)
		return nil, 0, err
	}

	offset := (req.Page - 1) * req.PageSize
	listSQL := fmt.Sprintf(`
		SELECT s.id, s.key, s.name, s.description, s.type, s.scope, s.service_id, s.owner,
			s.status, s.enabled, s.boolean_value, s.percentage_value, s.environment, s.tenant_id,
			s.require_approval, s.auto_rollback_enabled, s.auto_rollback_threshold,
			s.created_by, s.created_at, s.updated_at, sv.name as service_name
		FROM switches s
		LEFT JOIN services sv ON s.service_id = sv.id
		WHERE %s
		ORDER BY s.created_at DESC
		LIMIT $%d OFFSET $%d
	`, whereSQL, argIdx, argIdx+1)
	args = append(args, req.PageSize, offset)

	rows, err := DB.Query(ctx, listSQL, args...)
	if err != nil {
		logger.Errorf("list switches error: %v", err)
		return nil, 0, err
	}
	defer rows.Close()

	switches := make([]*model.Switch, 0)
	for rows.Next() {
		var s model.Switch
		var serviceName *string
		err := rows.Scan(
			&s.ID, &s.Key, &s.Name, &s.Description, &s.Type, &s.Scope, &s.ServiceID, &s.Owner,
			&s.Status, &s.Enabled, &s.BooleanValue, &s.PercentageValue, &s.Environment, &s.TenantID,
			&s.RequireApproval, &s.AutoRollbackEnabled, &s.AutoRollbackThreshold,
			&s.CreatedBy, &s.CreatedAt, &s.UpdatedAt, &serviceName,
		)
		if err != nil {
			logger.Errorf("scan switch error: %v", err)
			return nil, 0, err
		}
		if serviceName != nil {
			s.ServiceName = *serviceName
		}
		switches = append(switches, &s)
	}

	return switches, total, nil
}

func (d *SwitchDAO) GetAllEnabled(ctx context.Context) ([]*model.Switch, error) {
	sql := `
		SELECT s.id, s.key, s.name, s.description, s.type, s.scope, s.service_id, s.owner,
			s.status, s.enabled, s.boolean_value, s.percentage_value, s.environment, s.tenant_id,
			s.require_approval, s.auto_rollback_enabled, s.auto_rollback_threshold,
			s.created_by, s.created_at, s.updated_at
		FROM switches s
		WHERE s.status = 'ACTIVE' AND s.enabled = true AND s.deleted_at IS NULL
		ORDER BY s.created_at DESC
	`
	rows, err := DB.Query(ctx, sql)
	if err != nil {
		logger.Errorf("get all enabled switches error: %v", err)
		return nil, err
	}
	defer rows.Close()

	switches := make([]*model.Switch, 0)
	for rows.Next() {
		var s model.Switch
		err := rows.Scan(
			&s.ID, &s.Key, &s.Name, &s.Description, &s.Type, &s.Scope, &s.ServiceID, &s.Owner,
			&s.Status, &s.Enabled, &s.BooleanValue, &s.PercentageValue, &s.Environment, &s.TenantID,
			&s.RequireApproval, &s.AutoRollbackEnabled, &s.AutoRollbackThreshold,
			&s.CreatedBy, &s.CreatedAt, &s.UpdatedAt,
		)
		if err != nil {
			logger.Errorf("scan switch error: %v", err)
			return nil, err
		}
		switches = append(switches, &s)
	}
	return switches, nil
}

func (d *SwitchDAO) UpdateStatus(ctx context.Context, id string, status model.SwitchStatus, enabled bool, operator string) error {
	sql := `UPDATE switches SET status=$1, enabled=$2, updated_at=$3 WHERE id=$4 AND deleted_at IS NULL`
	_, err := DB.Exec(ctx, sql, status, enabled, time.Now(), id)
	if err != nil {
		logger.Errorf("update switch status error: %v", err)
		return err
	}
	return nil
}

func (d *SwitchDAO) BatchUpdateStatus(ctx context.Context, ids []string, status model.SwitchStatus, enabled bool, operator string) (int64, error) {
	if len(ids) == 0 {
		return 0, nil
	}
	placeholders := make([]string, len(ids))
	args := make([]interface{}, len(ids)+3)
	for i, id := range ids {
		placeholders[i] = fmt.Sprintf("$%d", i+4)
		args[i+3] = id
	}
	args[0] = status
	args[1] = enabled
	args[2] = time.Now()

	sql := fmt.Sprintf(`
		UPDATE switches SET status=$1, enabled=$2, updated_at=$3
		WHERE id IN (%s) AND deleted_at IS NULL
	`, strings.Join(placeholders, ","))

	result, err := DB.Exec(ctx, sql, args...)
	if err != nil {
		logger.Errorf("batch update switch status error: %v", err)
		return 0, err
	}
	return result.RowsAffected(), nil
}

func (d *SwitchDAO) BatchUpdateStatusByService(ctx context.Context, serviceID string, status model.SwitchStatus, enabled bool, operator string) (int64, error) {
	sql := `
		UPDATE switches SET status=$1, enabled=$2, updated_at=$3
		WHERE service_id=$4 AND deleted_at IS NULL
	`
	result, err := DB.Exec(ctx, sql, status, enabled, time.Now(), serviceID)
	if err != nil {
		logger.Errorf("batch update switch status by service error: %v", err)
		return 0, err
	}
	return result.RowsAffected(), nil
}

func (d *SwitchDAO) CreateWithJSON(ctx context.Context, data []byte, operator string) error {
	var s model.Switch
	if err := json.Unmarshal(data, &s); err != nil {
		return err
	}
	s.ID = model.NewID()
	s.CreatedBy = operator
	s.CreatedAt = time.Now()
	s.UpdatedAt = time.Now()
	return d.Create(ctx, &s)
}

func (d *SwitchDAO) Exists(ctx context.Context, key string) (bool, error) {
	sql := `SELECT COUNT(*) FROM switches WHERE key=$1 AND deleted_at IS NULL`
	var count int64
	err := DB.QueryRow(ctx, sql, key).Scan(&count)
	if err != nil {
		return false, err
	}
	return count > 0, nil
}
