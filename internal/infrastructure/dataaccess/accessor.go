package dataaccess

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type FileDataAccessor struct {
	dataDir      string
	records      map[string]*domain.DataRecord
	migrations   []Migration
	mu           sync.RWMutex
	logger       domain.Logger
	clock        domain.Clock
}

type Migration struct {
	Version     int
	Name        string
	Description string
	Up          func(map[string]interface{}) map[string]interface{}
	Down        func(map[string]interface{}) map[string]interface{}
}

type DataAccessorConfig struct {
	DataDir string
	Logger  domain.Logger
}

func NewFileDataAccessor(cfg DataAccessorConfig) (*FileDataAccessor, error) {
	if cfg.DataDir == "" {
		cfg.DataDir = "./data"
	}

	if err := os.MkdirAll(cfg.DataDir, 0755); err != nil {
		return nil, err
	}

	accessor := &FileDataAccessor{
		dataDir:    cfg.DataDir,
		records:    make(map[string]*domain.DataRecord),
		migrations: []Migration{},
		logger:     cfg.Logger,
		clock:      utils.NewRealClock(),
	}

	accessor.registerDefaultMigrations()

	if err := accessor.loadRecords(); err != nil {
		return nil, err
	}

	return accessor, nil
}

func (a *FileDataAccessor) registerDefaultMigrations() {
	a.migrations = append(a.migrations, Migration{
		Version:     1,
		Name:        "initial_schema",
		Description: "Initial data schema with basic fields",
		Up: func(data map[string]interface{}) map[string]interface{} {
			return data
		},
		Down: func(data map[string]interface{}) map[string]interface{} {
			return data
		},
	})

	a.migrations = append(a.migrations, Migration{
		Version:     2,
		Name:        "add_metadata",
		Description: "Add metadata fields to all records",
		Up: func(data map[string]interface{}) map[string]interface{} {
			if _, exists := data["_metadata"]; !exists {
				data["_metadata"] = map[string]interface{}{
					"schema_version": 2,
					"migrated_at":    time.Now().UTC().Format(time.RFC3339),
				}
			}
			return data
		},
		Down: func(data map[string]interface{}) map[string]interface{} {
			delete(data, "_metadata")
			return data
		},
	})

	a.migrations = append(a.migrations, Migration{
		Version:     3,
		Name:        "normalize_timestamps",
		Description: "Ensure all timestamps are in RFC3339 format",
		Up: func(data map[string]interface{}) map[string]interface{} {
			for _, key := range []string{"created_at", "updated_at", "timestamp"} {
				if val, ok := data[key].(string); ok {
					if t, err := time.Parse(time.RFC3339, val); err == nil {
						data[key] = t.Format(time.RFC3339)
					}
				}
			}
			return data
		},
		Down: func(data map[string]interface{}) map[string]interface{} {
			return data
		},
	})
}

func (a *FileDataAccessor) Migrate(ctx context.Context, targetVersion int) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	currentVersion, _ := a.getCurrentVersion()

	if targetVersion == 0 {
		targetVersion = len(a.migrations)
	}

	if targetVersion < currentVersion {
		return a.migrateDown(ctx, currentVersion, targetVersion)
	} else if targetVersion > currentVersion {
		return a.migrateUp(ctx, currentVersion, targetVersion)
	}

	return nil
}

func (a *FileDataAccessor) migrateUp(ctx context.Context, from, to int) error {
	sort.Slice(a.migrations, func(i, j int) bool {
		return a.migrations[i].Version < a.migrations[j].Version
	})

	migrated := 0
	for _, mig := range a.migrations {
		if mig.Version > from && mig.Version <= to {
			a.logger.Info("applying migration", "version", mig.Version, "name", mig.Name)

			for id, record := range a.records {
				record.Payload = mig.Up(record.Payload)
				record.SchemaVersion = mig.Version
				a.records[id] = record
			}

			migrated++
		}
	}

	if err := a.saveAll(); err != nil {
		return err
	}

	a.setCurrentVersion(to)
	a.logger.Info("migration complete", "migrations_applied", migrated, "current_version", to)
	return nil
}

func (a *FileDataAccessor) migrateDown(ctx context.Context, from, to int) error {
	sort.Slice(a.migrations, func(i, j int) bool {
		return a.migrations[i].Version > a.migrations[j].Version
	})

	migrated := 0
	for _, mig := range a.migrations {
		if mig.Version <= from && mig.Version > to {
			a.logger.Info("rolling back migration", "version", mig.Version, "name", mig.Name)

			for id, record := range a.records {
				record.Payload = mig.Down(record.Payload)
				record.SchemaVersion = mig.Version - 1
				a.records[id] = record
			}

			migrated++
		}
	}

	if err := a.saveAll(); err != nil {
		return err
	}

	a.setCurrentVersion(to)
	a.logger.Info("rollback complete", "migrations_rolled_back", migrated, "current_version", to)
	return nil
}

func (a *FileDataAccessor) GetSchemaVersion(ctx context.Context) (int, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.getCurrentVersion()
}

func (a *FileDataAccessor) getCurrentVersion() (int, error) {
	path := filepath.Join(a.dataDir, "schema_version")
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return 1, nil
		}
		return 0, err
	}

	var version int
	if _, err := fmt.Sscanf(string(data), "%d", &version); err != nil {
		return 1, nil
	}
	return version, nil
}

func (a *FileDataAccessor) setCurrentVersion(version int) {
	path := filepath.Join(a.dataDir, "schema_version")
	os.WriteFile(path, []byte(fmt.Sprintf("%d", version)), 0644)
}

func (a *FileDataAccessor) SaveRecord(ctx context.Context, record *domain.DataRecord) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if record.ID == "" {
		record.ID = utils.NewID("rec")
	}
	if record.SchemaVersion == 0 {
		version, _ := a.getCurrentVersion()
		record.SchemaVersion = version
	}
	record.CreatedAt = a.clock.Now()

	a.records[record.ID] = record

	if err := a.saveRecord(record); err != nil {
		delete(a.records, record.ID)
		return err
	}

	a.logger.Debug("record saved", "id", record.ID, "schema_version", record.SchemaVersion)
	return nil
}

func (a *FileDataAccessor) GetRecord(ctx context.Context, id string) (*domain.DataRecord, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	record, exists := a.records[id]
	if !exists {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("record not found: %s", id))
	}

	result := *record
	return &result, nil
}

func (a *FileDataAccessor) QueryRecords(ctx context.Context, filter map[string]interface{}) ([]domain.DataRecord, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	var result []domain.DataRecord

	for _, record := range a.records {
		if a.matchesFilter(record, filter) {
			result = append(result, *record)
		}
	}

	return result, nil
}

func (a *FileDataAccessor) matchesFilter(record *domain.DataRecord, filter map[string]interface{}) bool {
	if filter == nil || len(filter) == 0 {
		return true
	}

	for key, expected := range filter {
		actual, exists := record.Payload[key]
		if !exists {
			return false
		}
		if fmt.Sprintf("%v", actual) != fmt.Sprintf("%v", expected) {
			return false
		}
	}

	return true
}

func (a *FileDataAccessor) loadRecords() error {
	recordsDir := filepath.Join(a.dataDir, "records")
	if err := os.MkdirAll(recordsDir, 0755); err != nil {
		return err
	}

	files, err := os.ReadDir(recordsDir)
	if err != nil {
		return err
	}

	for _, f := range files {
		if f.IsDir() || !filepath.HasSuffix(f.Name(), ".json") {
			continue
		}

		data, err := os.ReadFile(filepath.Join(recordsDir, f.Name()))
		if err != nil {
			continue
		}

		var record domain.DataRecord
		if err := json.Unmarshal(data, &record); err != nil {
			continue
		}

		a.records[record.ID] = &record
	}

	a.logger.Info("records loaded", "count", len(a.records))
	return nil
}

func (a *FileDataAccessor) saveRecord(record *domain.DataRecord) error {
	recordsDir := filepath.Join(a.dataDir, "records")
	data, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(recordsDir, record.ID+".json"), data, 0644)
}

func (a *FileDataAccessor) saveAll() error {
	for _, record := range a.records {
		if err := a.saveRecord(record); err != nil {
			return err
		}
	}
	return nil
}

func (a *FileDataAccessor) RegisterMigration(mig Migration) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.migrations = append(a.migrations, mig)
}

func (a *FileDataAccessor) GetMigrations() []Migration {
	a.mu.RLock()
	defer a.mu.RUnlock()

	result := make([]Migration, len(a.migrations))
	copy(result, a.migrations)
	sort.Slice(result, func(i, j int) bool {
		return result[i].Version < result[j].Version
	})
	return result
}

func (a *FileDataAccessor) DeleteRecord(ctx context.Context, id string) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if _, exists := a.records[id]; !exists {
		return apperr.NewNotFoundError(fmt.Sprintf("record not found: %s", id))
	}

	delete(a.records, id)
	recordsDir := filepath.Join(a.dataDir, "records")
	os.Remove(filepath.Join(recordsDir, id+".json"))

	a.logger.Debug("record deleted", "id", id)
	return nil
}

func (a *FileDataAccessor) UpdateRecord(ctx context.Context, id string, updates map[string]interface{}) (*domain.DataRecord, error) {
	a.mu.Lock()
	defer a.mu.Unlock()

	record, exists := a.records[id]
	if !exists {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("record not found: %s", id))
	}

	for k, v := range updates {
		record.Payload[k] = v
	}

	if err := a.saveRecord(record); err != nil {
		return nil, err
	}

	result := *record
	return &result, nil
}

func (a *FileDataAccessor) CountRecords(ctx context.Context, filter map[string]interface{}) (int, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	count := 0
	for _, record := range a.records {
		if a.matchesFilter(record, filter) {
			count++
		}
	}
	return count, nil
}

func (a *FileDataAccessor) BatchSave(ctx context.Context, records []*domain.DataRecord) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	for _, record := range records {
		if record.ID == "" {
			record.ID = utils.NewID("rec")
		}
		if record.SchemaVersion == 0 {
			version, _ := a.getCurrentVersion()
			record.SchemaVersion = version
		}
		record.CreatedAt = a.clock.Now()
		a.records[record.ID] = record
	}

	return a.saveAll()
}
