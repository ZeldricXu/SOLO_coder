package parser

import "errors"

var (
	ErrInvalidFormat     = errors.New("invalid file format")
	ErrUnsupportedFormat = errors.New("unsupported format")
	ErrInvalidHeader     = errors.New("invalid header")
	ErrUnexpectedEOF     = errors.New("unexpected EOF")
	ErrInvalidVersion    = errors.New("invalid LAS version")
	ErrInvalidPointFmt   = errors.New("invalid point format")
	ErrNotImplemented    = errors.New("not implemented")
)
