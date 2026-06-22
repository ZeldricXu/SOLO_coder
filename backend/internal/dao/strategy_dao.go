package dao

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/featureflag/platform/internal/model"
	"github.com/featureflag/platform/pkg/logger"
	"github.com/featureflag/platform/pkg/utils"
)

type StrategyDAO struct{}

func NewStrategyDAO() *StrategyDAO {
	return &StrategyDAO{}
}

func (d *StrategyDAO) Create(ctx context.Context, s *model.Strategy) error {
	sql := `
		INSERT INTO strategies (id, switch_id, name, description, operator, priority, enabled, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`
	_, err := DB.Exec(ctx, sql,
		s.ID, s.SwitchID, s.Name, s.Description, s.Operator, s.Priority, s.Enabled,
		time.Now(), time.Now(),
	)
	if err != nil {
		logger.Errorf("create strategy error: %v", err)
		return err
	}
	return nil
}

func (d *StrategyDAO) Update(ctx context.Context, s *model.Strategy) error {
	sql := `
		UPDATE strategies SET name=$1, description=$2, operator=$3, priority=$4, enabled=$5, updated_at=$6
		WHERE id=$7
	`
	_, err := DB.Exec(ctx, sql,
		s.Name, s.Description, s.Operator, s.Priority, s.Enabled, time.Now(), s.ID,
	)
	if err != nil {
		logger.Errorf("update strategy error: %v", err)
		return err
	}
	return nil
}

func (d *StrategyDAO) Delete(ctx context.Context, id string) error {
	sql := `DELETE FROM strategies WHERE id=$1`
	_, err := DB.Exec(ctx, sql, id)
	if err != nil {
		logger.Errorf("delete strategy error: %v", err)
		return err
	}
	return nil
}

func (d *StrategyDAO) DeleteBySwitchID(ctx context.Context, switchID string) error {
	sql := `DELETE FROM strategies WHERE switch_id=$1`
	_, err := DB.Exec(ctx, sql, switchID)
	if err != nil {
		logger.Errorf("delete strategies by switch id error: %v", err)
		return err
	}
	return nil
}

func (d *StrategyDAO) GetByID(ctx context.Context, id string) (*model.Strategy, error) {
	sql := `
		SELECT id, switch_id, name, description, operator, priority, enabled, created_at, updated_at
		FROM strategies WHERE id=$1
	`
	var s model.Strategy
	err := DB.QueryRow(ctx, sql, id).Scan(
		&s.ID, &s.SwitchID, &s.Name, &s.Description, &s.Operator, &s.Priority, &s.Enabled,
		&s.CreatedAt, &s.UpdatedAt,
	)
	if err != nil {
		logger.Errorf("get strategy by id error: %v", err)
		return nil, err
	}
	return &s, nil
}

func (d *StrategyDAO) GetBySwitchID(ctx context.Context, switchID string) ([]*model.Strategy, error) {
	sql := `
		SELECT id, switch_id, name, description, operator, priority, enabled, created_at, updated_at
		FROM strategies WHERE switch_id=$1 ORDER BY priority ASC, created_at ASC
	`
	rows, err := DB.Query(ctx, sql, switchID)
	if err != nil {
		logger.Errorf("get strategies by switch id error: %v", err)
		return nil, err
	}
	defer rows.Close()

	strategies := make([]*model.Strategy, 0)
	for rows.Next() {
		var s model.Strategy
		err := rows.Scan(
			&s.ID, &s.SwitchID, &s.Name, &s.Description, &s.Operator, &s.Priority, &s.Enabled,
			&s.CreatedAt, &s.UpdatedAt,
		)
		if err != nil {
			logger.Errorf("scan strategy error: %v", err)
			return nil, err
		}
		strategies = append(strategies, &s)
	}
	return strategies, nil
}

func (d *StrategyDAO) GetAllWithConditions(ctx context.Context) (map[string][]*model.Strategy, error) {
	sql := `
		SELECT s.id, s.switch_id, s.name, s.description, s.operator, s.priority, s.enabled,
			w.id, w.strategy_id, w.field, w.operator, w.values
		FROM strategies s
		LEFT JOIN whitelist_conditions w ON s.id = w.strategy_id
		WHERE s.enabled = true
		ORDER BY s.switch_id, s.priority ASC, s.created_at ASC
	`
	rows, err := DB.Query(ctx, sql)
	if err != nil {
		logger.Errorf("get all strategies with conditions error: %v", err)
		return nil, err
	}
	defer rows.Close()

	result := make(map[string][]*model.Strategy)
	strategyMap := make(map[string]*model.Strategy)

	for rows.Next() {
		var s model.Strategy
		var c model.WhitelistCondition
		var cID, cStrategyID *string
		var cField, cOperator *string
		var cValues model.StringArray

		err := rows.Scan(
			&s.ID, &s.SwitchID, &s.Name, &s.Description, &s.Operator, &s.Priority, &s.Enabled,
			&cID, &cStrategyID, &cField, &cOperator, &cValues,
		)
		if err != nil {
			logger.Errorf("scan strategy with condition error: %v", err)
			return nil, err
		}

		if _, ok := strategyMap[s.ID]; !ok {
			s.Conditions = make([]*model.WhitelistCondition, 0)
			strategyMap[s.ID] = &s
			result[s.SwitchID] = append(result[s.SwitchID], &s)
		}

		if cID != nil {
			cond := &model.WhitelistCondition{
				ID:         *cID,
				StrategyID: *cStrategyID,
				Field:      model.WhitelistField(*cField),
				Operator:   model.WhitelistOperator(*cOperator),
				Values:     cValues,
			}
			strategyMap[s.ID].Conditions = append(strategyMap[s.ID].Conditions, cond)
		}
	}

	return result, nil
}

type ConditionDAO struct{}

func NewConditionDAO() *ConditionDAO {
	return &ConditionDAO{}
}

func (d *ConditionDAO) Create(ctx context.Context, c *model.WhitelistCondition) error {
	sql := `
		INSERT INTO whitelist_conditions (id, strategy_id, field, operator, values, created_at)
		VALUES ($1, $2, $3, $4, $5, $6)
	`
	_, err := DB.Exec(ctx, sql,
		c.ID, c.StrategyID, c.Field, c.Operator, c.Values, time.Now(),
	)
	if err != nil {
		logger.Errorf("create whitelist condition error: %v", err)
		return err
	}
	return nil
}

func (d *ConditionDAO) BatchCreate(ctx context.Context, conditions []*model.WhitelistCondition) error {
	if len(conditions) == 0 {
		return nil
	}

	placeholders := make([]string, 0, len(conditions))
	values := make([]interface{}, 0, len(conditions)*6)

	for i, c := range conditions {
		base := i * 6
		placeholders = append(placeholders, fmt.Sprintf("($%d, $%d, $%d, $%d, $%d, $%d)",
			base+1, base+2, base+3, base+4, base+5, base+6))
		values = append(values, c.ID, c.StrategyID, c.Field, c.Operator, c.Values, time.Now())
	}

	sql := fmt.Sprintf(`
		INSERT INTO whitelist_conditions (id, strategy_id, field, operator, values, created_at)
		VALUES %s
	`, strings.Join(placeholders, ","))

	_, err := DB.Exec(ctx, sql, values...)
	if err != nil {
		logger.Errorf("batch create whitelist conditions error: %v", err)
		return err
	}
	return nil
}

func (d *ConditionDAO) DeleteByStrategyID(ctx context.Context, strategyID string) error {
	sql := `DELETE FROM whitelist_conditions WHERE strategy_id=$1`
	_, err := DB.Exec(ctx, sql, strategyID)
	if err != nil {
		logger.Errorf("delete conditions by strategy id error: %v", err)
		return err
	}
	return nil
}

func (d *ConditionDAO) GetByStrategyID(ctx context.Context, strategyID string) ([]*model.WhitelistCondition, error) {
	sql := `
		SELECT id, strategy_id, field, operator, values, created_at
		FROM whitelist_conditions WHERE strategy_id=$1 ORDER BY created_at ASC
	`
	rows, err := DB.Query(ctx, sql, strategyID)
	if err != nil {
		logger.Errorf("get conditions by strategy id error: %v", err)
		return nil, err
	}
	defer rows.Close()

	conditions := make([]*model.WhitelistCondition, 0)
	for rows.Next() {
		var c model.WhitelistCondition
		err := rows.Scan(&c.ID, &c.StrategyID, &c.Field, &c.Operator, &c.Values, &c.CreatedAt)
		if err != nil {
			logger.Errorf("scan whitelist condition error: %v", err)
			return nil, err
		}
		conditions = append(conditions, &c)
	}
	return conditions, nil
}

func (d *ConditionDAO) GetByStrategyIDs(ctx context.Context, strategyIDs []string) (map[string][]*model.WhitelistCondition, error) {
	if len(strategyIDs) == 0 {
		return make(map[string][]*model.WhitelistCondition), nil
	}

	placeholders := make([]string, len(strategyIDs))
	args := make([]interface{}, len(strategyIDs))
	for i, id := range strategyIDs {
		placeholders[i] = fmt.Sprintf("$%d", i+1)
		args[i] = id
	}

	sql := fmt.Sprintf(`
		SELECT id, strategy_id, field, operator, values, created_at
		FROM whitelist_conditions WHERE strategy_id IN (%s) ORDER BY created_at ASC
	`, strings.Join(placeholders, ","))

	rows, err := DB.Query(ctx, sql, args...)
	if err != nil {
		logger.Errorf("get conditions by strategy ids error: %v", err)
		return nil, err
	}
	defer rows.Close()

	result := make(map[string][]*model.WhitelistCondition)
	for rows.Next() {
		var c model.WhitelistCondition
		err := rows.Scan(&c.ID, &c.StrategyID, &c.Field, &c.Operator, &c.Values, &c.CreatedAt)
		if err != nil {
			logger.Errorf("scan whitelist condition error: %v", err)
			return nil, err
		}
		result[c.StrategyID] = append(result[c.StrategyID], &c)
	}
	return result, nil
}
