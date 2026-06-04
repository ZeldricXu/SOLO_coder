package script

import (
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

func ParseFile(path string) (*TestScript, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read file %s: %w", path, err)
	}
	return ParseBytes(data)
}

func ParseBytes(data []byte) (*TestScript, error) {
	var script TestScript
	if err := yaml.Unmarshal(data, &script); err != nil {
		return nil, fmt.Errorf("failed to parse YAML: %w", err)
	}
	return &script, nil
}

func Validate(script *TestScript) error {
	if script.Name == "" {
		return fmt.Errorf("script name is required")
	}
	if len(script.Steps) == 0 {
		return fmt.Errorf("script must have at least one step")
	}
	for i, step := range script.Steps {
		if step.Name == "" {
			return fmt.Errorf("step %d: name is required", i)
		}
		if step.Delay != "" && step.Protocol == "" && step.Request.URL == "" {
			continue
		}
		if step.Protocol == "" {
			return fmt.Errorf("step %d (%s): protocol is required", i, step.Name)
		}
		switch step.Protocol {
		case "rest", "grpc", "gql", "ws":
		default:
			return fmt.Errorf("step %d (%s): unsupported protocol %q, must be one of rest/grpc/gql/ws", i, step.Name, step.Protocol)
		}
		if step.Protocol == "rest" && step.Request.Method == "" {
			return fmt.Errorf("step %d (%s): request method is required for REST protocol", i, step.Name)
		}
		if step.Request.URL == "" && step.Protocol != "grpc" {
			return fmt.Errorf("step %d (%s): request URL is required", i, step.Name)
		}
		for j, assert := range step.Assert {
			if assert.Type == "" {
				return fmt.Errorf("step %d (%s): assert %d: type is required", i, step.Name, j)
			}
			switch assert.Type {
			case "status", "headers", "body", "json", "latency":
			default:
				return fmt.Errorf("step %d (%s): assert %d: unsupported type %q", i, step.Name, j, assert.Type)
			}
			if assert.Operator != "" {
				switch assert.Operator {
				case "eq", "neq", "contains", "gt", "lt", "gte", "lte":
				default:
					return fmt.Errorf("step %d (%s): assert %d: unsupported operator %q", i, step.Name, j, assert.Operator)
				}
			}
		}
	}
	return nil
}
