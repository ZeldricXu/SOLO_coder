package search

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/segment"
)

type Indexer struct {
	db      *db.Database
	useCJK  bool
}

type TermPosting struct {
	NoteID    uint
	Frequency int
	Positions []int
}

func NewIndexer(db *db.Database, useCJK bool) *Indexer {
	return &Indexer{
		db:     db,
		useCJK: useCJK,
	}
}

func (idx *Indexer) IndexNote(noteID uint, title, content string) error {
	if err := idx.db.ClearSearchIndex(noteID); err != nil {
		return fmt.Errorf("clear old index failed: %w", err)
	}

	text := title + "\n" + content
	tokens := segment.Segment(text, idx.useCJK)

	termMap := make(map[string][]int)
	for tokenIdx, tok := range tokens {
		term := strings.ToLower(tok.Text)
		termMap[term] = append(termMap[term], tokenIdx)
	}

	for term, positions := range termMap {
		freq := len(positions)
		if err := idx.db.SaveSearchIndex(noteID, term, freq, positions); err != nil {
			return fmt.Errorf("save index failed for term %s: %w", term, err)
		}
	}

	return nil
}

func (idx *Indexer) DeleteNoteIndex(noteID uint) error {
	return idx.db.ClearSearchIndex(noteID)
}

func (idx *Indexer) GetPostings(term string) ([]TermPosting, error) {
	results, err := idx.db.SearchByTerm(strings.ToLower(term))
	if err != nil {
		return nil, err
	}

	postings := make([]TermPosting, len(results))
	for i, r := range results {
		postings[i] = TermPosting{
			NoteID:    r.NoteID,
			Frequency: r.Frequency,
			Positions: r.Positions,
		}
	}

	return postings, nil
}

func (idx *Indexer) GetDocFrequencies(terms []string) (map[string]int, error) {
	df := make(map[string]int)
	for _, term := range terms {
		postings, err := idx.GetPostings(term)
		if err != nil {
			return nil, err
		}
		df[strings.ToLower(term)] = len(postings)
	}
	return df, nil
}

func (idx *Indexer) GetAllDocLengths() (map[uint]int, error) {
	return idx.db.GetDocLengths()
}

func (idx *Indexer) GetTotalDocCount() (int, error) {
	return idx.db.GetTotalDocCount()
}

func (idx *Indexer) FuzzySearch(query string, threshold float64) ([]struct {
	Term      string
	Similarity float64
}, error) {
	queryLower := strings.ToLower(query)
	allTerms, err := idx.getAllTerms()
	if err != nil {
		return nil, err
	}

	var results []struct {
		Term      string
		Similarity float64
	}

	for _, term := range allTerms {
		sim := segment.FuzzyMatch(term, queryLower)
		if sim >= threshold {
			results = append(results, struct {
				Term      string
				Similarity float64
			}{term, sim})
		}
	}

	sort.Slice(results, func(i, j int) bool {
		return results[i].Similarity > results[j].Similarity
	})

	return results, nil
}

func (idx *Indexer) getAllTerms() ([]string, error) {
	rows, err := idx.db.Query("SELECT DISTINCT term FROM search_index")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var terms []string
	for rows.Next() {
		var term string
		if err := rows.Scan(&term); err != nil {
			return nil, err
		}
		terms = append(terms, term)
	}
	return terms, nil
}

func (idx *Indexer) GetNoteTerms(noteID uint) (map[string]int, error) {
	rows, err := idx.db.Query(`
		SELECT term, frequency FROM search_index WHERE note_id = ?
	`, noteID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	termFreq := make(map[string]int)
	for rows.Next() {
		var term string
		var freq int
		if err := rows.Scan(&term, &freq); err != nil {
			return nil, err
		}
		termFreq[term] = freq
	}
	return termFreq, nil
}

func (idx *Indexer) GetTermPositions(noteID uint, term string) ([]int, error) {
	var posData []byte
	err := idx.db.QueryRow(`
		SELECT positions FROM search_index WHERE note_id = ? AND term = ?
	`, noteID, strings.ToLower(term)).Scan(&posData)
	if err != nil {
		return nil, err
	}

	var positions []int
	json.Unmarshal(posData, &positions)
	return positions, nil
}

func (idx *Indexer) GetNote(noteID uint) (*models.Note, error) {
	return idx.db.GetNoteByID(noteID)
}

func (idx *Indexer) GetNoteByPath(path string) (*models.Note, error) {
	return idx.db.GetNoteByPath(path)
}

func (idx *Indexer) GetAllNotes() ([]*models.Note, error) {
	return idx.db.GetAllNotes()
}
