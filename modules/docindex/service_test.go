package docindex

import (
	"context"
	"depguard/test/testutils"
	"errors"
	"fmt"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockSearchIndex struct {
	docs        map[string]map[string]interface{}
	indexErr    error
	deleteErr    error
	searchResult *IndexSearchResult
	searchErr   error
}

func newMockSearchIndex() *mockSearchIndex {
	return &mockSearchIndex{
		docs: make(map[string]map[string]interface{}),
	}
}

func (m *mockSearchIndex) Index(id string, data map[string]interface{}) error {
	if m.indexErr != nil {
		return m.indexErr
	}
	m.docs[id] = data
	return nil
}

func (m *mockSearchIndex) Delete(id string) error {
	if m.deleteErr != nil {
		return m.deleteErr
	}
	delete(m.docs, id)
	return nil
}

func (m *mockSearchIndex) Search(query string, page, size int) (*IndexSearchResult, error) {
	if m.searchErr != nil {
		return nil, m.searchErr
	}
	if m.searchResult != nil {
		return m.searchResult, nil
	}
	var hits []IndexSearchHit
	for id := range m.docs {
		hits = append(hits, IndexSearchHit{ID: id, Score: 1.0})
	}
	return &IndexSearchResult{Total: int64(len(hits)), Hits: hits}, nil
}

func TestCreateDocument_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	index := newMockSearchIndex()
	svc := NewServiceWithDeps(db.DB, index)

	t.Run("should create document and index it", func(t *testing.T) {
		doc := testutils.NewDocBuilder().
			WithTitle("测试文档").
			WithContent("这是测试内容，包含关键词").
			WithSource("wiki").
			WithTags([]string{"test", "api"}).
			Build()

		ctx := context.Background()
		created, err := svc.CreateDocument(ctx, doc)

		assert.NoError(t, err)
		assert.NotEmpty(t, created.ID)
		assert.Equal(t, "测试文档", created.Title)

		var found Document
		err = db.First(&found, "id = ?", created.ID).Error
		assert.NoError(t, err)
		assert.Equal(t, "测试文档", found.Title)

		_, indexed := index.docs[created.ID]
		assert.True(t, indexed)
	})

	t.Run("should handle large content", func(t *testing.T) {
		largeContent := make([]byte, 10000)
		for i := range largeContent {
			largeContent[i] = 'a'
		}
		doc := testutils.NewDocBuilder().
			WithTitle("大内容文档").
			WithContent(string(largeContent)).
			Build()

		created, err := svc.CreateDocument(context.Background(), doc)

		assert.NoError(t, err)
		assert.NotEmpty(t, created.ID)
	})
}

func TestCreateDocument_ExceptionFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should return error when database fails", func(t *testing.T) {
		index := newMockSearchIndex()
		svc := NewServiceWithDeps(db.DB, index)

		db.Close()

		doc := testutils.NewDocBuilder().Build()
		_, err := svc.CreateDocument(context.Background(), doc)

		assert.Error(t, err)
	})

	t.Run("should still save doc when index fails", func(t *testing.T) {
		index := newMockSearchIndex()
		index.indexErr = errors.New("index failed")
		svc := NewServiceWithDeps(db.DB, index)

		doc := testutils.NewDocBuilder().WithTitle("索引失败测试").Build()
		created, err := svc.CreateDocument(context.Background(), doc)

		assert.NoError(t, err)
		assert.NotEmpty(t, created.ID)

		var found Document
		err = db.First(&found, "id = ?", created.ID).Error
		assert.NoError(t, err)
	})
}

func TestGetDocument_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	index := newMockSearchIndex()
	svc := NewServiceWithDeps(db.DB, index)

	t.Run("should get existing document", func(t *testing.T) {
		doc := testutils.NewDocBuilder().WithTitle("获取测试").Build()
		created, _ := svc.CreateDocument(context.Background(), doc)

		found, err := svc.GetDocument(context.Background(), created.ID)

		assert.NoError(t, err)
		assert.Equal(t, created.ID, found.ID)
		assert.Equal(t, "获取测试", found.Title)
	})
}

func TestGetDocument_ExceptionFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	index := newMockSearchIndex()
	svc := NewServiceWithDeps(db.DB, index)

	t.Run("should return error for non-existent document", func(t *testing.T) {
		_, err := svc.GetDocument(context.Background(), "non-existent-id")

		assert.Error(t, err)
	})
}

func TestDeleteDocument_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	index := newMockSearchIndex()
	svc := NewServiceWithDeps(db.DB, index)

	t.Run("should delete document and remove from index", func(t *testing.T) {
		doc := testutils.NewDocBuilder().WithTitle("删除测试").Build()
		created, _ := svc.CreateDocument(context.Background(), doc)

		err := svc.DeleteDocument(context.Background(), created.ID)

		assert.NoError(t, err)

		var found Document
		err = db.First(&found, "id = ?", created.ID).Error
		assert.Error(t, err)

		_, exists := index.docs[created.ID]
		assert.False(t, exists)
	})
}

func TestDeleteDocument_ExceptionFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should return error when index delete fails", func(t *testing.T) {
		index := newMockSearchIndex()
		index.deleteErr = errors.New("delete failed")
		svc := NewServiceWithDeps(db.DB, index)

		doc := testutils.NewDocBuilder().Build()
		created, _ := svc.CreateDocument(context.Background(), doc)

		err := svc.DeleteDocument(context.Background(), created.ID)

		assert.Error(t, err)
	})
}

func TestSearch_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should search and filter by permissions - public doc", func(t *testing.T) {
		index := newMockSearchIndex()
		svc := NewServiceWithDeps(db.DB, index)

		doc1 := testutils.NewDocBuilder().
			WithTitle("公开文档1").
			WithContent("包含关键词测试").
			Build()

		doc2 := testutils.NewDocBuilder().
			WithTitle("公开文档2").
			WithContent("也包含测试").
			Build()

		created1, _ := svc.CreateDocument(context.Background(), doc1)
		created2, _ := svc.CreateDocument(context.Background(), doc2)

		index.docs[created1.ID] = map[string]interface{}{"title": "公开文档1", "content": "包含关键词测试"}
		index.docs[created2.ID] = map[string]interface{}{"title": "公开文档2", "content": "也包含测试"}

		query := testutils.NewSearchQueryBuilder().
			WithQuery("测试").
			WithUser("user-999").
			Build()

		results, total, err := svc.Search(context.Background(), query)

		assert.NoError(t, err)
		assert.Equal(t, int64(2), total)
		assert.Len(t, results, 2)
	})

	t.Run("should filter by owner permission", func(t *testing.T) {
		index := newMockSearchIndex()
		svc := NewServiceWithDeps(db.DB, index)

		publicDoc := testutils.NewDocBuilder().WithTitle("公开文档").Build()
		privateDoc := testutils.NewDocBuilder().
			WithTitle("私有文档").
			AsPrivate("owner-1").
			Build()

		createdPublic, _ := svc.CreateDocument(context.Background(), publicDoc)
		createdPrivate, _ := svc.CreateDocument(context.Background(), privateDoc)

		index.docs[createdPublic.ID] = map[string]interface{}{"title": "公开文档"}
		index.docs[createdPrivate.ID] = map[string]interface{}{"title": "私有文档"}

		query := testutils.NewSearchQueryBuilder().
			WithUser("owner-1").
			Build()

		results, total, err := svc.Search(context.Background(), query)

		assert.NoError(t, err)
		assert.Equal(t, int64(2), total)
		assert.Len(t, results, 2)
	})

	t.Run("should filter by read_users", func(t *testing.T) {
		index := newMockSearchIndex()
		svc := NewServiceWithDeps(db.DB, index)

		privateDoc := testutils.NewDocBuilder().
			WithTitle("可读取私有").
			AsPrivate("owner-1").
			WithReadUsers([]string{"allowed-user"}).
			Build()

		created, _ := svc.CreateDocument(context.Background(), privateDoc)
		index.docs[created.ID] = map[string]interface{}{"title": "可读取私有"}

		query := testutils.NewSearchQueryBuilder().
			WithUser("allowed-user").
			Build()

		results, total, err := svc.Search(context.Background(), query)

		assert.NoError(t, err)
		assert.Equal(t, int64(1), total)
		assert.Len(t, results, 1)
	})

	t.Run("should filter by read_roles", func(t *testing.T) {
		index := newMockSearchIndex()
		svc := NewServiceWithDeps(db.DB, index)

		privateDoc := testutils.NewDocBuilder().
			WithTitle("角色可读文档").
			AsPrivate("owner-1").
			WithReadRoles([]string{"admin", "dev"}).
			Build()

		created, _ := svc.CreateDocument(context.Background(), privateDoc)
		index.docs[created.ID] = map[string]interface{}{"title": "角色可读文档"}

		query := testutils.NewSearchQueryBuilder().
			WithUser("some-user").
			WithRoles([]string{"dev"}).
			Build()

		results, total, err := svc.Search(context.Background(), query)

		assert.NoError(t, err)
		assert.Equal(t, int64(1), total)
		assert.Len(t, results, 1)
	})

	t.Run("should handle pagination", func(t *testing.T) {
		index := newMockSearchIndex()
		svc := NewServiceWithDeps(db.DB, index)

		for i := 0; i < 5; i++ {
			doc := testutils.NewDocBuilder().WithTitle(fmt.Sprintf("文档%d", i)).Build()
			created, _ := svc.CreateDocument(context.Background(), doc)
			index.docs[created.ID] = map[string]interface{}{"title": fmt.Sprintf("文档%d", i)}
		}

		query := testutils.NewSearchQueryBuilder().
			WithPagination(0, 2).
			Build()

		results, total, err := svc.Search(context.Background(), query)

		assert.NoError(t, err)
		assert.Equal(t, int64(5), total)
		assert.Len(t, results, 2)
	})
}

func TestSearch_ExceptionFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	t.Run("should return error when search engine fails", func(t *testing.T) {
		index := newMockSearchIndex()
		index.searchErr = errors.New("search engine down")
		svc := NewServiceWithDeps(db.DB, index)

		query := testutils.NewSearchQueryBuilder().WithQuery("test").Build()

		_, _, err := svc.Search(context.Background(), query)

		assert.Error(t, err)
	})

	t.Run("should return empty when no documents", func(t *testing.T) {
		index := newMockSearchIndex()
		svc := NewServiceWithDeps(db.DB, index)

		query := testutils.NewSearchQueryBuilder().WithQuery("nonexistent").Build()

		results, total, err := svc.Search(context.Background(), query)

		assert.NoError(t, err)
		assert.Equal(t, int64(0), total)
		assert.Len(t, results, 0)
	})

	t.Run("should exclude private docs for unauthorized users", func(t *testing.T) {
		index := newMockSearchIndex()
		svc := NewServiceWithDeps(db.DB, index)

		privateDoc := testutils.NewDocBuilder().
			WithTitle("无权访问").
			AsPrivate("owner-1").
			Build()

		created, _ := svc.CreateDocument(context.Background(), privateDoc)
		index.docs[created.ID] = map[string]interface{}{"title": "无权访问"}

		query := testutils.NewSearchQueryBuilder().
			WithUser("unauthorized-user").
			Build()

		results, total, err := svc.Search(context.Background(), query)

		assert.NoError(t, err)
		assert.Equal(t, int64(0), total)
		assert.Len(t, results, 0)
	})
}

func TestSyncSource_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	index := newMockSearchIndex()
	svc := NewServiceWithDeps(db.DB, index)

	t.Run("should sync from enabled source", func(t *testing.T) {
		src := testutils.NewSourceBuilder().
			WithName("维基同步源").
			WithType("wiki").
			Build()

		createdSrc, err := svc.CreateSource(context.Background(), src)
		assert.NoError(t, err)

		job, err := svc.SyncSource(context.Background(), createdSrc.ID)

		assert.NoError(t, err)
		assert.Equal(t, "running", job.Status)
		assert.Equal(t, createdSrc.ID, job.SourceID)
	})
}

func TestSyncSource_ExceptionFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	index := newMockSearchIndex()
	svc := NewServiceWithDeps(db.DB, index)

	t.Run("should fail for disabled source", func(t *testing.T) {
		src := testutils.NewSourceBuilder().
			Disabled().
			Build()

		createdSrc, _ := svc.CreateSource(context.Background(), src)

		_, err := svc.SyncSource(context.Background(), createdSrc.ID)

		assert.Error(t, err)
		assert.Contains(t, err.Error(), "disabled")
	})

	t.Run("should fail for non-existent source", func(t *testing.T) {
		_, err := svc.SyncSource(context.Background(), "non-existent")

		assert.Error(t, err)
	})
}

func TestUpdateDocument_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	index := newMockSearchIndex()
	svc := NewServiceWithDeps(db.DB, index)

	t.Run("should update document and reindex", func(t *testing.T) {
		doc := testutils.NewDocBuilder().WithTitle("原始标题").Build()
		created, _ := svc.CreateDocument(context.Background(), doc)

		created.Title = "更新后的标题"
		created.Content = "更新后的内容"
		updated, err := svc.UpdateDocument(context.Background(), created)

		assert.NoError(t, err)
		assert.Equal(t, "更新后的标题", updated.Title)

		indexedData := index.docs[created.ID]
		assert.NotNil(t, indexedData)
		assert.Equal(t, "更新后的标题", indexedData["title"])
	})
}
