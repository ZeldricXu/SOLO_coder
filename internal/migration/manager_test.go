package migration

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"dbmigrator/pkg/models"
)

func createTestMigration(t *testing.T, tempDir, version, name string, dependencies []string, upScript, downScript string) {
	upFileName := filepath.Join(tempDir, version+"_"+name+".up.sql")
	downFileName := filepath.Join(tempDir, version+"_"+name+".down.sql")

	upContent := upScript
	if len(dependencies) > 0 {
		upContent = "-- Migration: " + version + "_" + name + "\n"
		for _, dep := range dependencies {
			upContent += "-- @depends " + dep + "\n"
		}
		upContent += upScript
	}

	if err := os.WriteFile(upFileName, []byte(upContent), 0644); err != nil {
		t.Fatalf("Failed to create up migration file: %v", err)
	}

	if err := os.WriteFile(downFileName, []byte(downScript), 0644); err != nil {
		t.Fatalf("Failed to create down migration file: %v", err)
	}
}

func TestTopologicalSort_LinearDependency(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000001", "create_users", nil,
		"CREATE TABLE users (id INT PRIMARY KEY);",
		"DROP TABLE users;")

	createTestMigration(t, tempDir, "20260501_000002", "create_posts",
		[]string{"mig_20260501_000001_create_users"},
		"CREATE TABLE posts (id INT PRIMARY KEY, user_id INT);",
		"DROP TABLE posts;")

	createTestMigration(t, tempDir, "20260501_000003", "create_comments",
		[]string{"mig_20260501_000002_create_posts"},
		"CREATE TABLE comments (id INT PRIMARY KEY, post_id INT);",
		"DROP TABLE comments;")

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	migrations := manager.GetMigrations()

	if len(migrations) != 3 {
		t.Fatalf("Expected 3 migrations, got %d", len(migrations))
	}

	expectedOrder := []string{
		"mig_20260501_000001_create_users",
		"mig_20260501_000002_create_posts",
		"mig_20260501_000003_create_comments",
	}

	for i, expected := range expectedOrder {
		if migrations[i].MigrationID != expected {
			t.Errorf("Migration %d: expected %s, got %s", i, expected, migrations[i].MigrationID)
		}
	}
}

func TestTopologicalSort_MultiBranchDependency(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000001", "create_users", nil,
		"CREATE TABLE users (id INT PRIMARY KEY);",
		"DROP TABLE users;")

	createTestMigration(t, tempDir, "20260501_000002", "create_products", nil,
		"CREATE TABLE products (id INT PRIMARY KEY);",
		"DROP TABLE products;")

	createTestMigration(t, tempDir, "20260501_000003", "create_orders",
		[]string{"mig_20260501_000001_create_users", "mig_20260501_000002_create_products"},
		"CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, product_id INT);",
		"DROP TABLE orders;")

	createTestMigration(t, tempDir, "20260501_000004", "create_order_items",
		[]string{"mig_20260501_000003_create_orders"},
		"CREATE TABLE order_items (id INT PRIMARY KEY, order_id INT);",
		"DROP TABLE order_items;")

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	migrations := manager.GetMigrations()

	if len(migrations) != 4 {
		t.Fatalf("Expected 4 migrations, got %d", len(migrations))
	}

	position := make(map[string]int)
	for i, mig := range migrations {
		position[mig.MigrationID] = i
	}

	if position["mig_20260501_000001_create_users"] >= position["mig_20260501_000003_create_orders"] {
		t.Error("create_users should come before create_orders")
	}

	if position["mig_20260501_000002_create_products"] >= position["mig_20260501_000003_create_orders"] {
		t.Error("create_products should come before create_orders")
	}

	if position["mig_20260501_000003_create_orders"] >= position["mig_20260501_000004_create_order_items"] {
		t.Error("create_orders should come before create_order_items")
	}
}

func TestTopologicalSort_CyclicDependency(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000001", "migration_a",
		[]string{"mig_20260501_000002_migration_b"},
		"SELECT 1;",
		"SELECT 1;")

	createTestMigration(t, tempDir, "20260501_000002", "migration_b",
		[]string{"mig_20260501_000001_migration_a"},
		"SELECT 1;",
		"SELECT 1;")

	_, err = NewManager(tempDir)
	if err == nil {
		t.Error("Expected error for cyclic dependency, got nil")
	}
}

func TestTopologicalSort_NoDependency(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000003", "migration_c", nil,
		"SELECT 1;",
		"SELECT 1;")

	createTestMigration(t, tempDir, "20260501_000001", "migration_a", nil,
		"SELECT 1;",
		"SELECT 1;")

	createTestMigration(t, tempDir, "20260501_000002", "migration_b", nil,
		"SELECT 1;",
		"SELECT 1;")

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	migrations := manager.GetMigrations()

	if len(migrations) != 3 {
		t.Fatalf("Expected 3 migrations, got %d", len(migrations))
	}

	expectedOrder := []string{
		"mig_20260501_000001_migration_a",
		"mig_20260501_000002_migration_b",
		"mig_20260501_000003_migration_c",
	}

	for i, expected := range expectedOrder {
		if migrations[i].MigrationID != expected {
			t.Errorf("Migration %d: expected %s, got %s", i, expected, migrations[i].MigrationID)
		}
	}
}

func TestTopologicalSort_NonExistentDependency(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000001", "migration_a",
		[]string{"mig_nonexistent_dependency"},
		"SELECT 1;",
		"SELECT 1;")

	_, err = NewManager(tempDir)
	if err == nil {
		t.Error("Expected error for non-existent dependency, got nil")
	}
}

func TestDependencyGraph_Build(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000001", "base", nil,
		"CREATE TABLE base (id INT);",
		"DROP TABLE base;")

	createTestMigration(t, tempDir, "20260501_000002", "dependent",
		[]string{"mig_20260501_000001_base"},
		"CREATE TABLE dependent (id INT);",
		"DROP TABLE dependent;")

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	graph := manager.GetDependencyGraph()
	if graph == nil {
		t.Fatal("Expected dependency graph to be non-nil")
	}

	if len(graph.Nodes) != 2 {
		t.Errorf("Expected 2 nodes in graph, got %d", len(graph.Nodes))
	}

	baseNode, exists := graph.Nodes["mig_20260501_000001_base"]
	if !exists {
		t.Error("Base node not found in graph")
	} else {
		if len(baseNode.Dependents) != 1 {
			t.Errorf("Expected base node to have 1 dependent, got %d", len(baseNode.Dependents))
		}
		if len(baseNode.Dependencies) != 0 {
			t.Errorf("Expected base node to have 0 dependencies, got %d", len(baseNode.Dependencies))
		}
	}

	dependentNode, exists := graph.Nodes["mig_20260501_000002_dependent"]
	if !exists {
		t.Error("Dependent node not found in graph")
	} else {
		if len(dependentNode.Dependencies) != 1 {
			t.Errorf("Expected dependent node to have 1 dependency, got %d", len(dependentNode.Dependencies))
		}
	}
}

func TestResolveDependencies(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000001", "a", nil,
		"SELECT 1;",
		"SELECT 1;")

	createTestMigration(t, tempDir, "20260501_000002", "b",
		[]string{"mig_20260501_000001_a"},
		"SELECT 1;",
		"SELECT 1;")

	createTestMigration(t, tempDir, "20260501_000003", "c",
		[]string{"mig_20260501_000002_b"},
		"SELECT 1;",
		"SELECT 1;")

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	migC, exists := manager.GetMigrationByID("mig_20260501_000003_c")
	if !exists {
		t.Fatal("Migration c not found")
	}

	resolved, err := manager.ResolveDependencies(migC)
	if err != nil {
		t.Fatalf("ResolveDependencies failed: %v", err)
	}

	if len(resolved) != 3 {
		t.Errorf("Expected 3 resolved migrations, got %d", len(resolved))
	}

	expectedOrder := []string{
		"mig_20260501_000001_a",
		"mig_20260501_000002_b",
		"mig_20260501_000003_c",
	}

	for i, expected := range expectedOrder {
		if resolved[i].MigrationID != expected {
			t.Errorf("Resolved migration %d: expected %s, got %s", i, expected, resolved[i].MigrationID)
		}
	}
}

func TestGetPendingMigrations(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000001", "a", nil,
		"SELECT 1;",
		"SELECT 1;")

	createTestMigration(t, tempDir, "20260501_000002", "b", nil,
		"SELECT 1;",
		"SELECT 1;")

	createTestMigration(t, tempDir, "20260501_000003", "c", nil,
		"SELECT 1;",
		"SELECT 1;")

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	executedIDs := map[string]bool{
		"mig_20260501_000001_a": true,
	}

	pending := manager.GetPendingMigrations(executedIDs)

	if len(pending) != 2 {
		t.Errorf("Expected 2 pending migrations, got %d", len(pending))
	}

	if pending[0].MigrationID != "mig_20260501_000002_b" {
		t.Errorf("Expected first pending migration to be b, got %s", pending[0].MigrationID)
	}

	if pending[1].MigrationID != "mig_20260501_000003_c" {
		t.Errorf("Expected second pending migration to be c, got %s", pending[1].MigrationID)
	}
}

func TestGetMigrationsUpTo(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000001", "a", nil,
		"SELECT 1;",
		"SELECT 1;")

	createTestMigration(t, tempDir, "20260501_000002", "b", nil,
		"SELECT 1;",
		"SELECT 1;")

	createTestMigration(t, tempDir, "20260501_000003", "c", nil,
		"SELECT 1;",
		"SELECT 1;")

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	migrationsUpTo := manager.GetMigrationsUpTo("20260501_000002")

	if len(migrationsUpTo) != 2 {
		t.Errorf("Expected 2 migrations up to version 2, got %d", len(migrationsUpTo))
	}

	if migrationsUpTo[len(migrationsUpTo)-1].Version != "20260501_000002" {
		t.Errorf("Expected last migration to be version 2, got %s", migrationsUpTo[len(migrationsUpTo)-1].Version)
	}
}

func TestGetMigrationByVersion(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	createTestMigration(t, tempDir, "20260501_000001", "test_migration", nil,
		"SELECT 1;",
		"SELECT 1;")

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	mig, exists := manager.GetMigrationByVersion("20260501_000001")
	if !exists {
		t.Error("Expected migration to exist")
	}

	if mig.Name != "test_migration" {
		t.Errorf("Expected migration name to be 'test_migration', got '%s'", mig.Name)
	}

	_, exists = manager.GetMigrationByVersion("nonexistent_version")
	if exists {
		t.Error("Expected non-existent version to not exist")
	}
}

func TestParseDependencies(t *testing.T) {
	manager := &Manager{
		migrations: make(map[string]*models.Migration),
	}

	migration := &models.Migration{
		MigrationID:  "mig_test",
		Dependencies: []string{},
	}

	content := `-- @depends mig_dependency1
-- @dependency mig_dependency2
CREATE TABLE test (id INT);`

	manager.parseDependencies(migration, content)

	if len(migration.Dependencies) != 2 {
		t.Errorf("Expected 2 dependencies, got %d", len(migration.Dependencies))
	}

	found := map[string]bool{}
	for _, dep := range migration.Dependencies {
		found[dep] = true
	}

	if !found["mig_dependency1"] {
		t.Error("Expected mig_dependency1 to be in dependencies")
	}

	if !found["mig_dependency2"] {
		t.Error("Expected mig_dependency2 to be in dependencies")
	}
}

func TestCreateMigration(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	upPath, downPath, err := manager.CreateMigration("test_table")
	if err != nil {
		t.Fatalf("CreateMigration failed: %v", err)
	}

	if upPath == "" {
		t.Error("Expected up migration path to be non-empty")
	}

	if downPath == "" {
		t.Error("Expected down migration path to be non-empty")
	}

	if _, err := os.Stat(upPath); os.IsNotExist(err) {
		t.Error("Expected up migration file to exist")
	}

	if _, err := os.Stat(downPath); os.IsNotExist(err) {
		t.Error("Expected down migration file to exist")
	}

	migrations := manager.GetMigrations()
	if len(migrations) != 1 {
		t.Errorf("Expected 1 migration after create, got %d", len(migrations))
	}
}

func TestRefresh(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "migration_test")
	if err != nil {
		t.Fatalf("Failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tempDir)

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Failed to create migration manager: %v", err)
	}

	if len(manager.GetMigrations()) != 0 {
		t.Errorf("Expected 0 migrations initially, got %d", len(manager.GetMigrations()))
	}

	createTestMigration(t, tempDir, "20260501_000001", "test", nil,
		"SELECT 1;",
		"SELECT 1;")

	if err := manager.Refresh(); err != nil {
		t.Fatalf("Refresh failed: %v", err)
	}

	if len(manager.GetMigrations()) != 1 {
		t.Errorf("Expected 1 migration after refresh, got %d", len(manager.GetMigrations()))
	}
}

func TestNewManager_NonExistentDirectory(t *testing.T) {
	tempDir := filepath.Join(os.TempDir(), "nonexistent_migration_dir_"+time.Now().Format("20060102150405"))
	defer os.RemoveAll(tempDir)

	manager, err := NewManager(tempDir)
	if err != nil {
		t.Fatalf("Expected NewManager to create directory, got error: %v", err)
	}

	if manager == nil {
		t.Error("Expected manager to be non-nil")
	}

	if _, err := os.Stat(tempDir); os.IsNotExist(err) {
		t.Error("Expected directory to be created")
	}
}

func TestNewManager_FileInsteadOfDirectory(t *testing.T) {
	tempFile := filepath.Join(os.TempDir(), "migration_test_file_"+time.Now().Format("20060102150405"))
	if err := os.WriteFile(tempFile, []byte("test"), 0644); err != nil {
		t.Fatalf("Failed to create test file: %v", err)
	}
	defer os.Remove(tempFile)

	_, err := NewManager(tempFile)
	if err == nil {
		t.Error("Expected error when path is a file, got nil")
	}
}
