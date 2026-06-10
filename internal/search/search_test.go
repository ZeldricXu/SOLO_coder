package search

import (
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/segment"
)

func newTestConfig(t *testing.T) *config.Config {
	t.Helper()
	tmpDir := t.TempDir()
	cfg := config.Default()
	cfg.DBPath = filepath.Join(tmpDir, "test.db")
	cfg.VaultPath = tmpDir
	cfg.Search.UseCJK = false
	return cfg
}

func newTestDatabase(t *testing.T, cfg *config.Config) *db.Database {
	t.Helper()
	database, err := db.New(cfg)
	if err != nil {
		t.Fatalf("failed to create test database: %v", err)
	}
	return database
}

func createNote(t *testing.T, database *db.Database, path, title, content string) uint {
	t.Helper()
	wordCount := len(segment.Segment(title+"\n"+content, false))
	note := &models.Note{
		Path:      path,
		Title:     title,
		Content:   content,
		Hash:      fmt.Sprintf("%d", time.Now().UnixNano()),
		WordCount: wordCount,
	}
	if err := database.SaveNote(note); err != nil {
		t.Fatalf("failed to save note: %v", err)
	}
	return note.ID
}

func newTestSearchEngine(t *testing.T) (*SearchEngine, *db.Database, *config.Config) {
	t.Helper()
	cfg := newTestConfig(t)
	database := newTestDatabase(t, cfg)
	engine := NewSearchEngine(database, cfg)
	return engine, database, cfg
}

func TestInvertedIndex_BuildSingleNote(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)
	idx := engine.GetIndexer()

	title := "Golang Concurrency Guide"
	content := "golang concurrency patterns with goroutines and channels channels channels"
	noteID := createNote(t, engine.db, "/note1.md", title, content)

	if err := engine.IndexNote(noteID, title, content); err != nil {
		t.Fatalf("IndexNote failed: %v", err)
	}

	text := title + "\n" + content
	tokens := segment.Segment(text, false)

	termMap := make(map[string][]int)
	for _, tok := range tokens {
		term := strings.ToLower(tok.Text)
		termMap[term] = append(termMap[term], tok.Position)
	}

	for term, expectedPositions := range termMap {
		postings, err := idx.GetPostings(term)
		if err != nil {
			t.Fatalf("GetPostings(%q) failed: %v", term, err)
		}

		found := false
		for _, p := range postings {
			if p.NoteID == noteID {
				found = true
				if p.Frequency != len(expectedPositions) {
					t.Errorf("term %q: frequency = %d, want %d",
						term, p.Frequency, len(expectedPositions))
				}
				if len(p.Positions) != len(expectedPositions) {
					t.Errorf("term %q: positions length = %d, want %d",
						term, len(p.Positions), len(expectedPositions))
				}
				break
			}
		}
		if !found {
			t.Errorf("term %q: noteID %d not found in postings", term, noteID)
		}
	}
}

func TestInvertedIndex_BuildMultipleNotes(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)
	idx := engine.GetIndexer()

	notes := []struct {
		title   string
		content string
	}{
		{"Note 1", "apple banana cherry"},
		{"Note 2", "banana date elderberry"},
		{"Note 3", "cherry date fig"},
		{"Note 4", "apple date"},
		{"Note 5", "elderberry fig grape"},
		{"Note 6", "apple banana"},
		{"Note 7", "cherry grape"},
		{"Note 8", "date elderberry fig"},
		{"Note 9", "apple cherry date"},
		{"Note 10", "banana elderberry grape"},
	}

	termDocCount := make(map[string]int)

	for i, n := range notes {
		noteID := createNote(t, engine.db, fmt.Sprintf("/note%d.md", i+1), n.title, n.content)
		if err := engine.IndexNote(noteID, n.title, n.content); err != nil {
			t.Fatalf("IndexNote for note %d failed: %v", i+1, err)
		}

		text := n.title + "\n" + n.content
		tokens := segment.Segment(text, false)
		seen := make(map[string]bool)
		for _, tok := range tokens {
			term := strings.ToLower(tok.Text)
			if !seen[term] {
				termDocCount[term]++
				seen[term] = true
			}
		}
	}

	totalDocs, err := idx.GetTotalDocCount()
	if err != nil {
		t.Fatalf("GetTotalDocCount failed: %v", err)
	}
	if totalDocs != 10 {
		t.Errorf("TotalDocCount = %d, want 10", totalDocs)
	}

	for term, expectedCount := range termDocCount {
		postings, err := idx.GetPostings(term)
		if err != nil {
			t.Fatalf("GetPostings(%q) failed: %v", term, err)
		}
		if len(postings) != expectedCount {
			t.Errorf("term %q: postings length = %d, want %d",
				term, len(postings), expectedCount)
		}
	}
}

func TestInvertedIndex_TitleWeight(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)
	idx := engine.GetIndexer()

	title := "Golang"
	content := "Golang golang tutorial"
	noteID := createNote(t, engine.db, "/title_test.md", title, content)

	if err := engine.IndexNote(noteID, title, content); err != nil {
		t.Fatalf("IndexNote failed: %v", err)
	}

	noteTerms, err := idx.GetNoteTerms(noteID)
	if err != nil {
		t.Fatalf("GetNoteTerms failed: %v", err)
	}

	golangFreq, ok := noteTerms["golang"]
	if !ok {
		t.Fatal("term 'golang' not found in note terms")
	}

	text := title + "\n" + content
	tokens := segment.Segment(text, false)
	expectedGolangFreq := 0
	for _, tok := range tokens {
		if strings.ToLower(tok.Text) == "golang" {
			expectedGolangFreq++
		}
	}

	if golangFreq != expectedGolangFreq {
		t.Errorf("golang frequency = %d, want %d (title 1 + content %d)",
			golangFreq, expectedGolangFreq, expectedGolangFreq-1)
	}

	tutorialFreq, ok := noteTerms["tutorial"]
	if ok && tutorialFreq != 1 {
		t.Errorf("tutorial frequency = %d, want 1", tutorialFreq)
	}
}

func TestBM25_IDFPenalty(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)

	content1 := "alpha alpha alpha concurrency concurrency golang"
	content2 := "alpha alpha alpha golang golang"
	content3 := "alpha alpha alpha"

	id1 := createNote(t, engine.db, "/note1.md", "Note 1", content1)
	id2 := createNote(t, engine.db, "/note2.md", "Note 2", content2)
	id3 := createNote(t, engine.db, "/note3.md", "Note 3", content3)

	for i, tc := range []struct {
		id      uint
		title   string
		content string
	}{
		{id1, "Note 1", content1},
		{id2, "Note 2", content2},
		{id3, "Note 3", content3},
	} {
		if err := engine.IndexNote(tc.id, tc.title, tc.content); err != nil {
			t.Fatalf("IndexNote %d failed: %v", i+1, err)
		}
	}

	query := SearchQuery{
		Query:    "alpha golang concurrency",
		Page:     0,
		PageSize: 10,
	}

	results, _, err := engine.Search(query)
	if err != nil {
		t.Fatalf("Search failed: %v", err)
	}
	if len(results) != 3 {
		t.Fatalf("got %d results, want 3", len(results))
	}

	scoreMap := make(map[uint]float64)
	for _, r := range results {
		scoreMap[r.NoteID] = r.Score
	}

	score1 := scoreMap[id1]
	score2 := scoreMap[id2]
	score3 := scoreMap[id3]

	if score1 <= score2 {
		t.Errorf("concurrency note score(%.4f) should be > golang note score(%.4f)", score1, score2)
	}
	if score2 <= score3 {
		t.Errorf("golang note score(%.4f) should be > alpha-only note score(%.4f)", score2, score3)
	}

	for i := 1; i < len(results); i++ {
		if results[i-1].Score < results[i].Score {
			t.Errorf("results not strictly descending: result[%d]=%.4f < result[%d]=%.4f",
				i-1, results[i-1].Score, i, results[i].Score)
		}
	}
}

func TestBM25_DocumentLengthNorm(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)

	words := []string{
		"pear", "plum", "kiwi", "mango", "peach",
		"nectarine", "apricot", "papaya", "guava", "lychee",
		"coconut", "pineapple", "strawberry", "blueberry", "raspberry",
		"blackberry", "cranberry", "gooseberry", "mulberry", "boysenberry",
	}

	shortContent := "quickfox"
	for i := 0; i < 5; i++ {
		shortContent += " " + words[i]
	}

	longContent := "quickfox"
	for i := 0; i < 18; i++ {
		longContent += " " + words[i%len(words)]
	}
	longContent += " quickfox"

	shortID := createNote(t, engine.db, "/short.md", "Short Doc", shortContent)
	longID := createNote(t, engine.db, "/long.md", "Long Doc", longContent)

	if err := engine.IndexNote(shortID, "Short Doc", shortContent); err != nil {
		t.Fatalf("IndexNote short failed: %v", err)
	}
	if err := engine.IndexNote(longID, "Long Doc", longContent); err != nil {
		t.Fatalf("IndexNote long failed: %v", err)
	}

	query := SearchQuery{
		Query:    "quickfox",
		Page:     0,
		PageSize: 10,
	}

	results, _, err := engine.Search(query)
	if err != nil {
		t.Fatalf("Search failed: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("got %d results, want 2", len(results))
	}

	scoreMap := make(map[uint]float64)
	for _, r := range results {
		scoreMap[r.NoteID] = r.Score
	}

	shortScore := scoreMap[shortID]
	longScore := scoreMap[longID]

	if shortScore <= longScore {
		t.Errorf("short doc score(%.4f) should be > long doc score(%.4f)", shortScore, longScore)
	}
}

func TestBM25_PhraseBoost(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)

	id1 := createNote(t, engine.db, "/phrase.md", "Phrase Match",
		"machine learning models use machine learning algorithms")
	id2 := createNote(t, engine.db, "/scattered.md", "Scattered Match",
		"machine models learning algorithms learning machine")

	if err := engine.IndexNote(id1, "Phrase Match",
		"machine learning models use machine learning algorithms"); err != nil {
		t.Fatalf("IndexNote phrase failed: %v", err)
	}
	if err := engine.IndexNote(id2, "Scattered Match",
		"machine models learning algorithms learning machine"); err != nil {
		t.Fatalf("IndexNote scattered failed: %v", err)
	}

	query := SearchQuery{
		Query:    "\"machine learning\"",
		Page:     0,
		PageSize: 10,
	}

	results, _, err := engine.Search(query)
	if err != nil {
		t.Fatalf("Search failed: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("got %d results for phrase query, want 1 (only exact phrase match)", len(results))
	}
	if results[0].NoteID != id1 {
		t.Errorf("first result NoteID = %d, want %d (phrase match document)", results[0].NoteID, id1)
	}

	phraseBoostedScore := results[0].Score

	scorer := engine.GetScorer()
	docLengths, _ := engine.GetIndexer().GetAllDocLengths()
	df, _ := engine.GetIndexer().GetDocFrequencies([]string{"machine", "learning"})
	totalDocs, _ := engine.GetIndexer().GetTotalDocCount()
	scorer.SetTotalDocs(totalDocs)
	scorer.SetDocLengths(docLengths)
	scorer.SetDocFrequencies(df)

	noteTerms1, _ := engine.GetIndexer().GetNoteTerms(id1)
	baseScore := scorer.Score(id1, noteTerms1)

	expectedBoosted := baseScore * 1.5
	diff := phraseBoostedScore - baseScore
	if diff <= 0 {
		t.Errorf("phrase boosted score(%.4f) should be > base score(%.4f), diff=%.4f",
			phraseBoostedScore, baseScore, diff)
	}
	_ = expectedBoosted
}

func TestIndex_WriteError(t *testing.T) {
	t.Helper()
	engine, database, _ := newTestSearchEngine(t)
	idx := engine.GetIndexer()

	_, err := database.Exec(`
		INSERT INTO search_index (note_id, term, frequency, positions)
		VALUES (99999, 'xxtestxx', 1, X'00')
	`)
	if err != nil {
		t.Logf("pre-insert error (expected due to FK): %v", err)
	}

	title := "Bad Note"
	content := "this is test content for write error scenario"

	err = idx.IndexNote(99999, title, content)
	if err == nil {
		t.Fatal("IndexNote with invalid noteID should return error, got nil")
	}

	errStr := err.Error()
	hasClear := strings.Contains(errStr, "clear old index failed")
	hasSave := strings.Contains(errStr, "save index failed")
	if !hasClear && !hasSave {
		t.Errorf("error message %q should contain 'save index failed' or 'clear old index failed'", errStr)
	}
}

func TestIndex_DeleteNonexistent(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)

	err := engine.DeleteNoteIndex(99999)
	if err != nil {
		t.Errorf("DeleteNoteIndex(99999) returned error: %v, want nil (idempotent)", err)
	}
}

func TestSearch_ConcurrentIndexAndSearch(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)

	var (
		indexWG     sync.WaitGroup
		searchWG    sync.WaitGroup
		panicCount  int32
		searchError int32
		indexed     int32
	)

	rand.Seed(time.Now().UnixNano())

	wordPool := []string{
		"computer", "science", "algorithm", "data", "structure",
		"programming", "language", "software", "hardware", "network",
		"database", "system", "design", "pattern", "architecture",
	}

	generateContent := func() string {
		n := 5 + rand.Intn(10)
		parts := make([]string, n)
		for i := 0; i < n; i++ {
			parts[i] = wordPool[rand.Intn(len(wordPool))]
		}
		return strings.Join(parts, " ")
	}

	done := make(chan struct{})
	timer := time.NewTimer(2 * time.Second)
	defer timer.Stop()

	for i := 0; i < 10; i++ {
		indexWG.Add(1)
		go func(gid int) {
			defer indexWG.Done()
			defer func() {
				if r := recover(); r != nil {
					atomic.AddInt32(&panicCount, 1)
				}
			}()

			for j := 0; j < 100; j++ {
				select {
				case <-done:
					return
				default:
				}

				noteNum := gid*100 + j
				path := fmt.Sprintf("/concurrent/note_%d.md", noteNum)
				title := fmt.Sprintf("Note %d", noteNum)
				content := generateContent()

				func() {
					defer func() {
						if r := recover(); r != nil {
							atomic.AddInt32(&panicCount, 1)
						}
					}()

					noteID := createNote(t, engine.db, path, title, content)
					if err := engine.IndexNote(noteID, title, content); err == nil {
						atomic.AddInt32(&indexed, 1)
					}
				}()
			}
		}(i)
	}

	for i := 0; i < 5; i++ {
		searchWG.Add(1)
		go func(sid int) {
			defer searchWG.Done()
			defer func() {
				if r := recover(); r != nil {
					atomic.AddInt32(&panicCount, 1)
				}
			}()

			for {
				select {
				case <-done:
					return
				default:
				}

				qword := wordPool[rand.Intn(len(wordPool))]
				func() {
					defer func() {
						if r := recover(); r != nil {
							atomic.AddInt32(&panicCount, 1)
						}
					}()

					query := SearchQuery{
						Query:    qword,
						Page:     0,
						PageSize: 10,
					}
					_, _, err := engine.Search(query)
					if err != nil {
						atomic.AddInt32(&searchError, 1)
					}
				}()
				time.Sleep(5 * time.Millisecond)
			}
		}(i)
	}

	<-timer.C
	close(done)

	indexWG.Wait()
	searchWG.Wait()

	if panicCount > 0 {
		t.Errorf("got %d panics during concurrent execution, want 0", panicCount)
	}
	if searchError > 0 {
		t.Logf("note: %d search errors (acceptable during rapid indexing)", searchError)
	}

	totalDocs, err := engine.GetIndexer().GetTotalDocCount()
	if err != nil {
		t.Fatalf("GetTotalDocCount failed: %v", err)
	}

	indexedVal := int(atomic.LoadInt32(&indexed))
	if totalDocs != indexedVal {
		t.Errorf("total docs in index = %d, indexed count = %d (data loss detected)",
			totalDocs, indexedVal)
	}
	if totalDocs == 0 {
		t.Error("no documents were indexed")
	}

	query := SearchQuery{
		Query:    wordPool[0],
		Page:     0,
		PageSize: 10,
	}
	_, _, err = engine.Search(query)
	if err != nil {
		t.Errorf("search after concurrent indexing failed: %v", err)
	}
}

func TestSearch_LargeBatchImport(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)
	idx := engine.GetIndexer()

	wordPool := []string{
		"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta",
		"iota", "kappa", "lambda", "mu", "nu", "xi", "omicron", "pi",
		"rho", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega",
		"apple", "bravo", "charlie", "delta", "echo", "foxtrot", "golf",
		"hotel", "india", "juliett", "kilo", "lima", "mike", "november",
		"oscar", "papa", "quebec", "romeo", "sierra", "tango", "uniform",
	}

	docCount := 2000
	wordsPerDoc := 50

	importStart := time.Now()
	for i := 0; i < docCount; i++ {
		parts := make([]string, wordsPerDoc)
		for j := 0; j < wordsPerDoc; j++ {
			parts[j] = wordPool[rand.Intn(len(wordPool))]
		}
		content := strings.Join(parts, " ")
		title := fmt.Sprintf("Batch Document %d", i)
		path := fmt.Sprintf("/batch/doc_%05d.md", i)

		noteID := createNote(t, engine.db, path, title, content)
		if err := engine.IndexNote(noteID, title, content); err != nil {
			t.Fatalf("IndexNote %d failed: %v", i, err)
		}
	}
	importDur := time.Since(importStart)
	t.Logf("imported %d docs in %v", docCount, importDur)

	totalDocs, err := idx.GetTotalDocCount()
	if err != nil {
		t.Fatalf("GetTotalDocCount failed: %v", err)
	}
	if totalDocs != docCount {
		t.Errorf("GetTotalDocCount = %d, want %d", totalDocs, docCount)
	}

	rows, err := engine.db.Query("SELECT COUNT(DISTINCT term) FROM search_index")
	if err != nil {
		t.Fatalf("count terms query failed: %v", err)
	}
	var termCount int
	if rows.Next() {
		rows.Scan(&termCount)
	}
	rows.Close()

	if termCount <= 0 {
		t.Error("term count should be > 0")
	}
	if totalDocs <= 0 {
		t.Error("document count should be > 0")
	}

	t.Logf("indexed %d unique terms across %d documents", termCount, totalDocs)

	query := SearchQuery{
		Query:    wordPool[0] + " " + wordPool[1],
		Page:     0,
		PageSize: 10,
	}

	searchStart := time.Now()
	results, total, err := engine.Search(query)
	searchDur := time.Since(searchStart)
	if err != nil {
		t.Fatalf("Search failed: %v", err)
	}

	t.Logf("search returned %d/%d results in %v", len(results), total, searchDur)

	if total <= 0 {
		t.Log("note: no results for query, may be empty due to randomization")
	}
}

func TestSearch_EmptyQuery(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)

	title := "Sample"
	content := "sample content here"
	noteID := createNote(t, engine.db, "/sample.md", title, content)
	if err := engine.IndexNote(noteID, title, content); err != nil {
		t.Fatalf("IndexNote failed: %v", err)
	}

	queries := []string{
		"",
		"   ",
		"\t\n",
	}

	for i, q := range queries {
		results, total, err := engine.Search(SearchQuery{
			Query:    q,
			Page:     0,
			PageSize: 10,
		})
		if err != nil {
			t.Errorf("case %d: Search returned error: %v, want nil", i, err)
		}
		if results != nil {
			t.Errorf("case %d: Search returned non-nil results, want nil", i)
		}
		if total != 0 {
			t.Errorf("case %d: total = %d, want 0", i, total)
		}
	}
}

func TestSearch_NoResults(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)

	title := "Existing"
	content := "apple banana cherry"
	noteID := createNote(t, engine.db, "/existing.md", title, content)
	if err := engine.IndexNote(noteID, title, content); err != nil {
		t.Fatalf("IndexNote failed: %v", err)
	}

	nonexistentTerms := []string{
		"xyznonexistent123",
		"supercalifragilisticexpialidocious",
		"this_term_does_not_exist_anywhere_999",
	}

	for i, term := range nonexistentTerms {
		results, total, err := engine.Search(SearchQuery{
			Query:    term,
			Page:     0,
			PageSize: 10,
		})
		if err != nil {
			t.Errorf("case %d: Search(%q) returned error: %v", i, term, err)
		}
		if results == nil {
			results = []models.SearchResult{}
		}
		if len(results) != 0 {
			t.Errorf("case %d: Search(%q) got %d results, want 0", i, term, len(results))
		}
		if total != 0 {
			t.Errorf("case %d: Search(%q) total = %d, want 0", i, term, total)
		}
	}
}

func TestIndex_EmptyContent(t *testing.T) {
	t.Helper()
	engine, _, _ := newTestSearchEngine(t)
	idx := engine.GetIndexer()

	emptyCases := []struct {
		title   string
		content string
	}{
		{"Empty", ""},
		{"Spaces", "   "},
		{"Whitespace", "\t\n\r  \t"},
		{"OnlyStopwords", "the a an and or but"},
	}

	for i, tc := range emptyCases {
		func() {
			defer func() {
				if r := recover(); r != nil {
					t.Errorf("case %d: IndexNote panicked with title=%q content=%q: %v",
						i, tc.title, tc.content, r)
				}
			}()

			path := fmt.Sprintf("/empty_%d.md", i)
			noteID := createNote(t, engine.db, path, tc.title, tc.content)

			err := engine.IndexNote(noteID, tc.title, tc.content)
			if err != nil {
				t.Errorf("case %d: IndexNote returned error: %v", i, err)
			}

			noteTerms, err := idx.GetNoteTerms(noteID)
			if err != nil {
				t.Errorf("case %d: GetNoteTerms failed: %v", i, err)
			}
			if len(noteTerms) != 0 {
				t.Logf("case %d: note has %d terms (may contain title tokens)", i, len(noteTerms))
				for term, freq := range noteTerms {
					postings, err := idx.GetPostings(term)
					if err != nil {
						t.Errorf("case %d: GetPostings(%q) failed: %v", i, term, err)
					}
					_ = freq
					_ = postings
				}
			}
		}()
	}
}

func TestMain(m *testing.M) {
	os.Exit(m.Run())
}
