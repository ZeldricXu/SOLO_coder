package testutils

import (
	"depguard/modules/docindex"
	"depguard/modules/featureflags"
	"depguard/modules/qualitygate"
	"depguard/utils"
	"time"
)

type DocIndexBuilder struct {
	doc *docindex.Document
}

func NewDocBuilder() *DocIndexBuilder {
	now := time.Now()
	return &DocIndexBuilder{
		doc: &docindex.Document{
			ID:      utils.GenerateID("doc"),
			Title:   "Test Document",
			Content: "This is test content with search keyword",
			Source:  "wiki",
			Tags:    []string{"test", "document"},
			Permissions: docindex.DocPermissions{
				Public:  true,
				OwnerID: "user-1",
			},
			CreatedAt: now,
			UpdatedAt: now,
		},
	}
}

func (b *DocIndexBuilder) WithTitle(title string) *DocIndexBuilder {
	b.doc.Title = title
	return b
}

func (b *DocIndexBuilder) WithContent(content string) *DocIndexBuilder {
	b.doc.Content = content
	return b
}

func (b *DocIndexBuilder) WithSource(source string) *DocIndexBuilder {
	b.doc.Source = source
	return b
}

func (b *DocIndexBuilder) WithTags(tags []string) *DocIndexBuilder {
	b.doc.Tags = tags
	return b
}

func (b *DocIndexBuilder) AsPrivate(ownerID string) *DocIndexBuilder {
	b.doc.Permissions.Public = false
	b.doc.Permissions.OwnerID = ownerID
	return b
}

func (b *DocIndexBuilder) WithReadUsers(users []string) *DocIndexBuilder {
	b.doc.Permissions.ReadUsers = users
	return b
}

func (b *DocIndexBuilder) WithReadRoles(roles []string) *DocIndexBuilder {
	b.doc.Permissions.ReadRoles = roles
	return b
}

func (b *DocIndexBuilder) Build() *docindex.Document {
	return b.doc
}

type SourceBuilder struct {
	src *docindex.DocumentSource
}

func NewSourceBuilder() *SourceBuilder {
	now := time.Now()
	return &SourceBuilder{
		src: &docindex.DocumentSource{
			ID:      utils.GenerateID("src"),
			Name:    "Test Source",
			Type:    "wiki",
			URL:     "https://wiki.example.com",
			Enabled: true,
			Config:  map[string]interface{}{"auth": "token"},
			CreatedAt: now,
			UpdatedAt: now,
		},
	}
}

func (b *SourceBuilder) WithName(name string) *SourceBuilder {
	b.src.Name = name
	return b
}

func (b *SourceBuilder) WithType(srcType string) *SourceBuilder {
	b.src.Type = srcType
	return b
}

func (b *SourceBuilder) Disabled() *SourceBuilder {
	b.src.Enabled = false
	return b
}

func (b *SourceBuilder) Build() *docindex.DocumentSource {
	return b.src
}

type SearchQueryBuilder struct {
	q *docindex.SearchQuery
}

func NewSearchQueryBuilder() *SearchQueryBuilder {
	return &SearchQueryBuilder{
		q: &docindex.SearchQuery{
			Query: "",
			Page:  0,
			Size:  10,
		},
	}
}

func (b *SearchQueryBuilder) WithQuery(query string) *SearchQueryBuilder {
	b.q.Query = query
	return b
}

func (b *SearchQueryBuilder) WithSource(source string) *SearchQueryBuilder {
	b.q.Source = source
	return b
}

func (b *SearchQueryBuilder) WithTags(tags []string) *SearchQueryBuilder {
	b.q.Tags = tags
	return b
}

func (b *SearchQueryBuilder) WithUser(userID string) *SearchQueryBuilder {
	b.q.UserID = userID
	return b
}

func (b *SearchQueryBuilder) WithRoles(roles []string) *SearchQueryBuilder {
	b.q.Roles = roles
	return b
}

func (b *SearchQueryBuilder) WithPagination(page, size int) *SearchQueryBuilder {
	b.q.Page = page
	b.q.Size = size
	return b
}

func (b *SearchQueryBuilder) Build() *docindex.SearchQuery {
	return b.q
}

type AnalysisRuleBuilder struct {
	rule *qualitygate.AnalysisRule
}

func NewRuleBuilder() *AnalysisRuleBuilder {
	now := time.Now()
	return &AnalysisRuleBuilder{
		rule: &qualitygate.AnalysisRule{
			ID:          utils.GenerateID("rule"),
			Language:    "go",
			Key:         "GO999",
			Name:        "Custom Rule",
			Description: "Custom analysis rule",
			Severity:    "minor",
			Category:    "style",
			Default:     false,
			Enabled:     true,
			CreatedAt:   now,
			UpdatedAt:   now,
		},
	}
}

func (b *AnalysisRuleBuilder) WithLanguage(lang string) *AnalysisRuleBuilder {
	b.rule.Language = lang
	return b
}

func (b *AnalysisRuleBuilder) WithKey(key string) *AnalysisRuleBuilder {
	b.rule.Key = key
	return b
}

func (b *AnalysisRuleBuilder) WithSeverity(sev string) *AnalysisRuleBuilder {
	b.rule.Severity = sev
	return b
}

func (b *AnalysisRuleBuilder) Disabled() *AnalysisRuleBuilder {
	b.rule.Enabled = false
	return b
}

func (b *AnalysisRuleBuilder) WithConfig(cfg map[string]interface{}) *AnalysisRuleBuilder {
	b.rule.Config = cfg
	return b
}

func (b *AnalysisRuleBuilder) Build() *qualitygate.AnalysisRule {
	return b.rule
}

type AnalyzeRequestBuilder struct {
	req *qualitygate.AnalyzeRequest
}

func NewAnalyzeRequestBuilder() *AnalyzeRequestBuilder {
	return &AnalyzeRequestBuilder{
		req: &qualitygate.AnalyzeRequest{
			ProjectID: "proj-1",
			Commit:    "abc123",
			Branch:    "main",
			Language:  "go",
			Rules:     []string{},
			Code:      map[string]string{},
		},
	}
}

func (b *AnalyzeRequestBuilder) WithProject(id string) *AnalyzeRequestBuilder {
	b.req.ProjectID = id
	return b
}

func (b *AnalyzeRequestBuilder) WithLanguage(lang string) *AnalyzeRequestBuilder {
	b.req.Language = lang
	return b
}

func (b *AnalyzeRequestBuilder) WithRules(rules []string) *AnalyzeRequestBuilder {
	b.req.Rules = rules
	return b
}

func (b *AnalyzeRequestBuilder) WithFile(path, content string) *AnalyzeRequestBuilder {
	b.req.Code[path] = content
	return b
}

func (b *AnalyzeRequestBuilder) Build() *qualitygate.AnalyzeRequest {
	return b.req
}

type QualityGateBuilder struct {
	gate *qualitygate.QualityGate
}

func NewQualityGateBuilder() *QualityGateBuilder {
	now := time.Now()
	return &QualityGateBuilder{
		gate: &qualitygate.QualityGate{
			ID:         utils.GenerateID("gate"),
			Name:       "Test Gate",
			IsDefault:  false,
			ProjectIDs: []string{},
			Conditions: []qualitygate.GateCondition{},
			CreatedAt:  now,
			UpdatedAt:  now,
		},
	}
}

func (b *QualityGateBuilder) WithName(name string) *QualityGateBuilder {
	b.gate.Name = name
	return b
}

func (b *QualityGateBuilder) AsDefault() *QualityGateBuilder {
	b.gate.IsDefault = true
	return b
}

func (b *QualityGateBuilder) WithCondition(metric string, threshold float64, operator string) *QualityGateBuilder {
	b.gate.Conditions = append(b.gate.Conditions, qualitygate.GateCondition{
		Metric:    metric,
		Threshold: threshold,
		Operator:  operator,
	})
	return b
}

func (b *QualityGateBuilder) Build() *qualitygate.QualityGate {
	return b.gate
}

type FeatureFlagBuilder struct {
	flag *featureflags.FeatureFlag
}

func NewFlagBuilder() *FeatureFlagBuilder {
	now := time.Now()
	return &FeatureFlagBuilder{
		flag: &featureflags.FeatureFlag{
			ID:          utils.GenerateID("ff"),
			Key:         "new-feature",
			Name:        "New Feature",
			Description: "Test feature flag",
			Enabled:     true,
			Rules:       []featureflags.RolloutRule{},
			CreatedAt:   now,
			UpdatedAt:   now,
		},
	}
}

func (b *FeatureFlagBuilder) WithKey(key string) *FeatureFlagBuilder {
	b.flag.Key = key
	return b
}

func (b *FeatureFlagBuilder) Disabled() *FeatureFlagBuilder {
	b.flag.Enabled = false
	return b
}

func (b *FeatureFlagBuilder) WithPercentageRule(percentage float64, priority int) *FeatureFlagBuilder {
	now := time.Now()
	b.flag.Rules = append(b.flag.Rules, featureflags.RolloutRule{
		ID:         utils.GenerateID("rule"),
		Name:       "Percentage Rollout",
		Type:       "percentage",
		Percentage: percentage,
		Priority:   priority,
		Enabled:    true,
		StartAt:    &now,
		Value:      true,
	})
	return b
}

func (b *FeatureFlagBuilder) WithUserRule(users []string, priority int) *FeatureFlagBuilder {
	now := time.Now()
	b.flag.Rules = append(b.flag.Rules, featureflags.RolloutRule{
		ID:       utils.GenerateID("rule"),
		Name:     "User Allowlist",
		Type:     "users",
		Users:    users,
		Priority: priority,
		Enabled:  true,
		StartAt:  &now,
		Value:    true,
	})
	return b
}

func (b *FeatureFlagBuilder) WithConditionRule(attr string, op string, values []interface{}, priority int) *FeatureFlagBuilder {
	now := time.Now()
	b.flag.Rules = append(b.flag.Rules, featureflags.RolloutRule{
		ID:       utils.GenerateID("rule"),
		Name:     "Conditional Rule",
		Type:     "conditional",
		Priority: priority,
		Enabled:  true,
		StartAt:  &now,
		Value:    true,
		Conditions: []featureflags.RuleCondition{
			{Attribute: attr, Operator: op, Values: values},
		},
	})
	return b
}

func (b *FeatureFlagBuilder) Build() *featureflags.FeatureFlag {
	return b.flag
}

type EvaluationContextBuilder struct {
	ctx *featureflags.EvaluationContext
}

func NewEvalContextBuilder() *EvaluationContextBuilder {
	return &EvaluationContextBuilder{
		ctx: &featureflags.EvaluationContext{
			UserID:     "user-1",
			Segments:   []string{},
			Attributes: map[string]interface{}{},
		},
	}
}

func (b *EvaluationContextBuilder) WithUser(id string) *EvaluationContextBuilder {
	b.ctx.UserID = id
	return b
}

func (b *EvaluationContextBuilder) WithSegments(segs []string) *EvaluationContextBuilder {
	b.ctx.Segments = segs
	return b
}

func (b *EvaluationContextBuilder) WithAttr(key string, value interface{}) *EvaluationContextBuilder {
	b.ctx.Attributes[key] = value
	return b
}

func (b *EvaluationContextBuilder) Build() *featureflags.EvaluationContext {
	return b.ctx
}

type UserSegmentBuilder struct {
	seg *featureflags.UserSegment
}

func NewSegmentBuilder() *UserSegmentBuilder {
	now := time.Now()
	return &UserSegmentBuilder{
		seg: &featureflags.UserSegment{
			ID:          utils.GenerateID("seg"),
			Name:        "Test Segment",
			Description: "Test user segment",
			UserIDs:     []string{},
			Rules:       []featureflags.SegmentRule{},
			CreatedAt:   now,
			UpdatedAt:   now,
		},
	}
}

func (b *UserSegmentBuilder) WithName(name string) *UserSegmentBuilder {
	b.seg.Name = name
	return b
}

func (b *UserSegmentBuilder) WithUsers(users []string) *UserSegmentBuilder {
	b.seg.UserIDs = users
	return b
}

func (b *UserSegmentBuilder) WithRule(attr string, op string, values []interface{}) *UserSegmentBuilder {
	b.seg.Rules = append(b.seg.Rules, featureflags.SegmentRule{
		Attribute: attr,
		Operator:  op,
		Values:    values,
	})
	return b
}

func (b *UserSegmentBuilder) Build() *featureflags.UserSegment {
	return b.seg
}
