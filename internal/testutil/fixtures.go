package testutil

import (
	"encoding/binary"
	"io"
	"math"
	"math/rand"
	"os"
	"path/filepath"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/pkg/math3d"
	"time"
)

type PointCloudFixture struct {
	Points     []parser.Point
	Bounds     math3d.AABB
	PointCount int
}

func NewPointCloudFixture(pointCount int, seed int64) *PointCloudFixture {
	rng := rand.New(rand.NewSource(seed))

	points := make([]parser.Point, pointCount)
	minBounds := math3d.Vec3{X: -1000, Y: -1000, Z: -100}
	maxBounds := math3d.Vec3{X: 1000, Y: 1000, Z: 100}

	for i := 0; i < pointCount; i++ {
		points[i] = parser.Point{
			X:         minBounds.X + rng.Float64()*(maxBounds.X-minBounds.X),
			Y:         minBounds.Y + rng.Float64()*(maxBounds.Y-minBounds.Y),
			Z:         minBounds.Z + rng.Float64()*(maxBounds.Z-minBounds.Z),
			Intensity: uint16(rng.Intn(65536)),
			R:         uint8(rng.Intn(256)),
			G:         uint8(rng.Intn(256)),
			B:         uint8(rng.Intn(256)),
		}
	}

	return &PointCloudFixture{
		Points:     points,
		Bounds:     math3d.AABB{Min: minBounds, Max: maxBounds},
		PointCount: pointCount,
	}
}

func (f *PointCloudFixture) ToLASFile(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}

	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()

	header := make([]byte, 375)
	copy(header[0:4], "LASF")
	binary.LittleEndian.PutUint16(header[24:26], 14)
	binary.LittleEndian.PutUint16(header[94:96], 3)
	binary.LittleEndian.PutUint32(header[100:104], uint32(f.PointCount))
	binary.LittleEndian.PutUint64(header[104:112], uint64(f.PointCount))
	binary.LittleEndian.PutUint64(header[131:139], math.Float64bits(f.Bounds.Max.X))
	binary.LittleEndian.PutUint64(header[147:155], math.Float64bits(f.Bounds.Min.X))
	binary.LittleEndian.PutUint64(header[155:163], math.Float64bits(f.Bounds.Max.Y))
	binary.LittleEndian.PutUint64(header[171:179], math.Float64bits(f.Bounds.Min.Y))
	binary.LittleEndian.PutUint64(header[179:187], math.Float64bits(f.Bounds.Max.Z))
	binary.LittleEndian.PutUint64(header[195:203], math.Float64bits(f.Bounds.Min.Z))
	binary.LittleEndian.PutUint64(header[131:139], math.Float64bits(1.0))
	binary.LittleEndian.PutUint64(header[139:147], math.Float64bits(1.0))
	binary.LittleEndian.PutUint64(header[147:155], math.Float64bits(1.0))
	binary.LittleEndian.PutUint32(header[196:200], 227)

	if _, err := file.Write(header); err != nil {
		return err
	}

	pointData := make([]byte, 34*f.PointCount)
	for i, p := range f.Points {
		offset := i * 34
		binary.LittleEndian.PutUint32(pointData[offset:offset+4], math.Float32bits(float32(p.X)))
		binary.LittleEndian.PutUint32(pointData[offset+4:offset+8], math.Float32bits(float32(p.Y)))
		binary.LittleEndian.PutUint32(pointData[offset+8:offset+12], math.Float32bits(float32(p.Z)))
		binary.LittleEndian.PutUint16(pointData[offset+12:offset+14], p.Intensity)
		binary.LittleEndian.PutUint16(pointData[offset+20:offset+22], uint16(p.R))
		binary.LittleEndian.PutUint16(pointData[offset+22:offset+24], uint16(p.G))
		binary.LittleEndian.PutUint16(pointData[offset+24:offset+26], uint16(p.B))
	}

	_, err = file.Write(pointData)
	return err
}

func (f *PointCloudFixture) ToCorruptedLASFile(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}

	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()

	header := make([]byte, 100)
	copy(header[0:4], "LASF")
	header[24] = 0xFF
	header[25] = 0xFF
	if _, err := file.Write(header); err != nil {
		return err
	}

	_, err = file.Write([]byte("corrupted data here"))
	return err
}

func GenerateAABB(center math3d.Vec3, size float64) math3d.AABB {
	half := size / 2
	return math3d.AABB{
		Min: math3d.Vec3{X: center.X - half, Y: center.Y - half, Z: center.Z - half},
		Max: math3d.Vec3{X: center.X + half, Y: center.Y + half, Z: center.Z + half},
	}
}

func GenerateFrustum(cameraPos, target math3d.Vec3, fov, aspect, near, far float64) *math3d.Frustum {
	view := math3d.LookAt(cameraPos, target, math3d.Vec3{X: 0, Y: 0, Z: 1})
	proj := math3d.Perspective(fov, aspect, near, far)
	vp := proj.Mul(view)
	return math3d.ExtractFrustum(vp)
}

func GenerateViewState(rng *rand.Rand) map[string]interface{} {
	return map[string]interface{}{
		"position": map[string]float64{
			"x": rng.Float64() * 2000 - 1000,
			"y": rng.Float64() * 2000 - 1000,
			"z": rng.Float64() * 200,
		},
		"target": map[string]float64{
			"x": rng.Float64() * 1000 - 500,
			"y": rng.Float64() * 1000 - 500,
			"z": rng.Float64() * 100,
		},
		"up":  map[string]float64{"x": 0, "y": 0, "z": 1},
		"fov": 60.0,
	}
}

func GenerateAnnotation(rng *rand.Rand, id string) map[string]interface{} {
	annotationType := []string{"bbox3d", "polygon", "point", "line"}[rng.Intn(4)]
	base := map[string]interface{}{
		"id":          id,
		"type":        annotationType,
		"label":       "Test Annotation",
		"color":       "#FF0000",
		"created_by":  "user-1",
		"created_at":  time.Now().Unix(),
	}

	switch annotationType {
	case "bbox3d":
		base["min"] = map[string]float64{"x": 0, "y": 0, "z": 0}
		base["max"] = map[string]float64{"x": 10, "y": 10, "z": 10}
	case "point":
		base["position"] = map[string]float64{"x": 5, "y": 5, "z": 5}
	case "line":
		base["points"] = []map[string]float64{
			{"x": 0, "y": 0, "z": 0},
			{"x": 10, "y": 10, "z": 10},
		}
	case "polygon":
		base["points"] = []map[string]float64{
			{"x": 0, "y": 0, "z": 0},
			{"x": 10, "y": 0, "z": 0},
			{"x": 10, "y": 10, "z": 0},
			{"x": 0, "y": 10, "z": 0},
		}
	}

	return base
}

func GenerateTileData(datasetID string, lod, x, y, z int, pointCount int, seed int64) []byte {
	rng := rand.New(rand.NewSource(seed))
	buf := make([]byte, 4+2+2+4*3+4+8*6+8*3+4*pointCount)

	copy(buf[0:4], "PTLE")
	binary.LittleEndian.PutUint16(buf[4:6], 1)
	binary.LittleEndian.PutUint16(buf[6:8], uint16(lod))
	binary.LittleEndian.PutUint32(buf[8:12], uint32(x))
	binary.LittleEndian.PutUint32(buf[12:16], uint32(y))
	binary.LittleEndian.PutUint32(buf[16:20], uint32(z))
	binary.LittleEndian.PutUint32(buf[20:24], uint32(pointCount))

	minX, minY, minZ := float64(-1000), float64(-1000), float64(-100)
	maxX, maxY, maxZ := float64(1000), float64(1000), float64(100)

	binary.LittleEndian.PutUint64(buf[24:32], math.Float64bits(minX))
	binary.LittleEndian.PutUint64(buf[32:40], math.Float64bits(minY))
	binary.LittleEndian.PutUint64(buf[40:48], math.Float64bits(minZ))
	binary.LittleEndian.PutUint64(buf[48:56], math.Float64bits(maxX))
	binary.LittleEndian.PutUint64(buf[56:64], math.Float64bits(maxY))
	binary.LittleEndian.PutUint64(buf[64:72], math.Float64bits(maxZ))
	binary.LittleEndian.PutUint64(buf[72:80], math.Float64bits((minX+maxX)/2))
	binary.LittleEndian.PutUint64(buf[80:88], math.Float64bits((minY+maxY)/2))
	binary.LittleEndian.PutUint64(buf[88:96], math.Float64bits((minZ+maxZ)/2))

	pointOffset := 96
	for i := 0; i < pointCount; i++ {
		px := minX + rng.Float64()*(maxX-minX)
		py := minY + rng.Float64()*(maxY-minY)
		pz := minZ + rng.Float64()*(maxZ-minZ)

		binary.LittleEndian.PutUint32(buf[pointOffset:pointOffset+4], math.Float32bits(float32(px)))
		binary.LittleEndian.PutUint32(buf[pointOffset+4:pointOffset+8], math.Float32bits(float32(py)))
		binary.LittleEndian.PutUint32(buf[pointOffset+8:pointOffset+12], math.Float32bits(float32(pz)))
		binary.LittleEndian.PutUint16(buf[pointOffset+12:pointOffset+14], uint16(rng.Intn(65536)))
		pointOffset += 16
	}

	return buf
}

func TempDir(prefix string) (string, func(), error) {
	tmpDir, err := os.MkdirTemp("", prefix)
	if err != nil {
		return "", nil, err
	}

	cleanup := func() {
		os.RemoveAll(tmpDir)
	}

	return tmpDir, cleanup, nil
}

func CreateTestTileFile(dir, datasetID string, lod, x, y, z int, pointCount int) (string, error) {
	filename := filepath.Join(dir, datasetID, "tiles",
		string(rune(lod+'0')),
		string(rune(x+'0')),
		string(rune(y+'0')),
		string(rune(z+'0'))+".tile")

	if err := os.MkdirAll(filepath.Dir(filename), 0755); err != nil {
		return "", err
	}

	data := GenerateTileData(datasetID, lod, x, y, z, pointCount, time.Now().UnixNano())
	if err := os.WriteFile(filename, data, 0644); err != nil {
		return "", err
	}

	return filename, nil
}

type TestMessage struct {
	Type    string      `json:"type"`
	Payload interface{} `json:"payload"`
}

type SlowWriter struct {
	Delay    time.Duration
	Received int
	Writer   io.Writer
}

func (sw *SlowWriter) Write(p []byte) (n int, err error) {
	time.Sleep(sw.Delay)
	if sw.Writer != nil {
		n, err = sw.Writer.Write(p)
	} else {
		n = len(p)
	}
	sw.Received += n
	return n, err
}
