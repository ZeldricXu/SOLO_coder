package qualitygate

import (
	"fmt"
	"github.com/solocoder/tasktracker/internal/config"
	"github.com/solocoder/tasktracker/internal/logger"
	"sync"
	"time"
)

type Severity string

const (
	SeverityCritical Severity = "critical"
	SeverityMajor    Severity = "major"
	SeverityMinor    Severity = "minor"
	SeverityInfo     Severity = "info"
)

type Language string

const (
	LangGo       Language = "go"
	LangPython   Language = "python"
	LangJava     Language = "java"
	LangJavaScript Language = "javascript"
	LangTypeScript Language = "typescript"
	LangCPP      Language = "cpp"
	LangRust     Language = "rust"
)

type Rule struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Language    Language `json:"language"`
	Severity    Severity `json:"severity"`
	Enabled     bool     `json:"enabled"`
	Category    string   `json:"category"`
	Threshold   float64  `json:"threshold"`
}

type Issue struct {
	RuleID     string   `json:"rule_id"`
	Message    string   `json:"message"`
	File       string   `json:"file"`
	Line       int      `json:"line"`
	Column     int      `json:"column"`
	Severity   Severity `json:"severity"`
	Confidence float64  `json:"confidence"`
}

type AnalysisReport struct {
	ReportID    string    `json:"report_id"`
	ProjectID   string    `json:"project_id"`
	Language    Language  `json:"language"`
	AnalyzedAt  time.Time `json:"analyzed_at"`
	DurationMs  int64     `json:"duration_ms"`
	Issues      []Issue   `json:"issues"`
	TotalIssues int       `json:"total_issues"`
	Critical    int       `json:"critical"`
	Major       int       `json:"major"`
	Minor       int       `json:"minor"`
	Info        int       `json:"info"`
	Passed      bool      `json:"passed"`
	Score       float64   `json:"score"`
}

const (
	MaxFileSize       = 1024 * 1024
	MaxTotalFileSize  = 10 * 1024 * 1024
	MaxFilesPerAnalysis = 100
)

type QualityGate struct {
	mu          sync.RWMutex
	rules       map[string]Rule
	thresholds  map[Severity]int
	cfgManager  *config.Manager
	detectors   map[string]func(string) bool
}

type Config struct {
	MaxCritical int `json:"max_critical"`
	MaxMajor    int `json:"max_major"`
	MaxMinor    int `json:"max_minor"`
	MinScore    float64 `json:"min_score"`
}

func NewQualityGate(cfg Config, cfgManager *config.Manager) *QualityGate {
	qg := &QualityGate{
		rules:      make(map[string]Rule),
		thresholds: make(map[Severity]int),
		cfgManager: cfgManager,
		detectors:  make(map[string]func(string) bool),
	}

	qg.thresholds[SeverityCritical] = cfg.MaxCritical
	qg.thresholds[SeverityMajor] = cfg.MaxMajor
	qg.thresholds[SeverityMinor] = cfg.MaxMinor

	qg.registerDefaultRules()
	qg.registerDetectors()
	return qg
}

func (qg *QualityGate) registerDetectors() {
	qg.detectors = map[string]func(string) bool{
		"unused-import":    func(line string) bool { return containsPattern(line, "import") && containsPattern(line, "_") },
		"nil-dereference":  func(line string) bool { return containsPattern(line, ".") && !containsPattern(line, "if") && !containsPattern(line, "!=") },
		"unhandled-error":  func(line string) bool { return containsPattern(line, ", err :=") && !containsPattern(line, "if err") },
		"unused-variable":  func(line string) bool { return containsPattern(line, ":=") && !containsPattern(line, "for ") && !containsPattern(line, "if ") },
		"broad-except":     func(line string) bool { return containsPattern(line, "except:") },
		"eval-used":        func(line string) bool { return containsPattern(line, "eval(") },
		"no-console":       func(line string) bool { return containsPattern(line, "console.log") },
		"no-eval":          func(line string) bool { return containsPattern(line, "eval(") },
		"empty-catch":      func(line string) bool { return containsPattern(line, "catch") && containsPattern(line, "{}") },
		"unwrap-used":      func(line string) bool { return containsPattern(line, ".unwrap()") },
	}
}

func (qg *QualityGate) registerDefaultRules() {
	defaultRules := []Rule{
		{ID: "GO001", Name: "unused-import", Description: "Unused import detected", Language: LangGo, Severity: SeverityMinor, Enabled: true, Category: "style"},
		{ID: "GO002", Name: "nil-dereference", Description: "Potential nil pointer dereference", Language: LangGo, Severity: SeverityCritical, Enabled: true, Category: "bug"},
		{ID: "GO003", Name: "race-condition", Description: "Potential race condition detected", Language: LangGo, Severity: SeverityCritical, Enabled: true, Category: "bug"},
		{ID: "GO004", Name: "unhandled-error", Description: "Unhandled error", Language: LangGo, Severity: SeverityMajor, Enabled: true, Category: "error-handling"},
		{ID: "PY001", Name: "unused-variable", Description: "Unused variable detected", Language: LangPython, Severity: SeverityMinor, Enabled: true, Category: "style"},
		{ID: "PY002", Name: "broad-except", Description: "Too broad exception clause", Language: LangPython, Severity: SeverityMajor, Enabled: true, Category: "error-handling"},
		{ID: "PY003", Name: "eval-used", Description: "Use of eval is dangerous", Language: LangPython, Severity: SeverityCritical, Enabled: true, Category: "security"},
		{ID: "JS001", Name: "no-console", Description: "Console.log used in production code", Language: LangJavaScript, Severity: SeverityMinor, Enabled: true, Category: "style"},
		{ID: "JS002", Name: "no-eval", Description: "Use of eval is dangerous", Language: LangJavaScript, Severity: SeverityCritical, Enabled: true, Category: "security"},
		{ID: "JAVA001", Name: "empty-catch", Description: "Empty catch block", Language: LangJava, Severity: SeverityMajor, Enabled: true, Category: "error-handling"},
		{ID: "RUST001", Name: "unwrap-used", Description: "Use of unwrap() in production code", Language: LangRust, Severity: SeverityMajor, Enabled: true, Category: "error-handling"},
	}

	for _, rule := range defaultRules {
		qg.rules[rule.ID] = rule
	}
}

func (qg *QualityGate) AddRule(rule Rule) {
	qg.mu.Lock()
	defer qg.mu.Unlock()
	qg.rules[rule.ID] = rule
	logger.Info("Rule added", logger.String("rule_id", rule.ID), logger.String("name", rule.Name))
}

func (qg *QualityGate) RemoveRule(ruleID string) {
	qg.mu.Lock()
	defer qg.mu.Unlock()
	delete(qg.rules, ruleID)
	logger.Info("Rule removed", logger.String("rule_id", ruleID))
}

func (qg *QualityGate) EnableRule(ruleID string) error {
	qg.mu.Lock()
	defer qg.mu.Unlock()

	rule, ok := qg.rules[ruleID]
	if !ok {
		return fmt.Errorf("rule not found: %s", ruleID)
	}
	rule.Enabled = true
	qg.rules[ruleID] = rule
	return nil
}

func (qg *QualityGate) DisableRule(ruleID string) error {
	qg.mu.Lock()
	defer qg.mu.Unlock()

	rule, ok := qg.rules[ruleID]
	if !ok {
		return fmt.Errorf("rule not found: %s", ruleID)
	}
	rule.Enabled = false
	qg.rules[ruleID] = rule
	return nil
}

func (qg *QualityGate) GetRules(language Language) []Rule {
	qg.mu.RLock()
	defer qg.mu.RUnlock()

	result := make([]Rule, 0)
	for _, rule := range qg.rules {
		if language == "" || rule.Language == language {
			result = append(result, rule)
		}
	}
	return result
}

func (qg *QualityGate) Analyze(projectID string, language Language, sourceFiles map[string]string) (*AnalysisReport, error) {
	startTime := time.Now()

	if sourceFiles == nil {
		return nil, fmt.Errorf("source files cannot be nil")
	}

	if len(sourceFiles) > MaxFilesPerAnalysis {
		return nil, fmt.Errorf("too many files: %d (max %d)", len(sourceFiles), MaxFilesPerAnalysis)
	}

	totalSize := 0
	for file, content := range sourceFiles {
		if len(content) > MaxFileSize {
			return nil, fmt.Errorf("file %s exceeds maximum size: %d bytes (max %d)", file, len(content), MaxFileSize)
		}
		totalSize += len(content)
		if totalSize > MaxTotalFileSize {
			return nil, fmt.Errorf("total file size exceeds maximum: %d bytes (max %d)", totalSize, MaxTotalFileSize)
		}
	}

	logger.Info("Starting code analysis", logger.String("project_id", projectID), logger.String("language", string(language)))

	enabledRules := qg.GetRules(language)
	issues := make([]Issue, 0, len(sourceFiles)*5)

	for file, content := range sourceFiles {
		fileIssues := qg.analyzeFile(file, content, language, enabledRules)
		issues = append(issues, fileIssues...)
	}

	report := qg.generateReport(projectID, language, issues, startTime)

	logger.Info("Code analysis completed",
		logger.String("project_id", projectID),
		logger.Int("total_issues", report.TotalIssues),
		logger.Bool("passed", report.Passed),
		logger.Float64("score", report.Score),
	)

	return report, nil
}

func (qg *QualityGate) analyzeFile(file string, content string, language Language, rules []Rule) []Issue {
	issues := make([]Issue, 0)

	lines := splitLines(content)

	for _, rule := range rules {
		if !rule.Enabled {
			continue
		}

		ruleIssues := qg.applyRule(rule, file, lines)
		issues = append(issues, ruleIssues...)
	}

	return issues
}

func (qg *QualityGate) applyRule(rule Rule, file string, lines []string) []Issue {
	issues := make([]Issue, 0)

	qg.mu.RLock()
	detector, ok := qg.detectors[rule.Name]
	qg.mu.RUnlock()

	if !ok {
		return issues
	}

	for lineNum, line := range lines {
		if detector(line) {
			issues = append(issues, Issue{
				RuleID:     rule.ID,
				Message:    rule.Description,
				File:       file,
				Line:       lineNum + 1,
				Column:     1,
				Severity:   rule.Severity,
				Confidence: 0.85,
			})
		}
	}

	return issues
}

func (qg *QualityGate) generateReport(projectID string, language Language, issues []Issue, startTime time.Time) *AnalysisReport {
	critical, major, minor, info := 0, 0, 0, 0

	for _, issue := range issues {
		switch issue.Severity {
		case SeverityCritical:
			critical++
		case SeverityMajor:
			major++
		case SeverityMinor:
			minor++
		case SeverityInfo:
			info++
		}
	}

	qg.mu.RLock()
	maxCritical := qg.thresholds[SeverityCritical]
	maxMajor := qg.thresholds[SeverityMajor]
	maxMinor := qg.thresholds[SeverityMinor]
	qg.mu.RUnlock()

	score := qg.calculateScore(critical, major, minor, info)

	passed := critical <= maxCritical && major <= maxMajor && minor <= maxMinor

	return &AnalysisReport{
		ReportID:    fmt.Sprintf("report_%d", time.Now().UnixNano()),
		ProjectID:   projectID,
		Language:    language,
		AnalyzedAt:  time.Now(),
		DurationMs:  time.Since(startTime).Milliseconds(),
		Issues:      issues,
		TotalIssues: len(issues),
		Critical:    critical,
		Major:       major,
		Minor:       minor,
		Info:        info,
		Passed:      passed,
		Score:       score,
	}
}

func (qg *QualityGate) calculateScore(critical, major, minor, info int) float64 {
	total := float64(critical*100 + major*50 + minor*10 + info*1)
	maxPossible := 1000.0
	score := 100.0 - (total / maxPossible * 100.0)
	if score < 0 {
		score = 0
	}
	return score
}

func (qg *QualityGate) SetThreshold(severity Severity, max int) {
	qg.mu.Lock()
	defer qg.mu.Unlock()
	qg.thresholds[severity] = max
	logger.Info("Threshold updated", logger.String("severity", string(severity)), logger.Int("max", max))
}

func (qg *QualityGate) GetThresholds() map[Severity]int {
	qg.mu.RLock()
	defer qg.mu.RUnlock()
	thresholds := make(map[Severity]int)
	for k, v := range qg.thresholds {
		thresholds[k] = v
	}
	return thresholds
}

func splitLines(content string) []string {
	lines := make([]string, 0)
	current := ""
	for _, c := range content {
		if c == '\n' {
			lines = append(lines, current)
			current = ""
		} else {
			current += string(c)
		}
	}
	if current != "" {
		lines = append(lines, current)
	}
	return lines
}

func containsPattern(line, pattern string) bool {
	return len(line) >= len(pattern) && indexOf(line, pattern) >= 0
}

func indexOf(s, substr string) int {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return i
		}
	}
	return -1
}
