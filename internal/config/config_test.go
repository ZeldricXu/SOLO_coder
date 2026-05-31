package config

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/solocoder/tasktracker/internal/testfixtures"
)

func TestNewManager(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name      string
		namespace string
	}{
		{name: "with namespace", namespace: "production"},
		{name: "with empty namespace", namespace: ""},
	}

	for _, tt := range tests {
		tt := tt
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			m := NewManager(tt.namespace)
			assert.NotNil(t, m)
			assert.Equal(t, tt.namespace, m.namespace)
			assert.NotNil(t, m.configs)
			assert.NotNil(t, m.defaults)
			assert.NotNil(t, m.validators)
		})
	}
}

func TestManager_SetAndGet(t *testing.T) {
	t.Parallel()

	m := NewManager("test")

	t.Run("set valid config", func(t *testing.T) {
		cfg := testfixtures.NewConfigBuilder().Build()
		err := m.Set(cfg)
		require.NoError(t, err)
		assert.Equal(t, 1, cfg.Version)
	})

	t.Run("get existing config", func(t *testing.T) {
		cfg, err := m.Get("cfg_test_001")
		require.NoError(t, err)
		assert.Equal(t, "cfg_test_001", cfg.ConfigID)
		assert.Equal(t, "test", cfg.Namespace)
	})

	t.Run("get non-existing config", func(t *testing.T) {
		cfg, err := m.Get("non_existent")
		assert.Nil(t, cfg)
		assert.ErrorIs(t, err, ErrConfigNotFound)
	})

	t.Run("update config increments version", func(t *testing.T) {
		cfg := testfixtures.NewConfigBuilder().WithParams(map[string]interface{}{"timeout": 60}).Build()
		err := m.Set(cfg)
		require.NoError(t, err)
		assert.Equal(t, 2, cfg.Version)

		loaded, err := m.Get("cfg_test_001")
		require.NoError(t, err)
		assert.Equal(t, 60, loaded.Params["timeout"])
	})
}

func TestManager_ParameterValidation(t *testing.T) {
	t.Parallel()

	t.Run("config_id is required", func(t *testing.T) {
		m := NewManager("test")
		cfg := testfixtures.NewConfigBuilder().WithEmptyConfigID().Build()
		err := m.Set(cfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrValidation)
		assert.Contains(t, err.Error(), "config_id is required")
	})

	t.Run("custom range validator", func(t *testing.T) {
		m := NewManager("test")
		m.RegisterValidator("retries", ValidateRange(1, 10))

		validCfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_valid_retries").
			WithParams(map[string]interface{}{"retries": 5}).
			Build()
		err := m.Set(validCfg)
		assert.NoError(t, err)

		invalidCfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_invalid_retries").
			WithParams(map[string]interface{}{"retries": 15}).
			Build()
		err = m.Set(invalidCfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrValidation)
		assert.Contains(t, err.Error(), "out of range")
	})

	t.Run("required validator", func(t *testing.T) {
		m := NewManager("test")
		m.RegisterValidator("api_key", ValidateRequired())

		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_required").
			WithParams(map[string]interface{}{"api_key": ""}).
			Build()
		err := m.Set(cfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrValidation)
		assert.Contains(t, err.Error(), "value is required")
	})

	t.Run("enum validator", func(t *testing.T) {
		m := NewManager("test")
		m.RegisterValidator("environment", ValidateEnum("dev", "staging", "prod"))

		validCfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_valid_env").
			WithParams(map[string]interface{}{"environment": "prod"}).
			Build()
		err := m.Set(validCfg)
		assert.NoError(t, err)

		invalidCfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_invalid_env").
			WithParams(map[string]interface{}{"environment": "production"}).
			Build()
		err = m.Set(invalidCfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrValidation)
		assert.Contains(t, err.Error(), "not in allowed set")
	})

	t.Run("min length validator", func(t *testing.T) {
		m := NewManager("test")
		m.RegisterValidator("password", ValidateMinLength(8))

		validCfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_valid_pwd").
			WithParams(map[string]interface{}{"password": "secure123"}).
			Build()
		err := m.Set(validCfg)
		assert.NoError(t, err)

		invalidCfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_invalid_pwd").
			WithParams(map[string]interface{}{"password": "short"}).
			Build()
		err = m.Set(invalidCfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrValidation)
		assert.Contains(t, err.Error(), "less than minimum")
	})
}

func TestManager_DefaultValues(t *testing.T) {
	t.Parallel()

	m := NewManager("test")
	m.SetDefault("timeout", 30)
	m.SetDefault("retries", 3)
	m.SetDefault("debug", false)

	t.Run("get default when config not found", func(t *testing.T) {
		val, err := m.GetInt("non_existent", "timeout")
		assert.NoError(t, err)
		assert.Equal(t, 30, val)
	})

	t.Run("get default when param not found in config", func(t *testing.T) {
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_partial").
			WithParams(map[string]interface{}{"retries": 5}).
			Build()
		err := m.Set(cfg)
		require.NoError(t, err)

		timeout, err := m.GetInt("cfg_partial", "timeout")
		assert.NoError(t, err)
		assert.Equal(t, 30, timeout)

		retries, err := m.GetInt("cfg_partial", "retries")
		assert.NoError(t, err)
		assert.Equal(t, 5, retries)
	})

	t.Run("apply defaults to config", func(t *testing.T) {
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_apply_defaults").
			WithEmptyParams().
			Build()

		result := m.ApplyDefaults(cfg)
		assert.Equal(t, 30, result.Params["timeout"])
		assert.Equal(t, 3, result.Params["retries"])
		assert.Equal(t, false, result.Params["debug"])
	})

	t.Run("apply defaults does not override existing values", func(t *testing.T) {
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_no_override").
			WithParams(map[string]interface{}{"timeout": 60}).
			Build()

		result := m.ApplyDefaults(cfg)
		assert.Equal(t, 60, result.Params["timeout"])
		assert.Equal(t, 3, result.Params["retries"])
	})
}

func TestManager_TypeSafety(t *testing.T) {
	t.Parallel()

	m := NewManager("test")
	cfg := testfixtures.NewConfigBuilder().
		WithConfigID("cfg_types").
		WithParams(map[string]interface{}{
			"str":    "hello",
			"int":    42,
			"float":  3.14,
			"bool":   true,
			"dur":    "5s",
			"int64":  int64(100),
		}).
		Build()
	err := m.Set(cfg)
	require.NoError(t, err)

	t.Run("GetString success", func(t *testing.T) {
		val, err := m.GetString("cfg_types", "str")
		assert.NoError(t, err)
		assert.Equal(t, "hello", val)
	})

	t.Run("GetString wrong type", func(t *testing.T) {
		val, err := m.GetString("cfg_types", "int")
		assert.Error(t, err)
		assert.ErrorIs(t, err, ErrInvalidType)
		assert.Empty(t, val)
	})

	t.Run("GetInt success", func(t *testing.T) {
		val, err := m.GetInt("cfg_types", "int")
		assert.NoError(t, err)
		assert.Equal(t, 42, val)
	})

	t.Run("GetInt from float64", func(t *testing.T) {
		cfgFloat := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_float_int").
			WithParams(map[string]interface{}{"count": float64(10)}).
			Build()
		err := m.Set(cfgFloat)
		require.NoError(t, err)

		val, err := m.GetInt("cfg_float_int", "count")
		assert.NoError(t, err)
		assert.Equal(t, 10, val)
	})

	t.Run("GetInt wrong type", func(t *testing.T) {
		val, err := m.GetInt("cfg_types", "str")
		assert.Error(t, err)
		assert.ErrorIs(t, err, ErrInvalidType)
		assert.Equal(t, 0, val)
	})

	t.Run("GetBool success", func(t *testing.T) {
		val, err := m.GetBool("cfg_types", "bool")
		assert.NoError(t, err)
		assert.True(t, val)
	})

	t.Run("GetBool wrong type", func(t *testing.T) {
		val, err := m.GetBool("cfg_types", "str")
		assert.Error(t, err)
		assert.ErrorIs(t, err, ErrInvalidType)
		assert.False(t, val)
	})

	t.Run("GetFloat64 success", func(t *testing.T) {
		val, err := m.GetFloat64("cfg_types", "float")
		assert.NoError(t, err)
		assert.Equal(t, 3.14, val)
	})

	t.Run("GetFloat64 from int", func(t *testing.T) {
		val, err := m.GetFloat64("cfg_types", "int")
		assert.NoError(t, err)
		assert.Equal(t, 42.0, val)
	})

	t.Run("GetDuration success", func(t *testing.T) {
		val, err := m.GetDuration("cfg_types", "dur")
		assert.NoError(t, err)
		assert.Equal(t, 5*time.Second, val)
	})

	t.Run("GetDuration invalid format", func(t *testing.T) {
		cfgBadDur := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_bad_dur").
			WithParams(map[string]interface{}{"ttl": "invalid"}).
			Build()
		err := m.Set(cfgBadDur)
		require.NoError(t, err)

		val, err := m.GetDuration("cfg_bad_dur", "ttl")
		assert.Error(t, err)
		assert.Equal(t, time.Duration(0), val)
	})
}

func TestManager_BoundaryConditions(t *testing.T) {
	t.Parallel()

	t.Run("empty namespace gets default", func(t *testing.T) {
		m := NewManager("default_ns")
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_empty_ns").
			WithNamespace("").
			Build()
		err := m.Set(cfg)
		require.NoError(t, err)
		assert.Equal(t, "default_ns", cfg.Namespace)
	})

	t.Run("zero applied_at gets set", func(t *testing.T) {
		m := NewManager("test")
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_zero_time").
			Build()
		cfg.AppliedAt = time.Time{}

		err := m.Set(cfg)
		require.NoError(t, err)
		assert.False(t, cfg.AppliedAt.IsZero())
	})

	t.Run("list returns all configs", func(t *testing.T) {
		m := NewManager("test")
		for i := 0; i < 5; i++ {
			cfg := testfixtures.NewConfigBuilder().
				WithConfigID(string(rune('a' + i))).
				Build()
			err := m.Set(cfg)
			require.NoError(t, err)
		}
		assert.Len(t, m.List(), 5)
	})

	t.Run("delete removes config", func(t *testing.T) {
		m := NewManager("test")
		cfg := testfixtures.NewConfigBuilder().WithConfigID("to_delete").Build()
		err := m.Set(cfg)
		require.NoError(t, err)

		m.Delete("to_delete")
		_, err = m.Get("to_delete")
		assert.ErrorIs(t, err, ErrConfigNotFound)
	})

	t.Run("delete non-existent config is safe", func(t *testing.T) {
		m := NewManager("test")
		assert.NotPanics(t, func() {
			m.Delete("non_existent")
		})
	})
}

func TestManager_LoadFromFile(t *testing.T) {
	t.Parallel()

	t.Run("load valid yaml file", func(t *testing.T) {
		tmpFile, err := os.CreateTemp("", "config-*.yaml")
		require.NoError(t, err)
		defer os.Remove(tmpFile.Name())

		content := `
config_id: file_cfg
namespace: file_test
parameters:
  timeout: 30
  host: localhost
enabled: true
`
		_, err = tmpFile.WriteString(content)
		require.NoError(t, err)
		tmpFile.Close()

		m := NewManager("default")
		err = m.LoadFromFile(tmpFile.Name())
		require.NoError(t, err)

		cfg, err := m.Get("file_cfg")
		require.NoError(t, err)
		assert.Equal(t, "file_test", cfg.Namespace)
		assert.Equal(t, 30, cfg.Params["timeout"])
		assert.True(t, cfg.Enabled)
	})

	t.Run("load non-existent file", func(t *testing.T) {
		m := NewManager("test")
		err := m.LoadFromFile("/non/existent/path.yaml")
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "failed to read config file")
	})

	t.Run("load invalid yaml", func(t *testing.T) {
		tmpFile, err := os.CreateTemp("", "bad-*.yaml")
		require.NoError(t, err)
		defer os.Remove(tmpFile.Name())

		_, err = tmpFile.WriteString("invalid: yaml: content: [")
		require.NoError(t, err)
		tmpFile.Close()

		m := NewManager("test")
		err = m.LoadFromFile(tmpFile.Name())
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "failed to parse config file")
	})
}

func TestManager_ValidationTransactionRollback(t *testing.T) {
	t.Parallel()

	t.Run("validation failure does not save partial state", func(t *testing.T) {
		m := NewManager("test")
		m.RegisterValidator("count", ValidateRange(1, 10))

		validCfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_tx").
			WithParams(map[string]interface{}{"count": 5}).
			Build()
		err := m.Set(validCfg)
		require.NoError(t, err)

		invalidCfg := testfixtures.NewConfigBuilder().
			WithConfigID("cfg_tx").
			WithParams(map[string]interface{}{"count": 100}).
			Build()
		err = m.Set(invalidCfg)
		require.Error(t, err)

		loaded, err := m.Get("cfg_tx")
		require.NoError(t, err)
		assert.Equal(t, 5, loaded.Params["count"])
	})
}

func TestValidatorEdgeCases(t *testing.T) {
	t.Parallel()

	t.Run("ValidateRange with wrong type", func(t *testing.T) {
		v := ValidateRange(1, 10)
		err := v("not an int")
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "expected int")
	})

	t.Run("ValidateRequired with nil", func(t *testing.T) {
		v := ValidateRequired()
		err := v(nil)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "value is required")
	})

	t.Run("ValidateRequired with empty string", func(t *testing.T) {
		v := ValidateRequired()
		err := v("")
		assert.Error(t, err)
	})

	t.Run("ValidateRequired with non-empty string", func(t *testing.T) {
		v := ValidateRequired()
		err := v("value")
		assert.NoError(t, err)
	})

	t.Run("ValidateEnum with wrong type", func(t *testing.T) {
		v := ValidateEnum("a", "b")
		err := v(123)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "expected string")
	})

	t.Run("ValidateMinLength with wrong type", func(t *testing.T) {
		v := ValidateMinLength(5)
		err := v(123)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "expected string")
	})

	t.Run("ValidateRange boundary values", func(t *testing.T) {
		v := ValidateRange(1, 10)
		assert.NoError(t, v(1))
		assert.NoError(t, v(10))
		assert.Error(t, v(0))
		assert.Error(t, v(11))
	})
}

func TestManager_ConcurrentAccess(t *testing.T) {
	t.Parallel()

	m := NewManager("concurrent")
	done := make(chan bool)

	go func() {
		for i := 0; i < 100; i++ {
			cfg := testfixtures.NewConfigBuilder().
				WithConfigID(string(rune('a' + i%26))).
				WithParams(map[string]interface{}{"index": i}).
				Build()
			_ = m.Set(cfg)
		}
		done <- true
	}()

	go func() {
		for i := 0; i < 100; i++ {
			_, _ = m.Get(string(rune('a' + i%26)))
		}
		done <- true
	}()

	go func() {
		for i := 0; i < 50; i++ {
			_ = m.List()
		}
		done <- true
	}()

	<-done
	<-done
	<-done
}

func TestManager_ErrorUnwrapping(t *testing.T) {
	t.Parallel()

	m := NewManager("test")
	_, err := m.Get("not_found")

	assert.True(t, errors.Is(err, ErrConfigNotFound))
	assert.False(t, errors.Is(err, ErrValidation))
	assert.False(t, errors.Is(err, ErrInvalidType))
}

func TestManager_BoundaryValidation(t *testing.T) {
	t.Parallel()

	t.Run("nil config returns error", func(t *testing.T) {
		m := NewManager("test")
		err := m.Set(nil)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrValidation)
		assert.Contains(t, err.Error(), "cannot be nil")
	})

	t.Run("config_id too long", func(t *testing.T) {
		m := NewManager("test")
		longID := strings.Repeat("a", MaxConfigIDLength+1)
		cfg := testfixtures.NewConfigBuilder().WithConfigID(longID).Build()
		err := m.Set(cfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrLimitExceeded)
		assert.Contains(t, err.Error(), "exceeds maximum length")
	})

	t.Run("config_id at max length is valid", func(t *testing.T) {
		m := NewManager("test")
		maxID := strings.Repeat("a", MaxConfigIDLength)
		cfg := testfixtures.NewConfigBuilder().WithConfigID(maxID).Build()
		err := m.Set(cfg)
		assert.NoError(t, err)
	})

	t.Run("parameter key too long", func(t *testing.T) {
		m := NewManager("test")
		longKey := strings.Repeat("k", MaxKeyLength+1)
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("key_len_test").
			WithParams(map[string]interface{}{longKey: "value"}).
			Build()
		err := m.Set(cfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrLimitExceeded)
	})

	t.Run("string value too long", func(t *testing.T) {
		m := NewManager("test")
		longValue := strings.Repeat("v", MaxStringLength+1)
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("str_len_test").
			WithParams(map[string]interface{}{"key": longValue}).
			Build()
		err := m.Set(cfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrLimitExceeded)
	})

	t.Run("too many parameters", func(t *testing.T) {
		m := NewManager("test")
		params := make(map[string]interface{})
		for i := 0; i < MaxParamsCount+1; i++ {
			params[fmt.Sprintf("key_%d", i)] = i
		}
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("many_params").
			WithParams(params).
			Build()
		err := m.Set(cfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrLimitExceeded)
	})

	t.Run("nested map too large", func(t *testing.T) {
		m := NewManager("test")
		nested := make(map[string]interface{})
		for i := 0; i < MaxParamsCount+1; i++ {
			nested[fmt.Sprintf("nested_%d", i)] = i
		}
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("nested_map").
			WithParams(map[string]interface{}{"nested": nested}).
			Build()
		err := m.Set(cfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrLimitExceeded)
	})

	t.Run("nil params is valid", func(t *testing.T) {
		m := NewManager("test")
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("nil_params").
			WithEmptyParams().
			Build()
		err := m.Set(cfg)
		assert.NoError(t, err)
		assert.NotNil(t, cfg.Params)
	})
}

func TestManager_GetIntWithFractionalFloat(t *testing.T) {
	t.Parallel()

	m := NewManager("test")
	cfg := testfixtures.NewConfigBuilder().
		WithConfigID("float_test").
		WithParams(map[string]interface{}{
			"integer_float": float64(42),
			"fractional":    float64(42.5),
		}).
		Build()
	err := m.Set(cfg)
	require.NoError(t, err)

	t.Run("integer float converts to int", func(t *testing.T) {
		val, err := m.GetInt("float_test", "integer_float")
		assert.NoError(t, err)
		assert.Equal(t, 42, val)
	})

	t.Run("fractional float returns error", func(t *testing.T) {
		_, err := m.GetInt("float_test", "fractional")
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrInvalidType)
		assert.Contains(t, err.Error(), "fractional part")
	})
}

func TestManager_DeepNestedValidation(t *testing.T) {
	t.Parallel()

	m := NewManager("test")

	t.Run("deeply nested valid structure", func(t *testing.T) {
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("deep_nested").
			WithParams(map[string]interface{}{
				"level1": map[string]interface{}{
					"level2": map[string]interface{}{
						"level3": "value",
					},
				},
			}).
			Build()
		err := m.Set(cfg)
		assert.NoError(t, err)
	})

	t.Run("deeply nested with long key", func(t *testing.T) {
		longKey := strings.Repeat("x", MaxKeyLength+1)
		cfg := testfixtures.NewConfigBuilder().
			WithConfigID("deep_long_key").
			WithParams(map[string]interface{}{
				"level1": map[string]interface{}{
					longKey: "value",
				},
			}).
			Build()
		err := m.Set(cfg)
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrLimitExceeded)
	})
}
