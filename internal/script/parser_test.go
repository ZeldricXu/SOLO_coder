package script

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestParseBytes_ValidScript(t *testing.T) {
	data := []byte(`
name: Valid Script
description: A valid test script
steps:
  - name: Step One
    protocol: rest
    request:
      method: GET
      url: http://localhost/health
    assert:
      - type: status
        expected: 200
`)

	script, err := ParseBytes(data)
	require.NoError(t, err)
	require.NotNil(t, script)

	assert.Equal(t, "Valid Script", script.Name)
	assert.Equal(t, "A valid test script", script.Description)
	assert.Len(t, script.Steps, 1)
	assert.Equal(t, "Step One", script.Steps[0].Name)
	assert.Equal(t, "rest", script.Steps[0].Protocol)
	assert.Equal(t, "GET", script.Steps[0].Request.Method)
	assert.Equal(t, "http://localhost/health", script.Steps[0].Request.URL)
	assert.Len(t, script.Steps[0].Assert, 1)
	assert.Equal(t, "status", script.Steps[0].Assert[0].Type)
}

func TestParseBytes_InvalidYAML(t *testing.T) {
	data := []byte(`
name: [invalid
  yaml: content
`)

	_, err := ParseBytes(data)
	assert.Error(t, err)
}

func TestParseBytes_EmptyScript(t *testing.T) {
	data := []byte(`{}`)

	script, err := ParseBytes(data)
	require.NoError(t, err)
	require.NotNil(t, script)

	assert.Empty(t, script.Name)
	assert.Empty(t, script.Steps)
}

func TestParseFile_ValidFile(t *testing.T) {
	script, err := ParseFile("testdata/single_step.htest")
	require.NoError(t, err)
	require.NotNil(t, script)

	assert.Equal(t, "Single Step Test", script.Name)
	assert.Len(t, script.Steps, 1)
	assert.Equal(t, "Health Check", script.Steps[0].Name)
	assert.Equal(t, "rest", script.Steps[0].Protocol)
	assert.Equal(t, "GET", script.Steps[0].Request.Method)
}

func TestParseFile_NotFound(t *testing.T) {
	_, err := ParseFile("testdata/nonexistent.htest")
	assert.Error(t, err)
}

func TestParseFile_TwoStepChain(t *testing.T) {
	script, err := ParseFile("testdata/two_step_chain.htest")
	require.NoError(t, err)
	require.NotNil(t, script)

	assert.Equal(t, "Two Step Chain Test", script.Name)
	assert.Len(t, script.Steps, 2)

	firstStep := script.Steps[0]
	assert.Equal(t, "Create Resource", firstStep.Name)
	assert.Equal(t, "POST", firstStep.Request.Method)
	require.NotNil(t, firstStep.Extract)
	_, ok := firstStep.Extract["resource_id"]
	assert.True(t, ok)

	secondStep := script.Steps[1]
	assert.Equal(t, "Get Resource", secondStep.Name)
	assert.Contains(t, secondStep.Request.URL, "${resource_id}")
}

func TestParseFile_LoopTest(t *testing.T) {
	script, err := ParseFile("testdata/loop_test.htest")
	require.NoError(t, err)
	require.NotNil(t, script)

	assert.Equal(t, "Loop Test", script.Name)
	require.Len(t, script.Steps, 1)
	assert.Equal(t, 3, script.Steps[0].Loop.Count)
	assert.Equal(t, "10ms", script.Steps[0].Loop.Interval)
}

func TestValidate_ValidScript(t *testing.T) {
	script := &TestScript{
		Name: "Valid",
		Steps: []Step{
			{
				Name:     "Step1",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "http://localhost/health",
				},
			},
		},
	}

	err := Validate(script)
	assert.NoError(t, err)
}

func TestValidate_NoName(t *testing.T) {
	script := &TestScript{
		Steps: []Step{
			{
				Name:     "Step1",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "http://localhost/health",
				},
			},
		},
	}

	err := Validate(script)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "script name is required")
}

func TestValidate_NoSteps(t *testing.T) {
	script := &TestScript{
		Name:  "No Steps",
		Steps: []Step{},
	}

	err := Validate(script)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "at least one step")
}

func TestValidate_NoProtocol(t *testing.T) {
	script := &TestScript{
		Name: "No Protocol",
		Steps: []Step{
			{
				Name: "Step1",
				Request: RequestDef{
					URL: "http://localhost/health",
				},
			},
		},
	}

	err := Validate(script)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "protocol is required")
}

func TestValidate_UnsupportedProtocol(t *testing.T) {
	script := &TestScript{
		Name: "Bad Protocol",
		Steps: []Step{
			{
				Name:     "Step1",
				Protocol: "ftp",
				Request: RequestDef{
					URL: "ftp://localhost/file",
				},
			},
		},
	}

	err := Validate(script)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "unsupported protocol")
}

func TestValidate_NoMethod(t *testing.T) {
	script := &TestScript{
		Name: "No Method",
		Steps: []Step{
			{
				Name:     "Step1",
				Protocol: "rest",
				Request: RequestDef{
					URL: "http://localhost/health",
				},
			},
		},
	}

	err := Validate(script)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "request method is required")
}

func TestValidate_NoURL(t *testing.T) {
	script := &TestScript{
		Name: "No URL",
		Steps: []Step{
			{
				Name:     "Step1",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
				},
			},
		},
	}

	err := Validate(script)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "URL is required")
}

func TestValidate_DelayOnlyStep(t *testing.T) {
	script := &TestScript{
		Name: "Delay Only",
		Steps: []Step{
			{
				Name:  "Wait",
				Delay: "500ms",
			},
		},
	}

	err := Validate(script)
	assert.NoError(t, err)
}

func TestValidate_UnsupportedAssertType(t *testing.T) {
	script := &TestScript{
		Name: "Bad Assert Type",
		Steps: []Step{
			{
				Name:     "Step1",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "http://localhost/health",
				},
				Assert: []AssertDef{
					{
						Type:     "invalid",
						Expected: 200,
					},
				},
			},
		},
	}

	err := Validate(script)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "unsupported type")
}

func TestValidate_UnsupportedOperator(t *testing.T) {
	script := &TestScript{
		Name: "Bad Operator",
		Steps: []Step{
			{
				Name:     "Step1",
				Protocol: "rest",
				Request: RequestDef{
					Method: "GET",
					URL:    "http://localhost/health",
				},
				Assert: []AssertDef{
					{
						Type:     "status",
						Expected: 200,
						Operator: "invalid",
					},
				},
			},
		},
	}

	err := Validate(script)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "unsupported operator")
}

func TestValidate_AssertTypes(t *testing.T) {
	types := []string{"status", "headers", "body", "json", "latency"}

	for _, assertType := range types {
		t.Run(assertType, func(t *testing.T) {
			script := &TestScript{
				Name: "Assert Type " + assertType,
				Steps: []Step{
					{
						Name:     "Step1",
						Protocol: "rest",
						Request: RequestDef{
							Method: "GET",
							URL:    "http://localhost/health",
						},
						Assert: []AssertDef{
							{
								Type:     assertType,
								Expected: 200,
							},
						},
					},
				},
			}

			err := Validate(script)
			assert.NoError(t, err)
		})
	}
}

func TestValidate_Operators(t *testing.T) {
	operators := []string{"eq", "neq", "contains", "gt", "lt", "gte", "lte"}

	for _, op := range operators {
		t.Run(op, func(t *testing.T) {
			script := &TestScript{
				Name: "Operator " + op,
				Steps: []Step{
					{
						Name:     "Step1",
						Protocol: "rest",
						Request: RequestDef{
							Method: "GET",
							URL:    "http://localhost/health",
						},
						Assert: []AssertDef{
							{
								Type:     "status",
								Expected: 200,
								Operator: op,
							},
						},
					},
				},
			}

			err := Validate(script)
			assert.NoError(t, err)
		})
	}
}
