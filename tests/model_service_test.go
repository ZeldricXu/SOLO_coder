package tests

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"session133/internal/model"
	"session133/tests/testbuilders"
	"session133/tests/testutils"
)

func TestModelService_CreateModel_NormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
		&model.StageTransition{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	t.Run("创建模型成功", func(t *testing.T) {
		req := &model.CreateModelRequest{
			Name:        "GPT-4 Test",
			Namespace:   "test-ns",
			Description: "Test model creation",
			Type:        "llm",
			Labels:      map[string]string{"env": "test"},
		}

		createdModel, err := service.CreateModel(context.Background(), req, "test-user")

		require.NoError(t, err)
		assert.NotNil(t, createdModel)
		assert.Equal(t, "GPT-4 Test", createdModel.Name)
		assert.Equal(t, "test-ns", createdModel.Namespace)
		assert.Equal(t, model.ModelStatusDraft, createdModel.Status)
		assert.Equal(t, "test-user", createdModel.CreatedBy)
		assert.NotEmpty(t, createdModel.ID)
	})

	t.Run("创建包含元数据的模型成功", func(t *testing.T) {
		req := &model.CreateModelRequest{
			Name:        "Model with Metadata",
			Namespace:   "test-ns",
			Description: "Model with custom metadata",
			Type:        "embedding",
			Metadata:    map[string]interface{}{"framework": "tensorflow", "params": 1000000},
		}

		createdModel, err := service.CreateModel(context.Background(), req, "test-user")

		require.NoError(t, err)
		assert.Equal(t, "embedding", createdModel.Type)
		assert.Equal(t, "tensorflow", createdModel.Metadata["framework"])
	})
}

func TestModelService_CreateModel_AbnormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	t.Run("模型名称为空返回参数错误", func(t *testing.T) {
		req := &model.CreateModelRequest{
			Name:      "",
			Namespace: "test-ns",
			Type:      "llm",
		}

		_, err := service.CreateModel(context.Background(), req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "名称不能为空")
	})

	t.Run("命名空间为空返回参数错误", func(t *testing.T) {
		req := &model.CreateModelRequest{
			Name:      "Test Model",
			Namespace: "",
			Type:      "llm",
		}

		_, err := service.CreateModel(context.Background(), req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "命名空间不能为空")
	})

	t.Run("模型类型为空返回参数错误", func(t *testing.T) {
		req := &model.CreateModelRequest{
			Name:      "Test Model",
			Namespace: "test-ns",
			Type:      "",
		}

		_, err := service.CreateModel(context.Background(), req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "模型类型不能为空")
	})
}

func TestModelService_CreateVersion_NormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
		&model.StageTransition{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	baseModel := testbuilders.NewModelBuilder().
		WithID("model_001").
		WithName("Test Model").
		WithNamespace("test-ns").
		Build()
	require.NoError(t, db.Create(baseModel).Error)

	t.Run("创建版本成功", func(t *testing.T) {
		req := &model.CreateVersionRequest{
			Version:     "1.0.0",
			Description: "First production version",
			Checksum:    "sha256:a1b2c3",
			Size:        2048000,
			Metrics:     map[string]float64{"accuracy": 0.98},
			Artifacts:   []string{"model.pt", "vocab.json"},
		}

		version, err := service.CreateVersion(context.Background(), "model_001", req, "test-user")

		require.NoError(t, err)
		assert.NotNil(t, version)
		assert.Equal(t, "1.0.0", version.Version)
		assert.Equal(t, model.StageDevelopment, version.Stage)
		assert.Equal(t, "sha256:a1b2c3", version.Checksum)
		assert.Len(t, version.Artifacts, 2)
	})

	t.Run("同一模型创建多个版本", func(t *testing.T) {
		req1 := &model.CreateVersionRequest{
			Version: "2.0.0",
			Checksum: "sha256:def456",
		}
		req2 := &model.CreateVersionRequest{
			Version: "2.1.0",
			Checksum: "sha256:ghi789",
		}

		v1, err1 := service.CreateVersion(context.Background(), "model_001", req1, "test-user")
		v2, err2 := service.CreateVersion(context.Background(), "model_001", req2, "test-user")

		require.NoError(t, err1)
		require.NoError(t, err2)
		assert.Equal(t, "2.0.0", v1.Version)
		assert.Equal(t, "2.1.0", v2.Version)
	})
}

func TestModelService_CreateVersion_AbnormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	t.Run("模型不存在时创建版本返回错误", func(t *testing.T) {
		req := &model.CreateVersionRequest{
			Version: "1.0.0",
			Checksum: "sha256:abc",
		}

		_, err := service.CreateVersion(context.Background(), "non_existent_model", req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "模型不存在")
	})

	t.Run("版本号为空返回错误", func(t *testing.T) {
		baseModel := testbuilders.NewModelBuilder().
			WithID("model_002").
			WithName("Test Model 2").
			Build()
		db.Create(baseModel)

		req := &model.CreateVersionRequest{
			Version: "",
			Checksum: "sha256:abc",
		}

		_, err := service.CreateVersion(context.Background(), "model_002", req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "版本号不能为空")
	})
}

func TestModelService_TransitionStage_NormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
		&model.StageTransition{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	baseModel := testbuilders.NewModelBuilder().WithID("model_stage_test").Build()
	db.Create(baseModel)
	version := testbuilders.NewModelVersionBuilder().
		WithID("ver_stage_test").
		WithModelID("model_stage_test").
		WithStage(model.StageDevelopment).
		Build()
	db.Create(version)

	t.Run("Development -> Staging 流转成功", func(t *testing.T) {
		result, err := service.TransitionStage(context.Background(), "ver_stage_test", model.StageStaging, "test-user")

		require.NoError(t, err)
		assert.Equal(t, model.StageStaging, result.Stage)

		var transition model.StageTransition
		err = db.Where("version_id = ? AND from_stage = ? AND to_stage = ?",
			"ver_stage_test", model.StageDevelopment, model.StageStaging).First(&transition).Error
		require.NoError(t, err)
		assert.Equal(t, "test-user", transition.OperatedBy)
	})

	t.Run("Staging -> Production 流转成功", func(t *testing.T) {
		result, err := service.TransitionStage(context.Background(), "ver_stage_test", model.StageProduction, "test-user")

		require.NoError(t, err)
		assert.Equal(t, model.StageProduction, result.Stage)
	})

	t.Run("Production -> Archived 流转成功", func(t *testing.T) {
		result, err := service.TransitionStage(context.Background(), "ver_stage_test", model.StageArchived, "test-user")

		require.NoError(t, err)
		assert.Equal(t, model.StageArchived, result.Stage)
	})
}

func TestModelService_TransitionStage_AbnormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
		&model.StageTransition{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	baseModel := testbuilders.NewModelBuilder().WithID("model_abnormal").Build()
	db.Create(baseModel)

	t.Run("版本不存在返回错误", func(t *testing.T) {
		_, err := service.TransitionStage(context.Background(), "non_existent_ver", model.StageStaging, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "版本不存在")
	})

	t.Run("Development 直接到 Production 返回错误（非法流转）", func(t *testing.T) {
		version := testbuilders.NewModelVersionBuilder().
			WithID("ver_dev_only").
			WithModelID("model_abnormal").
			WithStage(model.StageDevelopment).
			Build()
		db.Create(version)

		_, err := service.TransitionStage(context.Background(), "ver_dev_only", model.StageProduction, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "不允许的Stage流转")
	})

	t.Run("Archived 状态不能再流转", func(t *testing.T) {
		version := testbuilders.NewModelVersionBuilder().
			WithID("ver_archived").
			WithModelID("model_abnormal").
			WithStage(model.StageArchived).
			Build()
		db.Create(version)

		_, err := service.TransitionStage(context.Background(), "ver_archived", model.StageStaging, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "不允许的Stage流转")
	})

	t.Run("无效的目标Stage返回错误", func(t *testing.T) {
		version := testbuilders.NewModelVersionBuilder().
			WithID("ver_invalid_stage").
			WithModelID("model_abnormal").
			WithStage(model.StageDevelopment).
			Build()
		db.Create(version)

		_, err := service.TransitionStage(context.Background(), "ver_invalid_stage", "INVALID_STAGE", "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "无效的目标Stage")
	})
}

func TestModelService_QueryOperations_NormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	model1 := testbuilders.NewModelBuilder().
		WithID("query_model_1").
		WithName("Alpha Model").
		WithNamespace("team-a").
		WithStatus(model.ModelStatusPublished).
		Build()
	model2 := testbuilders.NewModelBuilder().
		WithID("query_model_2").
		WithName("Beta Model").
		WithNamespace("team-b").
		WithStatus(model.ModelStatusDraft).
		Build()
	db.Create(model1)
	db.Create(model2)

	t.Run("按ID查询模型成功", func(t *testing.T) {
		result, err := service.GetModel(context.Background(), "query_model_1")

		require.NoError(t, err)
		assert.Equal(t, "Alpha Model", result.Name)
		assert.Equal(t, "team-a", result.Namespace)
	})

	t.Run("按命名空间分页查询成功", func(t *testing.T) {
		models, total, err := service.ListModels(context.Background(), "team-a", "", 1, 10)

		require.NoError(t, err)
		assert.Equal(t, int64(1), total)
		assert.Len(t, models, 1)
		assert.Equal(t, "Alpha Model", models[0].Name)
	})

	t.Run("按状态过滤查询成功", func(t *testing.T) {
		models, total, err := service.ListModels(context.Background(), "", string(model.ModelStatusDraft), 1, 10)

		require.NoError(t, err)
		assert.Equal(t, int64(1), total)
		assert.Len(t, models, 1)
		assert.Equal(t, "Beta Model", models[0].Name)
	})
}

func TestModelService_QueryOperations_AbnormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	t.Run("查询不存在的模型返回错误", func(t *testing.T) {
		_, err := service.GetModel(context.Background(), "non_existent")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "模型不存在")
	})

	t.Run("分页参数错误返回默认值", func(t *testing.T) {
		models, _, err := service.ListModels(context.Background(), "", "", 0, -1)

		require.NoError(t, err)
		assert.NotNil(t, models)
	})
}

func TestModelService_DeleteModel_NormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	testModel := testbuilders.NewModelBuilder().
		WithID("delete_test_model").
		WithName("To Delete").
		Build()
	db.Create(testModel)

	t.Run("删除模型成功", func(t *testing.T) {
		err := service.DeleteModel(context.Background(), "delete_test_model")

		require.NoError(t, err)

		var count int64
		db.Model(&model.Model{}).Where("id = ?", "delete_test_model").Count(&count)
		assert.Equal(t, int64(0), count)
	})
}

func TestModelService_DeleteModel_AbnormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	t.Run("删除不存在的模型返回错误", func(t *testing.T) {
		err := service.DeleteModel(context.Background(), "non_existent")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "模型不存在")
	})

	t.Run("有版本关联时删除模型", func(t *testing.T) {
		testModel := testbuilders.NewModelBuilder().
			WithID("model_with_versions").
			WithName("Has Versions").
			Build()
		db.Create(testModel)

		version := testbuilders.NewModelVersionBuilder().
			WithID("ver_linked").
			WithModelID("model_with_versions").
			Build()
		db.Create(version)

		err := service.DeleteModel(context.Background(), "model_with_versions")

		require.NoError(t, err)

		var modelCount int64
		db.Model(&model.Model{}).Where("id = ?", "model_with_versions").Count(&modelCount)
		assert.Equal(t, int64(0), modelCount)

		var versionCount int64
		db.Model(&model.ModelVersion{}).Where("model_id = ?", "model_with_versions").Count(&versionCount)
		assert.Equal(t, int64(0), versionCount)
	})
}

func TestModelService_ConcurrentOperations(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&model.Model{},
		&model.ModelVersion{},
	)
	defer cleanup()

	service := model.NewModelService(db, logger)

	t.Run("并发创建不同模型不会冲突", func(t *testing.T) {
		done := make(chan bool, 10)
		for i := 0; i < 10; i++ {
			go func(idx int) {
				req := &model.CreateModelRequest{
					Name:        "Concurrent Model " + string(rune('A'+idx)),
					Namespace:   "concurrent-ns",
					Type:        "llm",
				}
				_, err := service.CreateModel(context.Background(), req, "concurrent-user")
				assert.NoError(t, err)
				done <- true
			}(i)
		}

		for i := 0; i < 10; i++ {
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				t.Fatal("Timeout waiting for concurrent operations")
			}
		}

		var count int64
		db.Model(&model.Model{}).Where("namespace = ?", "concurrent-ns").Count(&count)
		assert.Equal(t, int64(10), count)
	})
}
