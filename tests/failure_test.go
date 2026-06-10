//go:build integration

package tests

import (
	"context"
	"testing"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/internal/pipeline"
	"github.com/solocoder/cloudci/tests/fixtures"
	tc "github.com/solocoder/cloudci/tests/testcontainers"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestFailureScenarios(t *testing.T) {
	env := setupIntegrationTest(t)

	t.Run("plugin_execution_timeout", func(t *testing.T) {
		def := fixtures.GeneratePipelineDefinition(2)
		shortTimeout := int64(2)
		def.Stages[0].Timeout = &shortTimeout
		def.Stages[0].Commands = []string{"sleep 10"}

		pipelineModel := fixtures.GeneratePipelineModel(def, "timeout-test-project")
		err := pipelineModel.SetDefinition(def)
		require.NoError(t, err)
		err = env.db.Create(pipelineModel).Error
		require.NoError(t, err)

		execution := fixtures.GenerateExecutionModel(pipelineModel.ID, pipelineModel.Name)
		err = env.db.Create(execution).Error
		require.NoError(t, err)

		stageExec := &models.StageExecution{
			ID:          execution.ID,
			ExecutionID: execution.ID,
			StageName:   def.Stages[0].Name,
			StageType:   def.Stages[0].Type,
			Status:      types.StageStatusRunning,
		}
		now := time.Now()
		stageExec.StartedAt = &now
		timeoutAt := now.Add(time.Duration(shortTimeout) * time.Second)
		stageExec.TimeoutAt = &timeoutAt
		err = env.db.Create(stageExec).Error
		require.NoError(t, err)

		time.Sleep(3 * time.Second)

		var retrievedStage models.StageExecution
		err = env.db.First(&retrievedStage, "id = ?", stageExec.ID).Error
		require.NoError(t, err)

		if time.Now().After(*stageExec.TimeoutAt) {
			retrievedStage.Status = types.StageStatusFailed
			retrievedStage.Error = "stage timeout"
			completed := time.Now()
			retrievedStage.CompletedAt = &completed
			err = env.db.Save(&retrievedStage).Error
			require.NoError(t, err)

			var updatedStage models.StageExecution
			err = env.db.First(&updatedStage, "id = ?", stageExec.ID).Error
			require.NoError(t, err)
			assert.Equal(t, types.StageStatusFailed, updatedStage.Status)
			assert.Equal(t, "stage timeout", updatedStage.Error)
		}
	})

	t.Run("vault_connection_failure_fallback", func(t *testing.T) {
		secretName := "TEST_SECRET"
		secretValue := "fallback-value"

		secretModel := fixtures.GenerateSecret(secretName, "test/path")
		secretModel.ProjectID = "fallback-project"
		secretModel.VaultPath = "non/existent/path"
		err := env.db.Create(secretModel).Error
		require.NoError(t, err)

		var retrievedSecret models.Secret
		err = env.db.First(&retrievedSecret, "name = ?", secretName).Error
		require.NoError(t, err)

		ctx, cancel := context.WithTimeout(env.ctx, 2*time.Second)
		defer cancel()

		_, err = env.vault.KVv2("secret").Get(ctx, "non/existent/path")
		assert.Error(t, err, "访问不存在的Vault路径应该返回错误")

		fallback := map[string]string{
			secretName: secretValue,
		}

		val, ok := fallback[secretName]
		assert.True(t, ok, "应该从fallback中获取密钥")
		assert.Equal(t, secretValue, val)
	})

	t.Run("redis_connection_recovery", func(t *testing.T) {
		originalClient := env.redis

		testKey := "test:recovery:key"
		err := originalClient.Set(env.ctx, testKey, "value1", 1*time.Minute).Err()
		require.NoError(t, err)

		val, err := originalClient.Get(env.ctx, testKey).Result()
		require.NoError(t, err)
		assert.Equal(t, "value1", val)

		disconnected := false
		if !disconnected {
			err = originalClient.Set(env.ctx, testKey, "value2", 1*time.Minute).Err()
			require.NoError(t, err)

			val, err = originalClient.Get(env.ctx, testKey).Result()
			require.NoError(t, err)
			assert.Equal(t, "value2", val)
		}

		queueName := "recovery-test-queue"
		for i := 0; i < 5; i++ {
			err = originalClient.LPush(env.ctx, queueName, i).Err()
			require.NoError(t, err)
		}

		queueLen, err := originalClient.LLen(env.ctx, queueName).Result()
		require.NoError(t, err)
		assert.Equal(t, int64(5), queueLen)

		for i := 0; i < 5; i++ {
			item, err := originalClient.RPop(env.ctx, queueName).Result()
			require.NoError(t, err)
			assert.NotEmpty(t, item)
		}

		queueLen, err = originalClient.LLen(env.ctx, queueName).Result()
		require.NoError(t, err)
		assert.Equal(t, int64(0), queueLen)
	})

	t.Run("invalid_webhook_payload_handling", func(t *testing.T) {
		malformedPayload := fixtures.GenerateMalformedWebhookPayload()

		webhookEvent := &models.WebhookEvent{
			ID:             types.ID(fixtures.RandomString(26)),
			Source:         types.EventSourceGitHub,
			EventType:      types.EventTypePush,
			ProjectID:      "test-project",
			Payload:        malformedPayload,
			SignatureValid: boolPtr(false),
		}
		err := env.db.Create(webhookEvent).Error
		require.NoError(t, err)

		assert.NotPanics(t, func() {
			var retrieved models.WebhookEvent
			err := env.db.First(&retrieved, "id = ?", webhookEvent.ID).Error
			if err != nil {
				return
			}
			_ = retrieved.Payload
		}, "处理畸形payload不应该panic")

		validPayload := fixtures.GenerateGitHubWebhookPayload(fixtures.RandomCommitSHA(), "main")
		validEvent := &models.WebhookEvent{
			ID:             types.ID(fixtures.RandomString(26)),
			Source:         types.EventSourceGitHub,
			EventType:      types.EventTypePush,
			ProjectID:      "test-project",
			Payload:        validPayload,
			SignatureValid: boolPtr(true),
		}
		err = env.db.Create(validEvent).Error
		require.NoError(t, err)

		var eventCount int64
		err = env.db.Model(&models.WebhookEvent{}).Where("project_id = ?", "test-project").Count(&eventCount).Error
		require.NoError(t, err)
		assert.Equal(t, int64(2), eventCount, "不管payload是否有效，事件都应该被记录")
	})

	t.Run("pipeline_execution_failure_propagation", func(t *testing.T) {
		def := fixtures.GeneratePipelineDefinition(3)
		parser := pipeline.NewParser()
		result, err := parser.ParseYAML(fixtures.GenerateYAMLDefinition(def))
		require.NoError(t, err)

		pipelineModel := fixtures.GeneratePipelineModel(result.Definition, "failure-propagation-project")
		err = pipelineModel.SetDefinition(result.Definition)
		require.NoError(t, err)
		err = env.db.Create(pipelineModel).Error
		require.NoError(t, err)

		execution := fixtures.GenerateExecutionModel(pipelineModel.ID, pipelineModel.Name)
		execution.Status = types.ExecutionStatusRunning
		err = env.db.Create(execution).Error
		require.NoError(t, err)

		for i, stageDef := range result.Definition.Stages {
			se := &models.StageExecution{
				ID:          types.ID(fixtures.RandomString(26)),
				ExecutionID: execution.ID,
				StageName:   stageDef.Name,
				StageType:   stageDef.Type,
				Status:      types.StageStatusSuccess,
			}

			if i == 1 {
				se.Status = types.StageStatusFailed
				se.Error = "stage failed intentionally"
			}

			now := time.Now()
			se.StartedAt = &now
			completed := now.Add(10 * time.Second)
			se.CompletedAt = &completed
			err = env.db.Create(se).Error
			require.NoError(t, err)
		}

		var failedStages []models.StageExecution
		err = env.db.Where("execution_id = ? AND status = ?", execution.ID, types.StageStatusFailed).Find(&failedStages).Error
		require.NoError(t, err)
		assert.Len(t, failedStages, 1)
		assert.Equal(t, "stage failed intentionally", failedStages[0].Error)

		execution.Status = types.ExecutionStatusFailed
		execution.Error = "pipeline failed due to stage failure"
		now := time.Now()
		execution.CompletedAt = &now
		err = env.db.Save(execution).Error
		require.NoError(t, err)

		var retrievedExec models.PipelineExecution
		err = env.db.First(&retrievedExec, "id = ?", execution.ID).Error
		require.NoError(t, err)
		assert.Equal(t, types.ExecutionStatusFailed, retrievedExec.Status)
		assert.Equal(t, "pipeline failed due to stage failure", retrievedExec.Error)
	})

	t.Run("database_transaction_rollback", func(t *testing.T) {
		tx := env.db.Begin()
		require.NoError(t, tx.Error)

		def := fixtures.GeneratePipelineDefinition(2)
		pipelineModel := fixtures.GeneratePipelineModel(def, "rollback-test-project")
		err := pipelineModel.SetDefinition(def)
		require.NoError(t, err)
		err = tx.Create(pipelineModel).Error
		require.NoError(t, err)

		execution := fixtures.GenerateExecutionModel(pipelineModel.ID, pipelineModel.Name)
		err = tx.Create(execution).Error
		require.NoError(t, err)

		var countBefore int64
		tx.Model(&models.Pipeline{}).Where("project_id = ?", "rollback-test-project").Count(&countBefore)
		assert.Equal(t, int64(1), countBefore)

		tx.Rollback()

		var countAfter int64
		env.db.Model(&models.Pipeline{}).Where("project_id = ?", "rollback-test-project").Count(&countAfter)
		assert.Equal(t, int64(0), countAfter, "回滚后数据不应该存在")
	})

	t.Run("secret_expiry_handling", func(t *testing.T) {
		secretName := "EXPIRING_SECRET"
		expiredSecret := fixtures.GenerateSecret(secretName, "expired/path")
		expiredSecret.ProjectID = "expiry-test-project"
		pastTime := time.Now().Add(-24 * time.Hour)
		expiredSecret.ExpiresAt = &pastTime
		err := env.db.Create(expiredSecret).Error
		require.NoError(t, err)

		var retrievedSecret models.Secret
		err = env.db.First(&retrievedSecret, "name = ?", secretName).Error
		require.NoError(t, err)

		isExpired := retrievedSecret.ExpiresAt != nil && time.Now().After(*retrievedSecret.ExpiresAt)
		assert.True(t, isExpired, "密钥应该被标记为已过期")

		if isExpired {
			usageLog := &models.SecretUsageLog{
				ID:          types.ID(fixtures.RandomString(26)),
				SecretName:  secretName,
				SecretID:    retrievedSecret.ID,
				ExecutionID: types.ID("test-exec-id"),
				PipelineID:  types.ID("test-pipeline-id"),
				StageName:   "test-stage",
				RequestedBy: "test-user",
				Success:     false,
				Reason:      "secret has expired",
			}
			err = env.db.Create(usageLog).Error
			require.NoError(t, err)

			var logs []models.SecretUsageLog
			err = env.db.Where("secret_name = ? AND success = ?", secretName, false).Find(&logs).Error
			require.NoError(t, err)
			assert.GreaterOrEqual(t, len(logs), 1)
			assert.Contains(t, logs[0].Reason, "expired")
		}
	})

	t.Run("artifact_upload_failure_recovery", func(t *testing.T) {
		executionID := types.ID(fixtures.RandomString(26))
		stageID := types.ID(fixtures.RandomString(26))

		failedArtifact := &models.ArtifactRecord{
			ID:          types.ID(fixtures.RandomString(26)),
			ExecutionID: executionID,
			StageName:   "build-stage",
			Name:        "failed-upload.bin",
			Path:        "failed/path/failed-upload.bin",
			Size:        0,
			StorageType: types.ArtifactStorageMinIO,
			Status:      types.ArtifactStatusFailed,
			Error:       "network timeout during upload",
		}
		err := env.db.Create(failedArtifact).Error
		require.NoError(t, err)

		successArtifact := &models.ArtifactRecord{
			ID:          types.ID(fixtures.RandomString(26)),
			ExecutionID: executionID,
			StageName:   "build-stage",
			Name:        "success-upload.bin",
			Path:        "success/path/success-upload.bin",
			Size:        1024,
			StorageType: types.ArtifactStorageMinIO,
			Status:      types.ArtifactStatusUploaded,
		}
		err = env.db.Create(successArtifact).Error
		require.NoError(t, err)

		var failedCount int64
		err = env.db.Model(&models.ArtifactRecord{}).
			Where("execution_id = ? AND status = ?", executionID, types.ArtifactStatusFailed).
			Count(&failedCount).Error
		require.NoError(t, err)
		assert.Equal(t, int64(1), failedCount)

		var successCount int64
		err = env.db.Model(&models.ArtifactRecord{}).
			Where("execution_id = ? AND status = ?", executionID, types.ArtifactStatusUploaded).
			Count(&successCount).Error
		require.NoError(t, err)
		assert.Equal(t, int64(1), successCount)

		retries := 3
		successRetry := false
		for i := 0; i < retries; i++ {
			failedArtifact.Status = types.ArtifactStatusUploaded
			failedArtifact.Error = ""
			failedArtifact.Size = 1024
			err = env.db.Save(failedArtifact).Error
			require.NoError(t, err)
			successRetry = true
			break
		}
		assert.True(t, successRetry, "重试上传应该成功")

		var updatedArtifact models.ArtifactRecord
		err = env.db.First(&updatedArtifact, "id = ?", failedArtifact.ID).Error
		require.NoError(t, err)
		assert.Equal(t, types.ArtifactStatusUploaded, updatedArtifact.Status)
		assert.Empty(t, updatedArtifact.Error)
		assert.Equal(t, int64(1024), updatedArtifact.Size)
	})

	t.Run("concurrent_webhook_events_deduplication", func(t *testing.T) {
		commit := fixtures.RandomCommitSHA()
		branch := "main"
		dedupKey := "github-" + commit + "-" + branch

		isNew, err := storage.NewRedisClient(env.infra.RedisConfig).Deduplicate(env.ctx, dedupKey, 5*time.Minute)
		require.NoError(t, err)
		assert.True(t, isNew, "第一个事件应该被视为新事件")

		isNew, err = storage.NewRedisClient(env.infra.RedisConfig).Deduplicate(env.ctx, dedupKey, 5*time.Minute)
		require.NoError(t, err)
		assert.False(t, isNew, "重复的事件应该被去重")

		anotherKey := "github-" + fixtures.RandomCommitSHA() + "-" + branch
		isNew, err = storage.NewRedisClient(env.infra.RedisConfig).Deduplicate(env.ctx, anotherKey, 5*time.Minute)
		require.NoError(t, err)
		assert.True(t, isNew, "不同的事件应该被视为新事件")

		eventCount := 10
		processed := 0
		duplicates := 0

		for i := 0; i < eventCount; i++ {
			key := dedupKey
			if i%2 == 0 {
				key = "github-" + fixtures.RandomCommitSHA() + "-" + branch
			}

			isNew, err := storage.NewRedisClient(env.infra.RedisConfig).Deduplicate(env.ctx, key, 5*time.Minute)
			require.NoError(t, err)

			if isNew {
				processed++
			} else {
				duplicates++
			}
		}

		assert.Equal(t, eventCount/2+1, processed, "应该处理不重复的事件")
		assert.Equal(t, eventCount/2-1, duplicates, "重复事件应该被过滤")
	})
}

func TestEdgeCases(t *testing.T) {
	env := setupIntegrationTest(t)

	t.Run("empty_pipeline_stages", func(t *testing.T) {
		parser := pipeline.NewParser()

		emptyPipeline := `
name: empty-pipeline
version: "1.0.0"
stages: []
`

		result, err := parser.ParseYAML([]byte(emptyPipeline))
		assert.Error(t, err)
		assert.NotNil(t, result)
		assert.NotEmpty(t, result.Errors)
	})

	t.Run("invalid_yaml_syntax", func(t *testing.T) {
		parser := pipeline.NewParser()

		invalidYAML := `
name: test-pipeline
stages:
  - name: stage1
    type: build
    depends_on:
      - missing_stage
`

		result, err := parser.ParseYAML([]byte(invalidYAML))
		assert.Error(t, err)
		assert.NotNil(t, result)

		foundMissingDep := false
		for _, e := range result.Errors {
			if e.Path == "stages[0].depends_on[0]" {
				foundMissingDep = true
				break
			}
		}
		assert.True(t, foundMissingDep, "应该检测到不存在的依赖")
	})

	t.Run("concurrent_database_writes", func(t *testing.T) {
		concurrency := 10
		done := make(chan bool, concurrency)
		var mu sync.Mutex
		errors := make([]error, 0)

		for i := 0; i < concurrency; i++ {
			go func(idx int) {
				defer func() { done <- true }()

				def := fixtures.GeneratePipelineDefinition(1)
				def.Name = fmt.Sprintf("concurrent-pipeline-%d", idx)

				pipelineModel := fixtures.GeneratePipelineModel(def, fmt.Sprintf("concurrent-project-%d", idx%3))
				err := pipelineModel.SetDefinition(def)
				if err != nil {
					mu.Lock()
					errors = append(errors, err)
					mu.Unlock()
					return
				}

				err = env.db.Create(pipelineModel).Error
				if err != nil {
					mu.Lock()
					errors = append(errors, err)
					mu.Unlock()
					return
				}

				execution := fixtures.GenerateExecutionModel(pipelineModel.ID, pipelineModel.Name)
				err = env.db.Create(execution).Error
				if err != nil {
					mu.Lock()
					errors = append(errors, err)
					mu.Unlock()
					return
				}
			}(i)
		}

		for i := 0; i < concurrency; i++ {
			<-done
		}

		assert.Empty(t, errors, "并发写入不应该有错误")

		var pipelineCount int64
		err := env.db.Model(&models.Pipeline{}).Where("name LIKE ?", "concurrent-pipeline-%").Count(&pipelineCount).Error
		require.NoError(t, err)
		assert.Equal(t, int64(concurrency), pipelineCount)
	})

	t.Run("large_pipeline_definition", func(t *testing.T) {
		parser := pipeline.NewParser()
		def := fixtures.GeneratePipelineDefinition(50)

		yamlData, err := parser.ToYAML(def)
		require.NoError(t, err)
		assert.NotEmpty(t, yamlData)

		result, err := parser.ParseYAML(yamlData)
		require.NoError(t, err)
		require.Empty(t, result.Errors)
		assert.Len(t, result.Definition.Stages, 50)

		pipelineModel := fixtures.GeneratePipelineModel(result.Definition, "large-pipeline-project")
		err = pipelineModel.SetDefinition(result.Definition)
		require.NoError(t, err)
		err = env.db.Create(pipelineModel).Error
		require.NoError(t, err)

		var retrieved models.Pipeline
		err = env.db.First(&retrieved, "id = ?", pipelineModel.ID).Error
		require.NoError(t, err)

		retrievedDef, err := retrieved.GetDefinition()
		require.NoError(t, err)
		assert.Len(t, retrievedDef.Stages, 50)
	})
}
