package codequality

import (
	"fmt"
	"regexp"
	"sync"
	"time"

	"taskflow/pkg/models"
)

type StaticAnalyzer interface {
	Analyze(code string, filename string) []models.AnalysisIssue
	AddRule(rule models.AnalysisRule)
	RemoveRule(ruleID string)
	EnableRule(ruleID string)
	DisableRule(ruleID string)
	GetRules() []models.AnalysisRule
	GetLanguage() models.Language
}

type compiledRule struct {
	models.AnalysisRule
	regex *regexp.Regexp
}

type baseAnalyzer struct {
	language      models.Language
	rules         []compiledRule
	ruleIndex     map[string]int
	mu            sync.RWMutex
}

func newBaseAnalyzer(language models.Language) baseAnalyzer {
	return baseAnalyzer{
		language:  language,
		rules:     make([]compiledRule, 0),
		ruleIndex: make(map[string]int),
	}
}

func (a *baseAnalyzer) GetLanguage() models.Language {
	return a.language
}

func (a *baseAnalyzer) AddRule(rule models.AnalysisRule) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.addRuleLocked(rule)
}

func (a *baseAnalyzer) addRuleLocked(rule models.AnalysisRule) {
	cr := compiledRule{AnalysisRule: rule}
	if rule.Pattern != "" {
		if re, err := regexp.Compile(rule.Pattern); err == nil {
			cr.regex = re
		}
	}
	a.ruleIndex[rule.RuleID] = len(a.rules)
	a.rules = append(a.rules, cr)
}

func (a *baseAnalyzer) RemoveRule(ruleID string) {
	a.mu.Lock()
	defer a.mu.Unlock()

	idx, exists := a.ruleIndex[ruleID]
	if !exists {
		return
	}

	a.rules = append(a.rules[:idx], a.rules[idx+1:]...)
	delete(a.ruleIndex, ruleID)

	for i := idx; i < len(a.rules); i++ {
		a.ruleIndex[a.rules[i].RuleID] = i
	}
}

func (a *baseAnalyzer) EnableRule(ruleID string) {
	a.mu.Lock()
	defer a.mu.Unlock()

	if idx, exists := a.ruleIndex[ruleID]; exists {
		a.rules[idx].Enabled = true
	}
}

func (a *baseAnalyzer) DisableRule(ruleID string) {
	a.mu.Lock()
	defer a.mu.Unlock()

	if idx, exists := a.ruleIndex[ruleID]; exists {
		a.rules[idx].Enabled = false
	}
}

func (a *baseAnalyzer) GetRules() []models.AnalysisRule {
	a.mu.RLock()
	defer a.mu.RUnlock()

	rules := make([]models.AnalysisRule, len(a.rules))
	for i, cr := range a.rules {
		rules[i] = cr.AnalysisRule
	}
	return rules
}

func (a *baseAnalyzer) Analyze(code string, filename string) []models.AnalysisIssue {
	a.mu.RLock()
	rules := make([]compiledRule, len(a.rules))
	copy(rules, a.rules)
	a.mu.RUnlock()

	var issues []models.AnalysisIssue
	lineOffsets := computeLineOffsets(code)

	for _, cr := range rules {
		if !cr.Enabled || cr.regex == nil {
			continue
		}

		matches := cr.regex.FindAllStringIndex(code, -1)
		for _, match := range matches {
			issue := createIssue(cr.AnalysisRule, code, lineOffsets, match)
			issues = append(issues, issue)
		}
	}

	return issues
}

func computeLineOffsets(code string) []int {
	offsets := []int{0}
	for i := 0; i < len(code); i++ {
		if code[i] == '\n' {
			offsets = append(offsets, i+1)
		}
	}
	return offsets
}

func getLineAndColumn(offset int, lineOffsets []int) (line int, col int) {
	left, right := 0, len(lineOffsets)-1
	for left < right {
		mid := (left + right + 1) / 2
		if lineOffsets[mid] <= offset {
			left = mid
		} else {
			right = mid - 1
		}
	}
	return left + 1, offset - lineOffsets[left]
}

func getLineContent(code string, lineOffsets []int, lineIdx int) string {
	if lineIdx < 0 || lineIdx >= len(lineOffsets) {
		return ""
	}
	start := lineOffsets[lineIdx]
	end := len(code)
	if lineIdx+1 < len(lineOffsets) {
		end = lineOffsets[lineIdx+1] - 1
	}
	return trimWhitespace(code[start:end])
}

func createIssue(rule models.AnalysisRule, code string, lineOffsets []int, match []int) models.AnalysisIssue {
	lineNum, colNum := getLineAndColumn(match[0], lineOffsets)
	snippet := getLineContent(code, lineOffsets, lineNum-1)

	return models.AnalysisIssue{
		RuleID:   rule.RuleID,
		Line:     lineNum,
		Column:   colNum,
		Message:  rule.Description,
		Severity: rule.Severity,
		Snippet:  snippet,
	}
}

func trimWhitespace(s string) string {
	start := 0
	for start < len(s) && (s[start] == ' ' || s[start] == '\t') {
		start++
	}
	end := len(s)
	for end > start && (s[end-1] == ' ' || s[end-1] == '\t' || s[end-1] == '\r') {
		end--
	}
	return s[start:end]
}

type PythonAnalyzer struct {
	baseAnalyzer
}

func NewPythonAnalyzer() *PythonAnalyzer {
	a := &PythonAnalyzer{
		baseAnalyzer: newBaseAnalyzer(models.LanguagePython),
	}
	a.loadDefaultRules()
	return a
}

func (a *PythonAnalyzer) loadDefaultRules() {
	rules := []models.AnalysisRule{
		{
			RuleID:      "PY001",
			Name:        "PrintStatement",
			Description: "Avoid using print() in production code, use logging instead",
			Language:    models.LanguagePython,
			Severity:    models.SeverityWarning,
			Pattern:     `^\s*print\s*\(`,
			Enabled:     true,
		},
		{
			RuleID:      "PY002",
			Name:        "TodoComment",
			Description: "TODO comment found, needs attention",
			Language:    models.LanguagePython,
			Severity:    models.SeverityInfo,
			Pattern:     `#.*(TODO|FIXME)`,
			Enabled:     true,
		},
		{
			RuleID:      "PY003",
			Name:        "LongLine",
			Description: "Line exceeds 120 characters",
			Language:    models.LanguagePython,
			Severity:    models.SeverityWarning,
			Pattern:     `^.{121,}$`,
			Enabled:     true,
		},
		{
			RuleID:      "PY004",
			Name:        "DebugImport",
			Description: "Debug import found (pdb, debugpy)",
			Language:    models.LanguagePython,
			Severity:    models.SeverityError,
			Pattern:     `import\s+(pdb|debugpy)`,
			Enabled:     true,
		},
		{
			RuleID:      "PY005",
			Name:        "HardcodedSecret",
			Description: "Potential hardcoded password or secret",
			Language:    models.LanguagePython,
			Severity:    models.SeverityCritical,
			Pattern:     `(password|secret|api_key|token)\s*=\s*["'][^"']+["']`,
			Enabled:     true,
		},
	}

	a.mu.Lock()
	defer a.mu.Unlock()
	for _, r := range rules {
		a.addRuleLocked(r)
	}
}

type JavaScriptAnalyzer struct {
	baseAnalyzer
}

func NewJavaScriptAnalyzer() *JavaScriptAnalyzer {
	a := &JavaScriptAnalyzer{
		baseAnalyzer: newBaseAnalyzer(models.LanguageJavaScript),
	}
	a.loadDefaultRules()
	return a
}

func (a *JavaScriptAnalyzer) loadDefaultRules() {
	rules := []models.AnalysisRule{
		{
			RuleID:      "JS001",
			Name:        "ConsoleLog",
			Description: "Avoid using console.log() in production code",
			Language:    models.LanguageJavaScript,
			Severity:    models.SeverityWarning,
			Pattern:     `console\s*\.\s*log\s*\(`,
			Enabled:     true,
		},
		{
			RuleID:      "JS002",
			Name:        "EvalUsage",
			Description: "Avoid using eval() - security risk",
			Language:    models.LanguageJavaScript,
			Severity:    models.SeverityCritical,
			Pattern:     `\beval\s*\(`,
			Enabled:     true,
		},
		{
			RuleID:      "JS003",
			Name:        "VarDeclaration",
			Description: "Use const/let instead of var",
			Language:    models.LanguageJavaScript,
			Severity:    models.SeverityWarning,
			Pattern:     `\bvar\s+\w+`,
			Enabled:     true,
		},
	}

	a.mu.Lock()
	defer a.mu.Unlock()
	for _, r := range rules {
		a.addRuleLocked(r)
	}
}

type GoAnalyzer struct {
	baseAnalyzer
}

func NewGoAnalyzer() *GoAnalyzer {
	a := &GoAnalyzer{
		baseAnalyzer: newBaseAnalyzer(models.LanguageGo),
	}
	a.loadDefaultRules()
	return a
}

func (a *GoAnalyzer) loadDefaultRules() {
	rules := []models.AnalysisRule{
		{
			RuleID:      "GO001",
			Name:        "PrintInCode",
			Description: "Avoid fmt.Print in production code",
			Language:    models.LanguageGo,
			Severity:    models.SeverityWarning,
			Pattern:     `fmt\.Print`,
			Enabled:     true,
		},
	}

	a.mu.Lock()
	defer a.mu.Unlock()
	for _, r := range rules {
		a.addRuleLocked(r)
	}
}

type AnalyzerFactory struct {
	analyzers map[models.Language]StaticAnalyzer
	mu        sync.RWMutex
}

var (
	factoryInstance *AnalyzerFactory
	factoryOnce     sync.Once
)

func GetAnalyzerFactory() *AnalyzerFactory {
	factoryOnce.Do(func() {
		factoryInstance = &AnalyzerFactory{
			analyzers: make(map[models.Language]StaticAnalyzer),
		}
	})
	return factoryInstance
}

func (f *AnalyzerFactory) GetAnalyzer(language models.Language) (StaticAnalyzer, error) {
	f.mu.RLock()
	analyzer, exists := f.analyzers[language]
	f.mu.RUnlock()

	if exists {
		return analyzer, nil
	}

	f.mu.Lock()
	defer f.mu.Unlock()

	if analyzer, exists := f.analyzers[language]; exists {
		return analyzer, nil
	}

	newAnalyzer, err := f.createAnalyzer(language)
	if err != nil {
		return nil, err
	}

	f.analyzers[language] = newAnalyzer
	return newAnalyzer, nil
}

func (f *AnalyzerFactory) createAnalyzer(language models.Language) (StaticAnalyzer, error) {
	switch language {
	case models.LanguagePython:
		return NewPythonAnalyzer(), nil
	case models.LanguageJavaScript, models.LanguageTypeScript:
		return NewJavaScriptAnalyzer(), nil
	case models.LanguageGo:
		return NewGoAnalyzer(), nil
	default:
		return nil, fmt.Errorf("no analyzer available for language: %s", language)
	}
}

var severityWeights = map[models.Severity]float64{
	models.SeverityCritical: 30,
	models.SeverityError:    15,
	models.SeverityWarning:  5,
	models.SeverityInfo:     1,
}

type QualityGate struct {
	thresholds models.QualityThreshold
	mu         sync.RWMutex
}

func NewQualityGate() *QualityGate {
	return &QualityGate{
		thresholds: models.QualityThreshold{
			Critical:     0,
			Error:        3,
			Warning:      10,
			QualityScore: 70.0,
		},
	}
}

func (g *QualityGate) SetThresholds(thresholds models.QualityThreshold) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.thresholds = thresholds
}

func (g *QualityGate) GetThresholds() models.QualityThreshold {
	g.mu.RLock()
	defer g.mu.RUnlock()
	return g.thresholds
}

func (g *QualityGate) Evaluate(report *models.AnalysisReport) {
	g.mu.RLock()
	thresholds := g.thresholds
	g.mu.RUnlock()

	report.QualityScore = calculateQualityScore(report)

	criticalCount := report.IssuesBySeverity[models.SeverityCritical]
	errorCount := report.IssuesBySeverity[models.SeverityError]
	warningCount := report.IssuesBySeverity[models.SeverityWarning]

	report.ThresholdPass = criticalCount <= thresholds.Critical &&
		errorCount <= thresholds.Error &&
		warningCount <= thresholds.Warning &&
		report.QualityScore >= thresholds.QualityScore
}

func calculateQualityScore(report *models.AnalysisReport) float64 {
	if report.TotalFiles == 0 {
		return 100.0
	}

	penalty := 0.0
	for severity, weight := range severityWeights {
		penalty += float64(report.IssuesBySeverity[severity]) * weight
	}

	maxPenalty := float64(report.TotalFiles) * 50.0
	normalized := 0.0
	if maxPenalty > 0 {
		normalized = penalty / maxPenalty
		if normalized > 1.0 {
			normalized = 1.0
		}
	}

	return 100.0 * (1.0 - normalized)
}

type CodeQualityService struct {
	qualityGate *QualityGate
	factory     *AnalyzerFactory
}

var (
	serviceInstance *CodeQualityService
	serviceOnce     sync.Once
)

func GetCodeQualityService() *CodeQualityService {
	serviceOnce.Do(func() {
		serviceInstance = &CodeQualityService{
			qualityGate: NewQualityGate(),
			factory:     GetAnalyzerFactory(),
		}
	})
	return serviceInstance
}

func (s *CodeQualityService) AnalyzeCode(code string, language models.Language, filename string) (*models.AnalysisReport, error) {
	analyzer, err := s.factory.GetAnalyzer(language)
	if err != nil {
		return nil, err
	}

	issues := analyzer.Analyze(code, filename)

	issuesBySeverity := make(map[models.Severity]int, 4)
	for _, issue := range issues {
		issuesBySeverity[issue.Severity]++
	}

	report := &models.AnalysisReport{
		ReportID:         generateReportID(),
		Language:         language,
		TotalFiles:       1,
		TotalIssues:      len(issues),
		IssuesBySeverity: issuesBySeverity,
		Issues:           issues,
		CreatedAt:        time.Now(),
	}

	s.qualityGate.Evaluate(report)
	return report, nil
}

func (s *CodeQualityService) GetRules(language models.Language) ([]models.AnalysisRule, error) {
	analyzer, err := s.factory.GetAnalyzer(language)
	if err != nil {
		return nil, err
	}
	return analyzer.GetRules(), nil
}

func (s *CodeQualityService) AddRule(language models.Language, rule models.AnalysisRule) error {
	analyzer, err := s.factory.GetAnalyzer(language)
	if err != nil {
		return err
	}
	analyzer.AddRule(rule)
	return nil
}

func (s *CodeQualityService) RemoveRule(language models.Language, ruleID string) error {
	analyzer, err := s.factory.GetAnalyzer(language)
	if err != nil {
		return err
	}
	analyzer.RemoveRule(ruleID)
	return nil
}

func (s *CodeQualityService) UpdateThresholds(thresholds models.QualityThreshold) {
	s.qualityGate.SetThresholds(thresholds)
}

func (s *CodeQualityService) GetThresholds() models.QualityThreshold {
	return s.qualityGate.GetThresholds()
}

func generateReportID() string {
	return fmt.Sprintf("report_%s", time.Now().Format("20060102150405"))
}
