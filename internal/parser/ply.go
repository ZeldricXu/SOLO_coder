package parser

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"pointcloud-platform/pkg/math3d"
	"strconv"
	"strings"
)

type PLYProperty struct {
	Name string
	Type string
}

type PLYElement struct {
	Name       string
	Count      int
	Properties []PLYProperty
}

type PLYParser struct {
	header     PointCloudHeader
	elements   []PLYElement
	format     string
	binaryType binary.ByteOrder
	vertexIdx  int
	propMap    map[string]int
}

func (p *PLYParser) DetectFormat(data []byte) bool {
	if len(data) < 4 {
		return false
	}
	return string(data[:4]) == "ply\n" || string(data[:6]) == "ply\r\n"
}

func (p *PLYParser) ParseHeader(r io.Reader) (*PointCloudHeader, error) {
	p.propMap = make(map[string]int)

	reader := bufio.NewReader(r)

	magic, err := reader.ReadString('\n')
	if err != nil {
		return nil, fmt.Errorf("failed to read PLY magic: %w", err)
	}

	magic = strings.TrimSpace(magic)
	if magic != "ply" {
		return nil, ErrInvalidHeader
	}

	p.format = "ascii"
	p.binaryType = binary.LittleEndian
	hasVertex := false

	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return nil, fmt.Errorf("failed to read header line: %w", err)
		}

		line = strings.TrimSpace(line)

		if line == "end_header" {
			break
		}

		parts := strings.Fields(line)
		if len(parts) == 0 {
			continue
		}

		switch parts[0] {
		case "format":
			if len(parts) >= 2 {
				p.format = parts[1]
				if p.format == "binary_little_endian" {
					p.binaryType = binary.LittleEndian
				} else if p.format == "binary_big_endian" {
					p.binaryType = binary.BigEndian
				}
			}
		case "element":
			if len(parts) >= 3 {
				elemName := parts[1]
				count, _ := strconv.Atoi(parts[2])
				elem := PLYElement{
					Name:  elemName,
					Count: count,
				}
				if elemName == "vertex" {
					hasVertex = true
					p.vertexIdx = len(p.elements)
				}
				p.elements = append(p.elements, elem)
			}
		case "property":
			if len(p.elements) > 0 && len(parts) >= 3 {
				propType := parts[1]
				propName := parts[2]
				lastElem := &p.elements[len(p.elements)-1]
				lastElem.Properties = append(lastElem.Properties, PLYProperty{
					Name: propName,
					Type: propType,
				})
			}
		}
	}

	if !hasVertex {
		return nil, fmt.Errorf("PLY file has no vertex element")
	}

	vertexElem := p.elements[p.vertexIdx]
	hasColor := false
	hasIntensity := false
	hasNormal := false

	for i, prop := range vertexElem.Properties {
		p.propMap[prop.Name] = i
		switch prop.Name {
		case "red", "green", "blue", "r", "g", "b", "diffuse_red", "diffuse_green", "diffuse_blue":
			hasColor = true
		case "intensity", "i", "value":
			hasIntensity = true
		case "nx", "ny", "nz":
			hasNormal = true
		}
	}
	_ = hasNormal

	p.header = PointCloudHeader{
		FileFormat:      "ply",
		Version:         "1.0",
		PointCount:      uint64(vertexElem.Count),
		PointDataOffset: 0,
		PointRecordLen:  p.calculateVertexSize(vertexElem),
		ScaleFactor:     math3d.Vec3{X: 1, Y: 1, Z: 1},
		Offset:          math3d.Vec3{X: 0, Y: 0, Z: 0},
		CoordSystem:     "unknown",
		HasColor:        hasColor,
		HasIntensity:    hasIntensity,
		HasGPSTime:      false,
	}

	return &p.header, nil
}

func (p *PLYParser) calculateVertexSize(elem PLYElement) uint16 {
	if p.format == "ascii" {
		return 0
	}

	size := 0
	for _, prop := range elem.Properties {
		switch prop.Type {
		case "char", "uchar", "uint8", "int8":
			size += 1
		case "short", "ushort", "uint16", "int16":
			size += 2
		case "int", "uint", "float", "uint32", "int32", "float32":
			size += 4
		case "double", "float64", "int64", "uint64":
			size += 8
		default:
			size += 4
		}
	}
	return uint16(size)
}

func (p *PLYParser) Parse(r io.Reader) (*PointCloud, error) {
	header, err := p.ParseHeader(r)
	if err != nil {
		return nil, err
	}

	pc := NewPointCloud()
	pc.Header = *header

	pointChan := make(chan Point, 10000)
	errChan := make(chan error, 1)

	go p.ParseStream(r, pointChan, errChan)

	for point := range pointChan {
		pc.AddPoint(point)
	}

	if err := <-errChan; err != nil && err != io.EOF {
		return pc, err
	}

	if len(pc.Points) > 0 {
		pc.ComputeBounds()
		p.autoDetectScaleFactor(pc)
	}

	return pc, nil
}

func (p *PLYParser) autoDetectScaleFactor(pc *PointCloud) {
	center := pc.Bounds.Center()
	size := pc.Bounds.Size()

	maxDim := math.Max(size.X, math.Max(size.Y, size.Z))
	if maxDim > 10000 {
		pc.Header.ScaleFactor = math3d.Vec3{X: 0.001, Y: 0.001, Z: 0.001}
	} else if maxDim < 0.01 {
		pc.Header.ScaleFactor = math3d.Vec3{X: 1000, Y: 1000, Z: 1000}
	}

	if math.Abs(center.X) > 1e6 || math.Abs(center.Y) > 1e6 || math.Abs(center.Z) > 1e6 {
		pc.Header.Offset = center
	}
}

func (p *PLYParser) ParseStream(r io.Reader, pointChan chan<- Point, errChan chan<- error) {
	defer close(pointChan)
	defer close(errChan)

	vertexElem := p.elements[p.vertexIdx]
	count := vertexElem.Count

	if p.format == "ascii" {
		p.parseASCIIStream(r, vertexElem, count, pointChan, errChan)
	} else {
		p.parseBinaryStream(r, vertexElem, count, pointChan, errChan)
	}
}

func (p *PLYParser) parseASCIIStream(r io.Reader, elem PLYElement, count int, pointChan chan<- Point, errChan chan<- error) {
	scanner := bufio.NewScanner(r)
	buf := make([]byte, 1024*1024)
	scanner.Buffer(buf, 10*1024*1024)

	parsed := 0

	for scanner.Scan() && parsed < count {
		line := scanner.Text()
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}

		parts := strings.Fields(line)
		if len(parts) < len(elem.Properties) {
			continue
		}

		point := p.parseASCIIPoint(parts, elem)
		pointChan <- point
		parsed++
	}

	if err := scanner.Err(); err != nil {
		errChan <- err
	}
}

func (p *PLYParser) parseASCIIPoint(parts []string, elem PLYElement) Point {
	var x, y, z float64
	var r, g, b uint8 = 255, 255, 255
	var intensity uint16

	for i, prop := range elem.Properties {
		if i >= len(parts) {
			break
		}

		valStr := parts[i]
		switch prop.Name {
		case "x", "X":
			x, _ = strconv.ParseFloat(valStr, 64)
		case "y", "Y":
			y, _ = strconv.ParseFloat(valStr, 64)
		case "z", "Z":
			z, _ = strconv.ParseFloat(valStr, 64)
		case "red", "r", "diffuse_red":
			v, _ := strconv.ParseUint(valStr, 10, 16)
			if v > 255 {
				r = uint8(v >> 8)
			} else {
				r = uint8(v)
			}
		case "green", "g", "diffuse_green":
			v, _ := strconv.ParseUint(valStr, 10, 16)
			if v > 255 {
				g = uint8(v >> 8)
			} else {
				g = uint8(v)
			}
		case "blue", "b", "diffuse_blue":
			v, _ := strconv.ParseUint(valStr, 10, 16)
			if v > 255 {
				b = uint8(v >> 8)
			} else {
				b = uint8(v)
			}
		case "intensity", "i", "value":
			v, _ := strconv.ParseUint(valStr, 10, 16)
			intensity = uint16(v)
		}
	}

	x = x*p.header.ScaleFactor.X + p.header.Offset.X
	y = y*p.header.ScaleFactor.Y + p.header.Offset.Y
	z = z*p.header.ScaleFactor.Z + p.header.Offset.Z

	return Point{
		X:         x,
		Y:         y,
		Z:         z,
		R:         r,
		G:         g,
		B:         b,
		Intensity: intensity,
	}
}

func (p *PLYParser) parseBinaryStream(r io.Reader, elem PLYElement, count int, pointChan chan<- Point, errChan chan<- error) {
	recordSize := int(p.calculateVertexSize(elem))
	bufSize := 1024 * recordSize
	buf := make([]byte, bufSize)

	parsed := 0

	for parsed < count {
		remaining := count - parsed
		readSize := bufSize
		if remaining*recordSize < bufSize {
			readSize = remaining * recordSize
		}

		n, err := io.ReadFull(r, buf[:readSize])
		if err != nil {
			if err == io.EOF {
				break
			}
			errChan <- fmt.Errorf("binary read error: %w", err)
			return
		}

		numPoints := n / recordSize
		for i := 0; i < numPoints; i++ {
			start := i * recordSize
			end := start + recordSize
			point := p.parseBinaryPoint(buf[start:end], elem)
			pointChan <- point
			parsed++
		}
	}
}

func (p *PLYParser) parseBinaryPoint(data []byte, elem PLYElement) Point {
	var x, y, z float64
	var r, g, b uint8 = 255, 255, 255
	var intensity uint16

	offset := 0

	for _, prop := range elem.Properties {
		if offset >= len(data) {
			break
		}

		var size int
		switch prop.Type {
		case "char", "int8":
			v := int8(data[offset])
			_ = v
			size = 1
		case "uchar", "uint8":
			v := data[offset]
			switch prop.Name {
			case "red", "r", "diffuse_red":
				r = v
			case "green", "g", "diffuse_green":
				g = v
			case "blue", "b", "diffuse_blue":
				b = v
			case "intensity", "i", "value":
				intensity = uint16(v)
			}
			size = 1
		case "short", "int16":
			v := int16(p.binaryType.Uint16(data[offset : offset+2]))
			_ = v
			size = 2
		case "ushort", "uint16":
			v := p.binaryType.Uint16(data[offset : offset+2])
			switch prop.Name {
			case "red", "r", "diffuse_red":
				r = uint8(v >> 8)
			case "green", "g", "diffuse_green":
				g = uint8(v >> 8)
			case "blue", "b", "diffuse_blue":
				b = uint8(v >> 8)
			case "intensity", "i", "value":
				intensity = v
			}
			size = 2
		case "int", "int32":
			v := int32(p.binaryType.Uint32(data[offset : offset+4]))
			_ = v
			size = 4
		case "uint", "uint32":
			v := p.binaryType.Uint32(data[offset : offset+4])
			_ = v
			size = 4
		case "float", "float32":
			v := math.Float32frombits(p.binaryType.Uint32(data[offset : offset+4]))
			switch prop.Name {
			case "x", "X":
				x = float64(v)
			case "y", "Y":
				y = float64(v)
			case "z", "Z":
				z = float64(v)
			case "intensity", "i", "value":
				intensity = uint16(v)
			}
			size = 4
		case "double", "float64":
			v := math.Float64frombits(p.binaryType.Uint64(data[offset : offset+8]))
			switch prop.Name {
			case "x", "X":
				x = v
			case "y", "Y":
				y = v
			case "z", "Z":
				z = v
			case "intensity", "i", "value":
				intensity = uint16(v)
			}
			size = 8
		default:
			size = 4
		}

		offset += size
	}

	x = x*p.header.ScaleFactor.X + p.header.Offset.X
	y = y*p.header.ScaleFactor.Y + p.header.Offset.Y
	z = z*p.header.ScaleFactor.Z + p.header.Offset.Z

	return Point{
		X:         x,
		Y:         y,
		Z:         z,
		R:         r,
		G:         g,
		B:         b,
		Intensity: intensity,
	}
}

func (p *PLYParser) detectCoordSystem(comments []string) string {
	for _, comment := range comments {
		if bytes.Contains([]byte(strings.ToLower(comment)), []byte("epsg:")) {
			idx := bytes.Index([]byte(strings.ToLower(comment)), []byte("epsg:"))
			codeStr := comment[idx+5:]
			if code, err := strconv.Atoi(strings.Fields(codeStr)[0]); err == nil {
				return fmt.Sprintf("EPSG:%d", code)
			}
		}
		if strings.Contains(strings.ToLower(comment), "utm") {
			return "UTM"
		}
		if strings.Contains(strings.ToLower(comment), "wgs84") {
			return "WGS84"
		}
	}
	return "unknown"
}
