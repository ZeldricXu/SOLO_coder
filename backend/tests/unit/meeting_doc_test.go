package unit_test

import (
	"meeting-system/tests/testutil"
	"meeting-system/internal/handler"
	"meeting-system/internal/model"
	"time"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/gin-gonic/gin"
	"testing"
)

var tdb *testutil.TestDB

func setupDocTest(t *testing.T) {
	t.Helper()
	if tdb == nil {
		tdb = testutil.SetupTestDB(t)
	}
	tdb.TruncateTables(t)
}

func TestMeetingDoc_AutoCreateOnFirstGet(t *testing.T) {
	setupDocTest(t)
	defer tdb.Cleanup(t)

	user := testutil.CreateUser(t, tdb.DB)
	room := testutil.CreateRoom(t, tdb.DB)
	startTime := time.Now().Add(time.Hour)
	endTime := startTime.Add(time.Hour)
	booking := testutil.CreateBooking(t, tdb.DB, room.ID, user.ID, startTime, endTime)

	docHandler := handler.NewMeetingDocHandler()

	c, w := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodGet,
		Path:     "/api/meeting-docs/booking/" + booking.ID.String(),
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "bookingId", Value: booking.ID.String()}}
		},
	})

	docHandler.GetByBooking(c)

	assert.Equal(t, http.StatusOK, w.Code)

	var doc model.MeetingDoc
	testutil.ParseResponseInto(t, w, &doc)
	assert.Contains(t, doc.Agenda, "会议议程")
	assert.Equal(t, booking.ID, doc.BookingID)

	var dbDoc model.MeetingDoc
	err := tdb.DB.Where("booking_id = ?", booking.ID).First(&dbDoc).Error
	require.NoError(t, err)
	assert.Equal(t, booking.ID, dbDoc.BookingID)
	assert.Contains(t, dbDoc.Agenda, "会议议程")
}

func TestMeetingDoc_UpdateContent_Success(t *testing.T) {
	setupDocTest(t)
	defer tdb.Cleanup(t)

	user := testutil.CreateUser(t, tdb.DB)
	room := testutil.CreateRoom(t, tdb.DB)
	startTime := time.Now().Add(time.Hour)
	endTime := startTime.Add(time.Hour)
	booking := testutil.CreateBooking(t, tdb.DB, room.ID, user.ID, startTime, endTime)

	docHandler := handler.NewMeetingDocHandler()

	c, w := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodGet,
		Path:     "/api/meeting-docs/booking/" + booking.ID.String(),
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "bookingId", Value: booking.ID.String()}}
		},
	})
	docHandler.GetByBooking(c)
	require.Equal(t, http.StatusOK, w.Code)

	var doc model.MeetingDoc
	testutil.ParseResponseInto(t, w, &doc)

	updateContent := `## 会议内容讨论

### TODO 列表
- [ ] 完成用户模块接口文档
- [ ] 修复登录页面样式bug
- [ ] 更新项目README.md
- [ ] 准备下周技术评审材料
`

	updateReq := handler.UpdateDocRequest{
		Content: updateContent,
	}

	c2, w2 := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodPut,
		Path:     "/api/meeting-docs/" + doc.ID.String(),
		Body:     updateReq,
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
		},
	})

	docHandler.Update(c2)

	assert.Equal(t, http.StatusOK, w2.Code)

	var updatedDoc model.MeetingDoc
	testutil.ParseResponseInto(t, w2, &updatedDoc)
	assert.Equal(t, updateContent, updatedDoc.Content)

	var dbDoc model.MeetingDoc
	err := tdb.DB.First(&dbDoc, doc.ID).Error
	require.NoError(t, err)
	assert.Equal(t, updateContent, dbDoc.Content)
}

func TestMeetingDoc_Archive_ExtractTodos(t *testing.T) {
	setupDocTest(t)
	defer tdb.Cleanup(t)

	tests := []struct {
		name              string
		content           string
		expectedTodoCount int
		expectedTodos     []string
	}{
		{
			name:              "多条TODO待办",
			content:           "讨论内容\n- [ ] 完成接口文档\n- [ ] 修复登录bug\n- [ ] 更新README",
			expectedTodoCount: 3,
			expectedTodos:     []string{"完成接口文档", "修复登录bug", "更新README"},
		},
		{
			name:              "混合格式待办",
			content:           "TODO: 发送会议纪要邮件\n待办: 准备下周评审\n- [ ] 技术方案评审",
			expectedTodoCount: 3,
			expectedTodos:     []string{"发送会议纪要邮件", "准备下周评审", "技术方案评审"},
		},
		{
			name:              "无待办内容",
			content:           "纯讨论内容无待办事项",
			expectedTodoCount: 0,
			expectedTodos:     nil,
		},
		{
			name:              "空行和无内容待办",
			content:           "- [ ]   \nTODO:\n待办:",
			expectedTodoCount: 0,
			expectedTodos:     nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tdb.TruncateTables(t)

			user := testutil.CreateUser(t, tdb.DB)
			room := testutil.CreateRoom(t, tdb.DB)
			startTime := time.Now().Add(time.Hour)
			endTime := startTime.Add(time.Hour)
			booking := testutil.CreateBooking(t, tdb.DB, room.ID, user.ID, startTime, endTime)

			docHandler := handler.NewMeetingDocHandler()

			c, w := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
				Method:   http.MethodGet,
				Path:     "/api/meeting-docs/booking/" + booking.ID.String(),
				UserID:   user.ID,
				UserRole: user.Role,
				SetupCtx: func(c *gin.Context) {
					c.Params = gin.Params{gin.Param{Key: "bookingId", Value: booking.ID.String()}}
				},
			})
			docHandler.GetByBooking(c)
			require.Equal(t, http.StatusOK, w.Code)

			var doc model.MeetingDoc
			testutil.ParseResponseInto(t, w, &doc)

			updateReq := handler.UpdateDocRequest{
				Content: tt.content,
			}
			c2, w2 := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
				Method:   http.MethodPut,
				Path:     "/api/meeting-docs/" + doc.ID.String(),
				Body:     updateReq,
				UserID:   user.ID,
				UserRole: user.Role,
				SetupCtx: func(c *gin.Context) {
					c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
				},
			})
			docHandler.Update(c2)
			require.Equal(t, http.StatusOK, w2.Code)

			c3, w3 := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
				Method:   http.MethodPost,
				Path:     "/api/meeting-docs/" + doc.ID.String() + "/archive",
				UserID:   user.ID,
				UserRole: user.Role,
				SetupCtx: func(c *gin.Context) {
					c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
				},
			})
			docHandler.Archive(c3)

			assert.Equal(t, http.StatusOK, w3.Code)

			var todos []model.Todo
			err := tdb.DB.Where("doc_id = ?", doc.ID).Find(&todos).Error
			require.NoError(t, err)
			assert.Equal(t, tt.expectedTodoCount, len(todos))

			for _, expectedTodo := range tt.expectedTodos {
				found := false
				for _, todo := range todos {
					if strings.Contains(todo.Content, expectedTodo) {
						found = true
						break
					}
				}
				assert.True(t, found, "Expected to find todo containing: %s", expectedTodo)
			}

			var archivedDoc model.MeetingDoc
			err = tdb.DB.First(&archivedDoc, doc.ID).Error
			require.NoError(t, err)
			assert.True(t, archivedDoc.IsArchived)
		})
	}
}

func TestMeetingDoc_Archive_Idempotent(t *testing.T) {
	setupDocTest(t)
	defer tdb.Cleanup(t)

	user := testutil.CreateUser(t, tdb.DB)
	room := testutil.CreateRoom(t, tdb.DB)
	startTime := time.Now().Add(time.Hour)
	endTime := startTime.Add(time.Hour)
	booking := testutil.CreateBooking(t, tdb.DB, room.ID, user.ID, startTime, endTime)

	docHandler := handler.NewMeetingDocHandler()

	c, w := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodGet,
		Path:     "/api/meeting-docs/booking/" + booking.ID.String(),
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "bookingId", Value: booking.ID.String()}}
		},
	})
	docHandler.GetByBooking(c)
	require.Equal(t, http.StatusOK, w.Code)

	var doc model.MeetingDoc
	testutil.ParseResponseInto(t, w, &doc)

	contentWithTodos := "- [ ] 待办1\n- [ ] 待办2\n"
	updateReq := handler.UpdateDocRequest{Content: contentWithTodos}
	c2, w2 := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodPut,
		Path:     "/api/meeting-docs/" + doc.ID.String(),
		Body:     updateReq,
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
		},
	})
	docHandler.Update(c2)
	require.Equal(t, http.StatusOK, w2.Code)

	c3, w3 := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodPost,
		Path:     "/api/meeting-docs/" + doc.ID.String() + "/archive",
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
		},
	})
	docHandler.Archive(c3)
	require.Equal(t, http.StatusOK, w3.Code)

	var firstTodos []model.Todo
	err := tdb.DB.Where("doc_id = ?", doc.ID).Find(&firstTodos).Error
	require.NoError(t, err)
	firstCount := len(firstTodos)

	var docAfterFirst model.MeetingDoc
	err = tdb.DB.First(&docAfterFirst, doc.ID).Error
	require.NoError(t, err)
	firstArchivedAt := docAfterFirst.ArchivedAt

	c4, w4 := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodPost,
		Path:     "/api/meeting-docs/" + doc.ID.String() + "/archive",
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
		},
	})
	docHandler.Archive(c4)
	assert.Equal(t, http.StatusOK, w4.Code)

	var secondTodos []model.Todo
	err = tdb.DB.Where("doc_id = ?", doc.ID).Find(&secondTodos).Error
	require.NoError(t, err)
	assert.Equal(t, firstCount, len(secondTodos), "第二次归档不应该重复创建待办")

	var docAfterSecond model.MeetingDoc
	err = tdb.DB.First(&docAfterSecond, doc.ID).Error
	require.NoError(t, err)
	assert.True(t, docAfterSecond.IsArchived)
	if firstArchivedAt != nil {
		assert.True(t, firstArchivedAt.Equal(*docAfterSecond.ArchivedAt) || firstArchivedAt.Before(*docAfterSecond.ArchivedAt))
	}
}

func TestMeetingDoc_Archived_CannotEdit(t *testing.T) {
	setupDocTest(t)
	defer tdb.Cleanup(t)

	user := testutil.CreateUser(t, tdb.DB)
	room := testutil.CreateRoom(t, tdb.DB)
	startTime := time.Now().Add(time.Hour)
	endTime := startTime.Add(time.Hour)
	booking := testutil.CreateBooking(t, tdb.DB, room.ID, user.ID, startTime, endTime)

	docHandler := handler.NewMeetingDocHandler()

	c, w := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodGet,
		Path:     "/api/meeting-docs/booking/" + booking.ID.String(),
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "bookingId", Value: booking.ID.String()}}
		},
	})
	docHandler.GetByBooking(c)
	require.Equal(t, http.StatusOK, w.Code)

	var doc model.MeetingDoc
	testutil.ParseResponseInto(t, w, &doc)

	c2, w2 := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodPost,
		Path:     "/api/meeting-docs/" + doc.ID.String() + "/archive",
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
		},
	})
	docHandler.Archive(c2)
	require.Equal(t, http.StatusOK, w2.Code)

	updateReq := handler.UpdateDocRequest{
		Content: "尝试修改已归档文档",
	}
	c3, w3 := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodPut,
		Path:     "/api/meeting-docs/" + doc.ID.String(),
		Body:     updateReq,
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
		},
	})
	docHandler.Update(c3)

	assert.Equal(t, http.StatusBadRequest, w3.Code)

	resp := testutil.ParseResponse(t, w3)
	errMsg, ok := resp["error"].(string)
	require.True(t, ok, "Response should contain error field")
	lowerErrMsg := strings.ToLower(errMsg)
	assert.True(t,
		strings.Contains(lowerErrMsg, "archived") || strings.Contains(lowerErrMsg, "cannot be edited") || strings.Contains(errMsg, "归档"),
		"Error message should indicate document is archived, got: %s", errMsg,
	)
}

func TestTodo_CreateAndUpdate(t *testing.T) {
	setupDocTest(t)
	defer tdb.Cleanup(t)

	user := testutil.CreateUser(t, tdb.DB)
	assignee := testutil.CreateUser(t, tdb.DB, testutil.WithName("待办负责人"))
	room := testutil.CreateRoom(t, tdb.DB)
	startTime := time.Now().Add(time.Hour)
	endTime := startTime.Add(time.Hour)
	booking := testutil.CreateBooking(t, tdb.DB, room.ID, user.ID, startTime, endTime)
	doc := testutil.CreateMeetingDoc(t, tdb.DB, booking.ID, "会议内容")

	todoHandler := handler.NewTodoHandler()

	createReq := handler.CreateTodoRequest{
		Content:    "完成接口文档编写",
		AssigneeID: assignee.ID.String(),
		Priority:   2,
	}

	c, w := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodPost,
		Path:     "/api/meeting-docs/" + doc.ID.String() + "/todos",
		Body:     createReq,
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
		},
	})

	todoHandler.Create(c)

	assert.Equal(t, http.StatusCreated, w.Code)

	var createdTodo model.Todo
	testutil.ParseResponseInto(t, w, &createdTodo)
	assert.Equal(t, "完成接口文档编写", createdTodo.Content)
	assert.Equal(t, assignee.ID, createdTodo.AssigneeID)
	assert.Equal(t, doc.ID, createdTodo.DocID)
	assert.Equal(t, booking.ID, createdTodo.BookingID)
	assert.Equal(t, "pending", createdTodo.Status)
	assert.Equal(t, 2, createdTodo.Priority)

	var dbTodo model.Todo
	err := tdb.DB.First(&dbTodo, createdTodo.ID).Error
	require.NoError(t, err)
	assert.Equal(t, "完成接口文档编写", dbTodo.Content)

	updateReq := handler.UpdateTodoRequest{
		Status: "completed",
	}

	c2, w2 := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodPut,
		Path:     "/api/todos/" + createdTodo.ID.String(),
		Body:     updateReq,
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "id", Value: createdTodo.ID.String()}}
		},
	})

	todoHandler.Update(c2)

	assert.Equal(t, http.StatusOK, w2.Code)

	var updatedTodo model.Todo
	testutil.ParseResponseInto(t, w2, &updatedTodo)
	assert.Equal(t, "completed", updatedTodo.Status)

	var dbTodoAfter model.Todo
	err = tdb.DB.First(&dbTodoAfter, createdTodo.ID).Error
	require.NoError(t, err)
	assert.Equal(t, "completed", dbTodoAfter.Status)
}

func TestTodo_ListByDoc(t *testing.T) {
	setupDocTest(t)
	defer tdb.Cleanup(t)

	user := testutil.CreateUser(t, tdb.DB)
	room := testutil.CreateRoom(t, tdb.DB)
	startTime := time.Now().Add(time.Hour)
	endTime := startTime.Add(time.Hour)
	booking := testutil.CreateBooking(t, tdb.DB, room.ID, user.ID, startTime, endTime)
	doc := testutil.CreateMeetingDoc(t, tdb.DB, booking.ID, "会议内容")

	todo1 := testutil.CreateTodo(t, tdb.DB, doc.ID, booking.ID, user.ID, "待办事项1")
	time.Sleep(10 * time.Millisecond)
	todo2 := testutil.CreateTodo(t, tdb.DB, doc.ID, booking.ID, user.ID, "待办事项2")
	time.Sleep(10 * time.Millisecond)
	todo3 := testutil.CreateTodo(t, tdb.DB, doc.ID, booking.ID, user.ID, "待办事项3")

	todoHandler := handler.NewTodoHandler()

	c, w := testutil.SetupGinContext(t, tdb.GetConfig(), testutil.TestRequest{
		Method:   http.MethodGet,
		Path:     "/api/meeting-docs/" + doc.ID.String() + "/todos",
		UserID:   user.ID,
		UserRole: user.Role,
		SetupCtx: func(c *gin.Context) {
			c.Params = gin.Params{gin.Param{Key: "id", Value: doc.ID.String()}}
		},
	})

	todoHandler.ListByDoc(c)

	assert.Equal(t, http.StatusOK, w.Code)

	var todos []model.Todo
	testutil.ParseResponseInto(t, w, &todos)
	assert.Len(t, todos, 3)

	assert.Equal(t, todo1.ID, todos[0].ID)
	assert.Equal(t, todo2.ID, todos[1].ID)
	assert.Equal(t, todo3.ID, todos[2].ID)

	contents := []string{todos[0].Content, todos[1].Content, todos[2].Content}
	assert.Contains(t, contents, "待办事项1")
	assert.Contains(t, contents, "待办事项2")
	assert.Contains(t, contents, "待办事项3")
}
