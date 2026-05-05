package rollback

import (
	"testing"

	"dbmigrator/pkg/models"
)

func TestRollbackParseSQLStatements(t *testing.T) {
	tests := []struct {
		name     string
		script   string
		expected []string
	}{
		{
			name: "single statement",
			script: "DROP TABLE users;",
			expected: []string{"DROP TABLE users"},
		},
		{
			name: "multiple statements",
			script: `DROP TABLE comments;
DROP TABLE posts;`,
			expected: []string{
				"DROP TABLE comments",
				"DROP TABLE posts",
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := &Rollbacker{}
			result := r.parseSQLStatements(tt.script)
			
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

func TestPreviewRollback(t *testing.T) {
	r := &Rollbacker{}
	
	t.Run("with down script", func(t *testing.T) {
		migration := &models.Migration{
			MigrationID: "mig_test",
			DownScript: `DROP TABLE users;
DROP TABLE posts;`,
		}
		
		statements, err := r.PreviewRollback(migration)
		if err != nil {
			t.Fatalf("PreviewRollback failed: %v", err)
		}
		
		if len(statements) != 2 {
			t.Errorf("Expected 2 statements, got %d", len(statements))
		}
	})
	
	t.Run("without down script", func(t *testing.T) {
		migration := &models.Migration{
			MigrationID: "mig_test_no_down",
			DownScript:  "",
		}
		
		_, err := r.PreviewRollback(migration)
		if err == nil {
			t.Error("Expected error for missing down script")
		}
	})
}

func TestRollbackMigration_EmptyScript(t *testing.T) {
	migration := &models.Migration{
		MigrationID: "mig_test_empty",
		Version:     "20260501_000001",
		DownScript:  "",
	}

	r := &Rollbacker{}

	result, err := r.RollbackMigration(migration)
	
	if err == nil {
		t.Error("Expected error for missing down script")
	}
	
	if result.Success {
		t.Error("Expected failure for missing down script")
	}
}

func TestRollbackMigrationTx_EmptyScript(t *testing.T) {
	migration := &models.Migration{
		MigrationID: "mig_test_empty",
		Version:     "20260501_000001",
		DownScript:  "",
	}

	r := &Rollbacker{}

	result, err := r.RollbackMigrationTx(nil, migration)
	
	if err == nil {
		t.Error("Expected error for missing down script")
	}
	
	if result.Success {
		t.Error("Expected failure for missing down script")
	}
}

func TestRollbackMigration_NoDownStatements(t *testing.T) {
	migration := &models.Migration{
		MigrationID: "mig_test_no_statements",
		Version:     "20260501_000001",
		DownScript:  "   \n\n   ",
	}

	r := &Rollbacker{}

	result, err := r.RollbackMigration(migration)
	
	if err != nil {
		t.Errorf("Expected no error for whitespace-only script, got: %v", err)
	}
	
	if !result.Success {
		t.Error("Expected success for whitespace-only script")
	}
}
