package pipeline

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"github.com/go-playground/validator/v10"
	"github.com/solocoder/cloudci/internal/common/errors"
	"github.com/solocoder/cloudci/internal/common/types"
	"gopkg.in/yaml.v3"
)

type Parser struct {
	validator *validator.Validate
}

type ParseResult struct {
	Definition *types.PipelineDefinition
	Warnings   []string
	Errors     []ValidationError
}

type ValidationError struct {
	Line    int    `json:"line,omitempty"`
	Column  int    `json:"column,omitempty"`
	Path    string `json:"path"`
	Message string `json:"message"`
	Value   string `json:"value,omitempty"`
}

func NewParser() *Parser {
	v := validator.New()

	v.RegisterValidation("stage_name", validateStageName)
	v.RegisterValidation("stage_type", validateStageType)
	v.RegisterValidation("cron_expr", validateCronExpression)
	v.RegisterValidation("env_var_name", validateEnvVarName)

	return &Parser{validator: v}
}

func (p *Parser) ParseYAML(data []byte) (*ParseResult, error) {
	var def types.PipelineDefinition
	var rawNode yaml.Node

	if err := yaml.Unmarshal(data, &rawNode); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeValidation, "failed to parse YAML")
	}

	if len(rawNode.Content) == 0 {
		return nil, errors.ValidationError("empty pipeline definition")
	}

	if err := rawNode.Content[0].Decode(&def); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeValidation, "failed to decode YAML")
	}

	return p.validateDefinition(&def, &rawNode)
}

func (p *Parser) ParseJSON(data []byte) (*ParseResult, error) {
	var def types.PipelineDefinition

	if err := json.Unmarshal(data, &def); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeValidation, "failed to parse JSON")
	}

	return p.validateDefinition(&def, nil)
}

func (p *Parser) validateDefinition(def *types.PipelineDefinition, yamlNode *yaml.Node) (*ParseResult, error) {
	result := &ParseResult{
		Definition: def,
		Warnings:   make([]string, 0),
		Errors:     make([]ValidationError, 0),
	}

	if def.Name == "" {
		result.Errors = append(result.Errors, ValidationError{
			Path:    "name",
			Message: "pipeline name is required",
		})
	}

	if len(def.Stages) == 0 {
		result.Errors = append(result.Errors, ValidationError{
			Path:    "stages",
			Message: "at least one stage is required",
		})
		return result, errors.New(errors.ErrCodeValidation,
			fmt.Sprintf("pipeline validation failed with %d errors", len(result.Errors)))
	}

	stageNames := make(map[string]bool)
	for i, stage := range def.Stages {
		stagePath := fmt.Sprintf("stages[%d]", i)

		if stage.Name == "" {
			result.Errors = append(result.Errors, ValidationError{
				Path:    stagePath + ".name",
				Message: "stage name is required",
			})
		} else if stageNames[stage.Name] {
			result.Errors = append(result.Errors, ValidationError{
				Path:    stagePath + ".name",
				Message: fmt.Sprintf("duplicate stage name: %s", stage.Name),
			})
		} else {
			stageNames[stage.Name] = true
		}

		if stage.Type == "" {
			result.Errors = append(result.Errors, ValidationError{
				Path:    stagePath + ".type",
				Message: "stage type is required",
			})
		} else {
			validTypes := map[types.StageType]bool{
				types.StageTypeScan:   true,
				types.StageTypeBuild:  true,
				types.StageTypeTest:   true,
				types.StageTypeDeploy: true,
				types.StageTypeCustom: true,
			}
			if !validTypes[stage.Type] {
				result.Errors = append(result.Errors, ValidationError{
					Path:    stagePath + ".type",
					Message: fmt.Sprintf("invalid stage type: %s", stage.Type),
				})
			}
		}

		for j, dep := range stage.DependsOn {
			depPath := fmt.Sprintf("%s.depends_on[%d]", stagePath, j)
			if !stageNames[dep] {
				result.Errors = append(result.Errors, ValidationError{
					Path:    depPath,
					Message: fmt.Sprintf("dependency stage not found: %s", dep),
				})
			}
			if dep == stage.Name {
				result.Errors = append(result.Errors, ValidationError{
					Path:    depPath,
					Message: "stage cannot depend on itself",
				})
			}
		}

		if stage.Plugin == nil && len(stage.Commands) == 0 {
			result.Warnings = append(result.Warnings,
				fmt.Sprintf("stage '%s' has no plugin or commands defined", stage.Name))
		}

		if stage.Plugin != nil {
			if stage.Plugin.Name == "" {
				result.Errors = append(result.Errors, ValidationError{
					Path:    stagePath + ".plugin.name",
					Message: "plugin name is required",
				})
			}
		}

		if err := p.validator.Struct(stage); err != nil {
			if validationErrs, ok := err.(validator.ValidationErrors); ok {
				for _, e := range validationErrs {
					result.Errors = append(result.Errors, ValidationError{
						Path:    stagePath + "." + e.Field(),
						Message: fmt.Sprintf("validation failed: %s", e.Tag()),
						Value:   fmt.Sprintf("%v", e.Value()),
					})
				}
			}
		}
	}

	if err := p.validateDependencies(def.Stages, result); err != nil {
		return nil, err
	}

	for i, trigger := range def.Triggers {
		triggerPath := fmt.Sprintf("triggers[%d]", i)
		if trigger.EventSource == "" {
			result.Errors = append(result.Errors, ValidationError{
				Path:    triggerPath + ".event_source",
				Message: "event source is required",
			})
		}
		if trigger.EventType == "" {
			result.Errors = append(result.Errors, ValidationError{
				Path:    triggerPath + ".event_type",
				Message: "event type is required",
			})
		}
		if trigger.Cron != nil && trigger.Cron.Schedule != "" {
			if !isValidCron(trigger.Cron.Schedule) {
				result.Errors = append(result.Errors, ValidationError{
					Path:    triggerPath + ".cron.schedule",
					Message: "invalid cron expression",
					Value:   trigger.Cron.Schedule,
				})
			}
		}
	}

	if len(result.Errors) > 0 {
		return result, errors.New(errors.ErrCodeValidation,
			fmt.Sprintf("pipeline validation failed with %d errors", len(result.Errors)))
	}

	return result, nil
}

func (p *Parser) validateDependencies(stages []types.StageDefinition, result *ParseResult) error {
	graph := make(map[string][]string)
	for _, stage := range stages {
		graph[stage.Name] = stage.DependsOn
	}

	visited := make(map[string]bool)
	recStack := make(map[string]bool)

	var hasCycle func(string) bool
	hasCycle = func(node string) bool {
		visited[node] = true
		recStack[node] = true

		for _, dep := range graph[node] {
			if !visited[dep] {
				if hasCycle(dep) {
					return true
				}
			} else if recStack[dep] {
				result.Errors = append(result.Errors, ValidationError{
					Path:    "stages",
					Message: fmt.Sprintf("circular dependency detected: %s -> %s", dep, node),
				})
				return true
			}
		}

		recStack[node] = false
		return false
	}

	for _, stage := range stages {
		if !visited[stage.Name] {
			hasCycle(stage.Name)
		}
	}

	return nil
}

func (p *Parser) ToJSON(def *types.PipelineDefinition) ([]byte, error) {
	return json.MarshalIndent(def, "", "  ")
}

func (p *Parser) ToYAML(def *types.PipelineDefinition) ([]byte, error) {
	return yaml.Marshal(def)
}

func (p *Parser) TopologicalSort(stages []types.StageDefinition) ([]string, error) {
	inDegree := make(map[string]int)
	graph := make(map[string][]string)

	for _, stage := range stages {
		if _, exists := inDegree[stage.Name]; !exists {
			inDegree[stage.Name] = 0
		}
		for _, dep := range stage.DependsOn {
			graph[dep] = append(graph[dep], stage.Name)
			inDegree[stage.Name]++
		}
	}

	queue := make([]string, 0)
	for name, degree := range inDegree {
		if degree == 0 {
			queue = append(queue, name)
		}
	}

	result := make([]string, 0)
	for len(queue) > 0 {
		current := queue[0]
		queue = queue[1:]
		result = append(result, current)

		for _, neighbor := range graph[current] {
			inDegree[neighbor]--
			if inDegree[neighbor] == 0 {
				queue = append(queue, neighbor)
			}
		}
	}

	if len(result) != len(stages) {
		return nil, errors.ValidationError("circular dependency detected in topological sort")
	}

	return result, nil
}

var (
	stageNameRegex = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9_-]{0,99}$`)
	envVarRegex    = regexp.MustCompile(`^[A-Z_][A-Z0-9_]*$`)
)

func validateStageName(fl validator.FieldLevel) bool {
	name := fl.Field().String()
	return stageNameRegex.MatchString(name)
}

func validateStageType(fl validator.FieldLevel) bool {
	stageType := fl.Field().String()
	validTypes := map[string]bool{
		"scan":   true,
		"build":  true,
		"test":   true,
		"deploy": true,
		"custom": true,
	}
	return validTypes[stageType]
}

func validateCronExpression(fl validator.FieldLevel) bool {
	cron := fl.Field().String()
	return isValidCron(cron)
}

func validateEnvVarName(fl validator.FieldLevel) bool {
	name := fl.Field().String()
	return envVarRegex.MatchString(name)
}

func isValidCron(expr string) bool {
	parts := strings.Fields(expr)
	if len(parts) != 5 && len(parts) != 6 {
		return false
	}

	for _, part := range parts {
		if part == "*" {
			continue
		}
		if strings.Contains(part, ",") || strings.Contains(part, "-") || strings.Contains(part, "/") {
			continue
		}
		matched, _ := regexp.MatchString(`^\d+$`, part)
		if !matched {
			return false
		}
	}
	return true
}
