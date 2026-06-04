package script

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"time"

	"github.com/oliveagle/jsonpath"
	googlegrpc "google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	"github.com/htest/htest/internal/engine/gql"
	"github.com/htest/htest/internal/engine/grpc"
	"github.com/htest/htest/internal/engine/rest"
	"github.com/htest/htest/internal/engine/ws"
)

type StepHandler interface {
	CanHandle(step Step) bool
	Handle(ctx context.Context, step Step, pc *PipelineContext) (*StepResult, error)
}

type RESTStepHandler struct{}

func (h *RESTStepHandler) CanHandle(step Step) bool {
	return step.Protocol == "rest"
}

func (h *RESTStepHandler) Handle(ctx context.Context, step Step, pc *PipelineContext) (*StepResult, error) {
	start := time.Now()

	timeout := step.Request.Timeout
	if timeout == 0 {
		timeout = 30
	}
	client := rest.NewClient(pc.BaseURL(), pc.AuthHeaders(), timeout)

	req := &rest.Request{
		Method:  step.Request.Method,
		URL:     pc.Resolve(step.Request.URL),
		Headers: step.Request.Headers,
		Body:    pc.Resolve(step.Request.Body),
		Timeout: timeout,
	}
	resp, err := client.Do(req)
	duration := time.Since(start)

	if err != nil {
		return &StepResult{
			StepName: step.Name,
			Status:   "error",
			Duration: duration,
			Error:    err.Error(),
		}, nil
	}

	return &StepResult{
		StepName:  step.Name,
		Status:    "pass",
		Response:  resp,
		Duration:  duration,
		Extracted: make(map[string]string),
	}, nil
}

type GRPCStepHandler struct{}

func (h *GRPCStepHandler) CanHandle(step Step) bool {
	return step.Protocol == "grpc"
}

func (h *GRPCStepHandler) Handle(ctx context.Context, step Step, pc *PipelineContext) (*StepResult, error) {
	start := time.Now()

	client, err := grpc.NewClient(pc.Resolve(step.Request.URL), googlegrpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return &StepResult{
			StepName: step.Name,
			Status:   "error",
			Duration: time.Since(start),
			Error:    err.Error(),
		}, nil
	}
	defer client.Close()

	resp, err := client.Invoke(ctx, step.Request.Service, step.Request.GrpcMethod, pc.Resolve(step.Request.Body))
	duration := time.Since(start)

	if err != nil {
		return &StepResult{
			StepName: step.Name,
			Status:   "error",
			Duration: duration,
			Error:    err.Error(),
		}, nil
	}

	return &StepResult{
		StepName:  step.Name,
		Status:    "pass",
		Response:  resp,
		Duration:  duration,
		Extracted: make(map[string]string),
	}, nil
}

type GQLStepHandler struct{}

func (h *GQLStepHandler) CanHandle(step Step) bool {
	return step.Protocol == "gql"
}

func (h *GQLStepHandler) Handle(ctx context.Context, step Step, pc *PipelineContext) (*StepResult, error) {
	start := time.Now()

	mergedHeaders := pc.AuthHeaders()
	for k, v := range step.Request.Headers {
		mergedHeaders[k] = v
	}
	timeout := step.Request.Timeout
	if timeout == 0 {
		timeout = 30
	}

	client := gql.NewClient(pc.Resolve(step.Request.URL), mergedHeaders, timeout)
	resp, err := client.Query(ctx, pc.Resolve(step.Request.Query), nil)
	duration := time.Since(start)

	if err != nil {
		return &StepResult{
			StepName: step.Name,
			Status:   "error",
			Duration: duration,
			Error:    err.Error(),
		}, nil
	}

	return &StepResult{
		StepName:  step.Name,
		Status:    "pass",
		Response:  resp,
		Duration:  duration,
		Extracted: make(map[string]string),
	}, nil
}

type WSStepHandler struct{}

func (h *WSStepHandler) CanHandle(step Step) bool {
	return step.Protocol == "ws"
}

func (h *WSStepHandler) Handle(ctx context.Context, step Step, pc *PipelineContext) (*StepResult, error) {
	start := time.Now()

	client := ws.NewClient(pc.Resolve(step.Request.URL), step.Request.Headers)
	connErr := client.Connect(ctx)
	if connErr != nil {
		return &StepResult{
			StepName: step.Name,
			Status:   "error",
			Duration: time.Since(start),
			Error:    connErr.Error(),
		}, nil
	}
	defer client.Close()

	if step.Request.Message != "" {
		if err := client.Send(pc.Resolve(step.Request.Message)); err != nil {
			return &StepResult{
				StepName: step.Name,
				Status:   "error",
				Duration: time.Since(start),
				Error:    err.Error(),
			}, nil
		}
	}

	msgCh, err := client.Receive()
	if err != nil {
		return &StepResult{
			StepName: step.Name,
			Status:   "error",
			Duration: time.Since(start),
			Error:    err.Error(),
		}, nil
	}

	var messages []ws.Message
	timeout := time.After(5 * time.Second)
	for len(messages) < 10 {
		select {
		case msg, ok := <-msgCh:
			if !ok {
				goto done
			}
			messages = append(messages, msg)
		case <-timeout:
			goto done
		}
	}
done:

	duration := time.Since(start)
	return &StepResult{
		StepName:  step.Name,
		Status:    "pass",
		Response:  messages,
		Duration:  duration,
		Extracted: make(map[string]string),
	}, nil
}

type DelayStepHandler struct{}

func (h *DelayStepHandler) CanHandle(step Step) bool {
	return step.Protocol == "" && step.Delay != ""
}

func (h *DelayStepHandler) Handle(ctx context.Context, step Step, pc *PipelineContext) (*StepResult, error) {
	if step.Delay != "" {
		d, err := time.ParseDuration(step.Delay)
		if err == nil {
			time.Sleep(d)
		}
	}
	return &StepResult{
		StepName:  step.Name,
		Status:    "pass",
		Duration:  0,
		Extracted: make(map[string]string),
	}, nil
}

type HandlerChain struct {
	handlers []StepHandler
}

func NewHandlerChain(handlers ...StepHandler) *HandlerChain {
	return &HandlerChain{handlers: handlers}
}

func (hc *HandlerChain) AddHandler(handler StepHandler) {
	hc.handlers = append(hc.handlers, handler)
}

func (hc *HandlerChain) CanHandleStep(step Step) bool {
	for _, handler := range hc.handlers {
		if handler.CanHandle(step) {
			return true
		}
	}
	return false
}

func (hc *HandlerChain) Execute(ctx context.Context, step Step, pc *PipelineContext) (*StepResult, error) {
	for _, handler := range hc.handlers {
		if handler.CanHandle(step) {
			return handler.Handle(ctx, step, pc)
		}
	}
	return nil, fmt.Errorf("unsupported protocol: %s", step.Protocol)
}

func ExtractValues(result *StepResult, extracts map[string]ExtractDef) {
	if result.Extracted == nil {
		result.Extracted = make(map[string]string)
	}

	for name, ext := range extracts {
		var value string

		switch ext.From {
		case "header":
			if resp, ok := result.Response.(*rest.Response); ok {
				if vals, ok := resp.Headers[ext.Header]; ok && len(vals) > 0 {
					value = vals[0]
				}
			}
		case "body", "json":
			if resp, ok := result.Response.(*rest.Response); ok {
				if ext.JSONPath != "" {
					var bodyData interface{}
					if err := json.Unmarshal([]byte(resp.Body), &bodyData); err == nil {
						path, err := jsonpath.Compile(ext.JSONPath)
						if err == nil {
							v, err := path.Lookup(bodyData)
							if err == nil {
								value = fmt.Sprintf("%v", v)
							}
						}
					}
				}
				if ext.Regex != "" {
					re, err := regexp.Compile(ext.Regex)
					if err == nil {
						matches := re.FindStringSubmatch(resp.Body)
						if len(matches) > 1 {
							value = matches[1]
						} else if len(matches) == 1 {
							value = matches[0]
						}
					}
				}
			}
		case "status":
			if resp, ok := result.Response.(*rest.Response); ok {
				value = fmt.Sprintf("%d", resp.StatusCode)
			}
		}

		if value != "" {
			result.Extracted[name] = value
		}
	}
}

func RunAssertions(result *StepResult, asserts []AssertDef, resp interface{}) {
	for _, a := range asserts {
		var actual interface{}

		switch a.Type {
		case "status":
			if r, ok := resp.(*rest.Response); ok {
				actual = r.StatusCode
			}
		case "headers":
			if r, ok := resp.(*rest.Response); ok {
				actual = r.Headers
			}
		case "body", "json":
			if r, ok := resp.(*rest.Response); ok {
				if a.JSONPath != "" {
					var bodyData interface{}
					if err := json.Unmarshal([]byte(r.Body), &bodyData); err == nil {
						path, err := jsonpath.Compile(a.JSONPath)
						if err == nil {
							v, err := path.Lookup(bodyData)
							if err == nil {
								actual = v
							}
						}
					}
				} else {
					actual = r.Body
				}
			}
		case "latency":
			actual = result.Duration.Milliseconds()
		}

		ar := EvaluateAssert(a, actual)
		result.Assertions = append(result.Assertions, ar)
	}
}
