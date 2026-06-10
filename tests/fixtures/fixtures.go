package fixtures

import (
	"fmt"
	"math/rand"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/models"
)

func init() {
	rand.Seed(time.Now().UnixNano())
}

func GeneratePipelineDefinition(stageCount int) *types.PipelineDefinition {
	stages := make([]types.StageDefinition, stageCount)
	for i := 0; i < stageCount; i++ {
		stageType := types.StageTypeBuild
		switch i % 4 {
		case 0:
			stageType = types.StageTypeScan
		case 1:
			stageType = types.StageTypeBuild
		case 2:
			stageType = types.StageTypeTest
		case 3:
			stageType = types.StageTypeDeploy
		}

		dependsOn := []string{}
		if i > 0 {
			dependsOn = []string{fmt.Sprintf("stage-%d", i-1)}
		}

		timeout := int64(3600)
		stages[i] = types.StageDefinition{
			Name:        fmt.Sprintf("stage-%d", i),
			Type:        stageType,
			Description: fmt.Sprintf("Stage %d description", i),
			DependsOn:   dependsOn,
			Commands: []string{
				fmt.Sprintf("echo \"Running stage %d\"", i),
				"sleep 1",
			},
			Timeout:      &timeout,
			AllowFailure: false,
			Env: []types.EnvVar{
				{Name: fmt.Sprintf("STAGE_%d_VAR", i), Value: fmt.Sprintf("value-%d", i)},
			},
		}
	}

	return &types.PipelineDefinition{
		Name:        fmt.Sprintf("test-pipeline-%d", rand.Intn(1000)),
		Version:     "1.0.0",
		Description: "Test pipeline definition",
		Stages:      stages,
		Triggers: []types.PipelineTrigger{
			{
				EventSource: types.EventSourceGitHub,
				EventType:   types.EventTypePush,
				Condition:   &types.TriggerCondition{Branch: "main"},
			},
		},
	}
}

func GeneratePipelineDefinitionWithCycle() *types.PipelineDefinition {
	return &types.PipelineDefinition{
		Name:    "cyclic-pipeline",
		Version: "1.0.0",
		Stages: []types.StageDefinition{
			{
				Name:      "stage-a",
				Type:      types.StageTypeBuild,
				DependsOn: []string{"stage-b"},
				Commands:  []string{"echo A"},
			},
			{
				Name:      "stage-b",
				Type:      types.StageTypeTest,
				DependsOn: []string{"stage-a"},
				Commands:  []string{"echo B"},
			},
		},
	}
}

func GenerateYAMLDefinition(def *types.PipelineDefinition) string {
	yaml := fmt.Sprintf(`name: %s
version: "%s"
description: "%s"

stages:
`, def.Name, def.Version, def.Description)

	for _, stage := range def.Stages {
		yaml += fmt.Sprintf(`  - name: %s
    type: %s
    description: "%s"
`, stage.Name, stage.Type, stage.Description)

		if len(stage.DependsOn) > 0 {
			yaml += "    depends_on:\n"
			for _, dep := range stage.DependsOn {
				yaml += fmt.Sprintf(`      - %s
`, dep)
			}
		}

		if len(stage.Commands) > 0 {
			yaml += "    commands:\n"
			for _, cmd := range stage.Commands {
				yaml += fmt.Sprintf(`      - "%s"
`, cmd)
			}
		}

		if stage.Timeout != nil {
			yaml += fmt.Sprintf("    timeout: %d\n", *stage.Timeout)
		}

		if len(stage.Env) > 0 {
			yaml += "    env:\n"
			for _, e := range stage.Env {
				yaml += fmt.Sprintf(`      - name: %s
        value: "%s"
`, e.Name, e.Value)
			}
		}
	}

	return yaml
}

func GenerateGitHubWebhookPayload(commit, branch string) []byte {
	return []byte(fmt.Sprintf(`{
  "ref": "refs/heads/%s",
  "before": "0000000000000000000000000000000000000000",
  "after": "%s",
  "repository": {
    "id": 123456,
    "name": "test-repo",
    "full_name": "org/test-repo",
    "html_url": "https://github.com/org/test-repo"
  },
  "pusher": {
    "name": "test-user",
    "email": "test@example.com"
  },
  "sender": {
    "login": "test-user",
    "id": 12345
  },
  "head_commit": {
    "id": "%s",
    "message": "test commit message",
    "timestamp": "2024-01-15T10:00:00Z",
    "author": {
      "name": "Test User",
      "email": "test@example.com"
    },
    "added": ["file1.go"],
    "modified": ["file2.go"],
    "removed": []
  },
  "commits": [
    {
      "id": "%s",
      "message": "test commit message",
      "timestamp": "2024-01-15T10:00:00Z",
      "author": {
        "name": "Test User",
        "email": "test@example.com"
      },
      "added": ["file1.go"],
      "modified": ["file2.go"],
      "removed": []
    }
  ]
}`, branch, commit, commit, commit))
}

func GenerateGitLabWebhookPayload(commit, branch string) []byte {
	return []byte(fmt.Sprintf(`{
  "object_kind": "push",
  "before": "0000000000000000000000000000000000000000",
  "after": "%s",
  "ref": "refs/heads/%s",
  "checkout_sha": "%s",
  "message": "test commit message",
  "user_id": 123,
  "user_name": "Test User",
  "user_username": "Test User",
  "user_email": "test@example.com",
  "project_id": 456,
  "project": {
    "id": 456,
    "name": "test-repo",
    "web_url": "https://gitlab.com/org/test-repo"
  },
  "commits": [
    {
      "id": "%s",
      "message": "test commit message",
      "title": "test commit",
      "timestamp": "2024-01-15T10:00:00Z",
      "author": {
        "name": "Test User",
        "email": "test@example.com"
      }
    }
  ]
}`, commit, branch, commit, commit))
}

func GenerateMalformedWebhookPayload() []byte {
	return []byte(`{
  "object_kind": "push",
  "ref": "refs/heads/main",
  "invalid_json": 
}`)
}

func GeneratePipelineModel(def *types.PipelineDefinition, projectID string) *models.Pipeline {
	return &models.Pipeline{
		ID:          types.ID(types.NewID()),
		Name:        def.Name,
		Description: def.Description,
		ProjectID:   projectID,
		Status:      types.PipelineStatusActive,
		Version:     1,
		CreatedBy:   "test-user",
	}
}

func GenerateExecutionModel(pipelineID types.ID, pipelineName string) *models.PipelineExecution {
	now := time.Now()
	queuedAt := now
	startedAt := now.Add(5 * time.Second)
	timeoutAt := now.Add(1 * time.Hour)

	return &models.PipelineExecution{
		ID:            types.ID(types.NewID()),
		PipelineID:    pipelineID,
		PipelineName:  pipelineName,
		Status:        types.ExecutionStatusRunning,
		ProjectID:     "test-project",
		TriggerSource: types.EventSourceManual,
		TriggerType:   types.EventTypeManual,
		Commit:        "abc123def456",
		Branch:        "main",
		QueuedAt:      &queuedAt,
		StartedAt:     &startedAt,
		TimeoutAt:     &timeoutAt,
	}
}

func GenerateInternalEvent(source types.EventSource, eventType types.EventType, commit, branch string) *types.InternalEvent {
	return &types.InternalEvent{
		ID:            types.ID(types.NewID()),
		EventSource:   source,
		EventType:     eventType,
		ProjectID:     "test-project",
		Commit:        commit,
		Branch:        branch,
		Ref:           fmt.Sprintf("refs/heads/%s", branch),
		Message:       "test commit message",
		Author:        "Test User",
		AuthorEmail:   "test@example.com",
		ReceivedAt:    time.Now(),
		DeduplicationKey: fmt.Sprintf("%s-%s-%s", source, commit, branch),
	}
}

func GenerateSecret(name, vaultPath string) *models.Secret {
	now := time.Now()
	expiresAt := now.Add(30 * 24 * time.Hour)
	return &models.Secret{
		ID:          types.ID(types.NewID()),
		Name:        name,
		Description: fmt.Sprintf("Test secret: %s", name),
		VaultPath:   vaultPath,
		VaultKey:    "value",
		EnvVarName:  name,
		Source:      types.SecretSourceVault,
		Version:     1,
		ProjectID:   "test-project",
		ExpiresAt:   &expiresAt,
		CreatedBy:   "test-user",
	}
}

func RandomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[rand.Intn(len(letters))]
	}
	return string(b)
}

func RandomCommitSHA() string {
	return RandomString(40)
}

func GenerateDAGStages(parallelWidth int) []types.StageDefinition {
	stages := []types.StageDefinition{
		{
			Name:     "init",
			Type:     types.StageTypeBuild,
			Commands: []string{"echo init"},
		},
	}

	for i := 0; i < parallelWidth; i++ {
		stages = append(stages, types.StageDefinition{
			Name:      fmt.Sprintf("parallel-%d", i),
			Type:      types.StageTypeTest,
			DependsOn: []string{"init"},
			Commands:  []string{fmt.Sprintf("echo parallel-%d", i)},
		})
	}

	finalDeps := make([]string, parallelWidth)
	for i := 0; i < parallelWidth; i++ {
		finalDeps[i] = fmt.Sprintf("parallel-%d", i)
	}

	stages = append(stages, types.StageDefinition{
		Name:      "final",
		Type:      types.StageTypeDeploy,
		DependsOn: finalDeps,
		Commands:  []string{"echo final"},
	})

	return stages
}
