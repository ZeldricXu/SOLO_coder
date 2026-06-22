package handler

import (
	"meeting-system/internal/model"
	"meeting-system/pkg/database"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
)

type StatsHandler struct{}

func NewStatsHandler() *StatsHandler {
	return &StatsHandler{}
}

type RoomUsageStat struct {
	RoomID     string  `json:"room_id"`
	RoomName   string  `json:"room_name"`
	TotalHours float64 `json:"total_hours"`
	UsageRate  float64 `json:"usage_rate"`
	BookingCount int   `json:"booking_count"`
}

func (h *StatsHandler) RoomUsage(c *gin.Context) {
	startDate := c.Query("start_date")
	endDate := c.Query("end_date")

	if startDate == "" {
		startDate = time.Now().AddDate(0, 0, -30).Format("2006-01-02")
	}
	if endDate == "" {
		endDate = time.Now().Format("2006-01-02")
	}

	startTime, _ := time.ParseInLocation("2006-01-02", startDate, time.Local)
	endTime, _ := time.ParseInLocation("2006-01-02", endDate, time.Local)
	endTime = endTime.AddDate(0, 0, 1)

	var rooms []model.Room
	database.DB.Where("status = ?", "active").Find(&rooms)

	var stats []RoomUsageStat
	totalDays := endTime.Sub(startTime).Hours() / 24
	workingHoursPerDay := 10.0
	totalAvailableHours := totalDays * workingHoursPerDay

	for _, room := range rooms {
		var bookings []model.Booking
		database.DB.Where("room_id = ? AND start_time >= ? AND end_time <= ? AND status = ?",
			room.ID, startTime, endTime, "confirmed").Find(&bookings)

		totalHours := 0.0
		for _, b := range bookings {
			totalHours += b.EndTime.Sub(b.StartTime).Hours()
		}

		usageRate := 0.0
		if totalAvailableHours > 0 {
			usageRate = (totalHours / totalAvailableHours) * 100
		}

		stats = append(stats, RoomUsageStat{
			RoomID:       room.ID.String(),
			RoomName:     room.Name,
			TotalHours:   totalHours,
			UsageRate:    usageRate,
			BookingCount: len(bookings),
		})
	}

	c.JSON(http.StatusOK, stats)
}

type MeetingHoursStat struct {
	Department    string  `json:"department"`
	TotalMeetings int     `json:"total_meetings"`
	TotalHours    float64 `json:"total_hours"`
	AvgHours      float64 `json:"avg_hours"`
}

func (h *StatsHandler) MeetingHours(c *gin.Context) {
	startDate := c.Query("start_date")
	endDate := c.Query("end_date")

	if startDate == "" {
		startDate = time.Now().AddDate(0, 0, -30).Format("2006-01-02")
	}
	if endDate == "" {
		endDate = time.Now().Format("2006-01-02")
	}

	startTime, _ := time.ParseInLocation("2006-01-02", startDate, time.Local)
	endTime, _ := time.ParseInLocation("2006-01-02", endDate, time.Local)

	var users []model.User
	database.DB.Find(&users)

	deptMap := make(map[string]*MeetingHoursStat)
	for _, user := range users {
		if user.Department == "" {
			continue
		}
		if _, ok := deptMap[user.Department]; !ok {
			deptMap[user.Department] = &MeetingHoursStat{
				Department: user.Department,
			}
		}
	}

	var bookings []model.Booking
	database.DB.Where("start_time >= ? AND end_time <= ? AND status = ?",
		startTime, endTime, "confirmed").Preload("User").Find(&bookings)

	for _, booking := range bookings {
		dept := booking.User.Department
		if dept == "" {
			dept = "其他"
		}
		if _, ok := deptMap[dept]; !ok {
			deptMap[dept] = &MeetingHoursStat{Department: dept}
		}
		deptMap[dept].TotalMeetings++
		deptMap[dept].TotalHours += booking.EndTime.Sub(booking.StartTime).Hours()
	}

	var stats []MeetingHoursStat
	for _, s := range deptMap {
		if s.TotalMeetings > 0 {
			s.AvgHours = s.TotalHours / float64(s.TotalMeetings)
		}
		stats = append(stats, *s)
	}

	c.JSON(http.StatusOK, stats)
}

type AttendanceStat struct {
	BookingID   string  `json:"booking_id"`
	Title       string  `json:"title"`
	TotalInvited int    `json:"total_invited"`
	CheckedIn   int     `json:"checked_in"`
	AttendanceRate float64 `json:"attendance_rate"`
}

func (h *StatsHandler) Attendance(c *gin.Context) {
	startDate := c.Query("start_date")
	endDate := c.Query("end_date")

	if startDate == "" {
		startDate = time.Now().AddDate(0, 0, -30).Format("2006-01-02")
	}
	if endDate == "" {
		endDate = time.Now().Format("2006-01-02")
	}

	startTime, _ := time.ParseInLocation("2006-01-02", startDate, time.Local)
	endTime, _ := time.ParseInLocation("2006-01-02", endDate, time.Local)

	var bookings []model.Booking
	database.DB.Where("start_time >= ? AND end_time <= ? AND status = ?",
		startTime, endTime, "confirmed").Find(&bookings)

	var stats []AttendanceStat
	for _, booking := range bookings {
		var checkIns []model.CheckIn
		database.DB.Where("booking_id = ?", booking.ID).Find(&checkIns)

		totalInvited := 1
		checkedIn := len(checkIns)
		rate := 0.0
		if totalInvited > 0 {
			rate = float64(checkedIn) / float64(totalInvited) * 100
		}

		stats = append(stats, AttendanceStat{
			BookingID:      booking.ID.String(),
			Title:          booking.Title,
			TotalInvited:   totalInvited,
			CheckedIn:      checkedIn,
			AttendanceRate: rate,
		})
	}

	c.JSON(http.StatusOK, stats)
}

type HeatmapData struct {
	DayOfWeek int `json:"day_of_week"`
	Hour      int `json:"hour"`
	Count     int `json:"count"`
}

func (h *StatsHandler) Heatmap(c *gin.Context) {
	startDate := c.Query("start_date")
	endDate := c.Query("end_date")

	if startDate == "" {
		startDate = time.Now().AddDate(0, 0, -30).Format("2006-01-02")
	}
	if endDate == "" {
		endDate = time.Now().Format("2006-01-02")
	}

	startTime, _ := time.ParseInLocation("2006-01-02", startDate, time.Local)
	endTime, _ := time.ParseInLocation("2006-01-02", endDate, time.Local)

	var bookings []model.Booking
	database.DB.Where("start_time >= ? AND end_time <= ? AND status = ?",
		startTime, endTime, "confirmed").Find(&bookings)

	heatmap := make(map[string]int)
	for _, booking := range bookings {
		current := booking.StartTime
		for current.Before(booking.EndTime) {
			dayOfWeek := int(current.Weekday())
			hour := current.Hour()
			key := strconv.Itoa(dayOfWeek) + "-" + strconv.Itoa(hour)
			heatmap[key]++
			current = current.Add(time.Hour)
		}
	}

	var stats []HeatmapData
	for day := 0; day < 7; day++ {
		for hour := 8; hour < 20; hour++ {
			key := strconv.Itoa(day) + "-" + strconv.Itoa(hour)
			stats = append(stats, HeatmapData{
				DayOfWeek: day,
				Hour:      hour,
				Count:     heatmap[key],
			})
		}
	}

	c.JSON(http.StatusOK, stats)
}

type EfficiencyStat struct {
	BookingID      string  `json:"booking_id"`
	Title          string  `json:"title"`
	PlannedMinutes float64 `json:"planned_minutes"`
	ActualMinutes  float64 `json:"actual_minutes"`
	EfficiencyRate float64 `json:"efficiency_rate"`
}

func (h *StatsHandler) Efficiency(c *gin.Context) {
	startDate := c.Query("start_date")
	endDate := c.Query("end_date")

	if startDate == "" {
		startDate = time.Now().AddDate(0, 0, -30).Format("2006-01-02")
	}
	if endDate == "" {
		endDate = time.Now().Format("2006-01-02")
	}

	startTime, _ := time.ParseInLocation("2006-01-02", startDate, time.Local)
	endTime, _ := time.ParseInLocation("2006-01-02", endDate, time.Local)

	var bookings []model.Booking
	database.DB.Where("start_time >= ? AND end_time <= ? AND status = ?",
		startTime, endTime, "confirmed").Find(&bookings)

	var stats []EfficiencyStat
	for _, booking := range bookings {
		plannedMinutes := booking.EndTime.Sub(booking.StartTime).Minutes()

		var checkIns []model.CheckIn
		database.DB.Where("booking_id = ?", booking.ID).Order("check_in_at").Find(&checkIns)

		actualMinutes := plannedMinutes
		if len(checkIns) >= 2 {
			firstCheckIn := checkIns[0].CheckInAt
			lastCheckIn := checkIns[len(checkIns)-1].CheckInAt
			actualMinutes = lastCheckIn.Sub(firstCheckIn).Minutes()
		}

		efficiencyRate := 100.0
		if plannedMinutes > 0 {
			efficiencyRate = (actualMinutes / plannedMinutes) * 100
		}

		stats = append(stats, EfficiencyStat{
			BookingID:      booking.ID.String(),
			Title:          booking.Title,
			PlannedMinutes: plannedMinutes,
			ActualMinutes:  actualMinutes,
			EfficiencyRate: efficiencyRate,
		})
	}

	c.JSON(http.StatusOK, stats)
}
