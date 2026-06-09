package parser

import (
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"pointcloud-platform/pkg/math3d"
)

type LASParser struct {
	header    PointCloudHeader
	pointFmt  uint8
	hasColor  bool
	hasGPS    bool
	pointSize uint16
}

func (p *LASParser) DetectFormat(data []byte) bool {
	if len(data) < 4 {
		return false
	}
	return string(data[:4]) == "LASF"
}

func (p *LASParser) ParseHeader(r io.Reader) (*PointCloudHeader, error) {
	headerBuf := make([]byte, 375)
	if _, err := io.ReadFull(r, headerBuf); err != nil {
		return nil, fmt.Errorf("failed to read LAS header: %w", err)
	}

	if string(headerBuf[:4]) != "LASF" {
		return nil, ErrInvalidHeader
	}

	fileSig := string(headerBuf[:4])
	_ = fileSig

	versionMajor := headerBuf[24]
	versionMinor := headerBuf[25]
	version := fmt.Sprintf("%d.%d", versionMajor, versionMinor)

	sysID := string(headerBuf[6:38])
	genSW := string(headerBuf[38:90])
	_ = sysID
	_ = genSW

	scanner := binary.LittleEndian.Uint16(headerBuf[90:92])
	_ = scanner

	headerSize := binary.LittleEndian.Uint16(headerBuf[94:96])
	pointDataOffset := binary.LittleEndian.Uint32(headerBuf[96:100])
	numVLRs := binary.LittleEndian.Uint32(headerBuf[100:104])
	_ = numVLRs

	pointFmt := headerBuf[104]
	pointRecordLen := binary.LittleEndian.Uint16(headerBuf[105:107])

	var pointCount uint64
	var pointCountsByReturn [5]uint32

	if versionMajor == 1 && versionMinor < 4 {
		pointCount = uint64(binary.LittleEndian.Uint32(headerBuf[107:111]))
		for i := 0; i < 5; i++ {
			pointCountsByReturn[i] = binary.LittleEndian.Uint32(headerBuf[111+i*4 : 115+i*4])
		}
		_ = pointCountsByReturn
	} else {
		for i := 0; i < 15; i++ {
			_ = binary.LittleEndian.Uint32(headerBuf[107+i*4 : 111+i*4])
		}
		pointCount = binary.LittleEndian.Uint64(headerBuf[247:255])
	}

	scaleX := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[131:139]))
	scaleY := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[139:147]))
	scaleZ := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[147:155]))

	offsetX := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[155:163]))
	offsetY := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[163:171]))
	offsetZ := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[171:179]))

	maxX := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[179:187]))
	minX := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[187:195]))
	maxY := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[195:203]))
	minY := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[203:211]))
	maxZ := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[211:219]))
	minZ := math.Float64frombits(binary.LittleEndian.Uint64(headerBuf[219:227]))

	coordSystem := "unknown"

	if pointDataOffset > uint32(headerSize) {
		vlrData := make([]byte, pointDataOffset-uint32(headerSize))
		if _, err := io.ReadFull(r, vlrData); err == nil {
			coordSystem = p.detectCoordinateSystem(vlrData)
		}
	}

	hasColor := pointFmt == 2 || pointFmt == 3 || pointFmt == 5 || pointFmt == 7 || pointFmt == 8 || pointFmt == 10
	hasGPS := pointFmt == 1 || pointFmt == 3 || pointFmt == 4 || pointFmt == 5 ||
		pointFmt == 6 || pointFmt == 7 || pointFmt == 8 || pointFmt == 9 || pointFmt == 10

	hasIntensity := true

	p.pointFmt = pointFmt
	p.hasColor = hasColor
	p.hasGPS = hasGPS
	p.pointSize = pointRecordLen

	p.header = PointCloudHeader{
		FileFormat:      "las",
		Version:         version,
		PointCount:      pointCount,
		PointDataOffset: uint64(pointDataOffset),
		PointRecordLen:  pointRecordLen,
		ScaleFactor:     math3d.Vec3{X: scaleX, Y: scaleY, Z: scaleZ},
		Offset:          math3d.Vec3{X: offsetX, Y: offsetY, Z: offsetZ},
		MinBounds:       math3d.Vec3{X: minX, Y: minY, Z: minZ},
		MaxBounds:       math3d.Vec3{X: maxX, Y: maxY, Z: maxZ},
		CoordSystem:     coordSystem,
		HasColor:        hasColor,
		HasIntensity:    hasIntensity,
		HasGPSTime:      hasGPS,
	}

	return &p.header, nil
}

func (p *LASParser) detectCoordinateSystem(vlrData []byte) string {
	if len(vlrData) < 18 {
		return "unknown"
	}

	offset := 0
	for offset+18 <= len(vlrData) {
		recordSig := binary.LittleEndian.Uint16(vlrData[offset : offset+2])
		recordID := binary.LittleEndian.Uint16(vlrData[offset+2 : offset+4])
		recordLenAfterHeader := binary.LittleEndian.Uint32(vlrData[offset+14 : offset+18])

		if recordSig == 0 && recordID >= 2000 && recordID <= 2199 {
			return fmt.Sprintf("EPSG:%d", recordID)
		}

		totalLen := 18 + int(recordLenAfterHeader)
		if totalLen%2 != 0 {
			totalLen++
		}
		offset += totalLen

		if offset >= len(vlrData) {
			break
		}
	}

	return "unknown"
}

func (p *LASParser) Parse(r io.Reader) (*PointCloud, error) {
	header, err := p.ParseHeader(r)
	if err != nil {
		return nil, err
	}

	pc := NewPointCloud()
	pc.Header = *header

	for i := uint64(0); i < header.PointCount; i++ {
		pointBuf := make([]byte, p.pointSize)
		if _, err := io.ReadFull(r, pointBuf); err != nil {
			return pc, fmt.Errorf("failed to read point %d: %w", i, err)
		}

		point := p.parsePoint(pointBuf)
		pc.AddPoint(point)
	}

	if len(pc.Points) > 0 && (pc.Header.MinBounds.X == 0 && pc.Header.MaxBounds.X == 0) {
		pc.ComputeBounds()
	}

	return pc, nil
}

func (p *LASParser) ParseStream(r io.Reader, pointChan chan<- Point, errChan chan<- error) {
	defer close(pointChan)

	header, err := p.ParseHeader(r)
	if err != nil {
		errChan <- err
		return
	}

	_ = header

	bufSize := 1024 * int(p.pointSize)
	buf := make([]byte, bufSize)

	pointsRead := uint64(0)

	for pointsRead < header.PointCount {
		remaining := int(header.PointCount - pointsRead)
		readSize := bufSize
		if remaining*int(p.pointSize) < bufSize {
			readSize = remaining * int(p.pointSize)
		}

		n, err := io.ReadFull(r, buf[:readSize])
		if err != nil {
			errChan <- fmt.Errorf("stream read error: %w", err)
			return
		}

		numPoints := n / int(p.pointSize)
		for i := 0; i < numPoints; i++ {
			start := i * int(p.pointSize)
			point := p.parsePoint(buf[start : start+int(p.pointSize)])
			pointChan <- point
			pointsRead++
		}
	}
}

func (p *LASParser) parsePoint(data []byte) Point {
	rawX := int32(binary.LittleEndian.Uint32(data[0:4]))
	rawY := int32(binary.LittleEndian.Uint32(data[4:8]))
	rawZ := int32(binary.LittleEndian.Uint32(data[8:12]))

	x := float64(rawX)*p.header.ScaleFactor.X + p.header.Offset.X
	y := float64(rawY)*p.header.ScaleFactor.Y + p.header.Offset.Y
	z := float64(rawZ)*p.header.ScaleFactor.Z + p.header.Offset.Z

	intensity := binary.LittleEndian.Uint16(data[12:14])

	byte14 := data[14]
	returnNumber := byte14 & 0x07
	numberOfReturns := (byte14 >> 3) & 0x07
	scanDirFlag := (byte14 >> 6) & 0x01
	edgeOfFlight := (byte14 >> 7) & 0x01
	_ = scanDirFlag
	_ = edgeOfFlight

	classification := data[15]
	scanAngleRank := int8(data[16])
	userData := data[17]
	pointSourceID := binary.LittleEndian.Uint16(data[18:20])

	offset := 20
	var gpsTime float64
	if p.hasGPS {
		gpsTime = math.Float64frombits(binary.LittleEndian.Uint64(data[offset : offset+8]))
		offset += 8
	}

	var r, g, b uint8
	if p.hasColor {
		r = uint8(binary.LittleEndian.Uint16(data[offset:offset+2]) >> 8)
		g = uint8(binary.LittleEndian.Uint16(data[offset+2:offset+4]) >> 8)
		b = uint8(binary.LittleEndian.Uint16(data[offset+4:offset+6]) >> 8)
	} else {
		intensity8 := uint8(intensity >> 8)
		r = intensity8
		g = intensity8
		b = intensity8
	}

	return Point{
		X:                x,
		Y:                y,
		Z:                z,
		R:                r,
		G:                g,
		B:                b,
		Intensity:        intensity,
		Classification:   classification,
		ReturnNumber:     returnNumber,
		NumberOfReturns:  numberOfReturns,
		ScanAngleRank:    scanAngleRank,
		UserData:         userData,
		PointSourceID:    pointSourceID,
		GPSTime:          gpsTime,
	}
}
