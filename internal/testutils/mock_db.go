package testutils

import (
	"database/sql"
	"database/sql/driver"
	"sync"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

type MockResult struct {
	lastInsertID int64
	rowsAffected int64
}

func (r *MockResult) LastInsertId() (int64, error) {
	return r.lastInsertID, nil
}

func (r *MockResult) RowsAffected() (int64, error) {
	return r.rowsAffected, nil
}

type MockRows struct {
	data     [][]interface{}
	columns  []string
	pos      int
	closed   bool
}

func (r *MockRows) Columns() ([]string, error) {
	return r.columns, nil
}

func (r *MockRows) Close() error {
	r.closed = true
	return nil
}

func (r *MockRows) Next() bool {
	if r.pos < len(r.data) {
		r.pos++
		return true
	}
	return false
}

func (r *MockRows) Scan(dest ...interface{}) error {
	if r.pos <= 0 || r.pos > len(r.data) {
		return sql.ErrNoRows
	}
	row := r.data[r.pos-1]
	for i, v := range row {
		if i >= len(dest) {
			break
		}
		switch d := dest[i].(type) {
		case *string:
			if s, ok := v.(string); ok {
				*d = s
			} else if s, ok := v.(*string); ok && s != nil {
				*d = *s
			}
		case *int:
			if n, ok := v.(int); ok {
				*d = n
			} else if n, ok := v.(int64); ok {
				*d = int(n)
			}
		case *int64:
			if n, ok := v.(int64); ok {
				*d = n
			} else if n, ok := v.(int); ok {
				*d = int64(n)
			}
		case *bool:
			if b, ok := v.(bool); ok {
				*d = b
			}
		case *sql.NullTime:
			if t, ok := v.(sql.NullTime); ok {
				*d = t
			} else if t, ok := v.(time.Time); ok {
				*d = sql.NullTime{Time: t, Valid: true}
			}
		}
	}
	return nil
}

func (r *MockRows) Err() error {
	return nil
}

type MockTx struct {
	db           *MockDB
	committed    bool
	rolledback   bool
	execErr      error
	commitErr    error
	rollbackErr  error
	statements   []string
}

func (t *MockTx) Commit() error {
	if t.commitErr != nil {
		return t.commitErr
	}
	t.committed = true
	return nil
}

func (t *MockTx) Rollback() error {
	if t.rollbackErr != nil {
		return t.rollbackErr
	}
	t.rolledback = true
	return nil
}

func (t *MockTx) Exec(query string, args ...interface{}) (sql.Result, error) {
	t.statements = append(t.statements, query)
	if t.execErr != nil {
		return nil, t.execErr
	}
	return &MockResult{lastInsertID: 1, rowsAffected: 1}, nil
}

func (t *MockTx) Query(query string, args ...interface{}) (*sql.Rows, error) {
	return nil, nil
}

func (t *MockTx) QueryRow(query string, args ...interface{}) *sql.Row {
	return nil
}

type Stmt struct{}

func (s *Stmt) Close() error { return nil }
func (s *Stmt) NumInput() int { return -1 }
func (s *Stmt) Exec(args []driver.Value) (driver.Result, error) { return nil, nil }
func (s *Stmt) Query(args []driver.Value) (driver.Rows, error) { return nil, nil }

type MockDB struct {
	driver         string
	execErr        error
	queryErr       error
	queryRowErr    error
	beginErr       error
	ensureTableErr error
	
	executedQueries []string
	queryResults    *MockRows
	tx              *MockTx
	mu              sync.Mutex
	
	queryRowResult interface{}
}

func NewMockDB(driver string) *MockDB {
	return &MockDB{
		driver:          driver,
		executedQueries: make([]string, 0),
	}
}

func (m *MockDB) Begin() (*sql.Tx, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	if m.beginErr != nil {
		return nil, m.beginErr
	}
	
	m.tx = &MockTx{
		db:         m,
		statements: make([]string, 0),
	}
	return nil, nil
}

func (m *MockDB) GetTx() *MockTx {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.tx
}

func (m *MockDB) Exec(query string, args ...interface{}) (sql.Result, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	m.executedQueries = append(m.executedQueries, query)
	
	if m.execErr != nil {
		return nil, m.execErr
	}
	return &MockResult{lastInsertID: 1, rowsAffected: 1}, nil
}

func (m *MockDB) Query(query string, args ...interface{}) (*sql.Rows, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	m.executedQueries = append(m.executedQueries, query)
	
	if m.queryErr != nil {
		return nil, m.queryErr
	}
	
	if m.queryResults != nil {
		m.queryResults.pos = 0
	}
	return nil, nil
}

func (m *MockDB) QueryRow(query string, args ...interface{}) *sql.Row {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	m.executedQueries = append(m.executedQueries, query)
	return nil
}

func (m *MockDB) DB() *sql.DB {
	return nil
}

func (m *MockDB) Driver() string {
	return m.driver
}

func (m *MockDB) Close() error {
	return nil
}

func (m *MockDB) EnsureMigrationTable(tableName string) error {
	if m.ensureTableErr != nil {
		return m.ensureTableErr
	}
	return nil
}

func (m *MockDB) EnsureLogTable(tableName string) error {
	return nil
}

func (m *MockDB) SetExecErr(err error) {
	m.execErr = err
}

func (m *MockDB) SetQueryErr(err error) {
	m.queryErr = err
}

func (m *MockDB) SetBeginErr(err error) {
	m.beginErr = err
}

func (m *MockDB) SetEnsureTableErr(err error) {
	m.ensureTableErr = err
}

func (m *MockDB) SetQueryResults(results *MockRows) {
	m.queryResults = results
}

func (m *MockDB) GetExecutedQueries() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.executedQueries
}

func (m *MockDB) ClearExecutedQueries() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.executedQueries = make([]string, 0)
}

func (m *MockDB) Reset() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.executedQueries = make([]string, 0)
	m.execErr = nil
	m.queryErr = nil
	m.queryRowErr = nil
	m.beginErr = nil
	m.ensureTableErr = nil
	m.queryResults = nil
	m.tx = nil
}
