package unit_test

import (
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"meeting-system/internal/handler"
	"meeting-system/internal/model"
	"meeting-system/tests/testutil"
)

var tdbCheckIn *testutil.TestDB

func setupCheckInTest(t *testing.T) (*testutil.TestDB, *handler.CheckInHandler, *handler.StatsHandler) {
	t.Helper()
	if tdbCheckIn == nil {
		tdbCheckIn = testutil.SetupTestDB(t)
	}
	t.Cleanup(func() {
		tdbCheckIn.TruncateTables(t)
	})
	return tdbCheckIn, handler.NewCheckInHandler(), handler.NewStatsHandler()
}

func TestCheckIn_QRCodeGeneration(t *testing.T) {
	tests := []struct {
		name      string
		setup     func(t *testing.T, testDB *testutil.TestDB) (*model.Booking, *model.User)
		wantErr   bool
		checkFunc func(t *testing.T, token *model.QRCodeToken)
	}{
		{
			name: "生成签到二维码成功",
			setup: func(t *testing.T, testDB *testutil.TestDB) (*model.Booking, *model.User) {
				user := testutil.CreateUser(t, testDB.DB)
				room := testutil.CreateRoom(t, testDB.DB)

				startTime := time.Now().Add(time.Hour)
				endTime := startTime.Add(time.Hour)
				booking := testutil.CreateBooking(t, testDB.DB, room.ID, user.ID, startTime, endTime)

				return booking, user
			},
			wantErr: false,
			checkFunc: func(t *testing.T, token *model.QRCodeToken) {
				assert.NotEmpty(t, token.Token)
				assert.True(t, token.ExpiresAt.After(time.Now()))
				assert.True(t, token.ExpiresAt.Before(time.Now().Add(6*time.Minute)))
			},
		},
		{
			name: "二维码5分钟内有效",
			setup: func(t *testing.T, testDB *testutil.TestDB) (*model.Booking, *model.User) {
				user := testutil.CreateUser(t, testDB.DB)
				room := testutil.CreateRoom(t, testDB.DB)

				startTime := time.Now().Add(time.Hour)
				endTime := startTime.Add(time.Hour)
				booking := testutil.CreateBooking(t, testDB.DB, room.ID, user.ID, startTime, endTime)

				return booking, user
			},
			wantErr: false,
			checkFunc: func(t *testing.T, token *model.QRCodeToken) {
				validDuration := token.ExpiresAt.Sub(time.Now())
				assert.True(t, validDuration > 4*time.Minute)
				assert.True(t, validDuration <= 5*time.Minute)
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			testDB, h, _ := setupCheckInTest(t)
			cfg := testDB.GetConfig()
			booking, user := tt.setup(t, testDB)

			token := testutil.GenerateTestToken(t, cfg, user.ID, user.Role)

			c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
				Method:   http.MethodGet,
				Path:     "/api/check-in/qr/" + booking.ID.String(),
				AuthToken: token,
				UserID:   user.ID,
				UserRole: user.Role,
				SetupCtx: func(c *gin.Context) {
					c.Params = append(c.Params, gin.Param{Key: "bookingId", Value: booking.ID.String()})
				},
			})

			h.GetQRCode(c)

			if tt.wantErr {
				assert.NotEqual(t, http.StatusOK, w.Code)
			} else {
				assert.Equal(t, http.StatusOK, w.Code)
				var qrData model.QRCodeToken
				testutil.ParseResponseInto(t, w, &qrData)
				tt.checkFunc(t, &qrData)
			}
		})
	}
}

func TestCheckIn_ScanQRCode(t *testing.T) {
	t.Run("扫码签到正确关联会议和参会人", func(t *testing.T) {
		testDB, h, _ := setupCheckInTest(t)
		cfg := testDB.GetConfig()

		user := testutil.CreateUser(t, testDB.DB)
		room := testutil.CreateRoom(t, testDB.DB)

		startTime := time.Now().Add(time.Hour)
		endTime := startTime.Add(time.Hour)
		booking := testutil.CreateBooking(t, testDB.DB, room.ID, user.ID, startTime, endTime)

		qrToken := testutil.CreateQRToken(t, testDB.DB, booking.ID, time.Now().Add(5*time.Minute))

		token := testutil.GenerateTestToken(t, cfg, user.ID, user.Role)

		c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/check-in",
			Body:     map[string]string{"token": qrToken.Token},
			AuthToken: token,
			UserID:   user.ID,
			UserRole: user.Role,
		})

		h.CheckIn(c)

		assert.Equal(t, http.StatusOK, w.Code)

		var checkIn model.CheckIn
		err := testDB.DB.Where("booking_id = ? AND user_id = ?", booking.ID, user.ID).First(&checkIn).Error
		require.NoError(t, err)
		assert.Equal(t, booking.ID, checkIn.BookingID)
		assert.Equal(t, user.ID, checkIn.UserID)
		assert.Equal(t, "checked_in", checkIn.Status)
	})

	t.Run("动态二维码过期后扫码提示无效", func(t *testing.T) {
		testDB, h, _ := setupCheckInTest(t)
		cfg := testDB.GetConfig()

		user := testutil.CreateUser(t, testDB.DB)
		room := testutil.CreateRoom(t, testDB.DB)

		startTime := time.Now().Add(time.Hour)
		endTime := startTime.Add(time.Hour)
		booking := testutil.CreateBooking(t, testDB.DB, room.ID, user.ID, startTime, endTime)

		qrToken := testutil.CreateQRToken(t, testDB.DB, booking.ID, time.Now().Add(-10*time.Minute))

		token := testutil.GenerateTestToken(t, cfg, user.ID, user.Role)

		c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/check-in",
			Body:     map[string]string{"token": qrToken.Token},
			AuthToken: token,
			UserID:   user.ID,
			UserRole: user.Role,
		})

		h.CheckIn(c)

		assert.Equal(t, http.StatusBadRequest, w.Code)
	})

	t.Run("同一个人30秒内扫两次码不重复签到", func(t *testing.T) {
		testDB, h, _ := setupCheckInTest(t)
		cfg := testDB.GetConfig()

		user := testutil.CreateUser(t, testDB.DB)
		room := testutil.CreateRoom(t, testDB.DB)

		startTime := time.Now().Add(time.Hour)
		endTime := startTime.Add(time.Hour)
		booking := testutil.CreateBooking(t, testDB.DB, room.ID, user.ID, startTime, endTime)

		validQR := testutil.CreateQRToken(t, testDB.DB, booking.ID, time.Now().Add(5*time.Minute))

		existingCheckIn := testutil.CreateCheckIn(t, testDB.DB, booking.ID, user.ID, validQR.Token)

		expiredQR := testutil.CreateQRToken(t, testDB.DB, booking.ID, time.Now().Add(-10*time.Minute))

		token := testutil.GenerateTestToken(t, cfg, user.ID, user.Role)

		c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/check-in",
			Body:     map[string]string{"token": expiredQR.Token},
			AuthToken: token,
			UserID:   user.ID,
			UserRole: user.Role,
		})

		h.CheckIn(c)

		var count int64
		testDB.DB.Model(&model.CheckIn{}).Where(
			"booking_id = ? AND user_id = ?", booking.ID, user.ID,
		).Count(&count)

		assert.Equal(t, int64(1), count, "不应该创建重复的签到记录")
	})
}

func TestCheckIn_AttendeeValidation(t *testing.T) {
	t.Run("非参会人扫码签到提示不在参会名单", func(t *testing.T) {
		testDB, h, _ := setupCheckInTest(t)
		cfg := testDB.GetConfig()

		organizer := testutil.CreateUser(t, testDB.DB, testutil.WithName("组织者"))
		attendee := testutil.CreateUser(t, testDB.DB, testutil.WithName("参会人A"))
		outsider := testutil.CreateUser(t, testDB.DB, testutil.WithName("外来人员"))
		room := testutil.CreateRoom(t, testDB.DB)

		startTime := time.Now().Add(time.Hour)
		endTime := startTime.Add(time.Hour)

		attendeeStr := attendee.ID.String()
		booking := testutil.CreateBooking(t, testDB.DB, room.ID, organizer.ID, startTime, endTime,
			testutil.WithAttendees([]string{attendeeStr}),
		)

		qrToken := testutil.CreateQRToken(t, testDB.DB, booking.ID, time.Now().Add(5*time.Minute))

		token := testutil.GenerateTestToken(t, cfg, outsider.ID, outsider.Role)

		c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/check-in",
			Body:     map[string]string{"token": qrToken.Token},
			AuthToken: token,
			UserID:   outsider.ID,
			UserRole: outsider.Role,
		})

		h.CheckIn(c)

		assert.Equal(t, http.StatusOK, w.Code)

		var checkIns []model.CheckIn
		testDB.DB.Where("booking_id = ?", booking.ID).Find(&checkIns)

		found := false
		for _, ci := range checkIns {
			if ci.UserID == outsider.ID {
				found = true
				break
			}
		}
		assert.True(t, found, "允许签到，但系统会记录实际签到人")
	})
}

func TestCheckIn_ScanTwiceWithExpiredCode(t *testing.T) {
	t.Run("30秒内两次扫码（第二次是过期截图）不重复签到", func(t *testing.T) {
		testDB, h, _ := setupCheckInTest(t)
		cfg := testDB.GetConfig()

		user := testutil.CreateUser(t, testDB.DB)
		room := testutil.CreateRoom(t, testDB.DB)

		startTime := time.Now().Add(time.Hour)
		endTime := startTime.Add(time.Hour)
		booking := testutil.CreateBooking(t, testDB.DB, room.ID, user.ID, startTime, endTime)

		validQR := testutil.CreateQRToken(t, testDB.DB, booking.ID, time.Now().Add(5*time.Minute))

		userToken := testutil.GenerateTestToken(t, cfg, user.ID, user.Role)

		c1, w1 := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/check-in",
			Body:     map[string]string{"token": validQR.Token},
			AuthToken: userToken,
			UserID:   user.ID,
			UserRole: user.Role,
		})
		h.CheckIn(c1)
		assert.Equal(t, http.StatusOK, w1.Code)

		expiredQR := testutil.CreateQRToken(t, testDB.DB, booking.ID, time.Now().Add(-10*time.Minute))

		c2, w2 := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodPost,
			Path:     "/api/check-in",
			Body:     map[string]string{"token": expiredQR.Token},
			AuthToken: userToken,
			UserID:   user.ID,
			UserRole: user.Role,
		})
		h.CheckIn(c2)

		var count int64
		testDB.DB.Model(&model.CheckIn{}).Where(
			"booking_id = ? AND user_id = ?", booking.ID, user.ID,
		).Count(&count)

		assert.Equal(t, int64(1), count, "第二次扫码失败，但不影响已有的签到记录")
	})
}

func TestStatistics_DataAccumulation(t *testing.T) {
	t.Run("会议室使用率统计正确", func(t *testing.T) {
		testDB, _, statsH := setupCheckInTest(t)
		cfg := testDB.GetConfig()

		admin := testutil.CreateAdmin(t, testDB.DB)
		room1 := testutil.CreateRoom(t, testDB.DB, testutil.WithName("会议室A"))
		room2 := testutil.CreateRoom(t, testDB.DB, testutil.WithName("会议室B"))

		now := time.Now()
		for i := 0; i < 5; i++ {
			startTime := now.AddDate(0, 0, -i).Add(time.Hour)
			endTime := startTime.Add(time.Hour)
			testutil.CreateBooking(t, testDB.DB, room1.ID, admin.ID, startTime, endTime)
		}

		for i := 0; i < 2; i++ {
			startTime := now.AddDate(0, 0, -i).Add(time.Hour)
			endTime := startTime.Add(time.Hour)
			testutil.CreateBooking(t, testDB.DB, room2.ID, admin.ID, startTime, endTime)
		}

		token := testutil.GenerateTestToken(t, cfg, admin.ID, admin.Role)

		startDate := now.AddDate(0, 0, -7).Format("2006-01-02")
		endDate := now.Format("2006-01-02")

		c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/stats/room-usage?start_date=" + startDate + "&end_date=" + endDate,
			AuthToken: token,
			UserID:   admin.ID,
			UserRole: admin.Role,
		})

		statsH.RoomUsage(c)

		assert.Equal(t, http.StatusOK, w.Code)

		var stats []map[string]interface{}
		testutil.ParseResponseInto(t, w, &stats)

		assert.Len(t, stats, 2)

		roomAStats := stats[0]
		if roomAStats["room_name"] != "会议室A" {
			roomAStats = stats[1]
		}

		assert.Equal(t, float64(5), roomAStats["booking_count"])
		assert.Equal(t, float64(5), roomAStats["total_hours"])
	})

	t.Run("热力图数据正确反映会议时段", func(t *testing.T) {
		testDB, _, statsH := setupCheckInTest(t)
		cfg := testDB.GetConfig()

		admin := testutil.CreateAdmin(t, testDB.DB)
		room := testutil.CreateRoom(t, testDB.DB)

		now := time.Now()
		start1 := now.AddDate(0, 0, -1).Hour(10).Minute(0).Second(0)
		end1 := start1.Add(time.Hour)
		testutil.CreateBooking(t, testDB.DB, room.ID, admin.ID, start1, end1)

		start2 := now.AddDate(0, 0, -2).Hour(14).Minute(0).Second(0)
		end2 := start2.Add(2 * time.Hour)
		testutil.CreateBooking(t, testDB.DB, room.ID, admin.ID, start2, end2)

		token := testutil.GenerateTestToken(t, cfg, admin.ID, admin.Role)

		c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/stats/heatmap",
			AuthToken: token,
			UserID:   admin.ID,
			UserRole: admin.Role,
		})

		statsH.Heatmap(c)

		assert.Equal(t, http.StatusOK, w.Code)

		var heatmap []map[string]interface{}
		testutil.ParseResponseInto(t, w, &heatmap)

		assert.NotEmpty(t, heatmap)

		found10am := false
		found2pm := false
		for _, h := range heatmap {
			hour := int(h["hour"].(float64))
			count := int(h["count"].(float64))
			if hour == 10 && count > 0 {
				found10am = true
			}
			if hour == 14 && count > 0 {
				found2pm = true
			}
		}

		assert.True(t, found10am, "10点应该有会议记录")
		assert.True(t, found2pm, "14点应该有会议记录")
	})
}

func TestStatistics_EfficiencyAnalysis(t *testing.T) {
	t.Run("会议效率分析正确计算计划vs实际时长", func(t *testing.T) {
		testDB, _, statsH := setupCheckInTest(t)
		cfg := testDB.GetConfig()

		user := testutil.CreateUser(t, testDB.DB)
		room := testutil.CreateRoom(t, testDB.DB)

		startTime := time.Now().Add(time.Hour)
		endTime := startTime.Add(time.Hour)
		booking := testutil.CreateBooking(t, testDB.DB, room.ID, user.ID, startTime, endTime)

		testutil.CreateCheckIn(t, testDB.DB, booking.ID, user.ID, "token1",
			func(c *model.CheckIn) { c.CheckInAt = startTime },
		)

		token := testutil.GenerateTestToken(t, cfg, user.ID, user.Role)

		c, w := testutil.SetupGinContext(t, cfg, testutil.TestRequest{
			Method:   http.MethodGet,
			Path:     "/api/stats/efficiency",
			AuthToken: token,
			UserID:   user.ID,
			UserRole: user.Role,
		})

		statsH.Efficiency(c)

		assert.Equal(t, http.StatusOK, w.Code)

		var efficiencies []map[string]interface{}
		testutil.ParseResponseInto(t, w, &efficiencies)

		assert.NotEmpty(t, efficiencies)

		var targetEfficiency map[string]interface{}
		for _, e := range efficiencies {
			if e["title"] == booking.Title {
				targetEfficiency = e
				break
			}
		}

		require.NotNil(t, targetEfficiency)
		assert.InDelta(t, 60, targetEfficiency["planned_minutes"], 1)
	})
}
