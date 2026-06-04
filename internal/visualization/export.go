package visualization

import (
	"encoding/json"
	"fmt"
	"text/template"
	"os"
)

type DashboardConfig struct {
	Title         string
	PrometheusURL string
	ClickHouseURL string
}

func GenerateGrafanaDashboard(cfg DashboardConfig) (string, error) {
	dashboard := map[string]interface{}{
		"annotations": map[string]interface{}{
			"list": []interface{}{},
		},
		"editable": true,
		"fiscalYearStartMonth": 0,
		"graphTooltip": 0,
		"id": nil,
		"links": []interface{}{},
		"liveNow": false,
		"panels": []interface{}{
			map[string]interface{}{
				"datasource": map[string]string{
					"type": "prometheus",
					"uid":  "prometheus",
				},
				"fieldConfig": map[string]interface{}{
					"defaults": map[string]interface{}{
						"color": map[string]string{
							"mode": "palette-classic",
						},
						"custom": map[string]interface{}{
							"axisCenteredZero": false,
							"axisColorMode":    "text",
							"axisLabel":        "",
							"axisPlacement":    "auto",
							"barAlignment":     0,
							"drawStyle":        "line",
							"fillOpacity":      10,
							"gradientMode":     "none",
							"hideFrom": map[string]interface{}{
								"legend": false,
								"tooltip": false,
								"viz":     false,
							},
							"lineInterpolation": "linear",
							"lineWidth":         1,
							"pointSize":         5,
							"scaleDistribution": map[string]string{
								"type": "linear",
							},
							"showPoints": "auto",
							"spanNulls":  false,
							"stacking": map[string]interface{}{
								"group": "A",
								"mode":  "none",
							},
							"thresholdsStyle": map[string]interface{}{
								"mode": "off",
							},
						},
						"mappings":    []interface{}{},
						"thresholds": map[string]interface{}{
							"mode":  "absolute",
							"steps": []interface{}{},
						},
						"unit": "short",
					},
					"overrides": []interface{}{},
				},
				"gridPos": map[string]int{
					"h": 8,
					"w": 12,
					"x": 0,
					"y": 0,
				},
				"id": 1,
				"options": map[string]interface{}{
					"legend": map[string]interface{}{
						"calcs":       []interface{}{},
						"displayMode": "list",
						"placement":   "bottom",
						"showLegend":  true,
					},
					"tooltip": map[string]interface{}{
						"mode": "single",
						"sort": "none",
					},
				},
				"targets": []map[string]interface{}{
					{
						"expr": "sum(rate(log_pipeline_logs_total[5m])) by (level)",
						"refId": "A",
					},
				},
				"title": "Log Volume by Level",
				"type":  "timeseries",
			},
			map[string]interface{}{
				"datasource": map[string]string{
					"type": "prometheus",
					"uid":  "prometheus",
				},
				"fieldConfig": map[string]interface{}{
					"defaults": map[string]interface{}{
						"color": map[string]string{
							"mode": "thresholds",
						},
						"mappings": []interface{}{},
						"thresholds": map[string]interface{}{
							"mode": "absolute",
							"steps": []interface{}{
								map[string]interface{}{
									"color": "green",
									"value": nil,
								},
								map[string]interface{}{
									"color": "red",
									"value": 80,
								},
							},
						},
					},
					"overrides": []interface{}{},
				},
				"gridPos": map[string]int{
					"h": 8,
					"w": 12,
					"x": 12,
					"y": 0,
				},
				"id": 2,
				"options": map[string]interface{}{
					"colorMode":      "value",
					"graphMode":      "area",
					"justifyMode":    "auto",
					"orientation":    "auto",
					"reduceOptions": map[string]interface{}{
						"calcs":       []string{"lastNotNull"},
						"fields":      "",
						"values":      false,
					},
					"textMode": "auto",
				},
				"targets": []map[string]interface{}{
					{
						"expr": "sum(increase(log_pipeline_errors_total[5m]))",
						"refId": "A",
					},
				},
				"title": "Error Count (5m)",
				"type":  "stat",
			},
			map[string]interface{}{
				"datasource": map[string]string{
					"type": "prometheus",
					"uid":  "prometheus",
				},
				"fieldConfig": map[string]interface{}{
					"defaults": map[string]interface{}{
						"color": map[string]string{
							"mode": "palette-classic",
						},
						"custom": map[string]interface{}{
							"hideFrom": map[string]interface{}{
								"legend": false,
								"tooltip": false,
								"viz":     false,
							},
						},
					},
					"overrides": []interface{}{},
				},
				"gridPos": map[string]int{
					"h": 8,
					"w": 12,
					"x": 0,
					"y": 8,
				},
				"id": 3,
				"options": map[string]interface{}{
					"legend": map[string]interface{}{
						"displayMode": "list",
						"placement":   "right",
						"showLegend":  true,
					},
					"tooltip": map[string]interface{}{
						"mode": "single",
						"sort": "none",
					},
				},
				"targets": []map[string]interface{}{
					{
						"expr": "sum(rate(log_pipeline_error_rate[5m])) by (key)",
						"refId": "A",
					},
				},
				"title": "Error Rate by Source",
				"type":  "timeseries",
			},
			map[string]interface{}{
				"datasource": map[string]string{
					"type": "prometheus",
					"uid":  "prometheus",
				},
				"fieldConfig": map[string]interface{}{
					"defaults": map[string]interface{}{
						"color": map[string]string{
							"mode": "thresholds",
						},
						"mappings": []interface{}{},
						"thresholds": map[string]interface{}{
							"mode": "absolute",
							"steps": []interface{}{
								map[string]interface{}{
									"color": "green",
									"value": nil,
								},
								map[string]interface{}{
									"color": "yellow",
									"value": 5,
								},
								map[string]interface{}{
									"color": "red",
									"value": 10,
								},
							},
						},
					},
					"overrides": []interface{}{},
				},
				"gridPos": map[string]int{
					"h": 8,
					"w": 12,
					"x": 12,
					"y": 8,
				},
				"id": 4,
				"options": map[string]interface{}{
					"colorMode":      "value",
					"graphMode":      "area",
					"justifyMode":    "auto",
					"orientation":    "auto",
					"reduceOptions": map[string]interface{}{
						"calcs":  []string{"lastNotNull"},
						"fields": "",
						"values": false,
					},
					"textMode": "auto",
				},
				"targets": []map[string]interface{}{
					{
						"expr": "sum(increase(log_pipeline_anomalies_total[5m]))",
						"refId": "A",
					},
				},
				"title": "Anomalies Detected (5m)",
				"type":  "stat",
			},
		},
		"refresh":       "30s",
		"schemaVersion": 38,
		"style":         "dark",
		"tags":          []string{"logs", "monitoring"},
		"templating": map[string]interface{}{
			"list": []interface{}{},
		},
		"time": map[string]string{
			"from": "now-1h",
			"to":   "now",
		},
		"timepicker": map[string]interface{}{},
		"timezone":   "",
		"title":      cfg.Title,
		"uid":        "log-pipeline-dashboard",
		"version":    1,
		"weekStart":  "",
	}

	data, err := json.MarshalIndent(dashboard, "", "  ")
	if err != nil {
		return "", err
	}

	return string(data), nil
}

func SaveDashboardToFile(cfg DashboardConfig, filename string) error {
	dashboard, err := GenerateGrafanaDashboard(cfg)
	if err != nil {
		return err
	}

	return os.WriteFile(filename, []byte(dashboard), 0644)
}

func GenerateAlertRules() string {
	rules := map[string]interface{}{
		"groups": []interface{}{
			map[string]interface{}{
				"name": "log-alerts",
				"rules": []interface{}{
					map[string]interface{}{
						"alert": "HighErrorRate",
						"expr":  `sum(rate(log_pipeline_error_rate[5m])) by (job) > 0.1`,
						"for":   "2m",
						"labels": map[string]string{
							"severity": "warning",
						},
						"annotations": map[string]string{
							"summary":     "High error rate detected",
							"description": "Error rate is above 10% for more than 2 minutes",
						},
					},
					map[string]interface{}{
						"alert": "AnomalyDetected",
						"expr":  `sum(increase(log_pipeline_anomalies_total[5m])) > 0`,
						"for":   "0m",
						"labels": map[string]string{
							"severity": "critical",
						},
						"annotations": map[string]string{
							"summary":     "Anomaly detected in log patterns",
							"description": "Unusual log pattern detected by anomaly detection",
						},
					},
					map[string]interface{}{
						"alert": "AuthFailures",
						"expr":  `sum(increase(log_pipeline_alerts_total{alert_type="auth_failure_401"}[1m])) > 0`,
						"for":   "0m",
						"labels": map[string]string{
							"severity": "warning",
						},
						"annotations": map[string]string{
							"summary":     "Multiple authentication failures",
							"description": "Multiple 401 errors detected from single IP",
						},
					},
				},
			},
		},
	}

	data, _ := json.MarshalIndent(rules, "", "  ")
	return string(data)
}

var dashboardTemplate = template.Must(template.New("dashboard").Parse(`
{
  "annotations": {
    "list": []
  },
  "editable": true,
  "fiscalYearStartMonth": 0,
  "graphTooltip": 0,
  "id": null,
  "links": [],
  "liveNow": false,
  "panels": [
    {
      "type": "stat",
      "title": "Total Logs",
      "gridPos": {"x": 0, "y": 0, "w": 6, "h": 4},
      "targets": [{"expr": "sum(increase(log_pipeline_logs_total[5m]))"}]
    }
  ],
  "schemaVersion": 38,
  "style": "dark",
  "tags": ["logs"],
  "templating": {"list": []},
  "time": {"from": "now-1h", "to": "now"},
  "timezone": "",
  "title": "{{.Title}}",
  "uid": "log-pipeline",
  "version": 1,
  "weekStart": ""
}
`))

func PrintExportInstructions() {
	fmt.Println(`
=============================================
  Grafana Dashboard Export Instructions
=============================================

1. Import the generated JSON in Grafana:
   - Go to Dashboards -> Import
   - Upload the JSON file or paste content

2. Add Prometheus datasource:
   - URL: http://localhost:9090
   - Name: Prometheus (default)

3. Available Panels:
   - Log Volume by Level
   - Error Count (5min window)
   - Error Rate by Source
   - Anomalies Detected

4. Alert Rules:
   - HighErrorRate (>10% errors for 2min)
   - AnomalyDetected
   - AuthFailures (multiple 401 errors)`)
}
