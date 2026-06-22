package testutil

import (
	"meeting-system/internal/model"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"gorm.io/gorm"
)

type UserFactoryOption func(*model.User)

func WithRole(role string) UserFactoryOption {
	return func(u *model.User) {
		u.Role = role
	}
}

func WithDepartment(dept string) UserFactoryOption {
	return func(u *model.User) {
		u.Department = dept
	}
}

func WithName(name string) UserFactoryOption {
	return func(u *model.User) {
		u.Name = name
	}
}

func WithEmail(email string) UserFactoryOption {
	return func(u *model.User) {
		u.Email = email
	}
}

func CreateUser(t *testing.T, db *gorm.DB, opts ...UserFactoryOption) *model.User {
	t.Helper()
	id := uuid.New()
	user := &model.User{
		ID:           id,
		Name:         "测试用户_" + id.String()[:8],
		Email:        "test_" + id.String()[:8] + "@example.com",
		Phone:        "13800000000",
		Role:         "user",
		PasswordHash: "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
	}
	for _, opt := range opts {
		opt(user)
	}
	err := db.Create(user).Error
	require.NoError(t, err, "Failed to create user")
	return user
}

func CreateAdmin(t *testing.T, db *gorm.DB) *model.User {
	t.Helper()
	return CreateUser(t, db, WithRole("admin"), WithName("系统管理员"), WithEmail("admin@example.com"))
}

type RoomFactoryOption func(*model.Room)

func WithCapacity(capacity int) RoomFactoryOption {
	return func(r *model.Room) {
		r.Capacity = capacity
	}
}

func WithNeedApproval(need bool, approverID *uuid.UUID) RoomFactoryOption {
	return func(r *model.Room) {
		r.NeedApproval = need
		r.ApproverID = approverID
	}
}

func WithFloor(floor int) RoomFactoryOption {
	return func(r *model.Room) {
		r.Floor = floor
	}
}

func WithRoomStatus(status string) RoomFactoryOption {
	return func(r *model.Room) {
		r.Status = status
	}
}

func CreateRoom(t *testing.T, db *gorm.DB, opts ...RoomFactoryOption) *model.Room {
	t.Helper()
	id := uuid.New()
	room := &model.Room{
		ID:          id,
		Name:        "测试会议室_" + id.String()[:8],
		Floor:       1,
		Capacity:    10,
		Equipment:   "投影仪,白板,视频会议",
		Description: "测试用会议室",
		Status:      "active",
		Location:    "A座101",
	}
	for _, opt := range opts {
		opt(room)
	}
	err := db.Create(room).Error
	require.NoError(t, err, "Failed to create room")
	return room
}

type BookingFactoryOption func(*model.Booking)

func WithBookingStatus(status string) BookingFactoryOption {
	return func(b *model.Booking) {
		b.Status = status
	}
}

func WithApprovalStatus(status string) BookingFactoryOption {
	return func(b *model.Booking) {
		b.ApprovalStatus = status
	}
}

func WithRecurring(rule string, recurringID uuid.UUID) BookingFactoryOption {
	return func(b *model.Booking) {
		b.RecurringRule = rule
		b.RecurringID = &recurringID
	}
}

func WithTimeOffset(startOffset, endOffset time.Duration) BookingFactoryOption {
	return func(b *model.Booking) {
		b.StartTime = time.Now().Add(startOffset)
		b.EndTime = time.Now().Add(endOffset)
	}
}

func WithAttendees(attendees []string) BookingFactoryOption {
	return func(b *model.Booking) {
		if len(attendees) > 0 {
			first := attendees[0]
			b.Attendees = &first
		}
	}
}

func CreateBooking(t *testing.T, db *gorm.DB, roomID, userID uuid.UUID, startTime, endTime time.Time, opts ...BookingFactoryOption) *model.Booking {
	t.Helper()
	id := uuid.New()
	booking := &model.Booking{
		ID:             id,
		RoomID:         roomID,
		UserID:         userID,
		Title:          "测试会议_" + id.String()[:8],
		Description:    "测试用会议预订",
		StartTime:      startTime,
		EndTime:        endTime,
		Status:         "confirmed",
		ApprovalStatus: "approved",
	}
	for _, opt := range opts {
		opt(booking)
	}
	err := db.Create(booking).Error
	require.NoError(t, err, "Failed to create booking")
	return booking
}

func CreateMeetingDoc(t *testing.T, db *gorm.DB, bookingID uuid.UUID, content string) *model.MeetingDoc {
	t.Helper()
	doc := &model.MeetingDoc{
		ID:         uuid.New(),
		BookingID:  bookingID,
		Agenda:     "## 测试议程\n- 项目进度\n",
		Content:    content,
		Summary:    "",
		IsArchived: false,
	}
	err := db.Create(doc).Error
	require.NoError(t, err, "Failed to create meeting doc")
	return doc
}

func CreateTodo(t *testing.T, db *gorm.DB, docID, bookingID, assigneeID uuid.UUID, content string) *model.Todo {
	t.Helper()
	todo := &model.Todo{
		ID:         uuid.New(),
		DocID:      docID,
		BookingID:  bookingID,
		Content:    content,
		AssigneeID: assigneeID,
		Status:     "pending",
		Priority:   1,
	}
	err := db.Create(todo).Error
	require.NoError(t, err, "Failed to create todo")
	return todo
}

func CreateCheckIn(t *testing.T, db *gorm.DB, bookingID, userID uuid.UUID, qrToken string, opts ...func(*model.CheckIn)) *model.CheckIn {
	t.Helper()
	checkIn := &model.CheckIn{
		ID:        uuid.New(),
		BookingID: bookingID,
		UserID:    userID,
		CheckInAt: time.Now(),
		QRCode:    qrToken,
		Status:    "checked_in",
	}
	for _, opt := range opts {
		opt(checkIn)
	}
	err := db.Create(checkIn).Error
	require.NoError(t, err, "Failed to create check-in")
	return checkIn
}

func CreateQRToken(t *testing.T, db *gorm.DB, bookingID uuid.UUID, expiresAt time.Time) *model.QRCodeToken {
	t.Helper()
	token := &model.QRCodeToken{
		ID:        uuid.New(),
		BookingID: bookingID,
		Token:     uuid.New().String(),
		ExpiresAt: expiresAt,
	}
	err := db.Create(token).Error
	require.NoError(t, err, "Failed to create QR code token")
	return token
}

func CreateNotification(t *testing.T, db *gorm.DB, userID uuid.UUID, notifType, title, content string) *model.Notification {
	t.Helper()
	notif := &model.Notification{
		ID:       uuid.New(),
		UserID:   userID,
		Type:     notifType,
		Title:    title,
		Content:  content,
		Channels: "wechat,email",
		Status:   "unread",
	}
	err := db.Create(notif).Error
	require.NoError(t, err, "Failed to create notification")
	return notif
}

type QRTokenFactoryOption func(*model.QRCodeToken)

func WithExpiredToken() QRTokenFactoryOption {
	return func(q *model.QRCodeToken) {
		q.ExpiresAt = time.Now().Add(-10 * time.Minute)
	}
}

func (f *Factory) CreateQRCodeToken(bookingID uuid.UUID, opts ...QRTokenFactoryOption) *model.QRCodeToken {
	id := uuid.New()
	token := &model.QRCodeToken{
		ID:        id,
		BookingID: bookingID,
		Token:     "qr_token_" + id.String(),
		ExpiresAt: time.Now().Add(5 * time.Minute),
	}
	for _, opt := range opts {
		opt(token)
	}
	return token
}

type CheckInFactoryOption func(*model.CheckIn)

func WithCheckInTime(t time.Time) CheckInFactoryOption {
	return func(c *model.CheckIn) {
		c.CheckInAt = t
	}
}

func (f *Factory) CreateCheckIn(bookingID, userID uuid.UUID, opts ...CheckInFactoryOption) *model.CheckIn {
	id := uuid.New()
	checkIn := &model.CheckIn{
		ID:        id,
		BookingID: bookingID,
		UserID:    userID,
		CheckInAt: time.Now(),
		Status:    "checked_in",
	}
	for _, opt := range opts {
		opt(checkIn)
	}
	return checkIn
}

type Factory struct{}

func NewFactory() *Factory {
	return &Factory{}
}
