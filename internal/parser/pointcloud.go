package parser

import (
	"io"
	"math"
	"pointcloud-platform/pkg/math3d"
)

type Point struct {
	X, Y, Z          float64
	R, G, B          uint8
	Intensity        uint16
	Classification   uint8
	ReturnNumber     uint8
	NumberOfReturns  uint8
	ScanAngleRank    int8
	UserData         uint8
	PointSourceID    uint16
	GPSTime          float64
}

type PointCloudHeader struct {
	FileFormat      string
	Version         string
	PointCount      uint64
	PointDataOffset uint64
	PointRecordLen  uint16
	ScaleFactor     math3d.Vec3
	Offset          math3d.Vec3
	MinBounds       math3d.Vec3
	MaxBounds       math3d.Vec3
	CoordSystem     string
	HasColor        bool
	HasIntensity    bool
	HasGPSTime      bool
}

type PointCloud struct {
	Header  PointCloudHeader
	Points  []Point
	Bounds  math3d.AABB
}

type Parser interface {
	ParseHeader(r io.Reader) (*PointCloudHeader, error)
	ParseStream(r io.Reader, pointChan chan<- Point, errChan chan<- error)
	Parse(r io.Reader) (*PointCloud, error)
	DetectFormat(data []byte) bool
}

func NewPointCloud() *PointCloud {
	return &PointCloud{
		Points: make([]Point, 0),
	}
}

func (pc *PointCloud) AddPoint(p Point) {
	pc.Points = append(pc.Points, p)
	if len(pc.Points) == 1 {
		pc.Bounds = math3d.NewAABB(
			math3d.Vec3{X: p.X, Y: p.Y, Z: p.Z},
			math3d.Vec3{X: p.X, Y: p.Y, Z: p.Z},
		)
	} else {
		pc.Bounds = pc.Bounds.Expand(math3d.Vec3{X: p.X, Y: p.Y, Z: p.Z})
	}
}

func (pc *PointCloud) ComputeBounds() {
	if len(pc.Points) == 0 {
		return
	}

	min := math3d.Vec3{X: math.Inf(1), Y: math.Inf(1), Z: math.Inf(1)}
	max := math3d.Vec3{X: math.Inf(-1), Y: math.Inf(-1), Z: math.Inf(-1)}

	for _, p := range pc.Points {
		if p.X < min.X {
			min.X = p.X
		}
		if p.Y < min.Y {
			min.Y = p.Y
		}
		if p.Z < min.Z {
			min.Z = p.Z
		}
		if p.X > max.X {
			max.X = p.X
		}
		if p.Y > max.Y {
			max.Y = p.Y
		}
		if p.Z > max.Z {
			max.Z = p.Z
		}
	}

	pc.Bounds = math3d.NewAABB(min, max)
	pc.Header.MinBounds = min
	pc.Header.MaxBounds = max
}

func DetectFormat(filename string, header []byte) (string, error) {
	if len(header) < 4 {
		return "", ErrInvalidFormat
	}

	if string(header[:4]) == "LASF" {
		return "las", nil
	}

	if len(header) >= 6 && string(header[:6]) == "ply\r\n" {
		return "ply", nil
	}
	if len(header) >= 4 && string(header[:4]) == "ply\n" {
		return "ply", nil
	}

	return "", ErrUnsupportedFormat
}

func NewParser(format string) (Parser, error) {
	switch format {
	case "las", "laz":
		return &LASParser{}, nil
	case "ply":
		return &PLYParser{}, nil
	default:
		return nil, ErrUnsupportedFormat
	}
}
