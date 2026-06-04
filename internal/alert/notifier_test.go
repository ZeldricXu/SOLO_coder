package alert

import (
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
)

type mockNotifier struct {
	name    string
	sendErr error
	sent    []*models.AlertEvent
}

func (m *mockNotifier) Send(alert *models.AlertEvent) error {
	m.sent = append(m.sent, alert)
	return m.sendErr
}

func (m *mockNotifier) Name() string {
	return m.name
}

func TestNotifierRegistry_Register(t *testing.T) {
	reg := &NotifierRegistry{notifiers: make(map[string]Notifier)}
	n := &mockNotifier{name: "test"}

	reg.Register(n)

	got, ok := reg.Get("test")
	assert.True(t, ok)
	assert.Equal(t, n, got)
}

func TestNotifierRegistry_Unregister(t *testing.T) {
	reg := &NotifierRegistry{notifiers: make(map[string]Notifier)}
	n := &mockNotifier{name: "test"}
	reg.Register(n)

	reg.Unregister("test")

	_, ok := reg.Get("test")
	assert.False(t, ok)
}

func TestNotifierRegistry_Get_NotFound(t *testing.T) {
	reg := &NotifierRegistry{notifiers: make(map[string]Notifier)}

	_, ok := reg.Get("nonexistent")
	assert.False(t, ok)
}

func TestNotifierRegistry_All(t *testing.T) {
	reg := &NotifierRegistry{notifiers: make(map[string]Notifier)}
	reg.Register(&mockNotifier{name: "a"})
	reg.Register(&mockNotifier{name: "b"})

	all := reg.All()
	assert.Len(t, all, 2)
}

func TestNotifierRegistry_Names(t *testing.T) {
	reg := &NotifierRegistry{notifiers: make(map[string]Notifier)}
	reg.Register(&mockNotifier{name: "a"})
	reg.Register(&mockNotifier{name: "b"})

	names := reg.Names()
	assert.Len(t, names, 2)
	assert.Contains(t, names, "a")
	assert.Contains(t, names, "b")
}

func TestNotifierRegistry_Clear(t *testing.T) {
	reg := &NotifierRegistry{notifiers: make(map[string]Notifier)}
	reg.Register(&mockNotifier{name: "a"})
	reg.Register(&mockNotifier{name: "b"})

	reg.Clear()

	assert.Len(t, reg.All(), 0)
}

func TestGlobalNotifierRegistry(t *testing.T) {
	reg := GlobalNotifierRegistry()
	assert.NotNil(t, reg)
}

func TestRegisterNotifier(t *testing.T) {
	reg := GlobalNotifierRegistry()
	original := reg.All()

	testNotifier := &mockNotifier{name: "test_global_register"}
	RegisterNotifier(testNotifier)

	_, ok := reg.Get("test_global_register")
	assert.True(t, ok)

	reg.Unregister("test_global_register")
	assert.Len(t, reg.All(), len(original))
}

func TestDingTalkNotifier_Name(t *testing.T) {
	n := NewDingTalkNotifier("webhook", "secret")
	assert.Equal(t, "dingtalk", n.Name())
}

func TestFeishuNotifier_Name(t *testing.T) {
	n := NewFeishuNotifier("webhook")
	assert.Equal(t, "feishu", n.Name())
}

func TestPagerDutyNotifier_Name(t *testing.T) {
	n := NewPagerDutyNotifier("token")
	assert.Equal(t, "pagerduty", n.Name())
}

func TestCreateNotifierFromConfig_DingTalk(t *testing.T) {
	chCfg := config.AlertChannelConfig{
		Type:    "dingtalk",
		Webhook: "https://oapi.dingtalk.com/robot/send?access_token=test",
		Secret:  "test-secret",
	}

	n := CreateNotifierFromConfig(chCfg)
	assert.NotNil(t, n)
	assert.Equal(t, "dingtalk", n.Name())
}

func TestCreateNotifierFromConfig_Feishu(t *testing.T) {
	chCfg := config.AlertChannelConfig{
		Type:    "feishu",
		Webhook: "https://open.feishu.cn/open-apis/bot/v2/hook/test",
	}

	n := CreateNotifierFromConfig(chCfg)
	assert.NotNil(t, n)
	assert.Equal(t, "feishu", n.Name())
}

func TestCreateNotifierFromConfig_Lark(t *testing.T) {
	chCfg := config.AlertChannelConfig{
		Type:    "lark",
		Webhook: "https://open.feishu.cn/open-apis/bot/v2/hook/test",
	}

	n := CreateNotifierFromConfig(chCfg)
	assert.NotNil(t, n)
	assert.Equal(t, "feishu", n.Name())
}

func TestCreateNotifierFromConfig_PagerDuty(t *testing.T) {
	chCfg := config.AlertChannelConfig{
		Type:  "pagerduty",
		Token: "test-token",
	}

	n := CreateNotifierFromConfig(chCfg)
	assert.NotNil(t, n)
	assert.Equal(t, "pagerduty", n.Name())
}

func TestCreateNotifierFromConfig_Unknown(t *testing.T) {
	chCfg := config.AlertChannelConfig{
		Type: "unknown",
	}

	n := CreateNotifierFromConfig(chCfg)
	assert.Nil(t, n)
}

func TestNotifierAdapter(t *testing.T) {
	mock := &mockNotifier{name: "test"}
	adapter := NewNotifierAdapter(mock)

	alert := &models.AlertEvent{ID: "1", Title: "test"}
	err := adapter.Send(alert)

	assert.NoError(t, err)
	assert.Len(t, mock.sent, 1)
	assert.Equal(t, "1", mock.sent[0].ID)
}

func TestNotifierAdapter_SendWithRetry(t *testing.T) {
	mock := &mockNotifier{name: "test", sendErr: errors.New("HTTP 500")}
	adapter := NewNotifierAdapter(mock)

	alert := &models.AlertEvent{ID: "1"}
	err := adapter.SendWithRetry(alert, 2)

	assert.Error(t, err)
}

func TestNotifierAdapter_SendWithRetry_Success(t *testing.T) {
	mock := &mockNotifier{name: "test"}
	adapter := NewNotifierAdapter(mock)

	alert := &models.AlertEvent{ID: "1"}
	err := adapter.SendWithRetry(alert, 3)

	assert.NoError(t, err)
	assert.Len(t, mock.sent, 1)
}

func TestAlertManager_RegisterNotifierFromConfig(t *testing.T) {
	dedup := newMockDedupStore()
	cfg := &config.AlertManagerConfig{
		Channels: []config.AlertChannelConfig{
			{Type: "dingtalk", Webhook: "https://oapi.dingtalk.com/robot/send?access_token=test", Secret: "secret"},
			{Type: "feishu", Webhook: "https://open.feishu.cn/open-apis/bot/v2/hook/test"},
		},
		SilentPeriod: time.Minute,
	}

	am := NewAlertManagerWithDedup(cfg, dedup)

	_, hasDingTalk := am.channels["dingtalk"]
	_, hasFeishu := am.channels["feishu"]

	assert.True(t, hasDingTalk, "should have dingtalk channel")
	assert.True(t, hasFeishu, "should have feishu channel")
}

func TestAlertManager_RegisterNotifierFromConfig_Unknown(t *testing.T) {
	dedup := newMockDedupStore()
	cfg := &config.AlertManagerConfig{
		Channels: []config.AlertChannelConfig{
			{Type: "unknown_channel"},
		},
		SilentPeriod: time.Minute,
	}

	am := NewAlertManagerWithDedup(cfg, dedup)
	assert.Len(t, am.channels, 0, "unknown channels should not be registered")
}
