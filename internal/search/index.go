package search

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/segment"
)

type Indexer struct {
	db        *db.Database
	useCJK    bool
	diskIndex *DiskInvertedIndex
}

type TermPosting struct {
	NoteID    uint
	Frequency int
	Positions []int
}

func NewIndexer(database *db.Database, cfg *config.Config) *Indexer {
	indexPath := cfg.Search.IndexPath
	if indexPath == "" {
		indexPath = cfg.IndexPath
	}

	diskIdx, err := NewDiskInvertedIndex(indexPath)
	if err != nil {
		fmt.Printf("Warning: failed to create disk index: %v, falling back to SQLite only\n", err)
		return &Indexer{
			db:     database,
			useCJK: cfg.Search.UseCJK,
		}
	}

	return &Indexer{
		db:        database,
		useCJK:    cfg.Search.UseCJK,
		diskIndex: diskIdx,
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

	if idx.diskIndex != nil {
		diskPostings := make(map[string]MemTermPosting)
		for term, positions := range termMap {
			diskPostings[term] = MemTermPosting{
				Frequency: len(positions),
				Positions: positions,
			}
		}

		if err := idx.diskIndex.IndexNote(noteID, diskPostings); err != nil {
			fmt.Printf("Warning: disk index note failed: %v\n", err)
		}
	}

	return nil
}

func (idx *Indexer) DeleteNoteIndex(noteID uint) error {
	if err := idx.db.ClearSearchIndex(noteID); err != nil {
		return err
	}

	if idx.diskIndex != nil {
		if err := idx.diskIndex.DeleteNote(noteID); err != nil {
			fmt.Printf("Warning: disk delete note failed: %v\n", err)
		}
	}

	return nil
}

func (idx *Indexer) GetPostings(term string) ([]TermPosting, error) {
	if idx.diskIndex != nil {
		records, err := idx.diskIndex.GetPostings(term)
		if err == nil {
			postings := make([]TermPosting, len(records))
			for i, r := range records {
				postings[i] = TermPosting{
					NoteID:    r.NoteID,
					Frequency: r.Frequency,
					Positions: r.Positions,
				}
			}
			return postings, nil
		}
		fmt.Printf("Warning: disk get postings failed, falling back to SQLite: %v\n", err)
	}

	return idx.getPostingsFromDB(term)
}

func (idx *Indexer) getPostingsFromDB(term string) ([]TermPosting, error) {
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

	if idx.diskIndex != nil {
		allOk := true
		for _, term := range terms {
			freq, err := idx.diskIndex.GetDocFrequency(term)
			if err != nil {
				allOk = false
				break
			}
			df[strings.ToLower(term)] = freq
		}
		if allOk {
			return df, nil
		}
	}

	for _, term := range terms {
		postings, err := idx.getPostingsFromDB(term)
		if err != nil {
			return nil, err
		}
		df[strings.ToLower(term)] = len(postings)
	}
	return df, nil
}

func (idx *Indexer) GetAllDocLengths() (map[uint]int, error) {
	if idx.diskIndex != nil {
		lengths, err := idx.diskIndex.GetDocLengths()
		if err == nil && len(lengths) > 0 {
			return lengths, nil
		}
	}
	return idx.db.GetDocLengths()
}

func (idx *Indexer) GetTotalDocCount() (int, error) {
	if idx.diskIndex != nil {
		count := idx.diskIndex.GetTotalDocCount()
		if count > 0 {
			return count, nil
		}
	}
	return idx.db.GetTotalDocCount()
}

func (idx *Indexer) FuzzySearch(query string, threshold float64) ([]struct {
	Term       string
	Similarity float64
}, error) {
	queryLower := strings.ToLower(query)
	allTerms, err := idx.getAllTerms()
	if err != nil {
		return nil, err
	}

	var results []struct {
		Term       string
		Similarity float64
	}

	for _, term := range allTerms {
		sim := segment.FuzzyMatch(term, queryLower)
		if sim >= threshold {
			results = append(results, struct {
				Term       string
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
	if idx.diskIndex != nil {
		terms, err := idx.diskIndex.GetAllTerms()
		if err == nil && len(terms) > 0 {
			return terms, nil
		}
	}

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
	if idx.diskIndex != nil {
		terms, err := idx.diskIndex.GetNoteTerms(noteID)
		if err == nil {
			return terms, nil
		}
	}

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
	if idx.diskIndex != nil {
		postings, err := idx.diskIndex.GetPostings(term)
		if err == nil {
			for _, p := range postings {
				if p.NoteID == noteID {
					return p.Positions, nil
				}
			}
			return []int{}, nil
		}
	}

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

func (idx *Indexer) FlushDiskIndex() error {
	if idx.diskIndex != nil {
		return idx.diskIndex.Flush()
	}
	return nil
}

func (idx *Indexer) CloseDiskIndex() error {
	if idx.diskIndex != nil {
		return idx.diskIndex.Close()
	}
	return nil
}
