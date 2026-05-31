package doccompare

import (
	"strings"
	"time"

	"gorm.io/gorm"
	"session187/internal/common"
	"session187/pkg/errors"
)

type DiffType string

const (
	DiffTypeAdd     DiffType = "add"
	DiffTypeDelete  DiffType = "delete"
	DiffTypeModify  DiffType = "modify"
)

type DiffChunk struct {
	OldText     string   `json:"old_text"`
	NewText     string   `json:"new_text"`
	OldStartLine int     `json:"old_start_line"`
	OldEndLine   int     `json:"old_end_line"`
	NewStartLine int     `json:"new_start_line"`
	NewEndLine   int     `json:"new_end_line"`
	Type        DiffType `json:"type"`
	Confidence  float64  `json:"confidence"`
}

type HighlightClause struct {
	ID          string    `json:"id"`
	Text        string    `json:"text"`
	Category    string    `json:"category"`
	Importance  string    `json:"importance"`
	Description string    `json:"description"`
}

type ChangeSummary struct {
	TotalChanges  int            `json:"total_changes"`
	Additions     int            `json:"additions"`
	Deletions     int            `json:"deletions"`
	Modifications int            `json:"modifications"`
	KeyClauses    []string       `json:"key_clauses"`
	Risks         []string       `json:"risks"`
	Summary       string         `json:"summary"`
}

type CompareResult struct {
	ID              string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID        string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	OldDocumentID   string                 `json:"old_document_id" gorm:"type:varchar(64)"`
	NewDocumentID   string                 `json:"new_document_id" gorm:"type:varchar(64)"`
	OldVersion      string                 `json:"old_version"`
	NewVersion      string                 `json:"new_version"`
	DiffChunks      []DiffChunk            `json:"diff_chunks" gorm:"type:jsonb;serializer:json"`
	Highlights      []HighlightClause      `json:"highlights" gorm:"type:jsonb;serializer:json"`
	Summary         ChangeSummary          `json:"summary" gorm:"type:jsonb;serializer:json"`
	Similarity      float64                `json:"similarity"`
	Status          string                 `json:"status" gorm:"type:varchar(32);index"`
	Metadata        map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
}

type Document struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID   string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	Title      string                 `json:"title" gorm:"type:varchar(256)"`
	Content    string                 `json:"content" gorm:"type:text"`
	Version    string                 `json:"version" gorm:"type:varchar(64)"`
	Category   string                 `json:"category" gorm:"type:varchar(64);index"`
	Tags       []string               `json:"tags" gorm:"type:jsonb;serializer:json"`
	Metadata   map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Comparer struct {
	db *gorm.DB
}

func NewComparer(db *gorm.DB) *Comparer {
	return &Comparer{db: db}
}

func (c *Comparer) SaveDocument(tenantID, title, content, version, category string, tags []string, metadata map[string]interface{}) (*Document, error) {
	if tags == nil {
		tags = []string{}
	}
	if metadata == nil {
		metadata = make(map[string]interface{})
	}
	doc := &Document{
		ID:        common.GenerateID("doc"),
		TenantID:  tenantID,
		Title:     title,
		Content:   content,
		Version:   version,
		Category:  category,
		Tags:      tags,
		Metadata:  metadata,
		CreatedAt: common.TimeNowUTC(),
		UpdatedAt: common.TimeNowUTC(),
	}
	if err := c.db.Create(doc).Error; err != nil {
		return nil, errors.NewWithDetail(500, "保存文档失败", err.Error())
	}
	return doc, nil
}

func (c *Comparer) GetDocument(tenantID, docID string) (*Document, error) {
	var doc Document
	err := c.db.Where("id = ? AND tenant_id = ?", docID, tenantID).First(&doc).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询文档失败", err.Error())
	}
	return &doc, nil
}

func (c *Comparer) Compare(tenantID, oldDocID, newDocID string) (*CompareResult, error) {
	oldDoc, err := c.GetDocument(tenantID, oldDocID)
	if err != nil {
		return nil, err
	}
	newDoc, err := c.GetDocument(tenantID, newDocID)
	if err != nil {
		return nil, err
	}
	diffs := c.computeDiff(oldDoc.Content, newDoc.Content)
	highlights := c.extractKeyClauses(newDoc.Content)
	summary := c.generateSummary(diffs, highlights)
	similarity := c.calculateSimilarity(oldDoc.Content, newDoc.Content)
	result := &CompareResult{
		ID:            common.GenerateID("cmp"),
		TenantID:      tenantID,
		OldDocumentID: oldDocID,
		NewDocumentID: newDocID,
		OldVersion:    oldDoc.Version,
		NewVersion:    newDoc.Version,
		DiffChunks:    diffs,
		Highlights:    highlights,
		Summary:       summary,
		Similarity:    similarity,
		Status:        "completed",
		CreatedAt:     common.TimeNowUTC(),
		UpdatedAt:     common.TimeNowUTC(),
	}
	if err := c.db.Create(result).Error; err != nil {
		return nil, errors.NewWithDetail(500, "保存比对结果失败", err.Error())
	}
	return result, nil
}

func (c *Comparer) computeDiff(oldText, newText string) []DiffChunk {
	oldLines := strings.Split(oldText, "\n")
	newLines := strings.Split(newText, "\n")
	var diffs []DiffChunk
	lcs := c.longestCommonSubsequence(oldLines, newLines)
	i, j := 0, 0
	for _, line := range lcs {
		for i < len(oldLines) && oldLines[i] != line {
			diffs = append(diffs, DiffChunk{
				OldText:     oldLines[i],
				OldStartLine: i + 1,
				OldEndLine:   i + 1,
				Type:        DiffTypeDelete,
				Confidence:  0.9,
			})
			i++
		}
		for j < len(newLines) && newLines[j] != line {
			diffs = append(diffs, DiffChunk{
				NewText:     newLines[j],
				NewStartLine: j + 1,
				NewEndLine:   j + 1,
				Type:        DiffTypeAdd,
				Confidence:  0.9,
			})
			j++
		}
		i++
		j++
	}
	for i < len(oldLines) {
		diffs = append(diffs, DiffChunk{
			OldText:     oldLines[i],
			OldStartLine: i + 1,
			OldEndLine:   i + 1,
			Type:        DiffTypeDelete,
			Confidence:  0.9,
		})
		i++
	}
	for j < len(newLines) {
		diffs = append(diffs, DiffChunk{
			NewText:     newLines[j],
			NewStartLine: j + 1,
			NewEndLine:   j + 1,
			Type:        DiffTypeAdd,
			Confidence:  0.9,
		})
		j++
	}
	return diffs
}

func (c *Comparer) longestCommonSubsequence(a, b []string) []string {
	m, n := len(a), len(b)
	dp := make([][]int, m+1)
	for i := range dp {
		dp[i] = make([]int, n+1)
	}
	for i := 1; i <= m; i++ {
		for j := 1; j <= n; j++ {
			if a[i-1] == b[j-1] {
				dp[i][j] = dp[i-1][j-1] + 1
			} else {
				dp[i][j] = common.MaxInt(dp[i-1][j], dp[i][j-1])
			}
		}
	}
	var result []string
	i, j := m, n
	for i > 0 && j > 0 {
		if a[i-1] == b[j-1] {
			result = append(result, a[i-1])
			i--
			j--
		} else if dp[i-1][j] > dp[i][j-1] {
			i--
		} else {
			j--
		}
	}
	for left, right := 0, len(result)-1; left < right; left, right = left+1, right-1 {
		result[left], result[right] = result[right], result[left]
	}
	return result
}

func (c *Comparer) extractKeyClauses(content string) []HighlightClause {
	keyPatterns := map[string][]string{
		"保密条款": {"保密", "机密", "不披露", "NDA"},
		"违约责任": {"违约", "赔偿", "责任", "违约金"},
		"知识产权": {"知识产权", "专利", "版权", "著作权", "商标"},
		"付款条款": {"付款", "支付", "费用", "价格", "金额"},
		"终止条款": {"终止", "解除", "提前终止"},
		"争议解决": {"争议", "仲裁", "诉讼", "管辖"},
	}
	var highlights []HighlightClause
	lines := strings.Split(content, "\n")
	for _, line := range lines {
		for category, patterns := range keyPatterns {
			for _, pattern := range patterns {
				if strings.Contains(line, pattern) {
					highlights = append(highlights, HighlightClause{
						ID:          common.GenerateID("hcl"),
						Text:        line,
						Category:    category,
						Importance:  "high",
						Description: "检测到关键条款: " + category,
					})
					break
				}
			}
		}
	}
	return highlights
}

func (c *Comparer) generateSummary(diffs []DiffChunk, highlights []HighlightClause) ChangeSummary {
	additions, deletions, modifications := 0, 0, 0
	for _, d := range diffs {
		switch d.Type {
		case DiffTypeAdd:
			additions++
		case DiffTypeDelete:
			deletions++
		case DiffTypeModify:
			modifications++
		}
	}
	var keyClauses []string
	for _, h := range highlights {
		keyClauses = append(keyClauses, h.Category)
	}
	summaryText := ""
	if len(diffs) == 0 {
		summaryText = "文档内容无变化"
	} else {
		summaryText = "文档包含 " + string(rune(len(diffs))) + " 处变更"
	}
	return ChangeSummary{
		TotalChanges:  len(diffs),
		Additions:     additions,
		Deletions:     deletions,
		Modifications: modifications,
		KeyClauses:    keyClauses,
		Risks:         []string{},
		Summary:       summaryText,
	}
}

func (c *Comparer) calculateSimilarity(oldText, newText string) float64 {
	if oldText == newText {
		return 1.0
	}
	oldWords := strings.Fields(oldText)
	newWords := strings.Fields(newText)
	if len(oldWords) == 0 || len(newWords) == 0 {
		return 0.0
	}
	commonWords := make(map[string]bool)
	for _, w := range oldWords {
		commonWords[w] = true
	}
	matchCount := 0
	for _, w := range newWords {
		if commonWords[w] {
			matchCount++
		}
	}
	return float64(matchCount) / float64(common.MaxInt(len(oldWords), len(newWords)))
}

func (c *Comparer) GetCompareResult(tenantID, resultID string) (*CompareResult, error) {
	var result CompareResult
	err := c.db.Where("id = ? AND tenant_id = ?", resultID, tenantID).First(&result).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询比对结果失败", err.Error())
	}
	return &result, nil
}

func (c *Comparer) ListCompareResults(tenantID string, page, pageSize int) ([]CompareResult, int64, error) {
	var results []CompareResult
	var total int64
	offset := (page - 1) * pageSize
	c.db.Model(&CompareResult{}).Where("tenant_id = ?", tenantID).Count(&total)
	err := c.db.Where("tenant_id = ?", tenantID).Order("created_at desc").
		Offset(offset).Limit(pageSize).Find(&results).Error
	if err != nil {
		return nil, 0, errors.NewWithDetail(500, "查询比对结果列表失败", err.Error())
	}
	return results, total, nil
}

func (d *Document) TableName() string {
	return "documents"
}

func (r *CompareResult) TableName() string {
	return "compare_results"
}
