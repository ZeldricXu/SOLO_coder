package testbuilder

import (
	"context"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/lineage"
	"github.com/datatrace/datatrace/internal/notification"
)

type SQLCase struct {
	Name        string
	SQL         string
	ExpectTables []string
	ExpectEdges int
	ShouldError bool
}

type NotificationCase struct {
	Name          string
	NotifType     string
	Recipient     string
	Payload       map[string]interface{}
	SenderError   error
	ShouldFail    bool
	MaxRetries    int
	RetryInterval time.Duration
}

type LineageTestDataBuilder struct {
	sqlCases []SQLCase
}

func NewLineageTestDataBuilder() *LineageTestDataBuilder {
	return &LineageTestDataBuilder{}
}

func (b *LineageTestDataBuilder) WithSimpleSelect() *LineageTestDataBuilder {
	b.sqlCases = append(b.sqlCases, SQLCase{
		Name:         "Simple SELECT",
		SQL:          "SELECT id, name FROM users",
		ExpectTables: []string{"users"},
		ExpectEdges:  0,
		ShouldError:  false,
	})
	return b
}

func (b *LineageTestDataBuilder) WithSelectJoin() *LineageTestDataBuilder {
	b.sqlCases = append(b.sqlCases, SQLCase{
		Name:         "SELECT with JOIN",
		SQL:          "SELECT u.id, u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id",
		ExpectTables: []string{"users", "orders"},
		ExpectEdges:  0,
		ShouldError:  false,
	})
	return b
}

func (b *LineageTestDataBuilder) WithInsertSelect() *LineageTestDataBuilder {
	b.sqlCases = append(b.sqlCases, SQLCase{
		Name:         "INSERT with SELECT",
		SQL:          "INSERT INTO user_summary SELECT id, name FROM users",
		ExpectTables: []string{"users", "user_summary"},
		ExpectEdges:  1,
		ShouldError:  false,
	})
	return b
}

func (b *LineageTestDataBuilder) WithComplexETL() *LineageTestDataBuilder {
	b.sqlCases = append(b.sqlCases, SQLCase{
		Name: "Complex ETL",
		SQL: `INSERT INTO analytics.daily_summary
			SELECT u.country, COUNT(o.id) as order_count, SUM(o.amount) as total_amount
			FROM production.users u
			JOIN production.orders o ON u.id = o.user_id
			WHERE o.created_at >= '2024-01-01'
			GROUP BY u.country`,
		ExpectTables: []string{"users", "orders", "daily_summary"},
		ExpectEdges:  2,
		ShouldError:  false,
	})
	return b
}

func (b *LineageTestDataBuilder) WithEmptySQL() *LineageTestDataBuilder {
	b.sqlCases = append(b.sqlCases, SQLCase{
		Name:         "Empty SQL",
		SQL:          "",
		ExpectTables: nil,
		ExpectEdges:  0,
		ShouldError:  true,
	})
	return b
}

func (b *LineageTestDataBuilder) WithWhitespaceSQL() *LineageTestDataBuilder {
	b.sqlCases = append(b.sqlCases, SQLCase{
		Name:         "Whitespace SQL",
		SQL:          "   \n\t  ",
		ExpectTables: nil,
		ExpectEdges:  0,
		ShouldError:  true,
	})
	return b
}

func (b *LineageTestDataBuilder) WithUpdateStatement() *LineageTestDataBuilder {
	b.sqlCases = append(b.sqlCases, SQLCase{
		Name:         "UPDATE statement",
		SQL:          "UPDATE users SET status = 'active' WHERE id = 1",
		ExpectTables: []string{"users"},
		ExpectEdges:  0,
		ShouldError:  false,
	})
	return b
}

func (b *LineageTestDataBuilder) WithCreateTableAsSelect() *LineageTestDataBuilder {
	b.sqlCases = append(b.sqlCases, SQLCase{
		Name:         "CREATE TABLE AS SELECT",
		SQL:          "CREATE TABLE archived_orders AS SELECT * FROM orders WHERE created_at < '2023-01-01'",
		ExpectTables: []string{"orders", "archived_orders"},
		ExpectEdges:  1,
		ShouldError:  false,
	})
	return b
}

func (b *LineageTestDataBuilder) Build() []SQLCase {
	return b.sqlCases
}

type NotificationTestDataBuilder struct {
	cases []NotificationCase
}

func NewNotificationTestDataBuilder() *NotificationTestDataBuilder {
	return &NotificationTestDataBuilder{}
}

func (b *NotificationTestDataBuilder) WithSimpleEmail() *NotificationTestDataBuilder {
	b.cases = append(b.cases, NotificationCase{
		Name:       "Simple email notification",
		NotifType:  "email",
		Recipient:  "user@example.com",
		Payload:    map[string]interface{}{"subject": "Hello", "body": "Welcome"},
		ShouldFail: false,
	})
	return b
}

func (b *NotificationTestDataBuilder) WithSMS() *NotificationTestDataBuilder {
	b.cases = append(b.cases, NotificationCase{
		Name:       "SMS notification",
		NotifType:  "sms",
		Recipient:  "+1234567890",
		Payload:    map[string]interface{}{"message": "Your code is 123456"},
		ShouldFail: false,
	})
	return b
}

func (b *NotificationTestDataBuilder) WithFailingSender() *NotificationTestDataBuilder {
	b.cases = append(b.cases, NotificationCase{
		Name:        "Failing sender with retry",
		NotifType:   "failing_email",
		Recipient:   "user@example.com",
		Payload:     map[string]interface{}{"subject": "Test"},
		SenderError: &MockSenderError{Message: "SMTP connection timeout"},
		ShouldFail:  true,
		MaxRetries:  3,
	})
	return b
}

func (b *NotificationTestDataBuilder) WithUnregisteredType() *NotificationTestDataBuilder {
	b.cases = append(b.cases, NotificationCase{
		Name:        "Unregistered notification type",
		NotifType:   "unknown_type",
		Recipient:   "user@example.com",
		Payload:     map[string]interface{}{},
		ShouldFail:  true,
	})
	return b
}

func (b *NotificationTestDataBuilder) WithLargePayload() *NotificationTestDataBuilder {
	largePayload := make(map[string]interface{})
	for i := 0; i < 1000; i++ {
		largePayload["key_"+string(rune(i))] = "value_"+string(rune(i))
	}
	b.cases = append(b.cases, NotificationCase{
		Name:       "Large payload",
		NotifType:  "email",
		Recipient:  "user@example.com",
		Payload:    largePayload,
		ShouldFail: false,
	})
	return b
}

func (b *NotificationTestDataBuilder) Build() []NotificationCase {
	return b.cases
}

type MockSenderError struct {
	Message string
}

func (e *MockSenderError) Error() string {
	return e.Message
}

type MockSender struct {
	Delay       time.Duration
	ReturnError error
	CallCount   int
	mu          sync.Mutex
}

func NewMockSender(delay time.Duration, returnErr error) *MockSender {
	return &MockSender{
		Delay:       delay,
		ReturnError: returnErr,
	}
}

func (m *MockSender) Send(ctx context.Context, notif *notification.Notification) error {
	m.mu.Lock()
	m.CallCount++
	m.mu.Unlock()

	if m.Delay > 0 {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(m.Delay):
		}
	}

	return m.ReturnError
}

func (m *MockSender) GetCallCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.CallCount
}

type TableNodeBuilder struct {
	node lineage.TableNode
}

func NewTableNodeBuilder() *TableNodeBuilder {
	return &TableNodeBuilder{
		node: lineage.TableNode{
			Timestamp: time.Now(),
		},
	}
}

func (b *TableNodeBuilder) WithName(name string) *TableNodeBuilder {
	b.node.Name = name
	return b
}

func (b *TableNodeBuilder) WithDatabase(db string) *TableNodeBuilder {
	b.node.Database = db
	return b
}

func (b *TableNodeBuilder) WithFields(fields []string) *TableNodeBuilder {
	b.node.Fields = fields
	return b
}

func (b *TableNodeBuilder) Build() lineage.TableNode {
	return b.node
}

type FieldLineageBuilder struct {
	lin lineage.FieldLineage
}

func NewFieldLineageBuilder() *FieldLineageBuilder {
	return &FieldLineageBuilder{
		lin: lineage.FieldLineage{
			Transform: "ETL",
		},
	}
}

func (b *FieldLineageBuilder) WithSource(table, field string) *FieldLineageBuilder {
	b.lin.SourceTable = table
	b.lin.SourceField = field
	return b
}

func (b *FieldLineageBuilder) WithTarget(table, field string) *FieldLineageBuilder {
	b.lin.TargetTable = table
	b.lin.TargetField = field
	return b
}

func (b *FieldLineageBuilder) WithTransform(transform string) *FieldLineageBuilder {
	b.lin.Transform = transform
	return b
}

func (b *FieldLineageBuilder) Build() lineage.FieldLineage {
	return b.lin
}
