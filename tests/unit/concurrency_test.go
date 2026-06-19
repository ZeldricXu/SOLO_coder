package unit

import (
	"math/rand"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/blevesearch/bleve/v2"
	"github.com/enterprise/knowledgebase/internal/ot"
	"github.com/enterprise/knowledgebase/internal/search"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type concurrentDoc struct {
	mu      sync.Mutex
	content string
	version int64
}

func (cd *concurrentDoc) applyOp(op *ot.Operation) error {
	cd.mu.Lock()
	defer cd.mu.Unlock()
	newContent, err := ot.Apply(cd.content, op)
	if err != nil {
		return err
	}
	cd.content = newContent
	cd.version++
	return nil
}

func (cd *concurrentDoc) getContent() string {
	cd.mu.Lock()
	defer cd.mu.Unlock()
	return cd.content
}

func (cd *concurrentDoc) applyWithTransform(localOps, remoteOps []*ot.Operation) error {
	cd.mu.Lock()
	defer cd.mu.Unlock()
	localPrime, _ := ot.TransformPair(localOps, remoteOps)
	doc := cd.content
	var err error
	for _, op := range remoteOps {
		doc, err = ot.Apply(doc, op)
		if err != nil {
			return err
		}
	}
	for _, op := range localPrime {
			_, err = ot.Apply(doc, op)
			if err != nil {
				return err
			}
		}
	cd.content = doc
	cd.version++
	return nil
}

type quotaCounter struct {
	count int64
}

func (qc *quotaCounter) incrementAtomic(n int64) {
	atomic.AddInt64(&qc.count, n)
}

func (qc *quotaCounter) getAtomic() int64 {
	return atomic.LoadInt64(&qc.count)
}

type permissionChecker struct {
	mu    sync.RWMutex
	perms map[string]map[string]bool
}

func newPermissionChecker() *permissionChecker {
	return &permissionChecker{
		perms: make(map[string]map[string]bool),
	}
}

func (pc *permissionChecker) grant(userID, resourceID string) {
	pc.mu.Lock()
	defer pc.mu.Unlock()
	if pc.perms[userID] == nil {
		pc.perms[userID] = make(map[string]bool)
	}
	pc.perms[userID][resourceID] = true
}

func (pc *permissionChecker) check(userID, resourceID string) bool {
	pc.mu.RLock()
	defer pc.mu.RUnlock()
	userPerms, ok := pc.perms[userID]
	if !ok {
		return false
	}
	return userPerms[resourceID]
}

func TestConcurrent_OTOperations(t *testing.T) {
	t.Parallel()

	const numGoroutines = 10
	const opsPerGoroutine = 10

	initialDoc := "Hello World"

	shared := &concurrentDoc{content: initialDoc}
	var wg sync.WaitGroup
	rng := rand.New(rand.NewSource(time.Now().UnixNano()))
	successfulOps := int64(0)

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			localRng := rand.New(rand.NewSource(rng.Int63() + int64(id)))
			doc := shared.getContent()

			for j := 0; j < opsPerGoroutine; j++ {
				op := ot.RandomOperation(doc, localRng)
				if op.Type == ot.Insert {
					if op.Position > len([]rune(doc)) {
						op.Position = len([]rune(doc))
					}
				} else if op.Type == ot.Delete {
					if op.Position+op.Length > len([]rune(doc)) {
						op.Length = len([]rune(doc)) - op.Position
						if op.Length < 0 {
							op.Length = 0
						}
					}
				}
				newDoc, err := ot.Apply(doc, op)
				if err != nil {
					continue
				}
				doc = newDoc
				err = shared.applyOp(op)
				if err == nil {
					atomic.AddInt64(&successfulOps, 1)
				}
			}
		}(i)
	}

	wg.Wait()

	finalContent := shared.getContent()
	assert.NotEmpty(t, finalContent)
	t.Logf("Successful concurrent operations: %d, final content length: %d", atomic.LoadInt64(&successfulOps), len([]rune(finalContent)))
}

func TestConcurrent_QuotaCounterRace(t *testing.T) {
	t.Parallel()

	const numGoroutines = 100
	const incrementsPerGoroutine = 10

	qc := &quotaCounter{}
	var wg sync.WaitGroup

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < incrementsPerGoroutine; j++ {
				qc.incrementAtomic(1)
			}
		}()
	}

	wg.Wait()

	expected := int64(numGoroutines * incrementsPerGoroutine)
	actual := qc.getAtomic()
	assert.Equal(t, expected, actual, "quota count should match expected value")
}

func TestConcurrent_BleveIndexBackpressure(t *testing.T) {
	t.Parallel()

	const numDocs = 1000
	const workerCount = 10
	const bufferSize = 100

	tmpDir := t.TempDir()
	_ = search.RegisterCustomAnalyzers()
	mapping := search.BuildDocumentMapping()
	idx, err := bleve.New(tmpDir+"/test-idx", mapping)
	require.NoError(t, err)
	defer func() {
		_ = idx.Close()
	}()

	type docEntry struct {
		ID      string
		DocData search.DocumentIndex
	}

	docCh := make(chan docEntry, bufferSize)
	var wg sync.WaitGroup
	var indexWg sync.WaitGroup
	var indexedCount int64

	for i := 0; i < workerCount; i++ {
		indexWg.Add(1)
		go func(workerID int) {
			defer indexWg.Done()
			for entry := range docCh {
				err := idx.Index(entry.ID, entry.DocData)
				if err == nil {
					atomic.AddInt64(&indexedCount, 1)
				}
			}
		}(i)
	}

	for i := 0; i < numDocs; i++ {
		wg.Add(1)
		go func(docID int) {
			defer wg.Done()
			doc := search.DocumentIndex{
				DocID:    string(rune(docID)),
				TenantID:  "test-tenant",
				SpaceID:   "test-space",
				Title:     "Test Document",
				Content:   "This is test content for document",
				Summary:   "Test summary",
				Tags:      []string{"test", "concurrent"},
				AuthorID:  "author-1",
				LangCode:  "zh-CN",
				Status:    "published",
				CreatedAt: time.Now().Unix(),
			}
			docCh <- docEntry{ID: doc.DocID, DocData: doc}
		}(i)
	}

	wg.Wait()
	close(docCh)
	indexWg.Wait()

	assert.Equal(t, int64(numDocs), indexedCount, "all documents should be indexed")

	count, err := idx.DocCount()
	require.NoError(t, err)
	assert.Equal(t, uint64(numDocs), count, "bleve index should contain all docs")
}

func TestConcurrent_MultipleEditors_SameDocument(t *testing.T) {
	t.Parallel()

	const numEditors = 5
	initialDoc := "Initial document content for collaborative editing test"

	doc := &concurrentDoc{content: initialDoc}
	var wg sync.WaitGroup
	opBroadcast := make(chan *ot.Operation, 100)

	for i := 0; i < numEditors; i++ {
		wg.Add(1)
		go func(editorID int) {
			defer wg.Done()
			localRng := rand.New(rand.NewSource(int64(editorID) + time.Now().UnixNano()))
			localDoc := initialDoc

			for j := 0; j < 20; j++ {
				op := ot.RandomOperation(localDoc, localRng)
				newLocal, err := ot.Apply(localDoc, op)
				if err != nil {
					continue
				}
				localDoc = newLocal

				_ = doc.applyOp(op)
				opBroadcast <- op
			}
		}(i)
	}

	wg.Wait()
	close(opBroadcast)

	broadcastOps := make([]*ot.Operation, 0)
	for op := range opBroadcast {
		broadcastOps = append(broadcastOps, op)
	}

	finalContent := doc.getContent()
	assert.NotEmpty(t, finalContent)
	t.Logf("Final document length: %d, operations broadcast: %d", len(finalContent), len(broadcastOps))
}

func TestConcurrent_PermissionCheck(t *testing.T) {
	t.Parallel()

	const numUsers = 50
	const numResources = 20

	pc := newPermissionChecker()
	var wg sync.WaitGroup

	for i := 0; i < numUsers; i++ {
		wg.Add(1)
		go func(userID int) {
			defer wg.Done()
			uid := string(rune(userID))
			for resID := 0; resID < numResources; resID++ {
				if resID%2 == 0 {
					pc.grant(uid, string(rune(resID)))
				}
			}
		}(i)
	}
	wg.Wait()

	for i := 0; i < numUsers; i++ {
		wg.Add(1)
		go func(userID int) {
			defer wg.Done()
			uid := string(rune(userID))
			for resID := 0; resID < numResources; resID++ {
				result := pc.check(uid, string(rune(resID)))
				if resID%2 == 0 {
					assert.True(t, result, "permission should be granted for even resource IDs")
				}
			}
		}(i)
	}

	wg.Wait()
}
