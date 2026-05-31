package docdiff

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"
)

type DiffType int

const (
	DiffEqual DiffType = iota
	DiffInsert
	DiffDelete
	DiffReplace
)

const (
	DefaultMaxDocLines    = 50000
	DefaultCompareTimeout = 30 * time.Second
)

type CompareOption func(*compareConfig)

type compareConfig struct {
	timeout    time.Duration
	maxLines   int
	ctx        context.Context
}

func WithTimeout(d time.Duration) CompareOption {
	return func(c *compareConfig) { c.timeout = d }
}

func WithMaxLines(n int) CompareOption {
	return func(c *compareConfig) { c.maxLines = n }
}

func WithContext(ctx context.Context) CompareOption {
	return func(c *compareConfig) { c.ctx = ctx }
}

type DiffSegment struct {
	Type     DiffType `json:"type"`
	OldStart int      `json:"old_start"`
	OldEnd   int      `json:"old_end"`
	NewStart int      `json:"new_start"`
	NewEnd   int      `json:"new_end"`
	OldText  string   `json:"old_text,omitempty"`
	NewText  string   `json:"new_text,omitempty"`
}

type ClauseHighlight struct {
	ClauseID    string `json:"clause_id"`
	ClauseName  string `json:"clause_name"`
	LineStart   int    `json:"line_start"`
	LineEnd     int    `json:"line_end"`
	ChangeType  string `json:"change_type"`
	Description string `json:"description"`
}

type ChangeSummary struct {
	TotalChanges    int         `json:"total_changes"`
	Insertions      int         `json:"insertions"`
	Deletions       int         `json:"deletions"`
	Replacements    int         `json:"replacements"`
	KeyChanges      []KeyChange `json:"key_changes"`
	SimilarityScore float64     `json:"similarity_score"`
}

type KeyChange struct {
	Type        string `json:"type"`
	Description string `json:"description"`
	Severity    string `json:"severity"`
	ClauseID    string `json:"clause_id"`
}

type Document struct {
	ID      string `json:"id"`
	Content string `json:"content"`
	Version int    `json:"version"`
}

type DiffResult struct {
	Segments   []DiffSegment     `json:"segments"`
	Highlights []ClauseHighlight `json:"highlights"`
	Summary    ChangeSummary     `json:"summary"`
	Truncated  bool              `json:"truncated,omitempty"`
}

type DocComparator struct {
	keywords map[string]ClauseHighlight
}

func NewDocComparator() *DocComparator {
	return &DocComparator{
		keywords: make(map[string]ClauseHighlight),
	}
}

func (dc *DocComparator) RegisterClause(id, name string, keywords []string) {
	for _, kw := range keywords {
		dc.keywords[strings.ToLower(kw)] = ClauseHighlight{
			ClauseID:   id,
			ClauseName: name,
		}
	}
}

func (dc *DocComparator) Compare(oldDoc, newDoc Document, opts ...CompareOption) (*DiffResult, error) {
	cfg := &compareConfig{
		timeout:  DefaultCompareTimeout,
		maxLines: DefaultMaxDocLines,
		ctx:      context.Background(),
	}
	for _, o := range opts {
		o(cfg)
	}

	ctx, cancel := context.WithTimeout(cfg.ctx, cfg.timeout)
	defer cancel()

	oldLines := strings.Split(oldDoc.Content, "\n")
	newLines := strings.Split(newDoc.Content, "\n")

	if len(oldLines) > cfg.maxLines {
		oldLines = oldLines[:cfg.maxLines]
	}
	if len(newLines) > cfg.maxLines {
		newLines = newLines[:cfg.maxLines]
	}

	truncated := len(strings.Split(oldDoc.Content, "\n")) > cfg.maxLines ||
		len(strings.Split(newDoc.Content, "\n")) > cfg.maxLines

	segments, err := dc.computeLCSWithCancel(ctx, oldLines, newLines)
	if err != nil {
		return nil, err
	}
	highlights := dc.detectClauseChanges(oldLines, newLines, segments)
	summary := dc.generateSummary(segments, oldLines, newLines)
	return &DiffResult{
		Segments:   segments,
		Highlights: highlights,
		Summary:    summary,
		Truncated:  truncated,
	}, nil
}

type lcsCheckpoint struct {
	lineIdx int
}

func (dc *DocComparator) computeLCSWithCancel(ctx context.Context, oldLines, newLines []string) ([]DiffSegment, error) {
	m := len(oldLines)
	n := len(newLines)
	dp := make([][]int, m+1)
	for i := range dp {
		dp[i] = make([]int, n+1)
	}

	checkInterval := 1000
	nextCheck := checkInterval

	for i := 1; i <= m; i++ {
		if i >= nextCheck {
			select {
			case <-ctx.Done():
				return nil, fmt.Errorf("compare operation cancelled: %w", ctx.Err())
			default:
			}
			nextCheck = i + checkInterval
		}
		for j := 1; j <= n; j++ {
			if strings.TrimSpace(oldLines[i-1]) == strings.TrimSpace(newLines[j-1]) {
				dp[i][j] = dp[i-1][j-1] + 1
			} else if dp[i-1][j] >= dp[i][j-1] {
				dp[i][j] = dp[i-1][j]
			} else {
				dp[i][j] = dp[i][j-1]
			}
		}
	}

	select {
	case <-ctx.Done():
		return nil, fmt.Errorf("compare operation cancelled: %w", ctx.Err())
	default:
	}

	type op struct {
		t    DiffType
		oi   int
		ni   int
		oldT string
		newT string
	}
	var ops []op
	i, j := m, n
	for i > 0 || j > 0 {
		if i > 0 && j > 0 && strings.TrimSpace(oldLines[i-1]) == strings.TrimSpace(newLines[j-1]) {
			ops = append(ops, op{DiffEqual, i - 1, j - 1, oldLines[i-1], newLines[j-1]})
			i--
			j--
		} else if j > 0 && (i == 0 || dp[i][j-1] >= dp[i-1][j]) {
			ops = append(ops, op{DiffInsert, -1, j - 1, "", newLines[j-1]})
			j--
		} else {
			ops = append(ops, op{DiffDelete, i - 1, -1, oldLines[i-1], ""})
			i--
		}
	}
	for left, right := 0, len(ops)-1; left < right; left, right = left+1, right-1 {
		ops[left], ops[right] = ops[right], ops[left]
	}

	var segments []DiffSegment
	mergeIdx := 0
	var oldTextBuf, newTextBuf strings.Builder

	for mergeIdx < len(ops) {
		if ops[mergeIdx].t == DiffEqual {
			seg := DiffSegment{
				Type:     DiffEqual,
				OldStart: ops[mergeIdx].oi + 1,
				OldEnd:   ops[mergeIdx].oi + 1,
				NewStart: ops[mergeIdx].ni + 1,
				NewEnd:   ops[mergeIdx].ni + 1,
			}
			mergeIdx++
			for mergeIdx < len(ops) && ops[mergeIdx].t == DiffEqual {
				seg.OldEnd = ops[mergeIdx].oi + 1
				seg.NewEnd = ops[mergeIdx].ni + 1
				mergeIdx++
			}
			segments = append(segments, seg)
		} else {
			seg := DiffSegment{Type: ops[mergeIdx].t}
			delStart, delEnd := -1, -1
			insStart, insEnd := -1, -1
			oldTextBuf.Reset()
			newTextBuf.Reset()
			for mergeIdx < len(ops) && ops[mergeIdx].t != DiffEqual {
				o := ops[mergeIdx]
				if o.t == DiffDelete {
					if delStart == -1 {
						delStart = o.oi + 1
					}
					delEnd = o.oi + 1
					oldTextBuf.WriteString(o.oldT)
					oldTextBuf.WriteByte('\n')
				} else {
					if insStart == -1 {
						insStart = o.ni + 1
					}
					insEnd = o.ni + 1
					newTextBuf.WriteString(o.newT)
					newTextBuf.WriteByte('\n')
				}
				mergeIdx++
			}
			if delStart != -1 && insStart != -1 {
				seg.Type = DiffReplace
			}
			seg.OldStart = delStart
			seg.OldEnd = delEnd
			seg.NewStart = insStart
			seg.NewEnd = insEnd
			seg.OldText = oldTextBuf.String()
			seg.NewText = newTextBuf.String()
			segments = append(segments, seg)
		}
	}
	return segments, nil
}

func (dc *DocComparator) detectClauseChanges(oldLines, newLines []string, segments []DiffSegment) []ClauseHighlight {
	var highlights []ClauseHighlight
	for _, seg := range segments {
		if seg.Type == DiffEqual {
			continue
		}
		var affectedLines []string
		if seg.NewStart > 0 {
			for i := seg.NewStart - 1; i < seg.NewEnd && i < len(newLines); i++ {
				affectedLines = append(affectedLines, newLines[i])
			}
		}
		if seg.OldStart > 0 {
			for i := seg.OldStart - 1; i < seg.OldEnd && i < len(oldLines); i++ {
				affectedLines = append(affectedLines, oldLines[i])
			}
		}
		for _, line := range affectedLines {
			lower := strings.ToLower(line)
			for kw, clause := range dc.keywords {
				if strings.Contains(lower, kw) {
					highlights = append(highlights, ClauseHighlight{
						ClauseID:    clause.ClauseID,
						ClauseName:  clause.ClauseName,
						LineStart:   seg.NewStart,
						LineEnd:     seg.NewEnd,
						ChangeType:  diffTypeName(seg.Type),
						Description: clause.ClauseName + " changed",
					})
					break
				}
			}
		}
	}
	return highlights
}

func (dc *DocComparator) generateSummary(segments []DiffSegment, oldLines, newLines []string) ChangeSummary {
	summary := ChangeSummary{}
	for _, seg := range segments {
		switch seg.Type {
		case DiffInsert:
			summary.Insertions++
			summary.TotalChanges++
		case DiffDelete:
			summary.Deletions++
			summary.TotalChanges++
		case DiffReplace:
			summary.Replacements++
			summary.TotalChanges++
		}
	}
	total := len(oldLines) + len(newLines)
	if total == 0 {
		summary.SimilarityScore = 1.0
	} else {
		equalLines := 0
		for _, seg := range segments {
			if seg.Type == DiffEqual {
				equalLines += seg.OldEnd - seg.OldStart + 1
			}
		}
		summary.SimilarityScore = float64(equalLines*2) / float64(total)
	}
	for _, seg := range segments {
		if seg.Type != DiffEqual {
			severity := "low"
			lineCount := 0
			if seg.OldEnd > 0 && seg.OldStart > 0 {
				lineCount += seg.OldEnd - seg.OldStart + 1
			}
			if seg.NewEnd > 0 && seg.NewStart > 0 {
				lineCount += seg.NewEnd - seg.NewStart + 1
			}
			if lineCount > 5 {
				severity = "high"
			} else if lineCount > 2 {
				severity = "medium"
			}
			desc := diffTypeName(seg.Type) + " at "
			if seg.OldStart > 0 {
				desc += "old:" + strconv.Itoa(seg.OldStart) + "-" + strconv.Itoa(seg.OldEnd)
			}
			if seg.NewStart > 0 {
				if seg.OldStart > 0 {
					desc += ", "
				}
				desc += "new:" + strconv.Itoa(seg.NewStart) + "-" + strconv.Itoa(seg.NewEnd)
			}
			summary.KeyChanges = append(summary.KeyChanges, KeyChange{
				Type:        diffTypeName(seg.Type),
				Description: desc,
				Severity:    severity,
			})
		}
	}
	return summary
}

func diffTypeName(t DiffType) string {
	switch t {
	case DiffEqual:
		return "equal"
	case DiffInsert:
		return "insert"
	case DiffDelete:
		return "delete"
	case DiffReplace:
		return "replace"
	default:
		return "unknown"
	}
}

func FormatDiffResult(result *DiffResult) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("Change Summary: %d total changes (insertions: %d, deletions: %d, replacements: %d)\n",
		result.Summary.TotalChanges, result.Summary.Insertions, result.Summary.Deletions, result.Summary.Replacements))
	sb.WriteString(fmt.Sprintf("Similarity Score: %.2f%%\n", result.Summary.SimilarityScore*100))
	if result.Truncated {
		sb.WriteString("WARNING: Document was truncated due to size limit\n")
	}
	if len(result.Summary.KeyChanges) > 0 {
		sb.WriteString("Key Changes:\n")
		for _, kc := range result.Summary.KeyChanges {
			sb.WriteString(fmt.Sprintf("  [%s] %s (severity: %s)\n", kc.Type, kc.Description, kc.Severity))
		}
	}
	if len(result.Highlights) > 0 {
		sb.WriteString("Clause Highlights:\n")
		for _, h := range result.Highlights {
			sb.WriteString(fmt.Sprintf("  %s (%s): %s at lines %d-%d\n", h.ClauseName, h.ClauseID, h.ChangeType, h.LineStart, h.LineEnd))
		}
	}
	return sb.String()
}
