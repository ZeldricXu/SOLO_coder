package tests

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"session133/internal/prompt"
	"session133/tests/testbuilders"
	"session133/tests/testutils"
)

func TestPromptService_CreatePrompt_NormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.PromptVersion{},
		&prompt.ABTest{},
		&prompt.ABTestResult{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	t.Run("创建Prompt成功", func(t *testing.T) {
		req := &prompt.CreatePromptRequest{
			Name:        "Customer Support",
			Namespace:   "support-ns",
			Description: "Customer support chat prompt",
			Content:     "You are a helpful customer support agent.",
			Variables:   []string{"user_name", "issue"},
			Labels:      map[string]string{"category": "support"},
		}

		createdPrompt, err := service.CreatePrompt(context.Background(), req, "test-user")

		require.NoError(t, err)
		assert.NotNil(t, createdPrompt)
		assert.Equal(t, "Customer Support", createdPrompt.Name)
		assert.Equal(t, 1, createdPrompt.Version)
		assert.Equal(t, prompt.PromptStatusActive, createdPrompt.Status)
		assert.Len(t, createdPrompt.Variables, 2)
	})

	t.Run("创建包含复杂变量的Prompt成功", func(t *testing.T) {
		req := &prompt.CreatePromptRequest{
			Name:        "Complex Prompt",
			Namespace:   "test-ns",
			Content:     "Hello {{name}}, your balance is {{balance}}.",
			Variables:   []string{"name", "balance", "currency"},
		}

		createdPrompt, err := service.CreatePrompt(context.Background(), req, "test-user")

		require.NoError(t, err)
		assert.Len(t, createdPrompt.Variables, 3)
		assert.Equal(t, "test-ns", createdPrompt.Namespace)
	})
}

func TestPromptService_CreatePrompt_AbnormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.PromptVersion{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	t.Run("名称为空返回参数错误", func(t *testing.T) {
		req := &prompt.CreatePromptRequest{
			Name:      "",
			Namespace: "test-ns",
			Content:   "test content",
		}

		_, err := service.CreatePrompt(context.Background(), req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "名称不能为空")
	})

	t.Run("内容为空返回参数错误", func(t *testing.T) {
		req := &prompt.CreatePromptRequest{
			Name:      "Test",
			Namespace: "test-ns",
			Content:   "",
		}

		_, err := service.CreatePrompt(context.Background(), req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "内容不能为空")
	})

	t.Run("命名空间为空返回参数错误", func(t *testing.T) {
		req := &prompt.CreatePromptRequest{
			Name:      "Test",
			Namespace: "",
			Content:   "test content",
		}

		_, err := service.CreatePrompt(context.Background(), req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "命名空间不能为空")
	})
}

func TestPromptService_CreateNewVersion_NormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.PromptVersion{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	basePrompt := testbuilders.NewPromptBuilder().
		WithID("prompt_version_test").
		WithName("Version Test Prompt").
		WithContent("v1 content").
		Build()
	db.Create(basePrompt)

	t.Run("创建新版本成功", func(t *testing.T) {
		req := &prompt.CreatePromptVersionRequest{
			Content:     "v2 content",
			Description: "Updated version",
			Variables:   []string{"user_input"},
		}

		newVersion, err := service.CreateNewVersion(context.Background(), "prompt_version_test", req, "test-user")

		require.NoError(t, err)
		assert.Equal(t, 2, newVersion.Version)
		assert.Equal(t, "v2 content", newVersion.Content)
		assert.Equal(t, "Updated version", newVersion.Description)
	})

	t.Run("创建多个版本递增版本号", func(t *testing.T) {
		for i := 0; i < 3; i++ {
			req := &prompt.CreatePromptVersionRequest{
				Content: "v" + string(rune('3'+i)) + " content",
			}
			version, err := service.CreateNewVersion(context.Background(), "prompt_version_test", req, "test-user")
			require.NoError(t, err)
			assert.Equal(t, i+3, version.Version)
		}
	})
}

func TestPromptService_ResourceReleaseOnDelete(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.PromptVersion{},
		&prompt.ABTest{},
		&prompt.ABTestResult{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	t.Run("删除Prompt时级联删除所有版本", func(t *testing.T) {
		p := testbuilders.NewPromptBuilder().
			WithID("delete_cascade_test").
			WithName("To Delete Cascade").
			Build()
		db.Create(p)

		for i := 0; i < 5; i++ {
			version := &prompt.PromptVersion{
				ID:        "ver_cascade_" + string(rune('0'+i)),
				PromptID:  "delete_cascade_test",
				Version:   i + 1,
				Content:   "version " + string(rune('0'+i)),
				CreatedBy: "test-user",
				CreatedAt: time.Now(),
			}
			db.Create(version)
		}

		err := service.DeletePrompt(context.Background(), "delete_cascade_test")

		require.NoError(t, err)

		var promptCount int64
		db.Model(&prompt.Prompt{}).Where("id = ?", "delete_cascade_test").Count(&promptCount)
		assert.Equal(t, int64(0), promptCount, "Prompt应该被删除")

		var versionCount int64
		db.Model(&prompt.PromptVersion{}).Where("prompt_id = ?", "delete_cascade_test").Count(&versionCount)
		assert.Equal(t, int64(0), versionCount, "所有关联版本应该被级联删除")
	})

	t.Run("删除Prompt时清理关联的AB测试", func(t *testing.T) {
		controlPrompt := testbuilders.NewPromptBuilder().
			WithID("control_prompt").
			WithName("Control").
			Build()
		testPrompt := testbuilders.NewPromptBuilder().
			WithID("test_prompt").
			WithName("Test").
			Build()
		db.Create(controlPrompt)
		db.Create(testPrompt)

		abTest := testbuilders.NewABTestBuilder().
			WithID("ab_test_cleanup").
			WithName("Test Experiment").
			Build()
		abTest.ControlPromptID = "control_prompt"
		abTest.TestPromptID = "test_prompt"
		db.Create(abTest)

		err := service.DeletePrompt(context.Background(), "control_prompt")

		require.NoError(t, err)

		var testAfter prompt.ABTest
		err = db.Where("id = ?", "ab_test_cleanup").First(&testAfter).Error
		require.NoError(t, err)
		assert.Equal(t, prompt.ABTestStatusCancelled, testAfter.Status, "AB测试应该被取消")
	})
}

func TestPromptService_ABTestResourceManagement(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.ABTest{},
		&prompt.ABTestResult{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	controlPrompt := testbuilders.NewPromptBuilder().
		WithID("ab_control").
		WithName("Control Prompt").
		Build()
	testPrompt := testbuilders.NewPromptBuilder().
		WithID("ab_test_prompt").
		WithName("Test Prompt").
		Build()
	db.Create(controlPrompt)
	db.Create(testPrompt)

	t.Run("创建AB测试成功", func(t *testing.T) {
		req := &prompt.CreateABTestRequest{
			Name:              "Conversion Optimization",
			Namespace:         "marketing",
			Description:       "Test new marketing copy",
			ControlPromptID:   "ab_control",
			TestPromptID:      "ab_test_prompt",
			TrafficPercentage: 50,
			Metrics: []prompt.ABTestMetric{
				{Name: "click_rate", DisplayName: "Click Through Rate"},
			},
		}

		abTest, err := service.CreateABTest(context.Background(), req, "test-user")

		require.NoError(t, err)
		assert.NotNil(t, abTest)
		assert.Equal(t, prompt.ABTestStatusCreated, abTest.Status)
		assert.Equal(t, 50, abTest.TrafficPercentage)
		assert.Len(t, abTest.Metrics, 1)
	})

	t.Run("启动AB测试成功", func(t *testing.T) {
		abTest := testbuilders.NewABTestBuilder().
			WithID("ab_test_start").
			WithName("Start Test").
			WithStatus(prompt.ABTestStatusCreated).
			Build()
		abTest.ControlPromptID = "ab_control"
		abTest.TestPromptID = "ab_test_prompt"
		db.Create(abTest)

		startedTest, err := service.StartABTest(context.Background(), "ab_test_start")

		require.NoError(t, err)
		assert.Equal(t, prompt.ABTestStatusRunning, startedTest.Status)
		assert.NotNil(t, startedTest.StartTime)
	})

	t.Run("停止AB测试成功并释放资源", func(t *testing.T) {
		abTest := testbuilders.NewABTestBuilder().
			WithID("ab_test_stop").
			WithName("Stop Test").
			WithStatus(prompt.ABTestStatusRunning).
			Build()
		abTest.ControlPromptID = "ab_control"
		abTest.TestPromptID = "ab_test_prompt"
		now := time.Now().Add(-1 * time.Hour)
		abTest.StartTime = &now
		db.Create(abTest)

		stoppedTest, err := service.StopABTest(context.Background(), "ab_test_stop")

		require.NoError(t, err)
		assert.Equal(t, prompt.ABTestStatusCompleted, stoppedTest.Status)
		assert.NotNil(t, stoppedTest.EndTime)
	})
}

func TestPromptService_ABTest_AbnormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.ABTest{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	controlPrompt := testbuilders.NewPromptBuilder().
		WithID("ctrl_prompt").
		WithName("Control").
		Build()
	db.Create(controlPrompt)

	t.Run("测试Prompt不存在创建AB测试失败", func(t *testing.T) {
		req := &prompt.CreateABTestRequest{
			Name:              "Invalid Test",
			Namespace:         "test",
			ControlPromptID:   "ctrl_prompt",
			TestPromptID:      "non_existent",
			TrafficPercentage: 50,
		}

		_, err := service.CreateABTest(context.Background(), req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "测试Prompt不存在")
	})

	t.Run("流量分配超出范围返回错误", func(t *testing.T) {
		testPrompt := testbuilders.NewPromptBuilder().
			WithID("test_prompt_2").
			WithName("Test 2").
			Build()
		db.Create(testPrompt)

		req := &prompt.CreateABTestRequest{
			Name:              "Traffic Test",
			Namespace:         "test",
			ControlPromptID:   "ctrl_prompt",
			TestPromptID:      "test_prompt_2",
			TrafficPercentage: 150,
		}

		_, err := service.CreateABTest(context.Background(), req, "test-user")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "流量百分比必须在1-100之间")
	})

	t.Run("停止未运行的AB测试返回错误", func(t *testing.T) {
		abTest := testbuilders.NewABTestBuilder().
			WithID("ab_not_running").
			WithName("Not Running").
			WithStatus(prompt.ABTestStatusCreated).
			Build()
		abTest.ControlPromptID = "ctrl_prompt"
		abTest.TestPromptID = "test_prompt_2"
		db.Create(abTest)

		_, err := service.StopABTest(context.Background(), "ab_not_running")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "AB测试未在运行中")
	})
}

func TestPromptService_RecordABTestResult_NormalFlow(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.ABTest{},
		&prompt.ABTestResult{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	controlPrompt := testbuilders.NewPromptBuilder().
		WithID("result_control").
		WithName("Control").
		Build()
	testPrompt := testbuilders.NewPromptBuilder().
		WithID("result_test").
		WithName("Test").
		Build()
	db.Create(controlPrompt)
	db.Create(testPrompt)

	abTest := testbuilders.NewABTestBuilder().
		WithID("result_test_ab").
		WithName("Result Test").
		WithStatus(prompt.ABTestStatusRunning).
		Build()
	abTest.ControlPromptID = "result_control"
	abTest.TestPromptID = "result_test"
	now := time.Now()
	abTest.StartTime = &now
	abTest.Metrics = []prompt.ABTestMetric{
		{Name: "conversion", DisplayName: "Conversion Rate"},
	}
	db.Create(abTest)

	t.Run("记录对照组结果成功", func(t *testing.T) {
		req := &prompt.RecordABTestResultRequest{
			TestID:       "result_test_ab",
			PromptID:     "result_control",
			Variant:      prompt.ABTestVariantControl,
			Metrics:      map[string]float64{"conversion": 1.0},
			SessionID:    "session_1",
		}

		result, err := service.RecordABTestResult(context.Background(), req)

		require.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, prompt.ABTestVariantControl, result.Variant)
		assert.Equal(t, 1.0, result.Metrics["conversion"])
	})

	t.Run("记录测试组结果成功", func(t *testing.T) {
		req := &prompt.RecordABTestResultRequest{
			TestID:       "result_test_ab",
			PromptID:     "result_test",
			Variant:      prompt.ABTestVariantTest,
			Metrics:      map[string]float64{"conversion": 0.8},
			SessionID:    "session_2",
		}

		result, err := service.RecordABTestResult(context.Background(), req)

		require.NoError(t, err)
		assert.Equal(t, prompt.ABTestVariantTest, result.Variant)
	})
}

func TestPromptService_ConcurrentABTestRecording(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.ABTest{},
		&prompt.ABTestResult{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	controlPrompt := testbuilders.NewPromptBuilder().
		WithID("conc_control").
		WithName("Control").
		Build()
	testPrompt := testbuilders.NewPromptBuilder().
		WithID("conc_test").
		WithName("Test").
		Build()
	db.Create(controlPrompt)
	db.Create(testPrompt)

	abTest := testbuilders.NewABTestBuilder().
		WithID("conc_ab_test").
		WithName("Concurrent Test").
		WithStatus(prompt.ABTestStatusRunning).
		Build()
	abTest.ControlPromptID = "conc_control"
	abTest.TestPromptID = "conc_test"
	now := time.Now()
	abTest.StartTime = &now
	abTest.Metrics = []prompt.ABTestMetric{
		{Name: "success", DisplayName: "Success Rate"},
	}
	db.Create(abTest)

	t.Run("并发记录AB测试结果不会丢失数据", func(t *testing.T) {
		var wg sync.WaitGroup
		concurrency := 100

		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				variant := prompt.ABTestVariantControl
				promptID := "conc_control"
				metricValue := 0.9
				if idx%2 == 1 {
					variant = prompt.ABTestVariantTest
					promptID = "conc_test"
					metricValue = 0.85
				}

				req := &prompt.RecordABTestResultRequest{
					TestID:    "conc_ab_test",
					PromptID:  promptID,
					Variant:   variant,
					Metrics:   map[string]float64{"success": metricValue},
					SessionID: "session_" + string(rune('A'+idx%26)) + string(rune('a'+idx%26)),
				}

				_, err := service.RecordABTestResult(context.Background(), req)
				assert.NoError(t, err)
			}(i)
		}

		wg.Wait()

		var resultCount int64
		db.Model(&prompt.ABTestResult{}).Where("test_id = ?", "conc_ab_test").Count(&resultCount)
		assert.Equal(t, int64(concurrency), resultCount, "所有并发记录应该被保存")
	})
}

func TestPromptService_GetABTestAnalysis_ResourceCleanup(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.ABTest{},
		&prompt.ABTestResult{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	controlPrompt := testbuilders.NewPromptBuilder().
		WithID("analysis_control").
		WithName("Control").
		Build()
	testPrompt := testbuilders.NewPromptBuilder().
		WithID("analysis_test").
		WithName("Test").
		Build()
	db.Create(controlPrompt)
	db.Create(testPrompt)

	abTest := testbuilders.NewABTestBuilder().
		WithID("analysis_test_id").
		WithName("Analysis Test").
		WithStatus(prompt.ABTestStatusRunning).
		Build()
	abTest.ControlPromptID = "analysis_control"
	abTest.TestPromptID = "analysis_test"
	now := time.Now().Add(-2 * time.Hour)
	abTest.StartTime = &now
	abTest.Metrics = []prompt.ABTestMetric{
		{Name: "conversion", DisplayName: "Conversion Rate"},
	}
	db.Create(abTest)

	for i := 0; i < 100; i++ {
		variant := prompt.ABTestVariantControl
		promptID := "analysis_control"
		value := 0.7
		if i%2 == 1 {
			variant = prompt.ABTestVariantTest
			promptID = "analysis_test"
			value = 0.8
		}

		result := &prompt.ABTestResult{
			ID:        "result_" + string(rune('A'+i/26)) + string(rune('a'+i%26)),
			TestID:    "analysis_test_id",
			PromptID:  promptID,
			Variant:   variant,
			Metrics:   map[string]float64{"conversion": value},
			SessionID: "sess_" + string(rune('0'+i%10)),
			CreatedAt: time.Now(),
		}
		db.Create(result)
	}

	t.Run("生成AB测试分析报告", func(t *testing.T) {
		analysis, err := service.GetABTestAnalysis(context.Background(), "analysis_test_id")

		require.NoError(t, err)
		assert.NotNil(t, analysis)
		assert.Contains(t, analysis, "control_samples")
		assert.Contains(t, analysis, "test_samples")
		assert.Contains(t, analysis, "metrics")

		metrics := analysis["metrics"].(map[string]interface{})
		assert.Contains(t, metrics, "conversion")

		convMetrics := metrics["conversion"].(map[string]interface{})
		assert.Contains(t, convMetrics, "control_mean")
		assert.Contains(t, convMetrics, "test_mean")
		assert.Contains(t, convMetrics, "z_score")
		assert.Contains(t, convMetrics, "p_value")
		assert.Contains(t, convMetrics, "significant")
	})
}

func TestPromptService_PromptVersionCleanup(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.PromptVersion{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	t.Run("删除指定版本成功", func(t *testing.T) {
		p := testbuilders.NewPromptBuilder().
			WithID("version_cleanup_test").
			WithName("Version Cleanup").
			Build()
		db.Create(p)

		for i := 0; i < 5; i++ {
			version := &prompt.PromptVersion{
				ID:        "ver_cleanup_" + string(rune('0'+i)),
				PromptID:  "version_cleanup_test",
				Version:   i + 1,
				Content:   "content v" + string(rune('0'+i)),
				CreatedBy: "test-user",
				CreatedAt: time.Now(),
			}
			db.Create(version)
		}

		err := service.DeletePromptVersion(context.Background(), "version_cleanup_test", 3)

		require.NoError(t, err)

		var remainingVersions []*prompt.PromptVersion
		db.Where("prompt_id = ?", "version_cleanup_test").Order("version asc").Find(&remainingVersions)
		assert.Len(t, remainingVersions, 4)
		assert.Equal(t, 1, remainingVersions[0].Version)
		assert.Equal(t, 2, remainingVersions[1].Version)
		assert.Equal(t, 4, remainingVersions[2].Version)
		assert.Equal(t, 5, remainingVersions[3].Version)
	})

	t.Run("删除当前活跃版本会设置新的活跃版本", func(t *testing.T) {
		p := testbuilders.NewPromptBuilder().
			WithID("active_version_test").
			WithName("Active Version Test").
			Build()
		db.Create(p)

		versions := []*prompt.PromptVersion{
			{ID: "active_v1", PromptID: "active_version_test", Version: 1, Content: "v1", CreatedBy: "user", CreatedAt: time.Now()},
			{ID: "active_v2", PromptID: "active_version_test", Version: 2, Content: "v2", CreatedBy: "user", CreatedAt: time.Now()},
			{ID: "active_v3", PromptID: "active_version_test", Version: 3, Content: "v3", CreatedBy: "user", CreatedAt: time.Now()},
		}
		for _, v := range versions {
			db.Create(v)
		}

		err := service.DeletePromptVersion(context.Background(), "active_version_test", 3)

		require.NoError(t, err)

		var updatedPrompt prompt.Prompt
		db.Where("id = ?", "active_version_test").First(&updatedPrompt)
		assert.Equal(t, 2, updatedPrompt.Version, "版本应该回退到v2")
	})
}

func TestPromptService_QueryOperations_ResourceManagement(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.PromptVersion{},
		&prompt.ABTest{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	for i := 0; i < 25; i++ {
		p := testbuilders.NewPromptBuilder().
			WithID("query_prompt_" + string(rune('A'+i/26)) + string(rune('a'+i%26))).
			WithName("Prompt " + string(rune('A'+i/26)) + string(rune('a'+i%26))).
			WithNamespace("query-ns").
			Build()
		db.Create(p)
	}

	t.Run("分页查询正确返回结果", func(t *testing.T) {
		prompts, total, err := service.ListPrompts(context.Background(), "query-ns", "", 1, 10)

		require.NoError(t, err)
		assert.Equal(t, int64(25), total)
		assert.Len(t, prompts, 10)
	})

	t.Run("第二页查询正确", func(t *testing.T) {
		prompts, total, err := service.ListPrompts(context.Background(), "query-ns", "", 3, 10)

		require.NoError(t, err)
		assert.Equal(t, int64(25), total)
		assert.Len(t, prompts, 5)
	})

	t.Run("获取Prompt详情包含版本列表", func(t *testing.T) {
		p := testbuilders.NewPromptBuilder().
			WithID("detail_test").
			WithName("Detail Test").
			WithNamespace("detail-ns").
			Build()
		db.Create(p)

		for i := 0; i < 3; i++ {
			v := &prompt.PromptVersion{
				ID:        "detail_v" + string(rune('0'+i)),
				PromptID:  "detail_test",
				Version:   i + 1,
				Content:   "detail content v" + string(rune('0'+i)),
				CreatedBy: "test-user",
				CreatedAt: time.Now(),
			}
			db.Create(v)
		}

		result, err := service.GetPromptDetail(context.Background(), "detail_test")

		require.NoError(t, err)
		assert.NotNil(t, result)
		assert.Contains(t, result, "versions")

		versions := result["versions"].([]*prompt.PromptVersion)
		assert.Len(t, versions, 3)
	})
}

func TestPromptService_ConcurrentPromptCreation(t *testing.T) {
	db, logger, cleanup := testutils.SetupTestDB(
		&prompt.Prompt{},
		&prompt.PromptVersion{},
	)
	defer cleanup()

	service := prompt.NewPromptService(db, logger)

	t.Run("并发创建Prompt不会冲突", func(t *testing.T) {
		var wg sync.WaitGroup
		concurrency := 50

		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				req := &prompt.CreatePromptRequest{
					Name:      "Concurrent Prompt " + string(rune('A'+idx%26)) + string(rune('a'+idx%26)),
					Namespace: "concurrent-ns",
					Content:   "Content " + string(rune('A'+idx%26)),
					Variables: []string{"input"},
				}

				_, err := service.CreatePrompt(context.Background(), req, "concurrent-user")
				assert.NoError(t, err)
			}(i)
		}

		wg.Wait()

		var count int64
		db.Model(&prompt.Prompt{}).Where("namespace = ?", "concurrent-ns").Count(&count)
		assert.Equal(t, int64(concurrency), count, "所有并发创建的Prompt应该被保存")
	})
}
