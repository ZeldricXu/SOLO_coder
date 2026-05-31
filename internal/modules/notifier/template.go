package notifier

import (
	"bytes"
	"fmt"
	"html/template"
	"strings"
	"time"

	"session189/internal/domain"
)

type TemplateData struct {
	AlertEvent    *domain.AlertEvent
	AlertRule     *domain.AlertRule
	Task          *domain.Task
	ServiceName   string
	Timestamp     time.Time
	AdditionalData map[string]interface{}
}

type Template struct {
	templates *template.Template
}

func NewTemplate() (*Template, error) {
	t := &Template{}
	if err := t.loadTemplates(); err != nil {
		return nil, err
	}
	return t, nil
}

func (t *Template) loadTemplates() error {
	funcMap := template.FuncMap{
		"formatTime": func(ts time.Time) string {
			return ts.Format("2006-01-02 15:04:05")
		},
		"formatSeverity": func(severity domain.AlertSeverity) string {
			return strings.ToUpper(string(severity))
		},
		"toUpper": strings.ToUpper,
		"toLower": strings.ToLower,
	}

	tmpl := template.New("notifier").Funcs(funcMap)

	templates := map[string]string{
		"alert_email": `
<!DOCTYPE html>
<html>
<head>
    <title>Alert Notification</title>
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; }
        .alert-critical { background-color: #ffebee; border-left: 4px solid #f44336; padding: 15px; }
        .alert-warning { background-color: #fff3e0; border-left: 4px solid #ff9800; padding: 15px; }
        .alert-info { background-color: #e3f2fd; border-left: 4px solid #2196f3; padding: 15px; }
        .header { font-weight: bold; font-size: 18px; margin-bottom: 10px; }
        .meta { color: #666; font-size: 12px; margin-bottom: 10px; }
        .message { margin-top: 15px; }
    </style>
</head>
<body>
    <div class="alert-{{.AlertEvent.Severity}}">
        <div class="header">[{{formatSeverity .AlertEvent.Severity}}] {{.AlertRule.Name}}</div>
        <div class="meta">
            Triggered at: {{formatTime .AlertEvent.TriggeredAt}}<br>
            Metric: {{.AlertRule.MetricName}}<br>
            Current Value: {{.AlertEvent.MetricValue}}
        </div>
        <div class="message">{{.AlertEvent.Message}}</div>
    </div>
</body>
</html>`,
		"alert_slack": `
*[{{formatSeverity .AlertEvent.Severity}}] {{.AlertRule.Name}}*
Triggered at: {{formatTime .AlertEvent.TriggeredAt}}
Metric: {{.AlertRule.MetricName}}
Current Value: {{.AlertEvent.MetricValue}}

{{.AlertEvent.Message}}`,
		"alert_webhook": `{
  "event_id": "{{.AlertEvent.EventID}}",
  "rule_id": "{{.AlertEvent.RuleID}}",
  "severity": "{{.AlertEvent.Severity}}",
  "metric_name": "{{.AlertRule.MetricName}}",
  "metric_value": {{.AlertEvent.MetricValue}},
  "message": {{.AlertEvent.Message}},
  "triggered_at": "{{formatTime .AlertEvent.TriggeredAt}}"
}`,
		"task_completed_email": `
<!DOCTYPE html>
<html>
<head>
    <title>Task Completed</title>
</head>
<body>
    <h2>Task Completed: {{.Task.Name}}</h2>
    <p>Task ID: {{.Task.TaskID}}</p>
    <p>Type: {{.Task.Type}}</p>
    <p>Status: {{.Task.Status}}</p>
    <p>Created at: {{formatTime .Task.CreatedAt}}</p>
    {{if .Task.CompletedAt}}<p>Completed at: {{formatTime .Task.CompletedAt}}</p>{{end}}
    {{if .Task.Error}}<p>Error: {{.Task.Error}}</p>{{end}}
</body>
</html>`,
	}

	var err error
	for name, content := range templates {
		tmpl, err = tmpl.New(name).Parse(content)
		if err != nil {
			return fmt.Errorf("parse template %s failed: %w", name, err)
		}
	}

	t.templates = tmpl
	return nil
}

func (t *Template) Render(templateName string, data TemplateData) (string, error) {
	var buf bytes.Buffer
	if err := t.templates.ExecuteTemplate(&buf, templateName, data); err != nil {
		return "", fmt.Errorf("render template %s failed: %w", templateName, err)
	}
	return buf.String(), nil
}

func (t *Template) RenderAlertEmail(data TemplateData) (string, error) {
	return t.Render("alert_email", data)
}

func (t *Template) RenderAlertSlack(data TemplateData) (string, error) {
	return t.Render("alert_slack", data)
}

func (t *Template) RenderAlertWebhook(data TemplateData) (string, error) {
	return t.Render("alert_webhook", data)
}

func (t *Template) RenderTaskCompleted(data TemplateData) (string, error) {
	return t.Render("task_completed_email", data)
}
