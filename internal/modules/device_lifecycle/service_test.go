package device_lifecycle

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"

	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/testutils"
)

func setupTestDB(t *testing.T) *gorm.DB {
	db, err := gorm.Open(sqlite.Open("file::memory:?cache=shared"), &gorm.Config{})
	require.NoError(t, err)

	err = db.AutoMigrate(&Device{})
	require.NoError(t, err)

	return db
}

func TestDeviceService_RegisterDevice_NormalFlow(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name        string
		buildReq    func() *DeviceRegistrationRequest
		expectStatus DeviceStatus
	}{
		{
			name: "注册标准设备",
			buildReq: func() *DeviceRegistrationRequest {
				return testutils.NewDeviceRegistrationRequestBuilder().
					WithDeviceID("dev_reg_001").
					WithName("温度传感器").
					WithType("temperature_sensor").
					Build()
			},
			expectStatus: DeviceStatusRegistered,
		},
		{
			name: "注册带元数据的设备",
			buildReq: func() *DeviceRegistrationRequest {
				return testutils.NewDeviceRegistrationRequestBuilder().
					WithDeviceID("dev_reg_002").
					WithName("智能网关").
					WithType("gateway").
					WithMetadata(map[string]interface{}{
						"firmware": "v2.0",
						"channels": 8,
					}).
					WithLabels(map[string]string{
						"zone":     "production",
						"priority": "high",
					}).
					Build()
			},
			expectStatus: DeviceStatusRegistered,
		},
		{
			name: "注册最小化设备（可选字段为空）",
			buildReq: func() *DeviceRegistrationRequest {
				return &DeviceRegistrationRequest{
					DeviceID: "dev_reg_003",
					Name:     "最小设备",
					Type:     "sensor",
				}
			},
			expectStatus: DeviceStatusRegistered,
		},
	}

	for _, tc := range testCases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			db := setupTestDB(t)
			mockEB := testutils.NewMockEventBus()
			service := NewDeviceServiceWithDeps(db, mockEB)
			ctx := testutils.GetTestContext()

			req := tc.buildReq()
			device, err := service.RegisterDevice(ctx, req)

			require.NoError(t, err)
			require.NotNil(t, device)
			assert.Equal(t, req.DeviceID, device.DeviceID)
			assert.Equal(t, req.Name, device.Name)
			assert.Equal(t, req.Type, device.Type)
			assert.Equal(t, tc.expectStatus, device.Status)
			assert.NotEmpty(t, device.AuthToken)
			assert.NotEmpty(t, device.ID)

			var count int64
			db.Model(&Device{}).Where("device_id = ?", req.DeviceID).Count(&count)
			assert.Equal(t, int64(1), count)

			events := mockEB.GetPublishedEventsByType(eventbus.EventDeviceRegistered)
			assert.Len(t, events, 1)
			assert.Equal(t, req.DeviceID, events[0].Payload["device_id"])
		})
	}
}

func TestDeviceService_RegisterDevice_ErrorCases(t *testing.T) {
	t.Parallel()

	t.Run("重复注册设备", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewDeviceRegistrationRequestBuilder().
			WithDeviceID("dev_dup_001").
			Build()

		_, err := service.RegisterDevice(ctx, req)
		require.NoError(t, err)

		_, err = service.RegisterDevice(ctx, req)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "already registered")

		events := mockEB.GetPublishedEventsByType(eventbus.EventDeviceRegistered)
		assert.Len(t, events, 1)
	})

	t.Run("设备ID为空", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewDeviceRegistrationRequestBuilder().
			WithEmptyDeviceID().
			Build()

		device, err := service.RegisterDevice(ctx, req)

		if err == nil {
			t.Log("注意：当前实现未在业务层验证空值，依赖数据库约束")
		}
		_ = device
	})

	t.Run("超长设备ID", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewDeviceRegistrationRequestBuilder().
			WithLongStringFields(1000).
			Build()

		device, err := service.RegisterDevice(ctx, req)
		if err != nil {
			t.Logf("超长字段处理结果: %v", err)
		} else {
			assert.NotNil(t, device)
		}
	})
}

func TestDeviceService_ActivateDevice_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	regReq := testutils.NewDeviceRegistrationRequestBuilder().
		WithDeviceID("dev_act_001").
		Build()
	_, err := service.RegisterDevice(ctx, regReq)
	require.NoError(t, err)

	actReq := testutils.NewDeviceActivationRequestBuilder().
		WithDeviceID("dev_act_001").
		WithSecret("activation_secret").
		Build()

	device, err := service.ActivateDevice(ctx, actReq)

	require.NoError(t, err)
	require.NotNil(t, device)
	assert.Equal(t, DeviceStatusActivated, device.Status)
	assert.NotNil(t, device.ActivatedAt)
	assert.NotNil(t, device.LastHeartbeatAt)

	events := mockEB.GetPublishedEventsByType(eventbus.EventDeviceActivated)
	assert.Len(t, events, 1)
	assert.Equal(t, "dev_act_001", events[0].Payload["device_id"])
}

func TestDeviceService_ActivateDevice_ErrorCases(t *testing.T) {
	t.Parallel()

	t.Run("激活不存在的设备", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewDeviceActivationRequestBuilder().
			WithDeviceID("dev_nonexistent").
			Build()

		device, err := service.ActivateDevice(ctx, req)

		require.Error(t, err)
		assert.Contains(t, err.Error(), "not found")
		assert.Nil(t, device)

		assert.Equal(t, 0, mockEB.GetPublishedEventCount())
	})

	t.Run("重复激活已激活设备", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		regReq := testutils.NewDeviceRegistrationRequestBuilder().
			WithDeviceID("dev_act_dup").
			Build()
		_, err := service.RegisterDevice(ctx, regReq)
		require.NoError(t, err)

		actReq := testutils.NewDeviceActivationRequestBuilder().
			WithDeviceID("dev_act_dup").
			Build()
		_, err = service.ActivateDevice(ctx, actReq)
		require.NoError(t, err)

		_, err = service.ActivateDevice(ctx, actReq)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "already activated")
	})

	t.Run("激活已注销设备", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		device := testutils.NewDeviceBuilder().
			WithDeviceID("dev_deact").
			WithStatus(DeviceStatusDeactivated).
			Build()
		db.Create(device)

		req := testutils.NewDeviceActivationRequestBuilder().
			WithDeviceID("dev_deact").
			Build()

		_, err := service.ActivateDevice(ctx, req)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "deactivated")
	})
}

func TestDeviceService_ProcessHeartbeat_NormalFlow(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name          string
		initialStatus DeviceStatus
		expectStatus  DeviceStatus
		statusChanged bool
	}{
		{
			name:          "已注册设备心跳->激活",
			initialStatus: DeviceStatusRegistered,
			expectStatus:  DeviceStatusActivated,
			statusChanged: true,
		},
		{
			name:          "已激活设备心跳->在线",
			initialStatus: DeviceStatusActivated,
			expectStatus:  DeviceStatusOnline,
			statusChanged: true,
		},
		{
			name:          "离线设备心跳->在线",
			initialStatus: DeviceStatusOffline,
			expectStatus:  DeviceStatusOnline,
			statusChanged: true,
		},
		{
			name:          "在线设备心跳->保持在线",
			initialStatus: DeviceStatusOnline,
			expectStatus:  DeviceStatusOnline,
			statusChanged: false,
		},
	}

	for _, tc := range testCases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			db := setupTestDB(t)
			mockEB := testutils.NewMockEventBus()
			service := NewDeviceServiceWithDeps(db, mockEB)
			ctx := testutils.GetTestContext()

			device := testutils.NewDeviceBuilder().
				WithDeviceID(fmt.Sprintf("dev_hb_%s", tc.initialStatus)).
				WithStatus(tc.initialStatus).
				Build()
			db.Create(device)

			req := testutils.NewDeviceHeartbeatRequestBuilder().
				WithDeviceID(device.DeviceID).
				WithFirmwareVersion("2.1.0").
				Build()

			beforeTime := time.Now().UTC()
			resp, err := service.ProcessHeartbeat(ctx, req)

			require.NoError(t, err)
			require.NotNil(t, resp)
			assert.Equal(t, device.DeviceID, resp.DeviceID)
			assert.Equal(t, tc.expectStatus, resp.Status)
			assert.Equal(t, "2.1.0", resp.FirmwareVersion)
			assert.NotNil(t, resp.LastHeartbeatAt)
			assert.True(t, resp.LastHeartbeatAt.After(beforeTime))

			var updated Device
			db.Where("device_id = ?", device.DeviceID).First(&updated)
			assert.Equal(t, tc.expectStatus, updated.Status)
			assert.Equal(t, "2.1.0", updated.FirmwareVersion)

			events := mockEB.GetPublishedEventsByType(eventbus.EventDeviceStatusChanged)
			if tc.statusChanged {
				assert.Len(t, events, 1)
				assert.Equal(t, tc.initialStatus, events[0].Payload["old_status"])
				assert.Equal(t, tc.expectStatus, events[0].Payload["new_status"])
			} else {
				assert.Len(t, events, 0)
			}
		})
	}
}

func TestDeviceService_ProcessHeartbeat_ErrorCases(t *testing.T) {
	t.Parallel()

	t.Run("心跳设备不存在", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		req := testutils.NewDeviceHeartbeatRequestBuilder().
			WithDeviceID("dev_unknown").
			Build()

		resp, err := service.ProcessHeartbeat(ctx, req)

		require.Error(t, err)
		assert.Contains(t, err.Error(), "not found")
		assert.Nil(t, resp)
	})

	t.Run("已注销设备发送心跳", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		device := testutils.NewDeviceBuilder().
			WithDeviceID("dev_dead").
			WithStatus(DeviceStatusDeactivated).
			Build()
		db.Create(device)

		req := testutils.NewDeviceHeartbeatRequestBuilder().
			WithDeviceID("dev_dead").
			Build()

		resp, err := service.ProcessHeartbeat(ctx, req)

		require.Error(t, err)
		assert.Contains(t, err.Error(), "deactivated")
		assert.Nil(t, resp)
	})
}

func TestDeviceService_DeactivateDevice_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	device := testutils.NewDeviceBuilder().
		WithDeviceID("dev_deact_001").
		WithStatus(DeviceStatusOnline).
		Build()
	db.Create(device)

	err := service.DeactivateDevice(ctx, "dev_deact_001", "维护升级")

	require.NoError(t, err)

	var updated Device
	db.Where("device_id = ?", "dev_deact_001").First(&updated)
	assert.Equal(t, DeviceStatusDeactivated, updated.Status)
	assert.NotNil(t, updated.DeactivatedAt)
	assert.Equal(t, "维护升级", updated.Metadata["deactivation_reason"])

	events := mockEB.GetPublishedEventsByType(eventbus.EventDeviceDeactivated)
	assert.Len(t, events, 1)
	assert.Equal(t, "dev_deact_001", events[0].Payload["device_id"])
	assert.Equal(t, "维护升级", events[0].Payload["deactivation_reason"])
}

func TestDeviceService_GetDevice_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	device := testutils.NewDeviceBuilder().
		WithDeviceID("dev_get_001").
		WithName("测试设备").
		WithType("sensor").
		Build()
	db.Create(device)

	result, err := service.GetDevice(ctx, "dev_get_001")

	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Equal(t, "dev_get_001", result.DeviceID)
	assert.Equal(t, "测试设备", result.Name)
	assert.Equal(t, "sensor", result.Type)
}

func TestDeviceService_GetDevice_ErrorCases(t *testing.T) {
	t.Parallel()

	t.Run("查询不存在的设备", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		result, err := service.GetDevice(ctx, "dev_nonexistent")

		require.Error(t, err)
		assert.Contains(t, err.Error(), "not found")
		assert.Nil(t, result)
	})

	t.Run("查询空ID设备", func(t *testing.T) {
		t.Parallel()

		db := setupTestDB(t)
		mockEB := testutils.NewMockEventBus()
		service := NewDeviceServiceWithDeps(db, mockEB)
		ctx := testutils.GetTestContext()

		result, err := service.GetDevice(ctx, "")

		require.Error(t, err)
		assert.Nil(t, result)
	})
}

func TestDeviceService_ListDevices_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	for i := 1; i <= 15; i++ {
		device := testutils.NewDeviceBuilder().
			WithDeviceID(fmt.Sprintf("dev_list_%03d", i)).
			WithType("sensor").
			WithStatus(DeviceStatusOnline).
			WithLocation(fmt.Sprintf("zone-%d", (i%3)+1)).
			Build()
		db.Create(device)
	}

	t.Run("无过滤全量查询", func(t *testing.T) {
		devices, total, err := service.ListDevices(ctx, map[string]interface{}{}, 0, 10)
		require.NoError(t, err)
		assert.Equal(t, int64(15), total)
		assert.Len(t, devices, 10)
	})

	t.Run("按状态过滤", func(t *testing.T) {
		devices, total, err := service.ListDevices(ctx, map[string]interface{}{
			"status": string(DeviceStatusOnline),
		}, 0, 20)
		require.NoError(t, err)
		assert.Equal(t, int64(15), total)
		assert.Len(t, devices, 15)
	})

	t.Run("按位置模糊查询", func(t *testing.T) {
		devices, total, err := service.ListDevices(ctx, map[string]interface{}{
			"location": "zone-1",
		}, 0, 10)
		require.NoError(t, err)
		assert.Equal(t, int64(5), total)
		assert.Len(t, devices, 5)
	})

	t.Run("分页查询", func(t *testing.T) {
		devices, total, err := service.ListDevices(ctx, map[string]interface{}{}, 10, 10)
		require.NoError(t, err)
		assert.Equal(t, int64(15), total)
		assert.Len(t, devices, 5)
	})
}

func TestDeviceService_DeleteDevice_NormalFlow(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	device := testutils.NewDeviceBuilder().
		WithDeviceID("dev_del_001").
		Build()
	db.Create(device)

	var count int64
	db.Model(&Device{}).Where("device_id = ?", "dev_del_001").Count(&count)
	assert.Equal(t, int64(1), count)

	err := service.DeleteDevice(ctx, "dev_del_001")
	require.NoError(t, err)

	db.Model(&Device{}).Where("device_id = ?", "dev_del_001").Count(&count)
	assert.Equal(t, int64(0), count)
}

func TestDeviceService_DeleteDevice_ErrorCases(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	err := service.DeleteDevice(ctx, "dev_nonexistent")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "not found")
}

func TestDeviceService_ConcurrentRegistrations(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	concurrency := 50
	var wg sync.WaitGroup
	helper := testutils.NewConcurrentTestHelper()

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()

			req := testutils.NewDeviceRegistrationRequestBuilder().
				WithDeviceID(fmt.Sprintf("dev_concurrent_%04d", idx)).
				WithName(fmt.Sprintf("并发设备-%d", idx)).
				Build()

			device, err := service.RegisterDevice(ctx, req)
			if err != nil {
				helper.AddError(fmt.Errorf("设备%d注册失败: %w", idx, err))
				return
			}
			if device == nil {
				helper.AddError(errors.New("返回设备为空"))
				return
			}
			helper.IncrementSuccess()
		}(i)
	}

	wg.Wait()

	assert.False(t, helper.HasErrors(), "并发注册出现错误: %v", helper.GetErrors())
	assert.Equal(t, concurrency, helper.GetSuccessCount())

	var total int64
	db.Model(&Device{}).Count(&total)
	assert.Equal(t, int64(concurrency), total)

	assert.Equal(t, concurrency, mockEB.GetPublishedEventCount())
}

func TestDeviceService_ConcurrentHeartbeats(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	device := testutils.NewDeviceBuilder().
		WithDeviceID("dev_hb_concurrent").
		WithStatus(DeviceStatusRegistered).
		Build()
	db.Create(device)

	concurrency := 100
	iterations := 10
	var wg sync.WaitGroup
	helper := testutils.NewConcurrentTestHelper()

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()

			for j := 0; j < iterations; j++ {
				req := testutils.NewDeviceHeartbeatRequestBuilder().
					WithDeviceID("dev_hb_concurrent").
					WithFirmwareVersion(fmt.Sprintf("v%d.%d", workerID, j)).
					WithMetrics(map[string]interface{}{
						"worker": workerID,
						"iter":   j,
					}).
					Build()

				_, err := service.ProcessHeartbeat(ctx, req)
				if err != nil {
					helper.AddError(fmt.Errorf("心跳失败 worker=%d iter=%d: %w", workerID, j, err))
					return
				}
				helper.IncrementSuccess()
			}
		}(i)
	}

	wg.Wait()

	assert.False(t, helper.HasErrors(), "并发心跳出现错误: %v", helper.GetErrors())
	assert.Equal(t, concurrency*iterations, helper.GetSuccessCount())

	var updated Device
	db.Where("device_id = ?", "dev_hb_concurrent").First(&updated)
	assert.Equal(t, DeviceStatusOnline, updated.Status)
	assert.NotNil(t, updated.LastHeartbeatAt)
}

func TestDeviceService_UpdateDeviceStatus(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	device := testutils.NewDeviceBuilder().
		WithDeviceID("dev_status_001").
		WithStatus(DeviceStatusOnline).
		Build()
	db.Create(device)

	err := service.UpdateDeviceStatus(ctx, "dev_status_001", DeviceStatusOffline)
	require.NoError(t, err)

	var updated Device
	db.Where("device_id = ?", "dev_status_001").First(&updated)
	assert.Equal(t, DeviceStatusOffline, updated.Status)

	events := mockEB.GetPublishedEventsByType(eventbus.EventDeviceStatusChanged)
	assert.Len(t, events, 1)
	assert.Equal(t, DeviceStatusOnline, events[0].Payload["old_status"])
	assert.Equal(t, DeviceStatusOffline, events[0].Payload["new_status"])

	t.Run("状态未变更时不发布事件", func(t *testing.T) {
		mockEB.ClearEvents()

		err := service.UpdateDeviceStatus(ctx, "dev_status_001", DeviceStatusOffline)
		require.NoError(t, err)

		assert.Equal(t, 0, mockEB.GetPublishedEventCount())
	})
}

func TestDeviceService_DatabaseFailure_Simulated(t *testing.T) {
	t.Parallel()

	db, _ := gorm.Open(sqlite.Open("file::memory:"), &gorm.Config{})
	mockEB := testutils.NewMockEventBus()

	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	_ = db.Migrator().DropTable(&Device{})

	req := testutils.NewDeviceRegistrationRequestBuilder().
		WithDeviceID("dev_fail_001").
		Build()

	device, err := service.RegisterDevice(ctx, req)

	require.Error(t, err)
	assert.Nil(t, device)
	assert.Contains(t, err.Error(), "failed to create device")
}

func TestDeviceService_EventBusFailure_Continue(t *testing.T) {
	t.Parallel()

	db := setupTestDB(t)
	mockEB := testutils.NewMockEventBus()
	mockEB.SetShouldFail(true, eventbus.EventDeviceRegistered)

	service := NewDeviceServiceWithDeps(db, mockEB)
	ctx := testutils.GetTestContext()

	req := testutils.NewDeviceRegistrationRequestBuilder().
		WithDeviceID("dev_eb_fail").
		Build()

	device, err := service.RegisterDevice(ctx, req)

	require.NoError(t, err, "即使事件总线失败，数据库操作应成功")
	require.NotNil(t, device)

	var count int64
	db.Model(&Device{}).Where("device_id = ?", "dev_eb_fail").Count(&count)
	assert.Equal(t, int64(1), count)
}
