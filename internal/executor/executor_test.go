package executor

import (
	"errors"
	"testing"

	"dbmigrator/pkg/models"
)

func TestParseSQLStatements(t *testing.T) {
	tests := []struct {
		name     string
		script   string
		expected []string
	}{
		{
			name: "single statement",
			script: "CREATE TABLE users (id INT);",
			expected: []string{"CREATE TABLE users (id INT)"},
		},
		{
			name: "multiple statements",
			script: `CREATE TABLE users (id INT);
CREATE TABLE posts (id INT);`,
			expected: []string{
				"CREATE TABLE users (id INT)",
				"CREATE TABLE posts (id INT)",
			},
		},
		{
			name: "statement with line comments",
			script: `-- This is a comment
CREATE TABLE users (id INT); -- Another comment`,
			expected: []string{"CREATE TABLE users (id INT)"},
		},
		{
			name: "statement with block comments",
			script: `/* Multi-line
comment */
CREATE TABLE users (id INT);`,
			expected: []string{"CREATE TABLE users (id INT)"},
		},
		{
			name: "statement with string containing semicolon",
			script: `INSERT INTO users (name) VALUES ('test;value');`,
			expected: []string{"INSERT INTO users (name) VALUES ('test;value')"},
		},
		{
			name: "empty statements",
			script: `;;CREATE TABLE users (id INT);;;`,
			expected: []string{"CREATE TABLE users (id INT)"},
		},
		{
			name: "statement without trailing semicolon",
			script: "CREATE TABLE users (id INT)",
			expected: []string{"CREATE TABLE users (id INT)"},
		},
		{
			name: "double quotes string",
			script: `INSERT INTO users (name) VALUES ("test;value");`,
			expected: []string{`INSERT INTO users (name) VALUES ("test;value")`},
		},
		{
			name: "backtick string",
			script: "INSERT INTO `users` (`name`) VALUES ('test');",
			expected: []string{"INSERT INTO `users` (`name`) VALUES ('test')"},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			e := &Executor{}
			result := e.parseSQLStatements(tt.script)
			
			if len(result) != len(tt.expected) {
				t.Errorf("Expected %d statements, got %d", len(tt.expected), len(result))
				return
			}
			
			for i, expected := range tt.expected {
				if result[i] != expected {
					t.Errorf("Statement %d:\nExpected: %s\nGot: %s", i, expected, result[i])
				}
			}
		})
	}
}

func TestPreviewMigration(t *testing.T) {
	e := &Executor{}
	
	migration := &models.Migration{
		MigrationID: "mig_test",
		UpScript: `CREATE TABLE users (id INT);
INSERT INTO users (id) VALUES (1);`,
	}
	
	statements, err := e.PreviewMigration(migration)
	if err != nil {
		t.Fatalf("PreviewMigration failed: %v", err)
	}
	
	if len(statements) != 2 {
		t.Errorf("Expected 2 statements, got %d", len(statements))
	}
}

func TestParseSQLStatements_EdgeCases(t *testing.T) {
	e := &Executor{}
	
	t.Run("windows line endings", func(t *testing.T) {
		script := "CREATE TABLE a (id INT);\r\nCREATE TABLE b (id INT);"
		result := e.parseSQLStatements(script)
		if len(result) != 2 {
			t.Errorf("Expected 2 statements, got %d", len(result))
		}
	})
	
	t.Run("old mac line endings", func(t *testing.T) {
		script := "CREATE TABLE a (id INT);\rCREATE TABLE b (id INT);"
		result := e.parseSQLStatements(script)
		if len(result) != 2 {
			t.Errorf("Expected 2 statements, got %d", len(result))
		}
	})
	
	t.Run("nested comments", func(t *testing.T) {
		script := `-- /* block comment inside line comment */
CREATE TABLE test (id INT);`
		result := e.parseSQLStatements(script)
		if len(result) != 1 {
			t.Errorf("Expected 1 statement, got %d", len(result))
		}
	})
	
	t.Run("escaped quotes", func(t *testing.T) {
		script := `INSERT INTO users (name) VALUES ('John''s Test');`
		result := e.parseSQLStatements(script)
		if len(result) != 1 {
			t.Errorf("Expected 1 statement, got %d", len(result))
		}
	})
}

func TestExecuteMigration_EmptyScript(t *testing.T) {
	migration := &models.Migration{
		MigrationID: "mig_test_empty",
		Version:     "20260501_000001",
		UpScript:    "",
		DownScript:  "DROP TABLE IF EXISTS test;",
	}

	e := &Executor{}

	result, err := e.ExecuteMigration(migration)
	
	if err != nil {
		t.Errorf("Expected no error for empty script, got: %v", err)
	}
	
	if !result.Success {
		t.Error("Expected success for empty script")
	}
	
	if result.SQLCount != 0 {
		t.Errorf("Expected 0 SQL statements, got %d", result.SQLCount)
	}
}

func TestExecuteMigrationTx_EmptyScript(t *testing.T) {
	migration := &models.Migration{
		MigrationID: "mig_test_empty",
		Version:     "20260501_000001",
		UpScript:    "",
	}

	e := &Executor{}

	result, err := e.ExecuteMigrationTx(nil, migration)
	
	if err != nil {
		t.Errorf("Expected no error for empty script, got: %v", err)
	}
	
	if !result.Success {
		t.Error("Expected success for empty script")
	}
}

type testError struct {
	message string
}

func (e *testError) Error() string {
	return e.message
}

var testErrBegin = errors.New("failed to begin transaction")
var testErrExec = errors.New("failed to execute statement")
var testErrCommit = errors.New("failed to commit")
