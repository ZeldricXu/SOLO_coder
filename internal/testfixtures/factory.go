package testfixtures

import (
	"time"

	"github.com/solocoder/tasktracker/internal/config"
	"github.com/solocoder/tasktracker/internal/models"
	"github.com/solocoder/tasktracker/internal/qualitygate"
)

type TaskBuilder struct {
	task *models.Task
}

func NewTaskBuilder() *TaskBuilder {
	return &TaskBuilder{
		task: &models.Task{
			ID:         "task_test_001",
			Name:       "test-task",
			Type:       "test_type",
			Status:     "pending",
			Payload:    map[string]interface{}{"key": "value"},
			RetryCount: 0,
			MaxRetries: 3,
			Priority:   1,
			CreatedAt:  time.Now(),
		},
	}
}

func (b *TaskBuilder) WithID(id string) *TaskBuilder {
	b.task.ID = id
	return b
}

func (b *TaskBuilder) WithType(taskType string) *TaskBuilder {
	b.task.Type = taskType
	return b
}

func (b *TaskBuilder) WithStatus(status string) *TaskBuilder {
	b.task.Status = status
	return b
}

func (b *TaskBuilder) WithPayload(payload map[string]interface{}) *TaskBuilder {
	b.task.Payload = payload
	return b
}

func (b *TaskBuilder) WithRetryCount(count int) *TaskBuilder {
	b.task.RetryCount = count
	return b
}

func (b *TaskBuilder) WithMaxRetries(max int) *TaskBuilder {
	b.task.MaxRetries = max
	return b
}

func (b *TaskBuilder) WithPriority(priority int) *TaskBuilder {
	b.task.Priority = priority
	return b
}

func (b *TaskBuilder) WithEmptyID() *TaskBuilder {
	b.task.ID = ""
	return b
}

func (b *TaskBuilder) Build() *models.Task {
	return b.task
}

type ConfigBuilder struct {
	cfg *config.Config
}

func NewConfigBuilder() *ConfigBuilder {
	return &ConfigBuilder{
		cfg: &config.Config{
			ConfigID:  "cfg_test_001",
			Namespace: "test",
			Version:   1,
			Params:    map[string]interface{}{"timeout": 30, "retries": 3},
			Enabled:   true,
			AppliedAt: time.Now(),
		},
	}
}

func (b *ConfigBuilder) WithConfigID(id string) *ConfigBuilder {
	b.cfg.ConfigID = id
	return b
}

func (b *ConfigBuilder) WithNamespace(ns string) *ConfigBuilder {
	b.cfg.Namespace = ns
	return b
}

func (b *ConfigBuilder) WithVersion(v int) *ConfigBuilder {
	b.cfg.Version = v
	return b
}

func (b *ConfigBuilder) WithParams(params map[string]interface{}) *ConfigBuilder {
	b.cfg.Params = params
	return b
}

func (b *ConfigBuilder) WithEnabled(enabled bool) *ConfigBuilder {
	b.cfg.Enabled = enabled
	return b
}

func (b *ConfigBuilder) WithEmptyConfigID() *ConfigBuilder {
	b.cfg.ConfigID = ""
	return b
}

func (b *ConfigBuilder) WithEmptyParams() *ConfigBuilder {
	b.cfg.Params = nil
	return b
}

func (b *ConfigBuilder) Build() *config.Config {
	return b.cfg
}

type QualityGateRuleBuilder struct {
	rule qualitygate.Rule
}

func NewQualityGateRuleBuilder() *QualityGateRuleBuilder {
	return &QualityGateRuleBuilder{
		rule: qualitygate.Rule{
			ID:          "RULE_TEST_001",
			Name:        "test-rule",
			Description: "Test rule for unit testing",
			Language:    qualitygate.LangGo,
			Severity:    qualitygate.SeverityMajor,
			Enabled:     true,
			Category:    "test",
			Threshold:   1.0,
		},
	}
}

func (b *QualityGateRuleBuilder) WithID(id string) *QualityGateRuleBuilder {
	b.rule.ID = id
	return b
}

func (b *QualityGateRuleBuilder) WithName(name string) *QualityGateRuleBuilder {
	b.rule.Name = name
	return b
}

func (b *QualityGateRuleBuilder) WithLanguage(lang qualitygate.Language) *QualityGateRuleBuilder {
	b.rule.Language = lang
	return b
}

func (b *QualityGateRuleBuilder) WithSeverity(sev qualitygate.Severity) *QualityGateRuleBuilder {
	b.rule.Severity = sev
	return b
}

func (b *QualityGateRuleBuilder) WithEnabled(enabled bool) *QualityGateRuleBuilder {
	b.rule.Enabled = enabled
	return b
}

func (b *QualityGateRuleBuilder) WithEmptyID() *QualityGateRuleBuilder {
	b.rule.ID = ""
	return b
}

func (b *QualityGateRuleBuilder) Build() qualitygate.Rule {
	return b.rule
}

type SourceFileBuilder struct {
	files map[string]string
}

func NewSourceFileBuilder() *SourceFileBuilder {
	return &SourceFileBuilder{
		files: make(map[string]string),
	}
}

func (b *SourceFileBuilder) AddFile(name, content string) *SourceFileBuilder {
	b.files[name] = content
	return b
}

func (b *QualityGateRuleBuilder) WithCategory(cat string) *QualityGateRuleBuilder {
	b.rule.Category = cat
	return b
}

func (b *SourceFileBuilder) AddGoFileWithUnusedImport() *SourceFileBuilder {
	b.files["main.go"] = `package main

import (
	"fmt"
	_ "unused"
)

func main() {
	fmt.Println("hello")
}
`
	return b
}

func (b *SourceFileBuilder) AddGoFileWithNilDereference() *SourceFileBuilder {
	b.files["risky.go"] = `package main

type User struct {
	Name string
}

func main() {
	var u *User
	println(u.Name)
}
`
	return b
}

func (b *SourceFileBuilder) AddGoFileWithUnhandledError() *SourceFileBuilder {
	b.files["error.go"] = `package main

import "os"

func main() {
	f, _ := os.Open("file.txt")
	f.Close()
}
`
	return b
}

func (b *SourceFileBuilder) AddPythonFileWithEval() *SourceFileBuilder {
	b.files["main.py"] = `
def main():
    expr = input("Enter expression: ")
    result = eval(expr)
    print(result)
`
	return b
}

func (b *SourceFileBuilder) AddPythonFileWithBroadExcept() *SourceFileBuilder {
	b.files["error.py"] = `
def risky():
    try:
        do_something()
    except:
        pass
`
	return b
}

func (b *SourceFileBuilder) AddJSFileWithConsoleLog() *SourceFileBuilder {
	b.files["app.js"] = `
function main() {
    console.log("debug message");
    return true;
}
`
	return b
}

func (b *SourceFileBuilder) AddCleanGoFile() *SourceFileBuilder {
	b.files["clean.go"] = `package main

import "fmt"

func main() {
	fmt.Println("hello world")
}
`
	return b
}

func (b *SourceFileBuilder) AddEmptyFile(name string) *SourceFileBuilder {
	b.files[name] = ""
	return b
}

func (b *SourceFileBuilder) Build() map[string]string {
	return b.files
}

type RunInstanceBuilder struct {
	run *models.RunInstance
}

func NewRunInstanceBuilder() *RunInstanceBuilder {
	now := time.Now()
	return &RunInstanceBuilder{
		run: &models.RunInstance{
			RunID:       "run_test_001",
			EntityID:    "task_test_001",
			Phase:       "running",
			Progress:    0.5,
			StartedAt:   now,
			CompletedAt: nil,
			ErrorDetail: nil,
		},
	}
}

func (b *RunInstanceBuilder) WithPhase(phase string) *RunInstanceBuilder {
	b.run.Phase = phase
	return b
}

func (b *RunInstanceBuilder) WithProgress(p float64) *RunInstanceBuilder {
	b.run.Progress = p
	return b
}

func (b *RunInstanceBuilder) WithError(err string) *RunInstanceBuilder {
	b.run.ErrorDetail = &err
	return b
}

func (b *RunInstanceBuilder) WithCompleted() *RunInstanceBuilder {
	now := time.Now()
	b.run.CompletedAt = &now
	b.run.Phase = "completed"
	b.run.Progress = 1.0
	return b
}

func (b *RunInstanceBuilder) Build() *models.RunInstance {
	return b.run
}
