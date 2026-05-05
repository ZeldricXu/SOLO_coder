package database

import (
	"database/sql"
)

type SqlResult interface {
	LastInsertId() (int64, error)
	RowsAffected() (int64, error)
}

type SqlRows interface {
	Columns() ([]string, error)
	Close() error
	Next() bool
	Scan(dest ...interface{}) error
	Err() error
}

type SqlRow interface {
	Scan(dest ...interface{}) error
}

type SqlTx interface {
	Commit() error
	Rollback() error
	Exec(query string, args ...interface{}) (SqlResult, error)
	Query(query string, args ...interface{}) (SqlRows, error)
	QueryRow(query string, args ...interface{}) SqlRow
}

type SqlDB interface {
	Begin() (*sql.Tx, error)
	Exec(query string, args ...interface{}) (sql.Result, error)
	Query(query string, args ...interface{}) (*sql.Rows, error)
	QueryRow(query string, args ...interface{}) *sql.Row
}

type IDBConnection interface {
	Begin() (*sql.Tx, error)
	Exec(query string, args ...interface{}) (sql.Result, error)
	Query(query string, args ...interface{}) (*sql.Rows, error)
	QueryRow(query string, args ...interface{}) *sql.Row
	DB() *sql.DB
	Driver() string
	Close() error
	EnsureMigrationTable(tableName string) error
	EnsureLogTable(tableName string) error
}

type IDBTransaction interface {
	Commit() error
	Rollback() error
	Exec(query string, args ...interface{}) (sql.Result, error)
	Query(query string, args ...interface{}) (*sql.Rows, error)
	QueryRow(query string, args ...interface{}) *sql.Row
}

var _ IDBConnection = (*DBConnection)(nil)
