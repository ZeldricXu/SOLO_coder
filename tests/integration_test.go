//go:build integration

package tests

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/oklog/ulid/v2"
	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/internal/pipeline"
	"github.com/solocoder/cloudci/internal/secret"
	"github.com/solocoder/cloudci/internal/storage"
	"github.com/solocoder/cloudci/tests/fixtures"
	tc "github.com/solocoder/cloudci/tests/testcontainers"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"

	vaultapi "github.com/hashicorp/vault/api"
	redisclient "github.com/redis/go-redis/v9"
)

type IntegrationTestEnv struct {
	infra *tc.TestInfrastructure
	ctx   context.Context
	db    *gorm.DB
	redis *redisclient.Client
	minio *minio.Client
	vault *vaultapi.Client
}

func setupIntegrationTest(t *testing.T) *IntegrationTestEnv {
	t.Helper()

	ctx := context.Background()

	infra, err := tc.SetupInfrastructure(ctx)
	require.NoError(t, err, "Failed to setup test infrastructure")

	t.Cleanup(func() {
		infra.Cleanup(ctx)
	})

	return &IntegrationTestEnv{
		infra: infra,
		ctx:   ctx,
		db:    infra.DB,
		redis: infra.Redis,
		minio: infra.Minio,
		vault: infra.Vault,
	}
}

func TestFullPipelineExecution(t *testing.T) {
	env := setupIntegrationTest(t)

	t.Run("pipeline_lifecycle", func(t *testing.T) {
		t.Run("1_create_and_parse_pipeline_definition", func(t *testing.T) {
			parser := pipeline.NewParser()

			def := fixtures.GeneratePipelineDefinition(3)
			yamlData, err := parser.ToYAML(def)
			require.NoError(t, err)

			result, err := parser.ParseYAML(yamlData)
			require.NoError(t, err)
			require.NotNil(t, result)
			require.Empty(t, result.Errors)
			require.Len(t, result.Definition.Stages, 3)

			pipelineModel := fixtures.GeneratePipelineModel(result.Definition, "test-project")
			err = pipelineModel.SetDefinition(result.Definition)
			require.NoError(t, err)

			err = env.db.Create(pipelineModel).Error
			require.NoError(t, err)

			var retrieved models.Pipeline
			err = env.db.First(&retrieved, "id = ?", pipelineModel.ID).Error
			require.NoError(t, err)
			assert.Equal(t, pipelineModel.Name, retrieved.Name)
			assert.Equal(t, pipelineModel.ProjectID, retrieved.ProjectID)

			retrievedDef, err := retrieved.GetDefinition()
			require.NoError(t, err)
			assert.Equal(t, result.Definition.Name, retrievedDef.Name)
			assert.Len(t, retrievedDef.Stages, 3)
		})

		t.Run("2_trigger_execution_via_webhook", func(t *testing.T) {
			def := fixtures.GeneratePipelineDefinition(3)
			parser := pipeline.NewParser()
			result, err := parser.ParseYAML(fixtures.GenerateYAMLDefinition(def))
			require.NoError(t, err)

			pipelineModel := fixtures.GeneratePipelineModel(result.Definition, "org/test-repo")
			err = pipelineModel.SetDefinition(result.Definition)
			require.NoError(t, err)
			err = env.db.Create(pipelineModel).Error
			require.NoError(t, err)

			commit := fixtures.RandomCommitSHA()
			branch := "main"
			event := fixtures.GenerateInternalEvent(
				types.EventSourceGitHub,
				types.EventTypePush,
				commit,
				branch,
			)
			event.ProjectID = "org/test-repo"

			webhookEvent := &models.WebhookEvent{
				ID:               event.ID,
				Source:           event.EventSource,
				EventType:        event.EventType,
				DeduplicationKey: event.DeduplicationKey,
				ProjectID:        event.ProjectID,
				Commit:           event.Commit,
				Branch:           event.Branch,
				Ref:              event.Ref,
				Message:          event.Message,
				Author:           event.Author,
				AuthorEmail:      event.AuthorEmail,
				SignatureValid:   boolPtr(true),
			}
			err = env.db.Create(webhookEvent).Error
			require.NoError(t, err)

			execution := &models.PipelineExecution{
				ID:            types.ID(ulid.Make().String()),
				PipelineID:    pipelineModel.ID,
				PipelineName:  pipelineModel.Name,
				Status:        types.ExecutionStatusQueued,
				ProjectID:     event.ProjectID,
				TriggerSource: event.EventSource,
				TriggerType:   event.EventType,
				Commit:        event.Commit,
				Branch:        event.Branch,
				Ref:           event.Ref,
				Message:       event.Message,
				Author:        event.Author,
				AuthorEmail:   event.AuthorEmail,
				EventID:       event.ID,
			}
			now := time.Now()
			execution.QueuedAt = &now
			err = env.db.Create(execution).Error
			require.NoError(t, err)

			var retrievedExec models.PipelineExecution
			err = env.db.First(&retrievedExec, "id = ?", execution.ID).Error
			require.NoError(t, err)
			assert.Equal(t, types.ExecutionStatusQueued, retrievedExec.Status)
			assert.Equal(t, commit, retrievedExec.Commit)
			assert.Equal(t, branch, retrievedExec.Branch)

			queuePayload, _ := json.Marshal(map[string]interface{}{
				"event_id":    event.ID,
				"pipeline_id": pipelineModel.ID,
				"enqueued_at": time.Now(),
			})
			err = env.redis.LPush(env.ctx, "executions", string(queuePayload)).Err()
			require.NoError(t, err)

			queueLen, err := env.redis.LLen(env.ctx, "executions").Result()
			require.NoError(t, err)
			assert.Equal(t, int64(1), queueLen)
		})

		t.Run("3_store_secrets_in_vault_and_resolve", func(t *testing.T) {
			secretName := "DB_PASSWORD"
			secretValue := "super-secret-password-123"
			vaultPath := "test-project/db"

			err := env.infra.PutVaultSecret(env.ctx, vaultPath, "value", secretValue)
			require.NoError(t, err)

			retrievedValue, err := env.infra.GetVaultSecret(env.ctx, vaultPath, "value")
			require.NoError(t, err)
			assert.Equal(t, secretValue, retrievedValue)

			secretModel := fixtures.GenerateSecret(secretName, vaultPath)
			secretModel.ProjectID = "test-project"
			err = env.db.Create(secretModel).Error
			require.NoError(t, err)

			var retrievedSecret models.Secret
			err = env.db.First(&retrievedSecret, "name = ?", secretName).Error
			require.NoError(t, err)
			assert.Equal(t, vaultPath, retrievedSecret.VaultPath)

			cfg := &secret.VaultConfig{
				Addr:       env.infra.VaultConfig.Addr,
				Token:      env.infra.VaultConfig.Token,
				SecretPath: env.infra.VaultConfig.SecretPath,
			}

			loggerCfg := &logger.Config{Level: "error", Format: "json"}
			logger.Init(loggerCfg)

			storage.SetDB(env.db)
			secretMgr, err := secret.NewSecretManager(cfg)
			require.NoError(t, err)

			secrets, err := secretMgr.ResolveSecrets(
				env.ctx,
				"test-pipeline-id",
				"test-execution-id",
				"build-stage",
				[]string{secretName},
			)
			require.NoError(t, err)
			assert.Equal(t, secretValue, secrets[secretName])
			assert.Equal(t, secretValue, os.Getenv(secretName))

			var usageLogs []models.SecretUsageLog
			err = env.db.Where("secret_name = ?", secretName).Find(&usageLogs).Error
			require.NoError(t, err)
			assert.GreaterOrEqual(t, len(usageLogs), 1)
			assert.Equal(t, "test-pipeline-id", string(usageLogs[0].PipelineID))
			assert.Equal(t, "build-stage", usageLogs[0].StageName)
			assert.True(t, usageLogs[0].Success)
		})

		t.Run("4_upload_and_download_artifact", func(t *testing.T) {
			testContent := []byte("test artifact content: build output binary")
			testFileName := "app-v1.0.0.tar.gz"

			tmpFile, err := os.CreateTemp("", testFileName)
			require.NoError(t, err)
			defer os.Remove(tmpFile.Name())

			_, err = tmpFile.Write(testContent)
			require.NoError(t, err)
			tmpFile.Close()

			objectName := fmt.Sprintf("test-pipeline/test-execution/%s", testFileName)
			_, err = env.minio.FPutObject(
				env.ctx,
				"test-artifacts",
				objectName,
				tmpFile.Name(),
				minio.PutObjectOptions{ContentType: "application/gzip"},
			)
			require.NoError(t, err)

			downloadPath := filepath.Join(os.TempDir(), "downloaded-"+testFileName)
			err = env.minio.FGetObject(
				env.ctx,
				"test-artifacts",
				objectName,
				downloadPath,
				minio.GetObjectOptions{},
			)
			require.NoError(t, err)
			defer os.Remove(downloadPath)

			downloadedContent, err := os.ReadFile(downloadPath)
			require.NoError(t, err)
			assert.Equal(t, testContent, downloadedContent)

			artifact := &models.ArtifactRecord{
				ID:          types.ID(ulid.Make().String()),
				ExecutionID: types.ID("test-execution-id"),
				StageName:   "build-stage",
				Name:        testFileName,
				Path:        objectName,
				Size:        int64(len(testContent)),
				ContentType: "application/gzip",
				StorageType: types.ArtifactStorageMinIO,
			}
			err = env.db.Create(artifact).Error
			require.NoError(t, err)

			var retrievedArtifact models.ArtifactRecord
			err = env.db.First(&retrievedArtifact, "id = ?", artifact.ID).Error
			require.NoError(t, err)
			assert.Equal(t, testFileName, retrievedArtifact.Name)
			assert.Equal(t, int64(len(testContent)), retrievedArtifact.Size)
		})

		t.Run("5_store_and_query_execution_logs", func(t *testing.T) {
			executionID := types.ID(ulid.Make().String())
			stageID := types.ID(ulid.Make().String())

			logs := []models.LogRecord{
				{
					ID:          types.ID(ulid.Make().String()),
					ExecutionID: executionID,
					StageID:     stageID,
					Timestamp:   time.Now(),
					Level:       "INFO",
					Message:     "Stage started",
					Stream:      "stdout",
				},
				{
					ID:          types.ID(ulid.Make().String()),
					ExecutionID: executionID,
					StageID:     stageID,
					Timestamp:   time.Now().Add(1 * time.Second),
					Level:       "INFO",
					Message:     "Building application...",
					Stream:      "stdout",
				},
				{
					ID:          types.ID(ulid.Make().String()),
					ExecutionID: executionID,
					StageID:     stageID,
					Timestamp:   time.Now().Add(2 * time.Second),
					Level:       "ERROR",
					Message:     "Warning: deprecated API used",
					Stream:      "stderr",
				},
			}

			for _, log := range logs {
				err := env.db.Create(&log).Error
				require.NoError(t, err)
			}

			channel := "logs:test-execution"
			msg, err := json.Marshal(logs[0])
			require.NoError(t, err)
			err = env.redis.Publish(env.ctx, channel, msg).Err()
			require.NoError(t, err)

			var retrievedLogs []models.LogRecord
			err = env.db.Where("execution_id = ?", executionID).Order("timestamp ASC").Find(&retrievedLogs).Error
			require.NoError(t, err)
			assert.Len(t, retrievedLogs, 3)
			assert.Equal(t, "Stage started", retrievedLogs[0].Message)
			assert.Equal(t, "Building application...", retrievedLogs[1].Message)
			assert.Equal(t, "ERROR", retrievedLogs[2].Level)
		})

		t.Run("6_update_execution_status_and_finalize", func(t *testing.T) {
			def := fixtures.GeneratePipelineDefinition(2)
			parser := pipeline.NewParser()
			result, err := parser.ParseYAML(fixtures.GenerateYAMLDefinition(def))
			require.NoError(t, err)

			pipelineModel := fixtures.GeneratePipelineModel(result.Definition, "test-project")
			err = pipelineModel.SetDefinition(result.Definition)
			require.NoError(t, err)
			err = env.db.Create(pipelineModel).Error
			require.NoError(t, err)

			execution := fixtures.GenerateExecutionModel(pipelineModel.ID, pipelineModel.Name)
			err = env.db.Create(execution).Error
			require.NoError(t, err)

			stageExecs := make([]*models.StageExecution, 2)
			for i, stageDef := range result.Definition.Stages {
				se := &models.StageExecution{
					ID:          types.ID(ulid.Make().String()),
					ExecutionID: execution.ID,
					StageName:   stageDef.Name,
					StageType:   stageDef.Type,
					Status:      types.StageStatusSuccess,
				}
				now := time.Now()
				se.StartedAt = &now
				completed := now.Add(30 * time.Second)
				se.CompletedAt = &completed
				se.DurationSec = int64Ptr(30)
				se.ExitCode = intPtr(0)
				err = env.db.Create(se).Error
				require.NoError(t, err)
				stageExecs[i] = se
			}

			execution.Status = types.ExecutionStatusSuccess
			now := time.Now()
			execution.CompletedAt = &now
			execution.DurationSec = int64Ptr(60)
			err = env.db.Save(execution).Error
			require.NoError(t, err)

			var retrievedExec models.PipelineExecution
			err = env.db.First(&retrievedExec, "id = ?", execution.ID).Error
			require.NoError(t, err)
			assert.Equal(t, types.ExecutionStatusSuccess, retrievedExec.Status)
			assert.NotNil(t, retrievedExec.CompletedAt)
			assert.Equal(t, int64(60), *retrievedExec.DurationSec)

			var executions []models.PipelineExecution
			err = env.db.Where("pipeline_id = ?", pipelineModel.ID).Order("created_at DESC").Find(&executions).Error
			require.NoError(t, err)
			assert.Len(t, executions, 1)
			assert.Equal(t, types.ExecutionStatusSuccess, executions[0].Status)
		})

		t.Run("7_redis_deduplication_and_queue", func(t *testing.T) {
			dedupKey := "github-abc123-main"

			isNew, err := storage.NewRedisClient(env.infra.RedisConfig).Deduplicate(env.ctx, dedupKey, 24*time.Hour)
			require.NoError(t, err)
			assert.True(t, isNew)

			isNew, err = storage.NewRedisClient(env.infra.RedisConfig).Deduplicate(env.ctx, dedupKey, 24*time.Hour)
			require.NoError(t, err)
			assert.False(t, isNew)

			queueName := "test-queue"
			for i := 0; i < 3; i++ {
				payload := fmt.Sprintf("item-%d", i)
				err = env.redis.LPush(env.ctx, queueName, payload).Err()
				require.NoError(t, err)
			}

			queueLen, err := env.redis.LLen(env.ctx, queueName).Result()
			require.NoError(t, err)
			assert.Equal(t, int64(3), queueLen)

			item, err := env.redis.RPop(env.ctx, queueName).Result()
			require.NoError(t, err)
			assert.Equal(t, "item-0", item)

			queueLen, err = env.redis.LLen(env.ctx, queueName).Result()
			require.NoError(t, err)
			assert.Equal(t, int64(2), queueLen)
		})
	})
}

func TestPipelineDefinitionPersistence(t *testing.T) {
	env := setupIntegrationTest(t)

	t.Run("yaml_and_json_roundtrip", func(t *testing.T) {
		parser := pipeline.NewParser()
		def := fixtures.GeneratePipelineDefinition(4)

		yamlData, err := parser.ToYAML(def)
		require.NoError(t, err)

		jsonData, err := parser.ToJSON(def)
		require.NoError(t, err)

		yamlResult, err := parser.ParseYAML(yamlData)
		require.NoError(t, err)
		require.Empty(t, yamlResult.Errors)

		jsonResult, err := parser.ParseJSON(jsonData)
		require.NoError(t, err)
		require.Empty(t, jsonResult.Errors)

		assert.Equal(t, yamlResult.Definition.Name, jsonResult.Definition.Name)
		assert.Equal(t, len(yamlResult.Definition.Stages), len(jsonResult.Definition.Stages))
		for i := range yamlResult.Definition.Stages {
			assert.Equal(t, yamlResult.Definition.Stages[i].Name, jsonResult.Definition.Stages[i].Name)
			assert.Equal(t, yamlResult.Definition.Stages[i].Type, jsonResult.Definition.Stages[i].Type)
			assert.Equal(t, yamlResult.Definition.Stages[i].DependsOn, jsonResult.Definition.Stages[i].DependsOn)
		}
	})

	t.Run("pipeline_versioning", func(t *testing.T) {
		parser := pipeline.NewParser()
		def := fixtures.GeneratePipelineDefinition(2)

		pipelineModel := fixtures.GeneratePipelineModel(def, "version-test-project")
		err := pipelineModel.SetDefinition(def)
		require.NoError(t, err)
		err = env.db.Create(pipelineModel).Error
		require.NoError(t, err)

		def.Description = "Updated description"
		def.Version = "1.1.0"
		pipelineModel.Version = 2
		pipelineModel.Description = "Updated description"
		err = pipelineModel.SetDefinition(def)
		require.NoError(t, err)
		err = env.db.Save(pipelineModel).Error
		require.NoError(t, err)

		var retrieved models.Pipeline
		err = env.db.First(&retrieved, "id = ?", pipelineModel.ID).Error
		require.NoError(t, err)
		assert.Equal(t, 2, retrieved.Version)
		assert.Equal(t, "Updated description", retrieved.Description)

		retrievedDef, err := retrieved.GetDefinition()
		require.NoError(t, err)
		assert.Equal(t, "1.1.0", retrievedDef.Version)
	})
}

func TestMinIOArtifactOperations(t *testing.T) {
	env := setupIntegrationTest(t)

	t.Run("upload_large_file", func(t *testing.T) {
		largeContent := bytes.Repeat([]byte("test data "), 10000)
		tmpFile, err := os.CreateTemp("", "large-*.bin")
		require.NoError(t, err)
		defer os.Remove(tmpFile.Name())

		_, err = tmpFile.Write(largeContent)
		require.NoError(t, err)
		tmpFile.Close()

		objectName := "large-files/test-large.bin"
		_, err = env.minio.FPutObject(
			env.ctx,
			"test-artifacts",
			objectName,
			tmpFile.Name(),
			minio.PutObjectOptions{ContentType: "application/octet-stream"},
		)
		require.NoError(t, err)

		stat, err := env.minio.StatObject(
			env.ctx,
			"test-artifacts",
			objectName,
			minio.StatObjectOptions{},
		)
		require.NoError(t, err)
		assert.Equal(t, int64(len(largeContent)), stat.Size)

		downloadPath := filepath.Join(os.TempDir(), "downloaded-large.bin")
		err = env.minio.FGetObject(
			env.ctx,
			"test-artifacts",
			objectName,
			downloadPath,
			minio.GetObjectOptions{},
		)
		require.NoError(t, err)
		defer os.Remove(downloadPath)

		downloadedContent, err := os.ReadFile(downloadPath)
		require.NoError(t, err)
		assert.Equal(t, largeContent, downloadedContent)
	})

	t.Run("list_and_delete_objects", func(t *testing.T) {
		files := []string{"dir1/file1.txt", "dir1/file2.txt", "dir2/file3.txt"}
		for _, f := range files {
			_, err := env.minio.PutObject(
				env.ctx,
				"test-artifacts",
				f,
				bytes.NewReader([]byte("content")),
				int64(len("content")),
				minio.PutObjectOptions{},
			)
			require.NoError(t, err)
		}

		objectCh := env.minio.ListObjects(env.ctx, "test-artifacts", minio.ListObjectsOptions{
			Prefix:    "dir1/",
			Recursive: true,
		})

		count := 0
		for obj := range objectCh {
			require.NoError(t, obj.Err)
			count++
		}
		assert.Equal(t, 2, count)

		err := env.minio.RemoveObject(env.ctx, "test-artifacts", "dir1/file1.txt", minio.RemoveObjectOptions{})
		require.NoError(t, err)

		objectCh = env.minio.ListObjects(env.ctx, "test-artifacts", minio.ListObjectsOptions{
			Prefix:    "dir1/",
			Recursive: true,
		})

		count = 0
		for obj := range objectCh {
			require.NoError(t, obj.Err)
			count++
		}
		assert.Equal(t, 1, count)
	})
}

func TestPostgreSQLDataIntegrity(t *testing.T) {
	env := setupIntegrationTest(t)

	t.Run("create_multiple_pipelines_and_executions", func(t *testing.T) {
		parser := pipeline.NewParser()

		for i := 0; i < 5; i++ {
			def := fixtures.GeneratePipelineDefinition(2)
			def.Name = fmt.Sprintf("pipeline-%d", i)

			pipelineModel := fixtures.GeneratePipelineModel(def, fmt.Sprintf("project-%d", i%2))
			err := pipelineModel.SetDefinition(def)
			require.NoError(t, err)
			err = env.db.Create(pipelineModel).Error
			require.NoError(t, err)

			for j := 0; j < 3; j++ {
				execution := fixtures.GenerateExecutionModel(pipelineModel.ID, pipelineModel.Name)
				execution.Status = types.ExecutionStatus(types.ExecutionStatusSuccess)
				if j%3 == 0 {
					execution.Status = types.ExecutionStatusFailed
				}
				err = env.db.Create(execution).Error
				require.NoError(t, err)
			}
		}

		var pipelineCount int64
		err := env.db.Model(&models.Pipeline{}).Count(&pipelineCount).Error
		require.NoError(t, err)
		assert.Equal(t, int64(5), pipelineCount)

		var executionCount int64
		err = env.db.Model(&models.PipelineExecution{}).Count(&executionCount).Error
		require.NoError(t, err)
		assert.Equal(t, int64(15), executionCount)

		var projectExecutions []models.PipelineExecution
		err = env.db.Where("project_id = ?", "project-0").Find(&projectExecutions).Error
		require.NoError(t, err)
		assert.Len(t, projectExecutions, 9)

		var failedCount int64
		err = env.db.Model(&models.PipelineExecution{}).Where("status = ?", types.ExecutionStatusFailed).Count(&failedCount).Error
		require.NoError(t, err)
		assert.Equal(t, int64(5), failedCount)
	})
}

func boolPtr(b bool) *bool {
	return &b
}

func intPtr(i int) *int {
	return &i
}

func int64Ptr(i int64) *int64 {
	return &i
}
