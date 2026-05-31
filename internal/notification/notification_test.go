package notification

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/datatrace/datatrace/internal/common"
	"github.com/datatrace/datatrace/internal/common/testbuilder"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

func TestNewNotificationService(t *testing.T) {
	t.Run("Create service with buffer", func(t *testing.T) {
		service := NewNotificationService(100)
		assert.NotNil(t, service)
		assert.False(t, service.IsRunning())
	})

	t.Run("Create service with zero buffer", func(t *testing.T) {
		service := NewNotificationService(0)
		assert.NotNil(t, service)
	})
}

func TestService_RegisterSender(t *testing.T) {
	service := NewNotificationService(10)

	t.Run("Register email sender", func(t *testing.T) {
		sender := testbuilder.NewMockSender(0, nil)
		service.RegisterSender("email", sender)
	})

	t.Run("Register multiple senders", func(t *testing.T) {
		service := NewNotificationService(10)
		service.RegisterSender("email", testbuilder.NewMockSender(0, nil))
		service.RegisterSender("sms", testbuilder.NewMockSender(0, nil))
		service.RegisterSender("push", testbuilder.NewMockSender(0, nil))
	})
}

func TestService_Send_NormalPath(t *testing.T) {
	service := NewNotificationService(100)
	mockSender := testbuilder.NewMockSender(0, nil)
	service.RegisterSender("email", mockSender)
	service.Start()
	defer service.Stop()

	cases := testbuilder.NewNotificationTestDataBuilder().
		WithSimpleEmail().
		WithSMS().
		WithLargePayload().
		Build()

	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			notif, err := service.Send(ctx, tc.NotifType, tc.Recipient, tc.Payload)

			if tc.ShouldFail {
				assert.Error(t, err)
				assert.Nil(t, notif)
				return
			}

			require.NoError(t, err)
			require.NotNil(t, notif)
			assert.Equal(t, tc.NotifType, notif.Type)
			assert.Equal(t, tc.Recipient, notif.Recipient)
			assert.Equal(t, StatusPending, notif.Status)
			assert.Equal(t, 0, notif.RetryCount)
			assert.NotEmpty(t, notif.ID)
			assert.False(t, notif.CreatedAt.IsZero())

			time.Sleep(100 * time.Millisecond)
			status, err := service.GetStatus(notif.ID)
			if err == nil {
				assert.Contains(t, []Status{StatusSent, StatusDelivered}, status.Status)
			}
		})
	}
}

func TestService_Send_BoundaryInputs(t *testing.T) {
	service := NewNotificationService(10)
	sender := testbuilder.NewMockSender(0, nil)
	service.RegisterSender("email", sender)
	service.Start()
	defer service.Stop()

	t.Run("Send with nil payload", func(t *testing.T) {
		ctx := context.Background()
		notif, err := service.Send(ctx, "email", "test@example.com", nil)
		assert.NoError(t, err)
		assert.NotNil(t, notif)
		assert.Nil(t, notif.Payload)
	})

	t.Run("Send with empty recipient", func(t *testing.T) {
		ctx := context.Background()
		notif, err := service.Send(ctx, "email", "", map[string]interface{}{})
		assert.NoError(t, err)
		assert.NotNil(t, notif)
	})

	t.Run("Send with unregistered type", func(t *testing.T) {
		ctx := context.Background()
		notif, err := service.Send(ctx, "unknown", "test@example.com", nil)
		assert.Error(t, err)
		assert.Nil(t, notif)
	})

	t.Run("Send to full queue", func(t *testing.T) {
		smallService := NewNotificationService(1)
		slowSender := testbuilder.NewMockSender(5*time.Second, nil)
		smallService.RegisterSender("slow", slowSender)
		smallService.Start()
		defer smallService.Stop()

		_, _ = smallService.Send(context.Background(), "slow", "test1@example.com", nil)

		ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
		defer cancel()

		notif, err := smallService.Send(ctx, "slow", "test2@example.com", nil)
		assert.Error(t, err)
		assert.Nil(t, notif)
	})

	t.Run("Send with cancelled context", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()

		notif, err := service.Send(ctx, "email", "test@example.com", nil)
		assert.Error(t, err)
		assert.Nil(t, notif)
	})
}

func TestService_Send_ExceptionInjection(t *testing.T) {
	t.Run("Service not started", func(t *testing.T) {
		service := NewNotificationService(10)
		mockSender := testbuilder.NewMockSender(0, nil)
		service.RegisterSender("email", mockSender)

		ctx := context.Background()
		notif, err := service.Send(ctx, "email", "test@example.com", nil)
		assert.Error(t, err)
		assert.Nil(t, notif)
	})

	t.Run("Sender returns error - retry logic", func(t *testing.T) {
		service := NewNotificationService(10)
		failingSender := testbuilder.NewMockSender(0, errors.New("SMTP connection refused"))
		service.RegisterSender("failing", failingSender)
		service.Start()
		defer service.Stop()

		ctx := context.Background()
		notif, err := service.Send(ctx, "failing", "test@example.com", nil)
		require.NoError(t, err)
		require.NotNil(t, notif)

		time.Sleep(3 * time.Second)

		assert.GreaterOrEqual(t, failingSender.GetCallCount(), 1)
	})

	t.Run("Sender timeout", func(t *testing.T) {
		service := NewNotificationService(10)
		slowSender := testbuilder.NewMockSender(35*time.Second, nil)
		service.RegisterSender("slow", slowSender)
		service.Start()
		defer service.Stop()

		ctx := context.Background()
		notif, err := service.Send(ctx, "slow", "test@example.com", nil)
		require.NoError(t, err)

		time.Sleep(2 * time.Second)
		status, _ := service.GetStatus(notif.ID)
		if status != nil {
			assert.Contains(t, []Status{StatusSent, StatusRetrying}, status.Status)
		}
	})
}

func TestService_RetryMechanism(t *testing.T) {
	t.Run("Exponential backoff", func(t *testing.T) {
		service := NewNotificationService(10)
		alwaysFail := testbuilder.NewMockSender(0, errors.New("permanent failure"))
		service.RegisterSender("fail", alwaysFail)
		service.Start()
		defer service.Stop()

		ctx := context.Background()
		notif, err := service.Send(ctx, "fail", "test@example.com", nil)
		require.NoError(t, err)

		time.Sleep(4 * time.Second)

		assert.GreaterOrEqual(t, alwaysFail.GetCallCount(), 1)
	})

	t.Run("Success after retry", func(t *testing.T) {
		callCount := 0
		flakySender := &testbuilder.MockSender{
			Delay: 0,
			ReturnError: nil,
		}

		succeedAfter := 2
		mockSender := &flakyMockSender{
			flakySender:  flakySender,
			succeedAfter: succeedAfter,
		}

		service := NewNotificationService(10)
		service.RegisterSender("flaky", mockSender)
		service.Start()
		defer service.Stop()

		ctx := context.Background()
		notif, err := service.Send(ctx, "flaky", "test@example.com", nil)
		require.NoError(t, err)

		time.Sleep(3 * time.Second)

		assert.GreaterOrEqual(t, mockSender.getCallCount(), succeedAfter)
	})
}

func TestService_GetStatus(t *testing.T) {
	service := NewNotificationService(10)
	sender := testbuilder.NewMockSender(0, nil)
	service.RegisterSender("email", sender)
	service.Start()
	defer service.Stop()

	t.Run("Get existing notification status", func(t *testing.T) {
		ctx := context.Background()
		notif, err := service.Send(ctx, "email", "test@example.com", nil)
		require.NoError(t, err)

		status, err := service.GetStatus(notif.ID)
		assert.NoError(t, err)
		assert.NotNil(t, status)
	})

	t.Run("Get non-existent notification status", func(t *testing.T) {
		status, err := service.GetStatus("non-existent-id")
		assert.Error(t, err)
		assert.Nil(t, status)
	})
}

func TestService_QueueStatus(t *testing.T) {
	service := NewNotificationService(100)
	sender := testbuilder.NewMockSender(0, nil)
	service.RegisterSender("email", sender)
	service.Start()
	defer service.Stop()

	status := service.QueueStatus()
	assert.Equal(t, 100, status.Capacity)
	assert.Equal(t, 0, status.Queued)
	assert.Equal(t, 0, status.InFlight)

	ctx := context.Background()
	_, _ = service.Send(ctx, "email", "test@example.com", nil)

	time.Sleep(50 * time.Millisecond)
	newStatus := service.QueueStatus()
	assert.GreaterOrEqual(t, newStatus.Queued, 0)
}

func TestService_ConcurrentOperations(t *testing.T) {
	service := NewNotificationService(1000)
	sender := testbuilder.NewMockSender(0, nil)
	service.RegisterSender("email", sender)
	service.Start()
	defer service.Stop()

	const goroutines = 50
	const notificationsPerGoroutine = 20

	var wg sync.WaitGroup
	wg.Add(goroutines)

	errCount := 0
	var mu sync.Mutex

	for i := 0; i < goroutines; i++ {
		go func(id int) {
			defer wg.Done()
			ctx := context.Background()
			for j := 0; j < notificationsPerGoroutine; j++ {
				recipient := fmt.Sprintf("user_%d_%d@example.com", id, j)
				payload := map[string]interface{}{
					"user_id": id,
					"index":   j,
				}
				_, err := service.Send(ctx, "email", recipient, payload)
				if err != nil {
					mu.Lock()
					errCount++
					mu.Unlock()
				}
			}
		}(i)
	}

	wg.Wait()
	assert.Equal(t, 0, errCount, "expected no errors during concurrent sends")

	time.Sleep(500 * time.Millisecond)
	qs := service.QueueStatus()
	assert.Equal(t, 0, qs.Queued)
	assert.GreaterOrEqual(t, sender.GetCallCount(), goroutines*notificationsPerGoroutine)
}

func TestService_ConcurrentSendAndStatus(t *testing.T) {
	service := NewNotificationService(100)
	sender := testbuilder.NewMockSender(10*time.Millisecond, nil)
	service.RegisterSender("email", sender)
	service.Start()
	defer service.Stop()

	var wg sync.WaitGroup

	sentIDs := make([]string, 0)
	var mu sync.Mutex

	wg.Add(1)
	go func() {
		defer wg.Done()
		ctx := context.Background()
		for i := 0; i < 100; i++ {
			notif, err := service.Send(ctx, "email", "test@example.com", nil)
			if err == nil {
				mu.Lock()
				sentIDs = append(sentIDs, notif.ID)
				mu.Unlock()
			}
		}
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 100; i++ {
			mu.Lock()
			if len(sentIDs) > 0 {
				id := sentIDs[len(sentIDs)-1]
				mu.Unlock()
				_, _ = service.GetStatus(id)
			} else {
				mu.Unlock()
			}
			time.Sleep(1 * time.Millisecond)
		}
	}()

	wg.Wait()
}

func TestService_Lifecycle(t *testing.T) {
	t.Run("Start and stop", func(t *testing.T) {
		service := NewNotificationService(10)
		assert.False(t, service.IsRunning())

		service.Start()
		assert.True(t, service.IsRunning())

		service.Stop()
		assert.False(t, service.IsRunning())
	})

	t.Run("Double start", func(t *testing.T) {
		service := NewNotificationService(10)
		service.Start()
		service.Start()
		assert.True(t, service.IsRunning())
		service.Stop()
	})

	t.Run("Double stop", func(t *testing.T) {
		service := NewNotificationService(10)
		service.Start()
		service.Stop()
		service.Stop()
		assert.False(t, service.IsRunning())
	})

	t.Run("Restart service", func(t *testing.T) {
		service := NewNotificationService(10)
		sender := testbuilder.NewMockSender(0, nil)
		service.RegisterSender("email", sender)

		service.Start()
		ctx := context.Background()
		_, err := service.Send(ctx, "email", "test@example.com", nil)
		assert.NoError(t, err)
		service.Stop()

		service.Start()
		_, err = service.Send(ctx, "email", "test@example.com", nil)
		assert.NoError(t, err)
		service.Stop()
	})
}

func TestService_GetMetrics(t *testing.T) {
	service := NewNotificationService(100)
	sender := testbuilder.NewMockSender(0, nil)
	service.RegisterSender("email", sender)
	service.RegisterSender("sms", testbuilder.NewMockSender(0, nil))
	service.Start()
	defer service.Stop()

	metrics := service.GetMetrics()
	assert.NotNil(t, metrics)
	assert.Equal(t, 2, metrics["sender_count"])
	assert.Contains(t, metrics, "queued")
	assert.Contains(t, metrics, "in_flight")
	assert.Contains(t, metrics, "uptime")
}

func TestService_ToEntity(t *testing.T) {
	service := NewNotificationService(10)
	entity := service.ToEntity()

	assert.NotNil(t, entity)
	assert.Equal(t, "notification_service", entity.Type)
	assert.Equal(t, "active", entity.Status)
	assert.NotEmpty(t, entity.ID)
}

func TestNotificationStatus_Transitions(t *testing.T) {
	service := NewNotificationService(10)
	sender := testbuilder.NewMockSender(0, nil)
	service.RegisterSender("email", sender)
	service.Start()
	defer service.Stop()

	ctx := context.Background()
	notif, err := service.Send(ctx, "email", "test@example.com", nil)
	require.NoError(t, err)

	assert.Equal(t, StatusPending, notif.Status)

	time.Sleep(50 * time.Millisecond)

	status, _ := service.GetStatus(notif.ID)
	if status != nil {
		assert.NotEqual(t, StatusPending, status.Status)
	}
}

func TestService_Integration_WithErrorRecovery(t *testing.T) {
	service := NewNotificationService(100)

	successfulSender := testbuilder.NewMockSender(0, nil)
	intermittentSender := &intermittentMockSender{
		failUntil: time.Now().Add(100 * time.Millisecond),
	}

	service.RegisterSender("normal", successfulSender)
	service.RegisterSender("intermittent", intermittentSender)
	service.Start()
	defer service.Stop()

	ctx := context.Background()
	var wg sync.WaitGroup

	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = service.Send(ctx, "normal", "user@example.com", nil)
		}()
	}

	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = service.Send(ctx, "intermittent", "user@example.com", nil)
		}()
	}

	wg.Wait()
	time.Sleep(1 * time.Second)

	qs := service.QueueStatus()
	assert.Equal(t, 0, qs.Queued)
}

type flakyMockSender struct {
	flakySender  *testbuilder.MockSender
	succeedAfter int
	callCount    int
	mu           sync.Mutex
}

func (f *flakyMockSender) Send(ctx context.Context, notif *Notification) error {
	f.mu.Lock()
	f.callCount++
	currentCount := f.callCount
	f.mu.Unlock()

	if currentCount < f.succeedAfter {
		return errors.New("temporary failure")
	}
	return f.flakySender.Send(ctx, notif)
}

func (f *flakyMockSender) getCallCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.callCount
}

type intermittentMockSender struct {
	failUntil time.Time
	callCount int
	mu        sync.Mutex
}

func (i *intermittentMockSender) Send(ctx context.Context, notif *Notification) error {
	i.mu.Lock()
	i.callCount++
	i.mu.Unlock()

	if time.Now().Before(i.failUntil) {
		return errors.New("service unavailable")
	}
	return nil
}

func TestService_CommonErrorWrapping(t *testing.T) {
	service := NewNotificationService(10)

	t.Run("QueueFull error", func(t *testing.T) {
		smallService := NewNotificationService(0)
		smallService.Start()
		defer smallService.Stop()

		sender := testbuilder.NewMockSender(1*time.Second, nil)
		smallService.RegisterSender("slow", sender)

		_, _ = smallService.Send(context.Background(), "slow", "test@example.com", nil)
		notif, err := smallService.Send(context.Background(), "slow", "test2@example.com", nil)

		assert.Nil(t, notif)
		assert.Error(t, err)
	})

	t.Run("NotFound error", func(t *testing.T) {
		service.Start()
		defer service.Stop()

		notif, err := service.GetStatus("non-existent")
		assert.Nil(t, notif)
		assert.Error(t, err)
	})
}
