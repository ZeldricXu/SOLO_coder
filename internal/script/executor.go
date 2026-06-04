package script

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/htest/htest/internal/env"
)

type StepHook func(ctx context.Context, step Step, result *StepResult) (*StepResult, error)

type ExecutorOption func(*Executor)

func WithBeforeStep(hook StepHook) ExecutorOption {
	return func(e *Executor) { e.beforeStep = hook }
}

func WithAfterStep(hook StepHook) ExecutorOption {
	return func(e *Executor) { e.afterStep = hook }
}

func WithDebugMode(debug bool) ExecutorOption {
	return func(e *Executor) { e.debugMode = debug }
}

func WithHandlers(hc *HandlerChain) ExecutorOption {
	return func(e *Executor) { e.handlers = hc }
}

type Executor struct {
	envMgr     *env.Manager
	pipeline   *PipelineContext
	handlers   *HandlerChain
	results    []StepResult
	variables  map[string]string
	beforeStep StepHook
	afterStep  StepHook
	debugMode  bool
}

func NewExecutor(envMgr *env.Manager, opts ...ExecutorOption) *Executor {
	e := &Executor{
		envMgr:   envMgr,
		handlers: DefaultHandlerChain(),
	}
	for _, opt := range opts {
		opt(e)
	}
	return e
}

func DefaultHandlerChain() *HandlerChain {
	return NewHandlerChain(
		&RESTStepHandler{},
		&GRPCStepHandler{},
		&GQLStepHandler{},
		&WSStepHandler{},
		&DelayStepHandler{},
	)
}

func (e *Executor) Execute(ctx context.Context, script *TestScript) (*RunResult, error) {
	start := time.Now()
	e.results = nil
	e.pipeline = NewPipelineContext(e.envMgr)
	e.variables = make(map[string]string)

	for name, vdef := range script.Variables {
		switch {
		case vdef.Value != "":
			e.pipeline.SetVar(name, vdef.Value)
			e.variables[name] = vdef.Value
		case vdef.Env != "":
			val := e.envMgr.GetVar(vdef.Env)
			e.pipeline.SetVar(name, val)
			e.variables[name] = val
		case vdef.Shell != "":
			val := e.envMgr.ResolveShell(vdef.Shell)
			e.pipeline.SetVar(name, val)
			e.variables[name] = val
		}
	}

	allPass := true
	for i, step := range script.Steps {
		e.pipeline.SetStepIndex(i)

		iterations := 1
		interval := time.Duration(0)

		if step.Loop.Count > 0 {
			iterations = step.Loop.Count
		}
		if step.Loop.Interval != "" {
			d, err := time.ParseDuration(step.Loop.Interval)
			if err == nil {
				interval = d
			}
		}

		for j := 0; j < iterations; j++ {
			if step.Loop.While != "" {
				resolved := e.pipeline.Resolve(step.Loop.While)
				if resolved == "false" || resolved == "0" || resolved == "" {
					break
				}
			}

			var result *StepResult

			skipStep := false
			if e.beforeStep != nil {
				modifiedResult, hookErr := e.beforeStep(ctx, step, nil)
				if hookErr != nil {
					result = &StepResult{StepName: step.Name, Status: "error", Error: hookErr.Error()}
					skipStep = true
				} else if modifiedResult != nil {
					result = modifiedResult
					skipStep = true
				}
			}

			if !skipStep {
				var err error
				result, err = e.handlers.Execute(ctx, step, e.pipeline)
				if err != nil {
					result = &StepResult{
						StepName: step.Name,
						Status:   "error",
						Error:    err.Error(),
					}
				}
			}

			if step.Extract != nil {
				ExtractValues(result, step.Extract)
			}

			if step.Assert != nil {
				RunAssertions(result, step.Assert, result.Response)
				if len(result.Assertions) > 0 {
					assertAllPass := true
					for _, ar := range result.Assertions {
						if !ar.Pass {
							assertAllPass = false
							break
						}
					}
					if assertAllPass {
						result.Status = "pass"
					} else {
						result.Status = "fail"
					}
				}
			}

			if e.afterStep != nil {
				modifiedResult, hookErr := e.afterStep(ctx, step, result)
				if hookErr != nil {
					result = &StepResult{StepName: step.Name, Status: "error", Error: hookErr.Error()}
				} else if modifiedResult != nil {
					result = modifiedResult
				}
			}

			if result.Status != "pass" {
				allPass = false
			}

			e.results = append(e.results, *result)
			e.pipeline.AddResult(*result)

			for k, v := range result.Extracted {
				e.pipeline.SetVar(k, v)
				e.variables[k] = v
			}

			if step.Delay != "" && step.Protocol == "" {
				// handled by DelayStepHandler
			} else if step.Delay != "" {
				d, err := time.ParseDuration(step.Delay)
				if err == nil {
					time.Sleep(d)
				}
			}

			if interval > 0 && j < iterations-1 {
				time.Sleep(interval)
			}
		}
	}

	status := "pass"
	if !allPass {
		status = "fail"
	}

	return &RunResult{
		ScriptName:    script.Name,
		Status:        status,
		Steps:         e.results,
		TotalDuration: time.Since(start),
		Variables:     e.variables,
	}, nil
}

func EvaluateAssert(a AssertDef, actual interface{}) AssertResult {
	ar := AssertResult{
		Assert: a,
		Actual: actual,
	}

	op := a.Operator
	if op == "" {
		op = "eq"
	}

	switch op {
	case "eq":
		ar.Pass = fmt.Sprintf("%v", actual) == fmt.Sprintf("%v", a.Expected)
		if !ar.Pass {
			ar.Message = fmt.Sprintf("expected %v, got %v", a.Expected, actual)
		}
	case "neq":
		ar.Pass = fmt.Sprintf("%v", actual) != fmt.Sprintf("%v", a.Expected)
		if !ar.Pass {
			ar.Message = fmt.Sprintf("expected not %v, got %v", a.Expected, actual)
		}
	case "contains":
		ar.Pass = containsValue(actual, a.Expected)
		if !ar.Pass {
			ar.Message = fmt.Sprintf("expected %v to contain %v", actual, a.Expected)
		}
	case "gt":
		ar.Pass = compareNumbers(actual, a.Expected) > 0
		if !ar.Pass {
			ar.Message = fmt.Sprintf("expected %v > %v", actual, a.Expected)
		}
	case "lt":
		ar.Pass = compareNumbers(actual, a.Expected) < 0
		if !ar.Pass {
			ar.Message = fmt.Sprintf("expected %v < %v", actual, a.Expected)
		}
	case "gte":
		ar.Pass = compareNumbers(actual, a.Expected) >= 0
		if !ar.Pass {
			ar.Message = fmt.Sprintf("expected %v >= %v", actual, a.Expected)
		}
	case "lte":
		ar.Pass = compareNumbers(actual, a.Expected) <= 0
		if !ar.Pass {
			ar.Message = fmt.Sprintf("expected %v <= %v", actual, a.Expected)
		}
	default:
		ar.Pass = false
		ar.Message = fmt.Sprintf("unsupported operator: %s", op)
	}

	return ar
}

func containsValue(actual, expected interface{}) bool {
	actualStr := fmt.Sprintf("%v", actual)
	expectedStr := fmt.Sprintf("%v", expected)
	return strings.Contains(actualStr, expectedStr)
}

func compareNumbers(actual, expected interface{}) int {
	aFloat, aErr := toFloat64(actual)
	eFloat, eErr := toFloat64(expected)
	if aErr != nil || eErr != nil {
		aStr := fmt.Sprintf("%v", actual)
		eStr := fmt.Sprintf("%v", expected)
		if aStr > eStr {
			return 1
		}
		if aStr < eStr {
			return -1
		}
		return 0
	}
	if aFloat > eFloat {
		return 1
	}
	if aFloat < eFloat {
		return -1
	}
	return 0
}

func toFloat64(v interface{}) (float64, error) {
	switch val := v.(type) {
	case float64:
		return val, nil
	case float32:
		return float64(val), nil
	case int:
		return float64(val), nil
	case int64:
		return float64(val), nil
	case int32:
		return float64(val), nil
	case string:
		return strconv.ParseFloat(val, 64)
	default:
		return strconv.ParseFloat(fmt.Sprintf("%v", v), 64)
	}
}

func replaceAll(s, old, new string) string {
	result := ""
	for {
		idx := findIndex(s, old)
		if idx == -1 {
			return result + s
		}
		result += s[:idx] + new
		s = s[idx+len(old):]
	}
}

func findIndex(s, substr string) int {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return i
		}
	}
	return -1
}
