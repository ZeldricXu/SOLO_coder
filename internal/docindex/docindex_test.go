package docindex

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"techplatform/internal/testdata"
	"techplatform/pkg/common"
	"techplatform/pkg/common/utils"
	"techplatform/pkg/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type docBuilder struct {
	doc *Document
}

func newDocBuilder() *docBuilder {
	return &docBuilder{
		doc: &Document{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Title:       "Test Document",
			Content:     "This is a test document about golang and microservices.",
			Source:      common.DocSourceLocal,
			ContentType: ".md",
			Tags:        "golang,microservices,test",
			Author:      "test-author",
			Language:    "markdown",
			Permissions: `{"role": "viewer", "groups": ["everyone"]}`,
			IndexedAt:   time.Now(),
			Checksum:    utils.MD5("This is a test document about golang and microservices."),
		},
	}
}

func (b *docBuilder) withTitle(title string) *docBuilder {
	b.doc.Title = title
	return b
}

func (b *docBuilder) withContent(content string) *docBuilder {
	b.doc.Content = content
	b.doc.Checksum = utils.MD5(content)
	return b
}

func (b *docBuilder) withSource(source string) *docBuilder {
	b.doc.Source = source
	return b
}

func (b *docBuilder) withTags(tags string) *docBuilder {
	b.doc.Tags = tags
	return b
}

func (b *docBuilder) withAuthor(author string) *docBuilder {
	b.doc.Author = author
	return b
}

func (b *docBuilder) withContentType(ct string) *docBuilder {
	b.doc.ContentType = ct
	return b
}

func (b *docBuilder) withPermissions(perms string) *docBuilder {
	b.doc.Permissions = perms
	return b
}

func (b *docBuilder) build() *Document {
	return b.doc
}

func setupIndexManager(t *testing.T) (*IndexManager, func()) {
	t.Helper()
	dao, daoCleanup := testdata.NewTestDAO("")
	indexPath := filepath.Join(os.TempDir(), fmt.Sprintf("test_index_%d", time.Now().UnixNano()))
	im := NewIndexManager(dao, indexPath)
	cleanup := func() {
		daoCleanup()
		os.RemoveAll(indexPath)
	}
	return im, cleanup
}

func TestNewIndexManager(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	assert.NotNil(t, im)
	assert.NotNil(t, im.sources)
	assert.NotNil(t, im.index)
	assert.NotNil(t, im.stopWords)
	assert.Contains(t, im.sources, common.DocSourceLocal)
	assert.Contains(t, im.sources, common.DocSourceConfluence)
	assert.Contains(t, im.sources, common.DocSourceGitlab)
	assert.Contains(t, im.sources, common.DocSourceNotion)
}

func TestNewIndexManager_DefaultPath(t *testing.T) {
	dao, daoCleanup := testdata.NewTestDAO("")
	defer daoCleanup()

	im := NewIndexManager(dao, "")
	assert.NotNil(t, im)
	os.RemoveAll("./index")
}

func TestIndexDocument(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	assert.Greater(t, len(im.index), 0)
}

func TestIndexDocument_DuplicateChecksum(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc1 := newDocBuilder().withContent("duplicate content").build()
	err := im.IndexDocument(doc1)
	require.NoError(t, err)

	doc2 := newDocBuilder().withContent("duplicate content").withTitle("Updated Title").build()
	err = im.IndexDocument(doc2)
	require.NoError(t, err)

	result, _ := im.ListAll(1, 10, "")
	assert.Equal(t, int64(1), result.Total)
}

func TestIndexDocument_DifferentContent(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc1 := newDocBuilder().withContent("first document").build()
	err := im.IndexDocument(doc1)
	require.NoError(t, err)

	doc2 := newDocBuilder().withContent("second document").build()
	err = im.IndexDocument(doc2)
	require.NoError(t, err)

	result, _ := im.ListAll(1, 10, "")
	assert.Equal(t, int64(2), result.Total)
}

func TestSearch_BasicKeyword(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().
		withContent("Golang is a programming language designed for building simple, reliable software.").
		build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	query := SearchQuery{
		Keyword:  "golang",
		Page:     1,
		PageSize: 10,
	}
	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}

	result, err := im.Search(context.Background(), query, perms)
	require.NoError(t, err)
	assert.Greater(t, result.Total, int64(0))
}

func TestSearch_NoResults(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().withContent("Hello world document").build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	query := SearchQuery{
		Keyword:  "xyznonexistent",
		Page:     1,
		PageSize: 10,
	}
	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}

	result, err := im.Search(context.Background(), query, perms)
	require.NoError(t, err)
	assert.Equal(t, int64(0), result.Total)
}

func TestSearch_EmptyKeyword(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	for i := 0; i < 3; i++ {
		doc := newDocBuilder().
			withTitle(fmt.Sprintf("Doc %d", i)).
			withContent(fmt.Sprintf("Content for document %d", i)).
			build()
		err := im.IndexDocument(doc)
		require.NoError(t, err)
	}

	query := SearchQuery{
		Keyword:  "",
		Page:     1,
		PageSize: 10,
	}
	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}

	result, err := im.Search(context.Background(), query, perms)
	require.NoError(t, err)
	assert.Equal(t, int64(3), result.Total)
}

func TestSearch_FilterBySource(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc1 := newDocBuilder().withSource(common.DocSourceLocal).withContent("local doc").build()
	err := im.IndexDocument(doc1)
	require.NoError(t, err)

	doc2 := newDocBuilder().withSource(common.DocSourceConfluence).withContent("confluence doc").build()
	err = im.IndexDocument(doc2)
	require.NoError(t, err)

	query := SearchQuery{
		Keyword:  "",
		Sources:  []string{common.DocSourceLocal},
		Page:     1,
		PageSize: 10,
	}
	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}

	result, err := im.Search(context.Background(), query, perms)
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.Total)
}

func TestSearch_FilterByContentType(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc1 := newDocBuilder().withContentType(".md").withContent("markdown doc").build()
	err := im.IndexDocument(doc1)
	require.NoError(t, err)

	doc2 := newDocBuilder().withContentType(".go").withContent("go source file").build()
	err = im.IndexDocument(doc2)
	require.NoError(t, err)

	query := SearchQuery{
		Keyword:     "",
		ContentType: ".md",
		Page:        1,
		PageSize:    10,
	}
	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}

	result, err := im.Search(context.Background(), query, perms)
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.Total)
}

func TestSearch_FilterByAuthor(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc1 := newDocBuilder().withAuthor("alice").withContent("alice doc").build()
	err := im.IndexDocument(doc1)
	require.NoError(t, err)

	doc2 := newDocBuilder().withAuthor("bob").withContent("bob doc").build()
	err = im.IndexDocument(doc2)
	require.NoError(t, err)

	query := SearchQuery{
		Keyword:  "",
		Author:   "alice",
		Page:     1,
		PageSize: 10,
	}
	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}

	result, err := im.Search(context.Background(), query, perms)
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.Total)
}

func TestSearch_FilterByDate(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().withContent("dated document").build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	now := time.Now()
	yesterday := now.Add(-24 * time.Hour)
	tomorrow := now.Add(24 * time.Hour)

	query := SearchQuery{
		Keyword:  "",
		DateFrom: &yesterday,
		DateTo:   &tomorrow,
		Page:     1,
		PageSize: 10,
	}
	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}

	result, err := im.Search(context.Background(), query, perms)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, result.Total, int64(1))
}

func TestSearch_Pagination(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	for i := 0; i < 15; i++ {
		doc := newDocBuilder().
			withTitle(fmt.Sprintf("Page Doc %d", i)).
			withContent(fmt.Sprintf("Content number %d about golang", i)).
			build()
		err := im.IndexDocument(doc)
		require.NoError(t, err)
	}

	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}

	result1, err := im.Search(context.Background(), SearchQuery{Keyword: "", Page: 1, PageSize: 5}, perms)
	require.NoError(t, err)
	assert.Equal(t, int64(15), result1.Total)
	items1, ok := result1.Items.([]interface{})
	if ok {
		assert.Equal(t, 5, len(items1))
	}

	result2, err := im.Search(context.Background(), SearchQuery{Keyword: "", Page: 2, PageSize: 5}, perms)
	require.NoError(t, err)
	assert.Equal(t, int64(15), result2.Total)
}

func TestSearch_DefaultPagination(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}
	query := SearchQuery{Keyword: "", Page: 0, PageSize: 0}

	result, err := im.Search(context.Background(), query, perms)
	require.NoError(t, err)
	assert.Equal(t, 1, result.Page)
	assert.Equal(t, 20, result.PageSize)
}

func TestGetDocument(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}
	found, err := im.GetDocument(doc.ID, perms)
	require.NoError(t, err)
	assert.Equal(t, doc.Title, found.Title)
	assert.Equal(t, 1, found.Views)
}

func TestGetDocument_NotFound(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}
	_, err := im.GetDocument("nonexistent-id", perms)
	assert.Error(t, err)
}

func TestGetDocument_ViewsIncrement(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}

	_, err = im.GetDocument(doc.ID, perms)
	require.NoError(t, err)

	var dbDoc Document
	im.db.DB().First(&dbDoc, "id = ?", doc.ID)
	assert.GreaterOrEqual(t, dbDoc.Views, 1)
}

func TestDeleteDocument(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().withContent("to be deleted").build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	err = im.DeleteDocument(doc.ID)
	require.NoError(t, err)

	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}
	_, err = im.GetDocument(doc.ID, perms)
	assert.Error(t, err)
}

func TestDeleteDocument_NotFound(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	err := im.DeleteDocument("nonexistent-id")
	assert.Error(t, err)
}

func TestDeleteDocument_CleansIndex(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().withContent("unique keyword for cleanup test").build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	indexSizeBefore := len(im.index)

	err = im.DeleteDocument(doc.ID)
	require.NoError(t, err)

	indexSizeAfter := len(im.index)
	assert.LessOrEqual(t, indexSizeAfter, indexSizeBefore)
}

func TestListAll(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	for i := 0; i < 5; i++ {
		doc := newDocBuilder().
			withTitle(fmt.Sprintf("List Doc %d", i)).
			withContent(fmt.Sprintf("content %d", i)).
			build()
		err := im.IndexDocument(doc)
		require.NoError(t, err)
	}

	result, err := im.ListAll(1, 3, "")
	require.NoError(t, err)
	assert.Equal(t, int64(5), result.Total)
	assert.Equal(t, 3, len(result.Items.([]Document)))
}

func TestListAll_FilterBySource(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc1 := newDocBuilder().withSource(common.DocSourceLocal).withContent("local").build()
	im.IndexDocument(doc1)

	doc2 := newDocBuilder().withSource(common.DocSourceConfluence).withContent("confluence").build()
	im.IndexDocument(doc2)

	result, err := im.ListAll(1, 10, common.DocSourceLocal)
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.Total)
}

func TestGetStats(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	for i := 0; i < 3; i++ {
		doc := newDocBuilder().
			withSource(common.DocSourceLocal).
			withContent(fmt.Sprintf("stats doc %d", i)).
			build()
		err := im.IndexDocument(doc)
		require.NoError(t, err)
	}

	stats := im.GetStats()
	assert.GreaterOrEqual(t, stats["total_documents"], int64(3))
	assert.NotNil(t, stats["sources"])
	assert.NotNil(t, stats["indexed_terms"])
}

func TestRebuildIndex(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().withContent("rebuild test content golang").build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	im.mu.Lock()
	im.index = make(map[string]map[string][]int)
	im.mu.Unlock()

	assert.Equal(t, 0, len(im.index))

	err = im.RebuildIndex()
	require.NoError(t, err)
	assert.Greater(t, len(im.index), 0)
}

func TestRebuildIndex_MultipleDocs(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	for i := 0; i < 5; i++ {
		doc := newDocBuilder().
			withContent(fmt.Sprintf("document about topic %d with golang", i)).
			build()
		err := im.IndexDocument(doc)
		require.NoError(t, err)
	}

	err := im.RebuildIndex()
	require.NoError(t, err)

	query := SearchQuery{Keyword: "golang", Page: 1, PageSize: 10}
	perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}
	result, err := im.Search(context.Background(), query, perms)
	require.NoError(t, err)
	assert.Greater(t, result.Total, int64(0))
}

func TestUpdateDocumentPermission(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().build()
	err := im.IndexDocument(doc)
	require.NoError(t, err)

	newPerms := DocumentPermission{
		Role:      "admin",
		Groups:    []string{"engineering"},
		CanView:   true,
		CanEdit:   true,
		CanDelete: true,
	}
	err = im.UpdateDocumentPermission(doc.ID, newPerms)
	require.NoError(t, err)
}

func TestUpdateDocumentPermission_NotFound(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	perms := DocumentPermission{Role: "admin", CanView: true}
	err := im.UpdateDocumentPermission("nonexistent", perms)
	assert.Equal(t, common.ErrNotFound, err)
}

func TestCheckPermission_NilPerms(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().build()
	assert.True(t, im.checkPermission(doc, nil))
}

func TestCheckPermission_AdminRole(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().build()
	perms := &DocumentPermission{UserID: "user1", Role: "admin"}
	assert.True(t, im.checkPermission(doc, perms))
}

func TestCheckPermission_CanView(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().build()
	perms := &DocumentPermission{UserID: "user1", Role: "viewer", CanView: true}
	assert.True(t, im.checkPermission(doc, perms))
}

func TestCheckPermission_EveryoneGroup(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().
		withPermissions(`{"role": "viewer", "groups": ["everyone"]}`).
		build()
	perms := &DocumentPermission{UserID: "user1", Role: "viewer", Groups: []string{"engineering"}}
	assert.True(t, im.checkPermission(doc, perms))
}

func TestCheckPermission_SpecificGroup(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().
		withPermissions(`{"role": "viewer", "groups": ["engineering", "backend"]}`).
		build()
	perms := &DocumentPermission{UserID: "user1", Role: "viewer", Groups: []string{"backend"}}
	assert.True(t, im.checkPermission(doc, perms))
}

func TestCheckPermission_Denied(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().
		withPermissions(`{"role": "private", "groups": ["leadership"]}`).
		build()
	perms := &DocumentPermission{UserID: "user1", Role: "viewer", Groups: []string{"interns"}}
	assert.False(t, im.checkPermission(doc, perms))
}

func TestCheckPermission_InvalidJSON(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().withPermissions("invalid json").build()
	perms := &DocumentPermission{UserID: "user1", Role: "viewer"}
	assert.True(t, im.checkPermission(doc, perms))
}

func TestSyncFromSource_Local(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	dir, dirCleanup := testdata.CreateLocalDocDir(map[string]string{
		"doc1.md":  "# Golang Guide\nThis is a guide about golang programming.",
		"doc2.txt": "Python tutorial for beginners.",
	})
	defer dirCleanup()

	count, err := im.SyncFromSource(context.Background(), common.DocSourceLocal, map[string]interface{}{
		"path": dir,
	})
	require.NoError(t, err)
	assert.Equal(t, 2, count)
}

func TestSyncFromSource_LocalWithSubdirs(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	dir, dirCleanup := testdata.CreateLocalDocDir(map[string]string{
		"root.md":       "Root document",
		"sub/nested.md": "Nested document about golang",
	})
	defer dirCleanup()

	count, err := im.SyncFromSource(context.Background(), common.DocSourceLocal, map[string]interface{}{
		"path": dir,
	})
	require.NoError(t, err)
	assert.Equal(t, 2, count)
}

func TestSyncFromSource_LocalFilterExtensions(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	dir, dirCleanup := testdata.CreateLocalDocDir(map[string]string{
		"doc.md":    "Markdown document",
		"code.go":   "package main",
		"image.png": "binary",
	})
	defer dirCleanup()

	count, err := im.SyncFromSource(context.Background(), common.DocSourceLocal, map[string]interface{}{
		"path":       dir,
		"extensions": []string{".md"},
	})
	require.NoError(t, err)
	assert.Equal(t, 1, count)
}

func TestSyncFromSource_LocalNoPath(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	_, err := im.SyncFromSource(context.Background(), common.DocSourceLocal, map[string]interface{}{})
	assert.Error(t, err)
}

func TestSyncFromSource_UnknownSource(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	_, err := im.SyncFromSource(context.Background(), "unknown_source", map[string]interface{}{})
	assert.Error(t, err)
}

func TestSyncFromSource_CancelledContext(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	dir, dirCleanup := testdata.CreateLocalDocDir(map[string]string{
		"doc1.md": "content1",
		"doc2.md": "content2",
		"doc3.md": "content3",
	})
	defer dirCleanup()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := im.SyncFromSource(ctx, common.DocSourceLocal, map[string]interface{}{
		"path": dir,
	})
	assert.Error(t, err)
}

func TestSyncFromSource_ConfluenceNoURL(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	_, err := im.SyncFromSource(context.Background(), common.DocSourceConfluence, map[string]interface{}{})
	assert.Error(t, err)
}

func TestSyncFromSource_ConfluenceWithURL(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	count, err := im.SyncFromSource(context.Background(), common.DocSourceConfluence, map[string]interface{}{
		"url":   "https://confluence.example.com",
		"token": "test-token",
	})
	require.NoError(t, err)
	assert.Equal(t, 0, count)
}

func TestSyncFromSource_GitlabNoURL(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	_, err := im.SyncFromSource(context.Background(), common.DocSourceGitlab, map[string]interface{}{})
	assert.Error(t, err)
}

func TestSyncFromSource_NotionNoToken(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	_, err := im.SyncFromSource(context.Background(), common.DocSourceNotion, map[string]interface{}{})
	assert.Error(t, err)
}

func TestConcurrentIndexAndSearch(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	var wg sync.WaitGroup
	const goroutines = 20

	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			doc := newDocBuilder().
				withTitle(fmt.Sprintf("Concurrent Doc %d", idx)).
				withContent(fmt.Sprintf("Concurrent content about topic %d golang", idx)).
				build()
			im.IndexDocument(doc)
		}(i)
	}

	wg.Wait()

	result, _ := im.ListAll(1, goroutines+5, "")
	assert.Equal(t, int64(goroutines), result.Total)
}

func TestConcurrentSearch(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	for i := 0; i < 10; i++ {
		doc := newDocBuilder().
			withContent(fmt.Sprintf("Searchable content about golang %d", i)).
			build()
		err := im.IndexDocument(doc)
		require.NoError(t, err)
	}

	var wg sync.WaitGroup
	var searchCount atomic.Int32
	const searches = 30

	for i := 0; i < searches; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			query := SearchQuery{Keyword: "golang", Page: 1, PageSize: 10}
			perms := &DocumentPermission{UserID: "user1", Role: "admin", CanView: true}
			_, err := im.Search(context.Background(), query, perms)
			if err == nil {
				searchCount.Add(1)
			}
		}()
	}

	wg.Wait()
	assert.Equal(t, int32(searches), searchCount.Load())
}

func TestConcurrentIndexAndDelete(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	docs := make([]string, 10)
	for i := 0; i < 10; i++ {
		doc := newDocBuilder().
			withContent(fmt.Sprintf("Index and delete test %d", i)).
			build()
		im.IndexDocument(doc)
		docs[i] = doc.ID
	}

	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			im.DeleteDocument(docs[idx])
		}(i)
	}

	for i := 5; i < 10; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			doc := newDocBuilder().
				withContent(fmt.Sprintf("New doc after delete %d", idx)).
				build()
			im.IndexDocument(doc)
		}(i)
	}

	wg.Wait()

	result, _ := im.ListAll(1, 20, "")
	assert.GreaterOrEqual(t, result.Total, int64(5))
}

func TestTokenize(t *testing.T) {
	tests := []struct {
		input    string
		expected int
	}{
		{"hello world", 2},
		{"golang微服务", 1},
		{"test123 number", 2},
		{"", 0},
		{"   ", 0},
		{"a b c d e", 5},
	}

	for _, tt := range tests {
		tokens := tokenize(tt.input)
		assert.Equal(t, tt.expected, len(tokens), "input: %q", tt.input)
	}
}

func TestTokenize_Chinese(t *testing.T) {
	tokens := tokenize("这是一个中文测试")
	assert.Greater(t, len(tokens), 0)
}

func TestCalculateChecksum(t *testing.T) {
	content := "test content"
	h := sha256.New()
	h.Write([]byte(content))
	expected := hex.EncodeToString(h.Sum(nil))

	result := calculateChecksum(content)
	assert.Equal(t, expected, result)
}

func TestCalculateChecksum_DifferentContent(t *testing.T) {
	cs1 := calculateChecksum("content1")
	cs2 := calculateChecksum("content2")
	assert.NotEqual(t, cs1, cs2)
}

func TestGenerateSummary(t *testing.T) {
	short := "short content"
	assert.Equal(t, short, generateSummary(short))

	long := ""
	for i := 0; i < 300; i++ {
		long += "a"
	}
	summary := generateSummary(long)
	assert.LessOrEqual(t, len(summary), 203)
	assert.Contains(t, summary, "...")
}

func TestDetectLanguage(t *testing.T) {
	tests := []struct {
		ext      string
		expected string
	}{
		{".go", "go"},
		{".java", "java"},
		{".py", "python"},
		{".js", "javascript"},
		{".ts", "typescript"},
		{".md", "markdown"},
		{".yaml", "yaml"},
		{".json", "json"},
		{".unknown", "plain"},
	}

	for _, tt := range tests {
		result := detectLanguage(tt.ext)
		assert.Equal(t, tt.expected, result, "extension: %s", tt.ext)
	}
}

func TestExtractTags(t *testing.T) {
	content := "This is a #golang tutorial about #microservices and #testing"
	tags := extractTags(content)
	assert.Contains(t, tags, "golang")
	assert.Contains(t, tags, "microservices")
	assert.Contains(t, tags, "testing")
}

func TestExtractTags_NoTags(t *testing.T) {
	content := "This content has no tags"
	tags := extractTags(content)
	assert.Equal(t, "", tags)
}

func TestExtractTags_Limit(t *testing.T) {
	content := ""
	for i := 0; i < 20; i++ {
		content += fmt.Sprintf(" #tag%d", i)
	}
	tags := extractTags(content)
	tagCount := len(strings.Split(tags, ","))
	assert.LessOrEqual(t, tagCount, 10)
}

func TestNormalizePagination(t *testing.T) {
	tests := []struct {
		page, pageSize     int
		expectedPage, expectedSize int
	}{
		{1, 10, 1, 10},
		{0, 0, 1, 20},
		{-1, -5, 1, 20},
		{1, 200, 1, 100},
		{5, 50, 5, 50},
	}

	for _, tt := range tests {
		p, ps := normalizePagination(tt.page, tt.pageSize)
		assert.Equal(t, tt.expectedPage, p)
		assert.Equal(t, tt.expectedSize, ps)
	}
}

func TestReadFileContent_Binary(t *testing.T) {
	_, err := readFileContent("test.pdf", ".pdf")
	assert.Error(t, err)
}

func TestReadFileContent_Text(t *testing.T) {
	dir := t.TempDir()
	fp := filepath.Join(dir, "test.md")
	content := "Hello, this is a test file."
	os.WriteFile(fp, []byte(content), 0644)

	result, err := readFileContent(fp, ".md")
	require.NoError(t, err)
	assert.Equal(t, content, result)
}

func TestReadFileContent_LargeFile(t *testing.T) {
	dir := t.TempDir()
	fp := filepath.Join(dir, "large.md")
	largeContent := strings.Repeat("a", 15*1024*1024)
	os.WriteFile(fp, []byte(largeContent), 0644)

	result, err := readFileContent(fp, ".md")
	require.NoError(t, err)
	assert.LessOrEqual(t, len(result), 10*1024*1024)
}

func TestLocalFileSource_Validate(t *testing.T) {
	s := &LocalFileSource{}
	err := s.Validate(map[string]interface{}{
		"path": "/tmp",
	})
	require.NoError(t, err)
	assert.Equal(t, "/tmp", s.rootPath)
}

func TestLocalFileSource_ValidateNoPath(t *testing.T) {
	s := &LocalFileSource{}
	err := s.Validate(map[string]interface{}{})
	assert.Error(t, err)
}

func TestLocalFileSource_Name(t *testing.T) {
	s := &LocalFileSource{}
	assert.Equal(t, common.DocSourceLocal, s.Name())
}

func TestConfluenceSource_Validate(t *testing.T) {
	s := &ConfluenceSource{}
	err := s.Validate(map[string]interface{}{
		"url": "https://confluence.example.com",
	})
	require.NoError(t, err)
}

func TestConfluenceSource_ValidateNoURL(t *testing.T) {
	s := &ConfluenceSource{}
	err := s.Validate(map[string]interface{}{})
	assert.Error(t, err)
}

func TestGitlabSource_Validate(t *testing.T) {
	s := &GitlabSource{}
	err := s.Validate(map[string]interface{}{
		"url": "https://gitlab.example.com",
	})
	require.NoError(t, err)
}

func TestNotionSource_Validate(t *testing.T) {
	s := &NotionSource{}
	err := s.Validate(map[string]interface{}{
		"token": "test-token",
	})
	require.NoError(t, err)
}

func TestFindMatches(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().
		withContent("The quick brown fox jumps over the lazy dog. The fox was very quick.").
		build()

	matches := im.findMatches(doc, "fox")
	assert.Greater(t, len(matches), 0)
	assert.Contains(t, matches[0], "fox")
}

func TestFindMatches_EmptyKeyword(t *testing.T) {
	im, cleanup := setupIndexManager(t)
	defer cleanup()

	doc := newDocBuilder().withContent("some content").build()
	matches := im.findMatches(doc, "")
	assert.Nil(t, matches)
}

func TestIndexManager_ResourceLifecycle(t *testing.T) {
	dao, daoCleanup := testdata.NewTestDAO("")
	indexPath := filepath.Join(os.TempDir(), fmt.Sprintf("lifecycle_%d", time.Now().UnixNano()))
	defer os.RemoveAll(indexPath)

	im1 := NewIndexManager(dao, indexPath)
	assert.NotNil(t, im1)

	for i := 0; i < 5; i++ {
		doc := newDocBuilder().
			withContent(fmt.Sprintf("lifecycle test doc %d", i)).
			build()
		err := im1.IndexDocument(doc)
		require.NoError(t, err)
	}

	im2 := NewIndexManager(dao, indexPath)
	result, _ := im2.ListAll(1, 10, "")
	assert.GreaterOrEqual(t, result.Total, int64(5))

	daoCleanup()
}
