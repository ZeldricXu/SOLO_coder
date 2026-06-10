package search

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/segment"
)

type SearchQuery struct {
	Query      string
	Tags       []string
	Folders    []string
	Page       int
	PageSize   int
	EnableFuzzy bool
	FuzzyThreshold float64
}

type SearchEngine struct {
	indexer     *Indexer
	scorer      *BM25Scorer
	highlighter *Highlighter
	db          *db.Database
	useCJK      bool
	vaultPath   string
}

type docScore struct {
	noteID  uint
	score   float64
	termFreq map[string]int
}

func NewSearchEngine(database *db.Database, cfg *config.Config) *SearchEngine {
	bm25Config := BM25Config{
		K1: cfg.Search.BM25K1,
		B:  cfg.Search.BM25B,
	}

	return &SearchEngine{
		indexer:     NewIndexer(database, cfg.Search.UseCJK),
		scorer:      NewBM25Scorer(bm25Config),
		highlighter: DefaultHighlighter(),
		db:          database,
		useCJK:      cfg.Search.UseCJK,
		vaultPath:   cfg.VaultPath,
	}
}

func (e *SearchEngine) IndexNote(noteID uint, title, content string) error {
	return e.indexer.IndexNote(noteID, title, content)
}

func (e *SearchEngine) DeleteNoteIndex(noteID uint) error {
	return e.indexer.DeleteNoteIndex(noteID)
}

func (e *SearchEngine) Search(query SearchQuery) ([]models.SearchResult, int, error) {
	if strings.TrimSpace(query.Query) == "" {
		return nil, 0, nil
	}

	terms, phraseTerms, boolOps := e.parseQuery(query.Query)

	if len(terms) == 0 && len(phraseTerms) == 0 {
		return nil, 0, nil
	}

	allTerms := make([]string, 0)
	allTerms = append(allTerms, terms...)
	for _, pt := range phraseTerms {
		allTerms = append(allTerms, pt...)
	}

	if query.EnableFuzzy {
		expandedTerms, err := e.expandFuzzyTerms(allTerms, query.FuzzyThreshold)
		if err != nil {
			return nil, 0, err
		}
		allTerms = append(allTerms, expandedTerms...)
	}

	docScores, err := e.computeScores(allTerms, terms, phraseTerms, boolOps)
	if err != nil {
		return nil, 0, err
	}

	docScores, err = e.filterByTags(docScores, query.Tags)
	if err != nil {
		return nil, 0, err
	}

	docScores, err = e.filterByFolders(docScores, query.Folders)
	if err != nil {
		return nil, 0, err
	}

	sort.Slice(docScores, func(i, j int) bool {
		return docScores[i].score > docScores[j].score
	})

	total := len(docScores)

	start := query.Page * query.PageSize
	if start < 0 {
		start = 0
	}
	if start >= total {
		return []models.SearchResult{}, total, nil
	}

	end := start + query.PageSize
	if end > total {
		end = total
	}

	pagedScores := docScores[start:end]

	results, err := e.buildResults(pagedScores, allTerms)
	if err != nil {
		return nil, 0, err
	}

	return results, total, nil
}

func (e *SearchEngine) parseQuery(query string) ([]string, [][]string, []string) {
	var terms []string
	var phraseTerms [][]string
	var boolOps []string

	query = strings.TrimSpace(query)
	tokens := tokenizeQuery(query)

	i := 0
	for i < len(tokens) {
		tok := tokens[i]

		if strings.HasPrefix(tok, "\"") && strings.HasSuffix(tok, "\"") && len(tok) > 2 {
			phrase := tok[1 : len(tok)-1]
			phraseTokens := segment.Segment(phrase, e.useCJK)
			var pt []string
			for _, t := range phraseTokens {
				pt = append(pt, strings.ToLower(t.Text))
			}
			if len(pt) > 0 {
				phraseTerms = append(phraseTerms, pt)
			}
			i++
			continue
		}

		upperTok := strings.ToUpper(tok)
		if upperTok == "AND" || upperTok == "OR" || upperTok == "NOT" {
			boolOps = append(boolOps, upperTok)
			i++
			continue
		}

		termTokens := segment.Segment(tok, e.useCJK)
		for _, t := range termTokens {
			terms = append(terms, strings.ToLower(t.Text))
		}
		i++
	}

	return terms, phraseTerms, boolOps
}

func tokenizeQuery(query string) []string {
	var tokens []string
	var current strings.Builder
	inQuote := false

	for _, r := range query {
		switch {
		case r == '"':
			if inQuote {
				current.WriteRune(r)
				tokens = append(tokens, current.String())
				current.Reset()
				inQuote = false
			} else {
				if current.Len() > 0 {
					tokens = append(tokens, current.String())
					current.Reset()
				}
				current.WriteRune(r)
				inQuote = true
			}
		case r == ' ' && !inQuote:
			if current.Len() > 0 {
				tokens = append(tokens, current.String())
				current.Reset()
			}
		default:
			current.WriteRune(r)
		}
	}

	if current.Len() > 0 {
		tokens = append(tokens, current.String())
	}

	return tokens
}

func (e *SearchEngine) expandFuzzyTerms(terms []string, threshold float64) ([]string, error) {
	if threshold <= 0 {
		threshold = 0.5
	}

	expanded := make(map[string]bool)
	for _, term := range terms {
		results, err := e.indexer.FuzzySearch(term, threshold)
		if err != nil {
			return nil, err
		}
		for _, r := range results {
			expanded[r.Term] = true
		}
	}

	var result []string
	for t := range expanded {
		result = append(result, t)
	}
	return result, nil
}

func (e *SearchEngine) computeScores(allTerms, terms []string, phraseTerms [][]string, boolOps []string) ([]docScore, error) {
	totalDocs, err := e.indexer.GetTotalDocCount()
	if err != nil {
		return nil, err
	}
	if totalDocs == 0 {
		return nil, nil
	}

	docLengths, err := e.indexer.GetAllDocLengths()
	if err != nil {
		return nil, err
	}

	df, err := e.indexer.GetDocFrequencies(allTerms)
	if err != nil {
		return nil, err
	}

	e.scorer.SetTotalDocs(totalDocs)
	e.scorer.SetDocLengths(docLengths)
	e.scorer.SetDocFrequencies(df)

	docTermFreq := make(map[uint]map[string]int)
	docPositions := make(map[uint]map[string][]int)

	for _, term := range allTerms {
		postings, err := e.indexer.GetPostings(term)
		if err != nil {
			return nil, err
		}
		for _, p := range postings {
			if _, ok := docTermFreq[p.NoteID]; !ok {
				docTermFreq[p.NoteID] = make(map[string]int)
				docPositions[p.NoteID] = make(map[string][]int)
			}
			docTermFreq[p.NoteID][term] += p.Frequency
			docPositions[p.NoteID][term] = append(docPositions[p.NoteID][term], p.Positions...)
		}
	}

	var scores []docScore

	for docID, termFreq := range docTermFreq {
		if !e.matchBoolean(termFreq, terms, boolOps) {
			continue
		}

		phraseMatch := true
		phraseBoost := 1.0
		for _, phrase := range phraseTerms {
			if !e.matchPhrase(docPositions[docID], phrase) {
				phraseMatch = false
				break
			}
			phraseBoost += 0.5
		}
		if len(phraseTerms) > 0 && !phraseMatch {
			continue
		}

		score := e.scorer.Score(docID, termFreq)
		score *= phraseBoost

		scores = append(scores, docScore{
			noteID:   docID,
			score:    score,
			termFreq: termFreq,
		})
	}

	return scores, nil
}

func (e *SearchEngine) matchBoolean(termFreq map[string]int, terms []string, boolOps []string) bool {
	if len(boolOps) == 0 {
		for _, term := range terms {
			if _, ok := termFreq[term]; ok {
				return true
			}
		}
		return len(terms) == 0
	}

	result := false
	op := "OR"
	termIdx := 0

	for _, token := range append(boolOps, "OR") {
		if token == "AND" || token == "OR" || token == "NOT" {
			if termIdx < len(terms) {
				term := terms[termIdx]
				hasTerm := false
				if _, ok := termFreq[term]; ok {
					hasTerm = true
				}

				switch op {
				case "AND":
					result = result && hasTerm
				case "OR":
					result = result || hasTerm
				case "NOT":
					result = result && !hasTerm
				}
				termIdx++
			}
			op = token
		}
	}

	return result
}

func (e *SearchEngine) matchPhrase(positions map[string][]int, phrase []string) bool {
	if len(phrase) == 0 {
		return true
	}

	firstTerm := phrase[0]
	firstPositions, ok := positions[firstTerm]
	if !ok {
		return false
	}

	for _, startPos := range firstPositions {
		matched := true
		for i := 1; i < len(phrase); i++ {
			term := phrase[i]
			termPositions, ok := positions[term]
			if !ok {
				matched = false
				break
			}

			expectedPos := startPos + i
			found := false
			for _, pos := range termPositions {
				if pos == expectedPos {
					found = true
					break
				}
			}
			if !found {
				matched = false
				break
			}
		}
		if matched {
			return true
		}
	}

	return false
}

func (e *SearchEngine) filterByTags(scores []docScore, tags []string) ([]docScore, error) {
	if len(tags) == 0 {
		return scores, nil
	}

	tagNameToID := make(map[string]uint)
	allTags, err := e.db.GetAllTags()
	if err != nil {
		return nil, err
	}
	for _, t := range allTags {
		tagNameToID[strings.ToLower(t.Name)] = t.ID
	}

	noteTagIDs := make(map[uint]map[uint]bool)
	for _, s := range scores {
		note, err := e.db.GetNoteByID(s.noteID)
		if err != nil {
			continue
		}
		tagIDs := make(map[uint]bool)
		for _, t := range note.Tags {
			tagIDs[t.ID] = true
		}
		noteTagIDs[s.noteID] = tagIDs
	}

	var filtered []docScore
	for _, s := range scores {
		hasAllTags := true
		for _, tag := range tags {
			tagID, ok := tagNameToID[strings.ToLower(tag)]
			if !ok {
				hasAllTags = false
				break
			}
			if !noteTagIDs[s.noteID][tagID] {
				hasAllTags = false
				break
			}
		}
		if hasAllTags {
			filtered = append(filtered, s)
		}
	}

	return filtered, nil
}

func (e *SearchEngine) filterByFolders(scores []docScore, folders []string) ([]docScore, error) {
	if len(folders) == 0 {
		return scores, nil
	}

	notePaths := make(map[uint]string)
	for _, s := range scores {
		note, err := e.db.GetNoteByID(s.noteID)
		if err != nil {
			continue
		}
		notePaths[s.noteID] = note.Path
	}

	var filtered []docScore
	for _, s := range scores {
		path := notePaths[s.noteID]
		for _, folder := range folders {
			if isInFolder(path, folder) {
				filtered = append(filtered, s)
				break
			}
		}
	}

	return filtered, nil
}

func isInFolder(path, folder string) bool {
	folder = filepath.Clean(folder)
	dir := filepath.Dir(path)
	dir = filepath.Clean(dir)

	if dir == folder {
		return true
	}

	if strings.HasPrefix(dir, folder+string(filepath.Separator)) {
		return true
	}

	return false
}

func (e *SearchEngine) buildResults(scores []docScore, terms []string) ([]models.SearchResult, error) {
	results := make([]models.SearchResult, 0, len(scores))

	for _, s := range scores {
		note, err := e.db.GetNoteByID(s.noteID)
		if err != nil {
			continue
		}

		content, err := e.loadNoteContent(note)
		if err != nil {
			content = ""
		}

		excerpt := e.highlighter.GenerateExcerpt(content, terms, 200)
		highlights := e.highlighter.ExtractHighlights(content, terms, 5)

		results = append(results, models.SearchResult{
			NoteID:     note.ID,
			Path:       note.Path,
			Title:      note.Title,
			Score:      s.score,
			Excerpt:    excerpt,
			Highlights: highlights,
		})
	}

	return results, nil
}

func (e *SearchEngine) loadNoteContent(note *models.Note) (string, error) {
	if note.Content != "" {
		return note.Content, nil
	}

	if e.vaultPath != "" && note.Path != "" {
		fullPath := note.Path
		if !filepath.IsAbs(fullPath) {
			fullPath = filepath.Join(e.vaultPath, note.Path)
		}
		data, err := os.ReadFile(fullPath)
		if err == nil {
			return string(data), nil
		}
	}

	return "", fmt.Errorf("content not loaded")
}

func (e *SearchEngine) SetHighlighter(h *Highlighter) {
	e.highlighter = h
}

func (e *SearchEngine) GetIndexer() *Indexer {
	return e.indexer
}

func (e *SearchEngine) GetScorer() *BM25Scorer {
	return e.scorer
}
