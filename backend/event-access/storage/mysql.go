package storage

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"gamestats/event-access/config"
	"gamestats/event-access/model"

	_ "github.com/go-sql-driver/mysql"
	"go.uber.org/zap"
)

type MySQLClient struct {
	db *sql.DB
}

func NewMySQLClient(cfg config.MySQLConfig) (*MySQLClient, error) {
	dsn := fmt.Sprintf("%s:%s@tcp(%s:%d)/%s?charset=utf8mb4&parseTime=True&loc=Local",
		cfg.User, cfg.Password, cfg.Host, cfg.Port, cfg.Database)

	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}

	db.SetMaxOpenConns(100)
	db.SetMaxIdleConns(10)
	db.SetConnMaxLifetime(time.Hour)

	if err := db.Ping(); err != nil {
		return nil, err
	}

	zap.L().Info("MySQL connection established")

	return &MySQLClient{db: db}, nil
}

func (c *MySQLClient) Close() {
	c.db.Close()
	zap.L().Info("MySQL connection closed")
}

func (c *MySQLClient) SaveEvent(ctx context.Context, event *model.GameEvent) error {
	eventDataJSON, err := json.Marshal(event.EventData)
	if err != nil {
		return err
	}

	query := `
		INSERT INTO events (event_id, player_id, game_id, server_id, event_type, event_time, event_data, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)
	`

	_, err = c.db.ExecContext(ctx, query,
		event.EventID,
		event.PlayerID,
		event.GameID,
		event.ServerID,
		event.EventType,
		event.EventTime,
		eventDataJSON,
		time.Now().UTC(),
	)

	return err
}

func (c *MySQLClient) GetEvent(ctx context.Context, eventID string) (*model.GameEvent, error) {
	query := `
		SELECT event_id, player_id, game_id, server_id, event_type, event_time, event_data
		FROM events WHERE event_id = ?
	`

	var event model.GameEvent
	var eventDataJSON []byte

	err := c.db.QueryRowContext(ctx, query, eventID).Scan(
		&event.EventID,
		&event.PlayerID,
		&event.GameID,
		&event.EventType,
		&event.EventTime,
		&eventDataJSON,
	)

	if err != nil {
		return nil, err
	}

	if err := json.Unmarshal(eventDataJSON, &event.EventData); err != nil {
		return nil, err
	}

	return &event, nil
}

func (c *MySQLClient) GetPlayerEvents(ctx context.Context, playerID string, limit int) ([]model.GameEvent, error) {
	query := `
		SELECT event_id, player_id, game_id, server_id, event_type, event_time, event_data
		FROM events 
		WHERE player_id = ? 
		ORDER BY event_time DESC 
		LIMIT ?
	`

	rows, err := c.db.QueryContext(ctx, query, playerID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var events []model.GameEvent
	for rows.Next() {
		var event model.GameEvent
		var eventDataJSON []byte

		if err := rows.Scan(
			&event.EventID,
			&event.PlayerID,
			&event.GameID,
			&event.ServerID,
			&event.EventType,
			&event.EventTime,
			&eventDataJSON,
		); err != nil {
			return nil, err
		}

		if err := json.Unmarshal(eventDataJSON, &event.EventData); err != nil {
			return nil, err
		}

		events = append(events, event)
	}

	return events, nil
}

func (c *MySQLClient) SavePlayerProfile(ctx context.Context, profile *model.PlayerProfile) error {
	tagsJSON, err := json.Marshal(profile.ProfileTags)
	if err != nil {
		return err
	}

	query := `
		INSERT INTO player_profiles (
			player_id, profile_tags, level, vip_level, total_play_time, 
			pay_amount, last_active, churn_risk, created_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON DUPLICATE KEY UPDATE
			profile_tags = VALUES(profile_tags),
			level = VALUES(level),
			vip_level = VALUES(vip_level),
			total_play_time = VALUES(total_play_time),
			pay_amount = VALUES(pay_amount),
			last_active = VALUES(last_active),
			churn_risk = VALUES(churn_risk),
			updated_at = VALUES(updated_at)
	`

	now := time.Now().UTC()
	_, err = c.db.ExecContext(ctx, query,
		profile.PlayerID,
		tagsJSON,
		profile.Level,
		profile.VIPLevel,
		profile.TotalPlayTime,
		profile.PayAmount,
		profile.LastActive,
		profile.ChurnRisk,
		now,
		now,
	)

	return err
}

func (c *MySQLClient) GetPlayerProfile(ctx context.Context, playerID string) (*model.PlayerProfile, error) {
	query := `
		SELECT player_id, profile_tags, level, vip_level, total_play_time, 
		       pay_amount, last_active, churn_risk
		FROM player_profiles WHERE player_id = ?
	`

	var profile model.PlayerProfile
	var tagsJSON []byte

	err := c.db.QueryRowContext(ctx, query, playerID).Scan(
		&profile.PlayerID,
		&tagsJSON,
		&profile.Level,
		&profile.VIPLevel,
		&profile.TotalPlayTime,
		&profile.PayAmount,
		&profile.LastActive,
		&profile.ChurnRisk,
	)

	if err != nil {
		return nil, err
	}

	if err := json.Unmarshal(tagsJSON, &profile.ProfileTags); err != nil {
		return nil, err
	}

	return &profile, nil
}

func (c *MySQLClient) GetAllProfiles(ctx context.Context) ([]model.PlayerProfile, error) {
	query := `
		SELECT player_id, profile_tags, level, vip_level, total_play_time, 
		       pay_amount, last_active, churn_risk
		FROM player_profiles
	`

	rows, err := c.db.QueryContext(ctx, query)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var profiles []model.PlayerProfile
	for rows.Next() {
		var profile model.PlayerProfile
		var tagsJSON []byte

		if err := rows.Scan(
			&profile.PlayerID,
			&tagsJSON,
			&profile.Level,
			&profile.VIPLevel,
			&profile.TotalPlayTime,
			&profile.PayAmount,
			&profile.LastActive,
			&profile.ChurnRisk,
		); err != nil {
			return nil, err
		}

		if err := json.Unmarshal(tagsJSON, &profile.ProfileTags); err != nil {
			return nil, err
		}

		profiles = append(profiles, profile)
	}

	return profiles, nil
}

func (c *MySQLClient) CreateEventConfig(ctx context.Context, config *model.EventConfig) error {
	requiredFieldsJSON, err := json.Marshal(config.RequiredFields)
	if err != nil {
		return err
	}
	
	optionalFieldsJSON, err := json.Marshal(config.OptionalFields)
	if err != nil {
		return err
	}

	query := `
		INSERT INTO event_configs (
			game_id, event_type, event_name, description, 
			required_fields, optional_fields, is_active, created_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
	`

	now := time.Now().UTC()
	result, err := c.db.ExecContext(ctx, query,
		config.GameID,
		config.EventType,
		config.EventName,
		config.Description,
		requiredFieldsJSON,
		optionalFieldsJSON,
		config.IsActive,
		now,
		now,
	)

	if err != nil {
		return err
	}

	id, err := result.LastInsertId()
	if err == nil {
		config.ID = id
	}

	return nil
}

func (c *MySQLClient) GetEventConfig(ctx context.Context, gameID, eventType string) (*model.EventConfig, error) {
	query := `
		SELECT id, game_id, event_type, event_name, description, 
		       required_fields, optional_fields, is_active, created_at, updated_at
		FROM event_configs 
		WHERE game_id = ? AND event_type = ?
	`

	var config model.EventConfig
	var requiredFieldsJSON, optionalFieldsJSON []byte

	err := c.db.QueryRowContext(ctx, query, gameID, eventType).Scan(
		&config.ID,
		&config.GameID,
		&config.EventType,
		&config.EventName,
		&config.Description,
		&requiredFieldsJSON,
		&optionalFieldsJSON,
		&config.IsActive,
		&config.CreatedAt,
		&config.UpdatedAt,
	)

	if err != nil {
		return nil, err
	}

	if err := json.Unmarshal(requiredFieldsJSON, &config.RequiredFields); err != nil {
		config.RequiredFields = map[string]string{}
	}

	if err := json.Unmarshal(optionalFieldsJSON, &config.OptionalFields); err != nil {
		config.OptionalFields = map[string]string{}
	}

	return &config, nil
}

func (c *MySQLClient) GetEventConfigs(ctx context.Context, gameID string) ([]model.EventConfig, error) {
	query := `
		SELECT id, game_id, event_type, event_name, description, 
		       required_fields, optional_fields, is_active, created_at, updated_at
		FROM event_configs 
		WHERE game_id = ?
		ORDER BY event_type
	`

	rows, err := c.db.QueryContext(ctx, query, gameID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var configs []model.EventConfig
	for rows.Next() {
		var config model.EventConfig
		var requiredFieldsJSON, optionalFieldsJSON []byte

		if err := rows.Scan(
			&config.ID,
			&config.GameID,
			&config.EventType,
			&config.EventName,
			&config.Description,
			&requiredFieldsJSON,
			&optionalFieldsJSON,
			&config.IsActive,
			&config.CreatedAt,
			&config.UpdatedAt,
		); err != nil {
			return nil, err
		}

		if err := json.Unmarshal(requiredFieldsJSON, &config.RequiredFields); err != nil {
			config.RequiredFields = map[string]string{}
		}

		if err := json.Unmarshal(optionalFieldsJSON, &config.OptionalFields); err != nil {
			config.OptionalFields = map[string]string{}
		}

		configs = append(configs, config)
	}

	return configs, nil
}

func (c *MySQLClient) UpdateEventConfig(ctx context.Context, config *model.EventConfig) error {
	requiredFieldsJSON, err := json.Marshal(config.RequiredFields)
	if err != nil {
		return err
	}
	
	optionalFieldsJSON, err := json.Marshal(config.OptionalFields)
	if err != nil {
		return err
	}

	query := `
		UPDATE event_configs SET
			event_name = ?,
			description = ?,
			required_fields = ?,
			optional_fields = ?,
			is_active = ?,
			updated_at = ?
		WHERE game_id = ? AND event_type = ?
	`

	now := time.Now().UTC()
	_, err = c.db.ExecContext(ctx, query,
		config.EventName,
		config.Description,
		requiredFieldsJSON,
		optionalFieldsJSON,
		config.IsActive,
		now,
		config.GameID,
		config.EventType,
	)

	return err
}

func (c *MySQLClient) DeleteEventConfig(ctx context.Context, gameID, eventType string) error {
	query := `
		DELETE FROM event_configs 
		WHERE game_id = ? AND event_type = ?
	`

	_, err := c.db.ExecContext(ctx, query, gameID, eventType)
	return err
}
