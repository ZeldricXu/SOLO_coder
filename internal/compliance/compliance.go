package compliance

import (
	"fmt"
	"os"
	"regexp"
	"strings"

	"github.com/multicloud/cli/internal/common"
)

type Severity string

const (
	SeverityCritical Severity = "critical"
	SeverityHigh     Severity = "high"
	SeverityMedium   Severity = "medium"
	SeverityLow      Severity = "low"
)

type ComplianceRule struct {
	ID          string
	Name        string
	Description string
	Framework   string
	Severity    Severity
	Check       func(*common.ResourceConfig) *ComplianceViolation
	Fix         func(*common.ResourceConfig) error
	AutoFixable bool
}

type ComplianceViolation struct {
	RuleID        string
	RuleName      string
	Severity      Severity
	Resource      string
	Message       string
	Path          string
	CurrentValue  interface{}
	ExpectedValue interface{}
	AutoFixable   bool
}

type ComplianceResult struct {
	Framework     string
	TotalRules    int
	Violations    []*ComplianceViolation
	Passed        int
	Failed        int
	SeverityCount map[Severity]int
}

type ComplianceScanner struct {
	rules      map[string]*ComplianceRule
	frameworks map[string][]*ComplianceRule
}

func NewComplianceScanner() *ComplianceScanner {
	scanner := &ComplianceScanner{
		rules:      make(map[string]*ComplianceRule),
		frameworks: make(map[string][]*ComplianceRule),
	}

	scanner.registerBuiltinRules()
	return scanner
}

func (s *ComplianceScanner) registerBuiltinRules() {
	cisRules := []*ComplianceRule{
		{
			ID:          "CIS-1.1",
			Name:        "Require Tags",
			Description: "All resources must have required tags (Environment, Owner)",
			Framework:   "cis",
			Severity:    SeverityMedium,
			AutoFixable: false,
			Check: func(rc *common.ResourceConfig) *ComplianceViolation {
				requiredTags := []string{"Environment", "Owner"}
				for _, tag := range requiredTags {
					if _, exists := rc.Tags[tag]; !exists {
						return &ComplianceViolation{
							RuleID:        "CIS-1.1",
							RuleName:      "Require Tags",
							Severity:      SeverityMedium,
							Resource:      rc.Name,
							Message:       fmt.Sprintf("Missing required tag: %s", tag),
							Path:          fmt.Sprintf("tags.%s", tag),
							ExpectedValue: "must be set",
							AutoFixable:   false,
						}
					}
				}
				return nil
			},
		},
		{
			ID:          "CIS-1.2",
			Name:        "Environment Tag Validation",
			Description: "Environment tag must be one of: dev, staging, prod",
			Framework:   "cis",
			Severity:    SeverityLow,
			AutoFixable: false,
			Check: func(rc *common.ResourceConfig) *ComplianceViolation {
				env, exists := rc.Tags["Environment"]
				if !exists {
					return nil
				}
				validEnvs := map[string]bool{"dev": true, "staging": true, "prod": true, "development": true, "production": true}
				if !validEnvs[strings.ToLower(env)] {
					return &ComplianceViolation{
						RuleID:        "CIS-1.2",
						RuleName:      "Environment Tag Validation",
						Severity:      SeverityLow,
						Resource:      rc.Name,
						Message:       fmt.Sprintf("Invalid environment tag: %s", env),
						Path:          "tags.Environment",
						CurrentValue:  env,
						ExpectedValue: "dev, staging, or prod",
						AutoFixable:   false,
					}
				}
				return nil
			},
		},
		{
			ID:          "CIS-2.1",
			Name:        "Storage Encryption",
			Description: "Storage resources must have encryption enabled",
			Framework:   "cis",
			Severity:    SeverityHigh,
			AutoFixable: true,
			Check: func(rc *common.ResourceConfig) *ComplianceViolation {
				if rc.Type != common.ResourceStorage {
					return nil
				}
				encrypted, exists := rc.Properties["encryption"]
				if !exists {
					return &ComplianceViolation{
						RuleID:        "CIS-2.1",
						RuleName:      "Storage Encryption",
						Severity:      SeverityHigh,
						Resource:      rc.Name,
						Message:       "Storage encryption is not enabled",
						Path:          "properties.encryption",
						CurrentValue:  nil,
						ExpectedValue: true,
						AutoFixable:   true,
					}
				}
				if !toBool(encrypted) {
					return &ComplianceViolation{
						RuleID:        "CIS-2.1",
						RuleName:      "Storage Encryption",
						Severity:      SeverityHigh,
						Resource:      rc.Name,
						Message:       "Storage encryption is disabled",
						Path:          "properties.encryption",
						CurrentValue:  encrypted,
						ExpectedValue: true,
						AutoFixable:   true,
					}
				}
				return nil
			},
			Fix: func(rc *common.ResourceConfig) error {
				rc.Properties["encryption"] = true
				return nil
			},
		},
		{
			ID:          "CIS-2.2",
			Name:        "Public Access Restriction",
			Description: "Storage resources must not have public access enabled",
			Framework:   "cis",
			Severity:    SeverityCritical,
			AutoFixable: true,
			Check: func(rc *common.ResourceConfig) *ComplianceViolation {
				if rc.Type != common.ResourceStorage {
					return nil
				}
				public, exists := rc.Properties["public_access"]
				if exists && toBool(public) {
					return &ComplianceViolation{
						RuleID:        "CIS-2.2",
						RuleName:      "Public Access Restriction",
						Severity:      SeverityCritical,
						Resource:      rc.Name,
						Message:       "Public access is enabled for storage resource",
						Path:          "properties.public_access",
						CurrentValue:  true,
						ExpectedValue: false,
						AutoFixable:   true,
					}
				}
				return nil
			},
			Fix: func(rc *common.ResourceConfig) error {
				rc.Properties["public_access"] = false
				return nil
			},
		},
		{
			ID:          "CIS-3.1",
			Name:        "Kubernetes Node Count",
			Description: "Kubernetes clusters must have at least 3 nodes for HA",
			Framework:   "cis",
			Severity:    SeverityMedium,
			AutoFixable: false,
			Check: func(rc *common.ResourceConfig) *ComplianceViolation {
				if rc.Type != common.ResourceKubernetes {
					return nil
				}
				nodeCount, exists := rc.Properties["node_count"]
				if !exists {
					return &ComplianceViolation{
						RuleID:        "CIS-3.1",
						RuleName:      "Kubernetes Node Count",
						Severity:      SeverityMedium,
						Resource:      rc.Name,
						Message:       "Kubernetes cluster node count not specified",
						Path:          "properties.node_count",
						CurrentValue:  nil,
						ExpectedValue: ">= 3",
						AutoFixable:   false,
					}
				}
				if toInt(nodeCount) < 3 {
					return &ComplianceViolation{
						RuleID:        "CIS-3.1",
						RuleName:      "Kubernetes Node Count",
						Severity:      SeverityMedium,
						Resource:      rc.Name,
						Message:       fmt.Sprintf("Kubernetes cluster has only %d nodes, minimum 3 required", toInt(nodeCount)),
						Path:          "properties.node_count",
						CurrentValue:  nodeCount,
						ExpectedValue: ">= 3",
						AutoFixable:   false,
					}
				}
				return nil
			},
		},
		{
			ID:          "CIS-4.1",
			Name:        "VM Instance Size Validation",
			Description: "Production VMs should not use t2.micro or similar instance types",
			Framework:   "cis",
			Severity:    SeverityLow,
			AutoFixable: false,
			Check: func(rc *common.ResourceConfig) *ComplianceViolation {
				if rc.Type != common.ResourceCompute {
					return nil
				}
				env, _ := rc.Tags["Environment"]
				if strings.ToLower(env) != "prod" && strings.ToLower(env) != "production" {
					return nil
				}

				instanceType := ""
				if it, ok := rc.Properties["instance_type"]; ok {
					instanceType = fmt.Sprintf("%v", it)
				} else if it, ok := rc.Properties["vm_size"]; ok {
					instanceType = fmt.Sprintf("%v", it)
				} else if it, ok := rc.Properties["machine_type"]; ok {
					instanceType = fmt.Sprintf("%v", it)
				}

				freeTierPatterns := []string{"t2.micro", "t3.micro", "f1-micro", "g1-small", "Standard_B1s", "Standard_A0"}
				for _, pattern := range freeTierPatterns {
					if strings.Contains(strings.ToLower(instanceType), strings.ToLower(pattern)) {
						return &ComplianceViolation{
							RuleID:        "CIS-4.1",
							RuleName:      "VM Instance Size Validation",
							Severity:      SeverityLow,
							Resource:      rc.Name,
							Message:       fmt.Sprintf("Production VM using free-tier instance type: %s", instanceType),
							Path:          "properties.instance_type",
							CurrentValue:  instanceType,
							ExpectedValue: "production-grade instance type",
							AutoFixable:   false,
						}
					}
				}
				return nil
			},
		},
	}

	etcRules := []*ComplianceRule{
		{
			ID:          "ETC-1.1",
			Name:        "Password Complexity",
			Description: "Database passwords must meet complexity requirements",
			Framework:   "etc",
			Severity:    SeverityHigh,
			AutoFixable: false,
			Check: func(rc *common.ResourceConfig) *ComplianceViolation {
				if rc.Type != common.ResourceDatabase {
					return nil
				}
				password, exists := rc.Properties["password"]
				if !exists {
					return nil
				}
				pwd := fmt.Sprintf("%v", password)
				if len(pwd) < 12 {
					return &ComplianceViolation{
						RuleID:        "ETC-1.1",
						RuleName:      "Password Complexity",
						Severity:      SeverityHigh,
						Resource:      rc.Name,
						Message:       "Password must be at least 12 characters long",
						Path:          "properties.password",
						CurrentValue:  "***",
						ExpectedValue: ">= 12 characters",
						AutoFixable:   false,
					}
				}
				hasUpper, _ := regexp.MatchString(`[A-Z]`, pwd)
				hasLower, _ := regexp.MatchString(`[a-z]`, pwd)
				hasDigit, _ := regexp.MatchString(`[0-9]`, pwd)
				hasSpecial, _ := regexp.MatchString(`[^A-Za-z0-9]`, pwd)
				if !hasUpper || !hasLower || !hasDigit || !hasSpecial {
					return &ComplianceViolation{
						RuleID:        "ETC-1.1",
						RuleName:      "Password Complexity",
						Severity:      SeverityHigh,
						Resource:      rc.Name,
						Message:       "Password must contain uppercase, lowercase, digit, and special character",
						Path:          "properties.password",
						CurrentValue:  "***",
						ExpectedValue: "complex password",
						AutoFixable:   false,
					}
				}
				return nil
			},
		},
		{
			ID:          "ETC-2.1",
			Name:        "Backup Enabled",
			Description: "Databases must have backups enabled",
			Framework:   "etc",
			Severity:    SeverityHigh,
			AutoFixable: true,
			Check: func(rc *common.ResourceConfig) *ComplianceViolation {
				if rc.Type != common.ResourceDatabase {
					return nil
				}
				backup, exists := rc.Properties["backup_enabled"]
				if !exists || !toBool(backup) {
					return &ComplianceViolation{
						RuleID:        "ETC-2.1",
						RuleName:      "Backup Enabled",
						Severity:      SeverityHigh,
						Resource:      rc.Name,
						Message:       "Database backups are not enabled",
						Path:          "properties.backup_enabled",
						CurrentValue:  backup,
						ExpectedValue: true,
						AutoFixable:   true,
					}
				}
				return nil
			},
			Fix: func(rc *common.ResourceConfig) error {
				rc.Properties["backup_enabled"] = true
				return nil
			},
		},
		{
			ID:          "ETC-3.1",
			Name:        "Resource Naming Convention",
			Description: "Resource names must follow naming convention: {env}-{type}-{name}",
			Framework:   "etc",
			Severity:    SeverityLow,
			AutoFixable: false,
			Check: func(rc *common.ResourceConfig) *ComplianceViolation {
				pattern := `^(dev|staging|prod|development|production)-(compute|storage|network|database|kubernetes|security)-.+$`
				matched, _ := regexp.MatchString(pattern, strings.ToLower(rc.Name))
				if !matched {
					return &ComplianceViolation{
						RuleID:        "ETC-3.1",
						RuleName:      "Resource Naming Convention",
						Severity:      SeverityLow,
						Resource:      rc.Name,
						Message:       "Resource name does not follow convention: {env}-{type}-{name}",
						Path:          "name",
						CurrentValue:  rc.Name,
						ExpectedValue: "format: {env}-{type}-{name}",
						AutoFixable:   false,
					}
				}
				return nil
			},
		},
	}

	for _, rule := range cisRules {
		s.rules[rule.ID] = rule
		s.frameworks["cis"] = append(s.frameworks["cis"], rule)
	}

	for _, rule := range etcRules {
		s.rules[rule.ID] = rule
		s.frameworks["etc"] = append(s.frameworks["etc"], rule)
	}

	s.frameworks["all"] = append(s.frameworks["all"], cisRules...)
	s.frameworks["all"] = append(s.frameworks["all"], etcRules...)
}

func (s *ComplianceScanner) Scan(resources []*common.ResourceConfig, framework string, minSeverity Severity) *ComplianceResult {
	result := &ComplianceResult{
		Framework:     framework,
		Violations:    make([]*ComplianceViolation, 0),
		SeverityCount: make(map[Severity]int),
	}

	rules, exists := s.frameworks[framework]
	if !exists {
		rules = s.frameworks["all"]
	}

	result.TotalRules = len(rules) * len(resources)

	for _, resource := range resources {
		for _, rule := range rules {
			if !isSeverityAtLeast(rule.Severity, minSeverity) {
				continue
			}

			violation := rule.Check(resource)
			if violation != nil {
				violation.AutoFixable = rule.AutoFixable
				result.Violations = append(result.Violations, violation)
				result.SeverityCount[violation.Severity]++
			}
		}
	}

	result.Failed = len(result.Violations)
	result.Passed = result.TotalRules - result.Failed

	return result
}

func (s *ComplianceScanner) AutoFix(resources []*common.ResourceConfig, violations []*ComplianceViolation) (int, error) {
	fixed := 0

	for _, violation := range violations {
		if !violation.AutoFixable {
			continue
		}

		rule, exists := s.rules[violation.RuleID]
		if !exists || rule.Fix == nil {
			continue
		}

		var resource *common.ResourceConfig
		for _, r := range resources {
			if r.Name == violation.Resource {
				resource = r
				break
			}
		}

		if resource == nil {
			continue
		}

		if err := rule.Fix(resource); err == nil {
			fixed++
		}
	}

	return fixed, nil
}

func (s *ComplianceScanner) AddRule(rule *ComplianceRule) {
	s.rules[rule.ID] = rule
	s.frameworks[rule.Framework] = append(s.frameworks[rule.Framework], rule)
	s.frameworks["all"] = append(s.frameworks["all"], rule)
}

func (s *ComplianceScanner) LoadRulesFromFile(filePath string) error {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to read rules file", err)
	}

	var rules []*ComplianceRule
	if err := common.UnmarshalJSON(data, &rules); err != nil {
		return common.NewError(common.ErrInvalidConfig, "failed to parse rules file", err)
	}

	for _, rule := range rules {
		s.AddRule(rule)
	}

	return nil
}

func (s *ComplianceScanner) GetRules(framework string) []*ComplianceRule {
	return s.frameworks[framework]
}

func (s *ComplianceScanner) GetFrameworks() []string {
	frameworks := make([]string, 0, len(s.frameworks))
	for f := range s.frameworks {
		frameworks = append(frameworks, f)
	}
	return frameworks
}

func toBool(v interface{}) bool {
	switch val := v.(type) {
	case bool:
		return val
	case string:
		return strings.ToLower(val) == "true" || val == "1" || val == "yes"
	case int:
		return val != 0
	default:
		return false
	}
}

func toInt(v interface{}) int {
	switch val := v.(type) {
	case int:
		return val
	case int64:
		return int(val)
	case float64:
		return int(val)
	case string:
		var i int
		fmt.Sscanf(val, "%d", &i)
		return i
	default:
		return 0
	}
}

func isSeverityAtLeast(actual, min Severity) bool {
	severityOrder := map[Severity]int{
		SeverityCritical: 4,
		SeverityHigh:     3,
		SeverityMedium:   2,
		SeverityLow:      1,
	}

	return severityOrder[actual] >= severityOrder[min]
}

func FormatResult(result *ComplianceResult, useColor bool) string {
	var sb strings.Builder

	green := func(s string) string { return s }
	red := func(s string) string { return s }
	yellow := func(s string) string { return s }
	cyan := func(s string) string { return s }
	bold := func(s string) string { return s }

	if useColor {
		green = func(s string) string { return "\033[32m" + s + "\033[0m" }
		red = func(s string) string { return "\033[31m" + s + "\033[0m" }
		yellow = func(s string) string { return "\033[33m" + s + "\033[0m" }
		cyan = func(s string) string { return "\033[36m" + s + "\033[0m" }
		bold = func(s string) string { return "\033[1m" + s + "\033[0m" }
	}

	sb.WriteString(bold("\nCompliance Scan Results\n"))
	sb.WriteString(strings.Repeat("=", 60) + "\n\n")
	sb.WriteString(fmt.Sprintf("Framework: %s\n\n", result.Framework))

	if len(result.Violations) == 0 {
		sb.WriteString(green("✓ All checks passed!") + "\n")
		sb.WriteString(fmt.Sprintf("  Rules checked: %d\n", result.TotalRules))
		return sb.String()
	}

	sb.WriteString(fmt.Sprintf("Summary: %s passed, %s failed\n\n",
		green(fmt.Sprintf("%d", result.Passed)),
		red(fmt.Sprintf("%d", result.Failed))))

	sb.WriteString("Severity breakdown:\n")
	for sev, count := range result.SeverityCount {
		sevStr := fmt.Sprintf("  %s: %d\n", sev, count)
		switch sev {
		case SeverityCritical:
			sb.WriteString(red(sevStr))
		case SeverityHigh:
			sb.WriteString(yellow(sevStr))
		case SeverityMedium:
			sb.WriteString(cyan(sevStr))
		default:
			sb.WriteString(sevStr)
		}
	}

	sb.WriteString("\nViolations:\n")
	sb.WriteString(strings.Repeat("-", 60) + "\n")

	for i, v := range result.Violations {
		sevColor := func(s string) string { return s }
		switch v.Severity {
		case SeverityCritical:
			sevColor = red
		case SeverityHigh:
			sevColor = yellow
		case SeverityMedium:
			sevColor = cyan
		}

		sb.WriteString(fmt.Sprintf("\n%d. [%s] %s - %s\n",
			i+1, sevColor(string(v.Severity)), cyan(v.Resource), bold(v.RuleName)))
		sb.WriteString(fmt.Sprintf("   Rule ID: %s\n", v.RuleID))
		sb.WriteString(fmt.Sprintf("   Message: %s\n", v.Message))
		sb.WriteString(fmt.Sprintf("   Path: %s\n", v.Path))
		if v.AutoFixable {
			sb.WriteString(fmt.Sprintf("   %s\n", green("✓ Auto-fixable")))
		}
	}

	return sb.String()
}
