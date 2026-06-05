package parser

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

type ParseResult struct {
	Header     *PointCloudHeader
	FilePath   string
	PointCount uint64
	Error      error
}

type ParseService struct {
	workers int
}

func NewParseService(workers int) *ParseService {
	if workers <= 0 {
		workers = 4
	}
	return &ParseService{workers: workers}
}

func (s *ParseService) ParseFile(filePath string) (*PointCloud, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to open file: %w", err)
	}
	defer file.Close()

	header := make([]byte, 256)
	n, err := file.Read(header)
	if err != nil {
		return nil, fmt.Errorf("failed to read header: %w", err)
	}
	header = header[:n]

	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return nil, fmt.Errorf("failed to seek file: %w", err)
	}

	format, err := DetectFormat(filePath, header)
	if err != nil {
		return nil, err
	}

	parser, err := NewParser(format)
	if err != nil {
		return nil, err
	}

	pc, err := parser.Parse(file)
	if err != nil {
		return pc, fmt.Errorf("parse error: %w", err)
	}

	pc.Header.FileFormat = format
	return pc, nil
}

func (s *ParseService) ParseFileHeader(filePath string) (*PointCloudHeader, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to open file: %w", err)
	}
	defer file.Close()

	header := make([]byte, 256)
	n, err := file.Read(header)
	if err != nil {
		return nil, fmt.Errorf("failed to read header: %w", err)
	}
	header = header[:n]

	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return nil, fmt.Errorf("failed to seek file: %w", err)
	}

	format, err := DetectFormat(filePath, header)
	if err != nil {
		return nil, err
	}

	parser, err := NewParser(format)
	if err != nil {
		return nil, err
	}

	pcHeader, err := parser.ParseHeader(file)
	if err != nil {
		return nil, fmt.Errorf("parse header error: %w", err)
	}

	pcHeader.FileFormat = format
	return pcHeader, nil
}

func (s *ParseService) ParseStream(filePath string, pointChan chan<- Point, errChan chan<- error) {
	file, err := os.Open(filePath)
	if err != nil {
		errChan <- fmt.Errorf("failed to open file: %w", err)
		close(pointChan)
		close(errChan)
		return
	}

	header := make([]byte, 256)
	n, err := file.Read(header)
	if err != nil {
		errChan <- fmt.Errorf("failed to read header: %w", err)
		file.Close()
		close(pointChan)
		close(errChan)
		return
	}
	header = header[:n]

	if _, err := file.Seek(0, io.SeekStart); err != nil {
		errChan <- fmt.Errorf("failed to seek file: %w", err)
		file.Close()
		close(pointChan)
		close(errChan)
		return
	}

	format, err := DetectFormat(filePath, header)
	if err != nil {
		errChan <- err
		file.Close()
		close(pointChan)
		close(errChan)
		return
	}

	parser, err := NewParser(format)
	if err != nil {
		errChan <- err
		file.Close()
		close(pointChan)
		close(errChan)
		return
	}

	go func() {
		defer file.Close()
		parser.ParseStream(file, pointChan, errChan)
	}()
}

func (s *ParseService) ParseDirectory(dirPath string) ([]ParseResult, error) {
	files, err := os.ReadDir(dirPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read directory: %w", err)
	}

	var results []ParseResult
	jobs := make(chan string, len(files))
	resultsChan := make(chan ParseResult, len(files))

	for w := 0; w < s.workers; w++ {
		go func() {
			for filePath := range jobs {
				pc, err := s.ParseFile(filePath)
				header := (*PointCloudHeader)(nil)
				pointCount := uint64(0)
				if pc != nil {
					header = &pc.Header
					pointCount = uint64(len(pc.Points))
				}
				resultsChan <- ParseResult{
					Header:     header,
					FilePath:   filePath,
					PointCount: pointCount,
					Error:      err,
				}
			}
		}()
	}

	for _, f := range files {
		if f.IsDir() {
			continue
		}
		ext := strings.ToLower(filepath.Ext(f.Name()))
		if ext == ".las" || ext == ".laz" || ext == ".ply" {
			fullPath := filepath.Join(dirPath, f.Name())
			jobs <- fullPath
		}
	}
	close(jobs)

	expected := 0
	for _, f := range files {
		if !f.IsDir() {
			ext := strings.ToLower(filepath.Ext(f.Name()))
			if ext == ".las" || ext == ".laz" || ext == ".ply" {
				expected++
			}
		}
	}

	for i := 0; i < expected; i++ {
		results = append(results, <-resultsChan)
	}
	close(resultsChan)

	return results, nil
}

func GetFormatFromFilename(filename string) string {
	ext := strings.ToLower(filepath.Ext(filename))
	switch ext {
	case ".las":
		return "las"
	case ".laz":
		return "laz"
	case ".ply":
		return "ply"
	default:
		return ""
	}
}
