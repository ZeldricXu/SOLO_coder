package graph

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/markdown"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/utils"
	"gopkg.in/yaml.v3"
)

type GraphInteraction struct {
	graph *Graph
	db    *db.Database
	cfg   *config.Config
	md    *markdown.MarkdownParser
}

func NewGraphInteraction(g *Graph, database *db.Database, cfg *config.Config) *GraphInteraction {
	return &GraphInteraction{
		graph: g,
		db:    database,
		cfg:   cfg,
		md:    markdown.NewParser(cfg),
	}
}

func (gi *GraphInteraction) GetNodePreview(nodeID uint) (*models.NodePreview, error) {
	note, err := gi.db.GetNoteByID(nodeID)
	if err != nil {
		return nil, fmt.Errorf("note not found: %w", err)
	}

	fullPath := filepath.Join(gi.cfg.VaultPath, note.Path)
	data, err := os.ReadFile(fullPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read note file: %w", err)
	}
	content := string(data)

	result, err := gi.md.Parse(content, note.Path)
	if err != nil {
		return nil, fmt.Errorf("failed to parse note: %w", err)
	}

	previewText := result.PlainText
	runes := []rune(previewText)
	if len(runes) > 500 {
		previewText = string(runes[:500]) + "..."
	}

	tagNames := make([]string, 0, len(note.Tags))
	for _, tag := range note.Tags {
		tagNames = append(tagNames, tag.Name)
	}

	links, _ := gi.db.GetLinks()
	linkCount := 0
	for _, link := range links {
		if link.SourceID == nodeID || link.TargetID == nodeID {
			linkCount++
		}
	}

	return &models.NodePreview{
		NoteID:    note.ID,
		Title:     note.Title,
		Content:   previewText,
		Tags:      tagNames,
		WordCount: note.WordCount,
		LinkCount: linkCount,
		Path:      note.Path,
	}, nil
}

func (gi *GraphInteraction) AddLink(sourceID, targetID uint) error {
	sourceNote, err := gi.db.GetNoteByID(sourceID)
	if err != nil {
		return fmt.Errorf("source note not found: %w", err)
	}
	targetNote, err := gi.db.GetNoteByID(targetID)
	if err != nil {
		return fmt.Errorf("target note not found: %w", err)
	}

	fullPath := filepath.Join(gi.cfg.VaultPath, sourceNote.Path)
	data, err := os.ReadFile(fullPath)
	if err != nil {
		return fmt.Errorf("failed to read source file: %w", err)
	}

	linkText := fmt.Sprintf("\n[[%s]]", targetNote.Title)
	newContent := string(data) + linkText

	if err := os.WriteFile(fullPath, []byte(newContent), 0644); err != nil {
		return fmt.Errorf("failed to write source file: %w", err)
	}

	link := models.Link{
		SourceID:   sourceID,
		TargetID:   targetID,
		SourcePath: sourceNote.Path,
		TargetPath: targetNote.Path,
	}
	if err := gi.db.AddLink(&link); err != nil {
		return fmt.Errorf("failed to save link: %w", err)
	}

	gi.graph.Edges = append(gi.graph.Edges, &GraphEdge{
		GraphEdge: models.GraphEdge{
			Source: sourceID,
			Target: targetID,
		},
		Weight: 1.0,
	})

	if sourceNode, ok := gi.graph.Nodes[sourceID]; ok {
		sourceNode.OutDegree++
		sourceNode.IsOrphan = false
	}
	if targetNode, ok := gi.graph.Nodes[targetID]; ok {
		targetNode.InDegree++
		targetNode.IsOrphan = false
	}

	return nil
}

func (gi *GraphInteraction) RemoveLink(sourceID, targetID uint) error {
	sourceNote, err := gi.db.GetNoteByID(sourceID)
	if err != nil {
		return fmt.Errorf("source note not found: %w", err)
	}
	targetNote, err := gi.db.GetNoteByID(targetID)
	if err != nil {
		return fmt.Errorf("target note not found: %w", err)
	}

	fullPath := filepath.Join(gi.cfg.VaultPath, sourceNote.Path)
	data, err := os.ReadFile(fullPath)
	if err != nil {
		return fmt.Errorf("failed to read source file: %w", err)
	}

	content := string(data)
	linkPattern := fmt.Sprintf(`\[\[%s\]\]`, regexp.QuoteMeta(targetNote.Title))
	re := regexp.MustCompile(linkPattern)
	content = re.ReplaceAllString(content, "")

	if err := os.WriteFile(fullPath, []byte(content), 0644); err != nil {
		return fmt.Errorf("failed to write source file: %w", err)
	}

	if err := gi.db.DeleteLink(sourceID, targetID); err != nil {
		return fmt.Errorf("failed to delete link: %w", err)
	}

	newEdges := make([]*GraphEdge, 0, len(gi.graph.Edges))
	for _, edge := range gi.graph.Edges {
		if edge.Source == sourceID && edge.Target == targetID {
			continue
		}
		newEdges = append(newEdges, edge)
	}
	gi.graph.Edges = newEdges

	if sourceNode, ok := gi.graph.Nodes[sourceID]; ok {
		sourceNode.OutDegree--
		if sourceNode.InDegree == 0 && sourceNode.OutDegree == 0 {
			sourceNode.IsOrphan = true
		}
	}
	if targetNode, ok := gi.graph.Nodes[targetID]; ok {
		targetNode.InDegree--
		if targetNode.InDegree == 0 && targetNode.OutDegree == 0 {
			targetNode.IsOrphan = true
		}
	}

	return nil
}

func (gi *GraphInteraction) RenameNode(nodeID uint, newTitle string) error {
	note, err := gi.db.GetNoteByID(nodeID)
	if err != nil {
		return fmt.Errorf("note not found: %w", err)
	}
	oldTitle := note.Title

	fullPath := filepath.Join(gi.cfg.VaultPath, note.Path)
	data, err := os.ReadFile(fullPath)
	if err != nil {
		return fmt.Errorf("failed to read note file: %w", err)
	}

	content := string(data)
	content = updateFrontMatterTitle(content, newTitle)

	if err := os.WriteFile(fullPath, []byte(content), 0644); err != nil {
		return fmt.Errorf("failed to write note file: %w", err)
	}

	note.Title = newTitle
	note.Hash = utils.Hash(content)
	note.WordCount = utils.CountWords(content)
	if err := gi.db.SaveNote(note); err != nil {
		return fmt.Errorf("failed to update note: %w", err)
	}

	links, _ := gi.db.GetLinks()
	for _, link := range links {
		if link.TargetID == nodeID {
			sourceNote, err := gi.db.GetNoteByID(link.SourceID)
			if err != nil {
				continue
			}
			srcPath := filepath.Join(gi.cfg.VaultPath, sourceNote.Path)
			srcData, err := os.ReadFile(srcPath)
			if err != nil {
				continue
			}
			srcContent := string(srcData)
			srcContent = strings.ReplaceAll(srcContent, fmt.Sprintf("[[%s]]", oldTitle), fmt.Sprintf("[[%s]]", newTitle))
			if err := os.WriteFile(srcPath, []byte(srcContent), 0644); err != nil {
				continue
			}
		}
	}

	if node, ok := gi.graph.Nodes[nodeID]; ok {
		node.Title = newTitle
		node.Label = newTitle
	}

	return nil
}

func (gi *GraphInteraction) DeleteNode(nodeID uint) error {
	_, err := gi.db.GetNoteByID(nodeID)
	if err != nil {
		return fmt.Errorf("note not found: %w", err)
	}

	newEdges := make([]*GraphEdge, 0, len(gi.graph.Edges))
	for _, edge := range gi.graph.Edges {
		if edge.Source == nodeID || edge.Target == nodeID {
			continue
		}
		newEdges = append(newEdges, edge)
	}
	gi.graph.Edges = newEdges

	delete(gi.graph.Nodes, nodeID)

	if err := gi.db.DeleteNoteByID(nodeID); err != nil {
		return fmt.Errorf("failed to delete note from db: %w", err)
	}

	return nil
}

func (gi *GraphInteraction) CreateSummaryFromNodes(nodeIDs []uint) (*models.Note, error) {
	type noteInfo struct {
		Title   string
		Content string
		Path    string
	}
	var notes []noteInfo
	var titles []string

	for _, id := range nodeIDs {
		note, err := gi.db.GetNoteByID(id)
		if err != nil {
			continue
		}
		fullPath := filepath.Join(gi.cfg.VaultPath, note.Path)
		data, err := os.ReadFile(fullPath)
		if err != nil {
			continue
		}
		content := string(data)
		result, err := gi.md.Parse(content, note.Path)
		if err != nil {
			continue
		}
		plainText := result.PlainText
		runes := []rune(plainText)
		if len(runes) > 200 {
			plainText = string(runes[:200]) + "..."
		}
		notes = append(notes, noteInfo{
			Title:   note.Title,
			Content: plainText,
			Path:    note.Path,
		})
		titles = append(titles, note.Title)
	}

	if len(notes) == 0 {
		return nil, fmt.Errorf("no valid notes found")
	}

	summaryTitle := buildSummaryTitle(titles)

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("# %s\n\n", summaryTitle))
	sb.WriteString("本笔记汇总了以下笔记的内容：\n\n")

	for _, n := range notes {
		sb.WriteString(fmt.Sprintf("## %s\n", n.Title))
		sb.WriteString(fmt.Sprintf("> 摘自 [[%s]]\n\n", n.Title))
		sb.WriteString(n.Content + "\n\n")
	}

	sb.WriteString("---\n")
	sb.WriteString(fmt.Sprintf("*自动生成于 %s*\n", time.Now().Format("2006-01-02")))

	summaryContent := sb.String()

	slug := utils.Slugify(summaryTitle)
	fileName := slug + ".md"
	notePath := fileName

	fullPath := filepath.Join(gi.cfg.VaultPath, notePath)
	os.MkdirAll(filepath.Dir(fullPath), 0755)
	if err := os.WriteFile(fullPath, []byte(summaryContent), 0644); err != nil {
		return nil, fmt.Errorf("failed to write summary file: %w", err)
	}

	newNote := &models.Note{
		Path:      notePath,
		Title:     summaryTitle,
		Hash:      utils.Hash(summaryContent),
		WordCount: utils.CountWords(summaryContent),
	}
	if err := gi.db.SaveNote(newNote); err != nil {
		return nil, fmt.Errorf("failed to save summary note: %w", err)
	}

	var summaryLinks []models.Link
	for _, n := range notes {
		targetNote, err := gi.db.GetNoteByPath(n.Path)
		if err != nil {
			continue
		}
		summaryLinks = append(summaryLinks, models.Link{
			SourceID:   newNote.ID,
			TargetID:   targetNote.ID,
			SourcePath: notePath,
			TargetPath: n.Path,
		})
	}
	if err := gi.db.SaveLinks(newNote.ID, summaryLinks); err != nil {
		return newNote, err
	}

	gi.graph.Nodes[newNote.ID] = &GraphNode{
		GraphNode: models.GraphNode{
			ID:        newNote.ID,
			Path:      newNote.Path,
			Title:     newNote.Title,
			Size:      gi.graph.calculateNodeSize(0),
			InDegree:  0,
			OutDegree: len(summaryLinks),
			IsOrphan:  false,
		},
		Visible: true,
		Label:   newNote.Title,
	}

	for _, link := range summaryLinks {
		gi.graph.Edges = append(gi.graph.Edges, &GraphEdge{
			GraphEdge: models.GraphEdge{
				Source: link.SourceID,
				Target: link.TargetID,
			},
			Weight: 1.0,
		})
		if targetNode, ok := gi.graph.Nodes[link.TargetID]; ok {
			targetNode.InDegree++
		}
	}

	return newNote, nil
}

func updateFrontMatterTitle(content, newTitle string) string {
	re := regexp.MustCompile(`(?s)^---\s*\n(.*?)\n---\s*\n?`)
	matches := re.FindStringSubmatch(content)

	if len(matches) < 2 {
		return fmt.Sprintf("---\ntitle: %s\n---\n\n%s", newTitle, content)
	}

	frontMatter := matches[1]
	var metadata map[string]interface{}
	if err := yaml.Unmarshal([]byte(frontMatter), &metadata); err != nil {
		return content
	}

	metadata["title"] = newTitle

	newFM, err := yaml.Marshal(metadata)
	if err != nil {
		return content
	}

	rest := content[len(matches[0]):]
	return fmt.Sprintf("---\n%s---\n\n%s", string(newFM), rest)
}

func buildSummaryTitle(titles []string) string {
	if len(titles) <= 3 {
		return "汇总: " + strings.Join(titles, "、")
	}
	return "汇总: " + strings.Join(titles[:3], "、") + "等"
}
