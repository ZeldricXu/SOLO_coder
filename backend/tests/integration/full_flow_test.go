package integration_test

import (
	"encoding/json"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"meeting-system/internal/handler"
	"meeting-system/internal/model"
	"meeting-system/tests/testutil"
)

var tdbIntegration *testutil.TestDB

func setupIntegrationTest(t *testing.T) *testutil.TestDB {
	t.Helper()
	if tdbIntegration == nil {
		tdbIntegration = testutil.SetupTestDB(t)
	}
	t.Cleanup(func() {
		tdbIntegration.TruncateTables(t)
	})
	return tdbIntegration
}

func TestIntegration_FullBusinessFlow(t *testing.T) {
	t.Run("完整业务链路: 创建会议室→预订→通知→签到→协作→归档→统计", func(t *testing.T) {
		testDB := setupIntegrationTest(t)
		cfg := testDB.GetConfig()

		admin := testutil.CreateAdmin(t, testDB.DB)
		user1 := testutil.CreateUser(t, testDB.DB, testutil.WithName("张三"))
		user2 := testutil.CreateUser(t, testDB.DB, testutil.WithName("李四"))
		user3 := testutil.CreateUser(t, testDB.DB, testutil.WithName("王五"))

		roomHandler := handler.NewRoomHandler()
		bookingHandler := handler.NewBookingHandler()
		docHandler := handler.NewMeetingDocHandler()
		checkInHandler := handler.NewCheckInHandler()
		notificationHandler := handler.NewNotificationHandler()
		statsHandler := handler.NewStatsHandler()
		todoHandler := handler.NewTodoHandler()

		// Step 1: 管理员创建会议室
		t.Log("Step 1: 创建会议室")
		roomReq := map[string]interface{}{
			"name":          "集成测试会议室",
			"floor":         3,
			"capacity":      10,
			"equipment":     "投影仪,白板,视频会议",
			"description":   "集成测试用会议室",
			"need_approval": false,
			"location":      "3楼东侧",
		}

		adminToken := testutil.GenerateTestToken(t, cfg, admin.ID, admin.Role)

		c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/rooms",
			Body:     roomReq,
			AuthToken: adminToken,
			UserID:   admin.ID,
			UserRole: admin.Role,
		})

		roomHandler.Create(c)

		assert.Equal(t, http.StatusCreated, w.Code)
		var createdRoom model.Room
		testutil.ParseResponseInto(t, w, &createdRoom)
		assert.Equal(t, "集成测试会议室", createdRoom.Name)
		assert.False(t, createdRoom.NeedApproval)

		// Step 2: 用户1预订会议室
		t.Log("Step 2: 用户1预订会议室")
		now := time.Now()
		startTime := now.Add(time.Hour)
		endTime := now.Add(2 * time.Hour)

		user1Token := testutil.GenerateTestToken(t, cfg, user1.ID, user1.Role)

		bookingReq := testutil.BookingRequestForTest{
			RoomID:      createdRoom.ID.String(),
			Title:       "项目周会",
			Description: "讨论项目进度和本周计划",
			StartTime:   testutil.FormatTimeForRequest(startTime),
			EndTime:     testutil.FormatTimeForRequest(endTime),
			Attendees:   []string{user2.ID.String(), user3.ID.String()},
		}

		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/bookings",
			Body:     bookingReq,
			AuthToken: user1Token,
			UserID:   user1.ID,
			UserRole: user1.Role,
		})

		bookingHandler.Create(c)

		assert.Equal(t, http.StatusCreated, w.Code)
		bookingResp := testutil.ParseResponse(t, w)
		assert.Contains(t, bookingResp, "bookings")

		bookings := bookingResp["bookings"].([]interface{})
		bookingID := bookings[0].(map[string]interface{})["id"].(string)
		bookingUUID := uuid.MustParse(bookingID)

		// Step 3: 验证预订确认通知
		t.Log("Step 3: 验证预订确认通知")
		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/notifications",
			AuthToken: user1Token,
			UserID:   user1.ID,
			UserRole: user1.Role,
		})

		notificationHandler.List(c)

		assert.Equal(t, http.StatusOK, w.Code)
		var notifications []model.Notification
		testutil.ParseResponseInto(t, w, &notifications)

		found := false
		for _, n := range notifications {
			if n.Type == "booking_confirm" && n.Title == "预订确认" {
				found = true
				break
			}
		}
		assert.True(t, found, "应该收到预订确认通知")

		// Step 4: 用户1获取签到二维码
		t.Log("Step 4: 获取签到二维码")
		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/check-in/qr/" + bookingID,
			AuthToken: user1Token,
			UserID:   user1.ID,
			UserRole: user1.Role,
			SetupCtx: func(c *gin.Context) {
				c.Params = append(c.Params, gin.Param{Key: "bookingId", Value: bookingID})
			},
		})

		checkInHandler.GetQRCode(c)

		assert.Equal(t, http.StatusOK, w.Code)
		var qrData model.QRCodeToken
		testutil.ParseResponseInto(t, w, &qrData)
		assert.NotEmpty(t, qrData.Token)
		assert.True(t, qrData.ExpiresAt.After(time.Now()))

		// Step 5: 参会人扫码签到
		t.Log("Step 5: 参会人扫码签到")
		attendees := []*model.User{user1, user2, user3}
		for _, attendee := range attendees {
			token := testutil.GenerateTestToken(t, cfg, attendee.ID, attendee.Role)
			c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
				Method:   http.MethodPost,
				Path:     "/api/check-in",
				Body:     map[string]string{"token": qrData.Token},
				AuthToken: token,
				UserID:   attendee.ID,
				UserRole: attendee.Role,
			})
			checkInHandler.CheckIn(c)
			assert.Equal(t, http.StatusOK, w.Code, "%s 签到失败", attendee.Name)
		}

		var checkInCount int64
		testDB.DB.Model(&model.CheckIn{}).Where("booking_id = ?", bookingUUID).Count(&checkInCount)
		assert.Equal(t, int64(3), checkInCount, "3个参会人都应该签到成功")

		// Step 6: 协作编辑会议纪要
		t.Log("Step 6: 协作编辑会议纪要")
		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/meeting-docs/booking/" + bookingID,
			AuthToken: user1Token,
			UserID:   user1.ID,
			UserRole: user1.Role,
			SetupCtx: func(c *gin.Context) {
				c.Params = append(c.Params, gin.Param{Key: "bookingId", Value: bookingID})
			},
		})

		docHandler.GetByBooking(c)

		assert.Equal(t, http.StatusOK, w.Code)
		var doc model.MeetingDoc
		testutil.ParseResponseInto(t, w, &doc)
		assert.NotEmpty(t, doc.Agenda, "议程应该自动生成")

		// 用户1编辑内容
		content1 := `## 会议纪要

### 讨论事项
1. 项目进度正常，完成率85%
2. 下周需要上线新功能
3. 性能优化需要优先处理

### 待办事项
- [ ] 完成API文档 - @张三
- [ ] 修复登录bug - @李四
- [ ] 准备用户演示 - @王五
`
		updateReq := map[string]interface{}{
			"content": content1,
		}

		docID := doc.ID.String()
		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPut,
			Path:     "/api/meeting-docs/" + docID,
			Body:     updateReq,
			AuthToken: user1Token,
			UserID:   user1.ID,
			UserRole: user1.Role,
			SetupCtx: func(c *gin.Context) {
				c.Params = append(c.Params, gin.Param{Key: "id", Value: docID})
			},
		})
		docHandler.Update(c)
		assert.Equal(t, http.StatusOK, w.Code)

		// 用户2补充内容
		content2 := content1 + `
### 补充说明
- 下周演示时间安排在周三下午
- 需要准备演示环境
`
		user2Token := testutil.GenerateTestToken(t, cfg, user2.ID, user2.Role)
		updateReq2 := map[string]interface{}{
			"content": content2,
		}

		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPut,
			Path:     "/api/meeting-docs/" + docID,
			Body:     updateReq2,
			AuthToken: user2Token,
			UserID:   user2.ID,
			UserRole: user2.Role,
			SetupCtx: func(c *gin.Context) {
				c.Params = append(c.Params, gin.Param{Key: "id", Value: docID})
			},
		})
		docHandler.Update(c)
		assert.Equal(t, http.StatusOK, w.Code)

		// Step 7: 归档会议纪要
		t.Log("Step 7: 归档会议纪要")
		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/meeting-docs/" + docID + "/archive",
			AuthToken: user1Token,
			UserID:   user1.ID,
			UserRole: user1.Role,
			SetupCtx: func(c *gin.Context) {
				c.Params = append(c.Params, gin.Param{Key: "id", Value: docID})
			},
		})
		docHandler.Archive(c)

		assert.Equal(t, http.StatusOK, w.Code)
		var archivedDoc model.MeetingDoc
		testutil.ParseResponseInto(t, w, &archivedDoc)
		assert.True(t, archivedDoc.IsArchived)
		assert.NotNil(t, archivedDoc.ArchivedAt)

		// Step 8: 验证待办事项同步
		t.Log("Step 8: 验证待办事项同步")
		user2Token = testutil.GenerateTestToken(t, cfg, user2.ID, user2.Role)
		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/todos/my",
			AuthToken: user2Token,
			UserID:   user2.ID,
			UserRole: user2.Role,
		})

		todoHandler.MyTodos(c)

		assert.Equal(t, http.StatusOK, w.Code)
		var todos []model.Todo
		testutil.ParseResponseInto(t, w, &todos)

		foundTodo := false
		for _, todo := range todos {
			if todo.AssigneeID == user2.ID {
				foundTodo = true
				break
			}
		}
		assert.True(t, foundTodo, "李四应该看到分配给自己的待办")

		// Step 9: 验证统计面板数据
		t.Log("Step 9: 验证统计面板数据")
		startDate := now.AddDate(0, 0, -1).Format("2006-01-02")
		endDate := now.AddDate(0, 0, 1).Format("2006-01-02")

		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/stats/room-usage?start_date=" + startDate + "&end_date=" + endDate,
			AuthToken: adminToken,
			UserID:   admin.ID,
			UserRole: admin.Role,
		})

		statsHandler.RoomUsage(c)

		assert.Equal(t, http.StatusOK, w.Code)
		var stats []map[string]interface{}
		testutil.ParseResponseInto(t, w, &stats)

		assert.NotEmpty(t, stats)
		var targetStat map[string]interface{}
		for _, s := range stats {
			if s["room_name"] == "集成测试会议室" {
				targetStat = s
				break
			}
		}
		require.NotNil(t, targetStat)
		assert.Equal(t, float64(1), targetStat["booking_count"])
		assert.Equal(t, float64(1), targetStat["total_hours"])

		// Step 10: 验证热力图数据
		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/stats/heatmap?start_date=" + startDate + "&end_date=" + endDate,
			AuthToken: adminToken,
			UserID:   admin.ID,
			UserRole: admin.Role,
		})

		statsHandler.Heatmap(c)

		assert.Equal(t, http.StatusOK, w.Code)
		var heatmap []map[string]interface{}
		testutil.ParseResponseInto(t, w, &heatmap)

		meetingHour := now.Add(time.Hour).Hour()
		foundHeat := false
		for _, h := range heatmap {
			hour := int(h["hour"].(float64))
			count := int(h["count"].(float64))
			if hour == meetingHour && count > 0 {
				foundHeat = true
				break
			}
		}
		assert.True(t, foundHeat, "热力图应该显示会议时段")

		t.Log("✅ 完整业务链路测试通过！")
	})
}

func TestIntegration_ConcurrentBookingConflict(t *testing.T) {
	t.Run("并发冲突链路: 两用户同时预订同一会议室同一时段", func(t *testing.T) {
		testDB := setupIntegrationTest(t)
		cfg := testDB.GetConfig()

		admin := testutil.CreateAdmin(t, testDB.DB)
		userA := testutil.CreateUser(t, testDB.DB, testutil.WithName("用户A"))
		userB := testutil.CreateUser(t, testDB.DB, testutil.WithName("用户B"))
		room := testutil.CreateRoom(t, testDB.DB, testutil.WithName("并发测试会议室"))

		bookingHandler := handler.NewBookingHandler()

		now := time.Now()
		startTime := now.Add(time.Hour)
		endTime := now.Add(2 * time.Hour)

		bookingReq := testutil.BookingRequestForTest{
			RoomID:    room.ID.String(),
			Title:     "并发测试会议",
			StartTime: testutil.FormatTimeForRequest(startTime),
			EndTime:   testutil.FormatTimeForRequest(endTime),
		}

		tokenA := testutil.GenerateTestToken(t, cfg, userA.ID, userA.Role)
		tokenB := testutil.GenerateTestToken(t, cfg, userB.ID, userB.Role)

		var wg sync.WaitGroup
		wg.Add(2)

		results := make(chan struct {
			user       string
			statusCode int
			response   string
		}, 2)

		go func() {
			defer wg.Done()
			c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
				Method:   http.MethodPost,
				Path:     "/api/bookings",
				Body:     bookingReq,
				AuthToken: tokenA,
				UserID:   userA.ID,
				UserRole: userA.Role,
			})
			bookingHandler.Create(c)
			results <- struct {
				user       string
				statusCode int
				response   string
			}{"A", w.Code, w.Body.String()}
		}()

		go func() {
			defer wg.Done()
			c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
				Method:   http.MethodPost,
				Path:     "/api/bookings",
				Body:     bookingReq,
				AuthToken: tokenB,
				UserID:   userB.ID,
				UserRole: userB.Role,
			})
			bookingHandler.Create(c)
			results <- struct {
				user       string
				statusCode int
				response   string
			}{"B", w.Code, w.Body.String()}
		}()

		wg.Wait()
		close(results)

		var successCount, failCount int
		var successUser, failUser string
		var failMessage string

		for result := range results {
			if result.statusCode == http.StatusCreated {
				successCount++
				successUser = result.user
			} else if result.statusCode == http.StatusConflict {
				failCount++
				failUser = result.user
				var resp map[string]interface{}
				json.Unmarshal([]byte(result.response), &resp)
				failMessage, _ = resp["error"].(string)
			}
		}

		assert.Equal(t, 1, successCount, "应该只有一个预订成功")
		assert.Equal(t, 1, failCount, "应该有一个预订失败")

		t.Logf("成功用户: %s, 失败用户: %s", successUser, failUser)
		t.Logf("失败原因: %s", failMessage)

		var bookingCount int64
		testDB.DB.Model(&model.Booking{}).Where(
			"room_id = ? AND status = ?", room.ID, "confirmed",
		).Count(&bookingCount)
		assert.Equal(t, int64(1), bookingCount, "数据库中应该只有一条预订记录")

		var booking model.Booking
		testDB.DB.Where("room_id = ? AND status = ?", room.ID, "confirmed").First(&booking)
		if successUser == "A" {
			assert.Equal(t, userA.ID, booking.UserID)
		} else {
			assert.Equal(t, userB.ID, booking.UserID)
		}

		assert.Contains(t, failMessage, "conflict")

		t.Log("✅ 并发冲突链路测试通过！")
	})
}

func TestIntegration_ApprovalWorkflow(t *testing.T) {
	t.Run("审批流程: 特殊会议室预订需要审批", func(t *testing.T) {
		testDB := setupIntegrationTest(t)
		cfg := testDB.GetConfig()

		approver := testutil.CreateAdmin(t, testDB.DB, testutil.WithName("审批人"))
		employee := testutil.CreateUser(t, testDB.DB, testutil.WithName("员工"))
		room := testutil.CreateRoom(t, testDB.DB,
			testutil.WithName("董事会议室"),
			testutil.WithNeedApproval(true, &approver.ID),
		)

		bookingHandler := handler.NewBookingHandler()

		employeeToken := testutil.GenerateTestToken(t, cfg, employee.ID, employee.Role)
		now := time.Now()
		startTime := now.Add(24 * time.Hour)
		endTime := now.Add(26 * time.Hour)

		bookingReq := testutil.BookingRequestForTest{
			RoomID:      room.ID.String(),
			Title:       "董事会议",
			Description: "季度总结",
			StartTime:   testutil.FormatTimeForRequest(startTime),
			EndTime:     testutil.FormatTimeForRequest(endTime),
		}

		c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/bookings",
			Body:     bookingReq,
			AuthToken: employeeToken,
			UserID:   employee.ID,
			UserRole: employee.Role,
		})

		bookingHandler.Create(c)

		assert.Equal(t, http.StatusCreated, w.Code)
		bookingResp := testutil.ParseResponse(t, w)
		assert.Equal(t, "pending", bookingResp["approval_status"])

		bookings := bookingResp["bookings"].([]interface{})
		bookingID := bookings[0].(map[string]interface{})["id"].(string)

		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/bookings/my?status=pending",
			AuthToken: employeeToken,
			UserID:   employee.ID,
			UserRole: employee.Role,
		})

		bookingHandler.MyBookings(c)

		assert.Equal(t, http.StatusOK, w.Code)
		var myBookings []model.Booking
		testutil.ParseResponseInto(t, w, &myBookings)
		assert.Len(t, myBookings, 1)
		assert.Equal(t, "pending", myBookings[0].ApprovalStatus)

		approverToken := testutil.GenerateTestToken(t, cfg, approver.ID, approver.Role)
		rejectReq := map[string]interface{}{
			"reason": "该时段另有安排，请调整时间",
		}

		c, w = testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/bookings/" + bookingID + "/reject",
			Body:     rejectReq,
			AuthToken: approverToken,
			UserID:   approver.ID,
			UserRole: approver.Role,
			SetupCtx: func(c *gin.Context) {
				c.Params = append(c.Params, gin.Param{Key: "id", Value: bookingID})
			},
		})

		bookingHandler.Reject(c)

		assert.Equal(t, http.StatusOK, w.Code)
		var rejected model.Booking
		testutil.ParseResponseInto(t, w, &rejected)
		assert.Equal(t, "rejected", rejected.ApprovalStatus)
		assert.Equal(t, "cancelled", rejected.Status)

		var count int64
		testDB.DB.Model(&model.Booking{}).Where(
			"room_id = ? AND status = ? AND approval_status != ?",
			room.ID, "confirmed", "rejected",
		).Count(&count)
		assert.Equal(t, int64(0), count)

		t.Log("✅ 审批流程测试通过！")
	})
}
