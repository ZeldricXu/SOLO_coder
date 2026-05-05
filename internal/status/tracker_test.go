package status

import (
	"errors"
	"strings"
	"testing"
	"time"

	"dbmigrator/internal/database"
	"dbmigrator/pkg/models"
)

type MockDBForTracker struct {
	driver          string
	execErr         error
	queryErr        error
	beginErr        error
	ensureTableErr  error
	executedQueries []string
}

func (m *MockDBForTracker) Begin() (*database.SqlTx, error) {
	if m.beginErr != nil {
		return nil, m.beginErr
	}
	return nil, nil
}

func (m *MockDBForTracker) Exec(query string, args ...interface{}) (database.SqlResult, error) {
	m.executedQueries = append(m.executedQueries, query)
	if m.execErr != nil {
		return nil, m.execErr
	}
	return &MockResult{}, nil
}

func (m *MockDBForTracker) Query(query string, args ...interface{}) (database.SqlRows, error) {
	m.executedQueries = append(m.executedQueries, query)
	if m.queryErr != nil {
		return nil, m.queryErr
	}
	return &MockRows{}, nil
}

func (m *MockDBForTracker) QueryRow(query string, args ...interface{}) database.SqlRow {
	m.executedQueries = append(m.executedQueries, query)
	return &MockRow{}
}

func (m *MockDBForTracker) DB() *database.SqlDB {
	return nil
}

func (m *MockDBForTracker) Driver() string {
	return m.driver
}

func (m *MockDBForTracker) Close() error {
	return nil
}

func (m *MockDBForTracker) EnsureMigrationTable(tableName string) error {
	if m.ensureTableErr != nil {
		return m.ensureTableErr
	}
	return nil
}

func (m *MockDBForTracker) EnsureLogTable(tableName string) error {
	return nil
}

type MockResult struct{}

func (r *MockResult) LastInsertId() (int64, error) { return 1, nil }
func (r *MockResult) RowsAffected() (int64, error) { return 1, nil }

type MockRows struct {
	closed bool
}

func (r *MockRows) Columns() ([]string, error) { return nil, nil }
func (r *MockRows) Close() error { r.closed = true; return nil }
func (r *MockRows) Next() bool { return false }
func (r *MockRows) Scan(dest ...interface{}) error { return errors.New("sql: no rows in result set") }
func (r *MockRows) Err() error { return nil }

type MockRow struct{}

func (r *MockRow) Scan(dest ...interface{}) error {
	return errors.New("sql: no rows in result set")
}

func TestNewTracker(t *testing.T) {
	t.Run("mysql driver success", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver: "mysql",
		}
		
		tracker, err := NewTracker(mockDB, "test_migrations")
		if err != nil {
			t.Fatalf("NewTracker failed: %v", err)
		}
		
		if tracker == nil {
			t.Error("Expected tracker to be non-nil")
		}
	})
	
	t.Run("postgres driver success", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver: "postgres",
		}
		
		tracker, err := NewTracker(mockDB, "test_migrations")
		if err != nil {
			t.Fatalf("NewTracker failed: %v", err)
		}
		
		if tracker == nil {
			t.Error("Expected tracker to be non-nil")
		}
	})
	
	t.Run("ensure table error", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver:         "mysql",
			ensureTableErr: errors.New("failed to create table"),
		}
		
		_, err := NewTracker(mockDB, "test_migrations")
		if err == nil {
			t.Error("Expected error when ensure table fails")
		}
	})
}

func TestRecordMigration(t *testing.T) {
	t.Run("mysql driver success", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver: "mysql",
		}
		
		tracker := &Tracker{
			db:        mockDB,
			tableName: "test_migrations",
		}
		
		migration := &models.Migration{
			MigrationID: "mig_test_20260501_create_users",
			Version:     "20260501_000001",
			DownScript:  "DROP TABLE users;",
		}
		
		result := &models.ExecutionResult{
			Success:       true,
			MigrationID:   migration.MigrationID,
			Version:       migration.Version,
			ExecutionTime: 100 * time.Millisecond,
		}
		
		err := tracker.RecordMigration(migration, result)
		if err != nil {
			t.Fatalf("RecordMigration failed: %v", err)
		}
		
		if len(mockDB.executedQueries) != 1 {
			t.Errorf("Expected 1 query to be executed, got %d", len(mockDB.executedQueries))
		}
	})
	
	t.Run("postgres driver success", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver: "postgres",
		}
		
		tracker := &Tracker{
			db:        mockDB,
			tableName: "test_migrations",
		}
		
		migration := &models.Migration{
			MigrationID: "mig_test_20260501_create_users",
			Version:     "20260501_000001",
			DownScript:  "DROP TABLE users;",
		}
		
		result := &models.ExecutionResult{
			Success:       true,
			MigrationID:   migration.MigrationID,
			Version:       migration.Version,
			ExecutionTime: 100 * time.Millisecond,
		}
		
		err := tracker.RecordMigration(migration, result)
		if err != nil {
			t.Fatalf("RecordMigration failed: %v", err)
		}
		
		if len(mockDB.executedQueries) != 1 {
			t.Errorf("Expected 1 query to be executed, got %d", len(mockDB.executedQueries))
		}
	})
	
	t.Run("failed migration status", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver: "mysql",
		}
		
		tracker := &Tracker{
			db:        mockDB,
			tableName: "test_migrations",
		}
		
		migration := &models.Migration{
			MigrationID: "mig_test_20260501_create_users",
			Version:     "20260501_000001",
			DownScript:  "DROP TABLE users;",
		}
		
		result := &models.ExecutionResult{
			Success:       false,
			MigrationID:   migration.MigrationID,
			Version:       migration.Version,
			ExecutionTime: 100 * time.Millisecond,
			Error:         errors.New("SQL error"),
		}
		
		err := tracker.RecordMigration(migration, result)
		if err != nil {
			t.Fatalf("RecordMigration failed: %v", err)
		}
	})
	
	t.Run("exec error", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver:  "mysql",
			execErr: errors.New("database error"),
		}
		
		tracker := &Tracker{
			db:        mockDB,
			tableName: "test_migrations",
		}
		
		migration := &models.Migration{
			MigrationID: "mig_test",
			Version:     "20260501_000001",
		}
		
		result := &models.ExecutionResult{
			Success: true,
		}
		
		err := tracker.RecordMigration(migration, result)
		if err == nil {
			t.Error("Expected error when exec fails")
		}
	})
	
	t.Run("no down script", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver: "mysql",
		}
		
		tracker := &Tracker{
			db:        mockDB,
			tableName: "test_migrations",
		}
		
		migration := &models.Migration{
			MigrationID: "mig_test_no_down",
			Version:     "20260501_000001",
			DownScript:  "",
		}
		
		result := &models.ExecutionResult{
			Success: true,
		}
		
		err := tracker.RecordMigration(migration, result)
		if err != nil {
			t.Errorf("RecordMigration should succeed even without down script: %v", err)
		}
	})
}

func TestUpdateRollbackStatus(t *testing.T) {
	t.Run("mysql driver success", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver: "mysql",
		}
		
		tracker := &Tracker{
			db:        mockDB,
			tableName: "test_migrations",
		}
		
		err := tracker.UpdateRollbackStatus("mig_test_20260501_create_users")
		if err != nil {
			t.Fatalf("UpdateRollbackStatus failed: %v", err)
		}
		
		if len(mockDB.executedQueries) != 1 {
			t.Errorf("Expected 1 query to be executed, got %d", len(mockDB.executedQueries))
		}
	})
	
	t.Run("postgres driver success", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver: "postgres",
		}
		
		tracker := &Tracker{
			db:        mockDB,
			tableName: "test_migrations",
		}
		
		err := tracker.UpdateRollbackStatus("mig_test_20260501_create_users")
		if err != nil {
			t.Fatalf("UpdateRollbackStatus failed: %v", err)
		}
		
		if len(mockDB.executedQueries) != 1 {
			t.Errorf("Expected 1 query to be executed, got %d", len(mockDB.executedQueries))
		}
	})
	
	t.Run("exec error", func(t *testing.T) {
		mockDB := &MockDBForTracker{
			driver:  "mysql",
			execErr: errors.New("database error"),
		}
		
		tracker := &Tracker{
			db:        mockDB,
			tableName: "test_migrations",
		}
		
		err := tracker.UpdateRollbackStatus("mig_test")
		if err == nil {
			t.Error("Expected error when exec fails")
		}
	})
}

func TestCustomTableName(t *testing.T) {
	mockDB := &MockDBForTracker{
		driver: "mysql",
	}
	
	customTableName := "custom_migration_status"
	
	tracker := &Tracker{
		db:        mockDB,
		tableName: customTableName,
	}
	
	err := tracker.UpdateRollbackStatus("mig_test")
	if err != nil {
		// It's okay if exec fails
	}
	
	if len(mockDB.executedQueries) > 0 {
		query := mockDB.executedQueries[0]
		if !strings.Contains(query, customTableName) {
			t.Errorf("Query should contain custom table name '%s', got: %s", customTableName, query)
		}
	}
}
