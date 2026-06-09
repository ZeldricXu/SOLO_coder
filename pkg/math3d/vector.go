package math3d

import "math"

type Vec3 struct {
	X, Y, Z float64
}

type Vec4 struct {
	X, Y, Z, W float64
}

type Mat4 [16]float64

func (v Vec3) Add(o Vec3) Vec3 {
	return Vec3{v.X + o.X, v.Y + o.Y, v.Z + o.Z}
}

func (v Vec3) Sub(o Vec3) Vec3 {
	return Vec3{v.X - o.X, v.Y - o.Y, v.Z - o.Z}
}

func (v Vec3) Mul(s float64) Vec3 {
	return Vec3{v.X * s, v.Y * s, v.Z * s}
}

func (v Vec3) Div(s float64) Vec3 {
	return Vec3{v.X / s, v.Y / s, v.Z / s}
}

func (v Vec3) Dot(o Vec3) float64 {
	return v.X*o.X + v.Y*o.Y + v.Z*o.Z
}

func (v Vec3) Cross(o Vec3) Vec3 {
	return Vec3{
		v.Y*o.Z - v.Z*o.Y,
		v.Z*o.X - v.X*o.Z,
		v.X*o.Y - v.Y*o.X,
	}
}

func (v Vec3) Length() float64 {
	return math.Sqrt(v.X*v.X + v.Y*v.Y + v.Z*v.Z)
}

func (v Vec3) Normalize() Vec3 {
	l := v.Length()
	if l == 0 {
		return Vec3{0, 0, 0}
	}
	return v.Div(l)
}

func (v Vec3) Distance(o Vec3) float64 {
	return v.Sub(o).Length()
}

func (v Vec3) Angle(o Vec3) float64 {
	dot := v.Dot(o)
	lenProduct := v.Length() * o.Length()
	if lenProduct == 0 {
		return 0
	}
	cos := dot / lenProduct
	if cos > 1 {
		cos = 1
	} else if cos < -1 {
		cos = -1
	}
	return math.Acos(cos)
}

type AABB struct {
	Min, Max Vec3
}

func NewAABB(min, max Vec3) AABB {
	return AABB{Min: min, Max: max}
}

func (a AABB) Center() Vec3 {
	return a.Min.Add(a.Max).Mul(0.5)
}

func (a AABB) Size() Vec3 {
	return a.Max.Sub(a.Min)
}

func (a AABB) Extent() float64 {
	return a.Size().Length() / 2
}

func (a AABB) Contains(p Vec3) bool {
	return p.X >= a.Min.X && p.X <= a.Max.X &&
		p.Y >= a.Min.Y && p.Y <= a.Max.Y &&
		p.Z >= a.Min.Z && p.Z <= a.Max.Z
}

func (a AABB) Intersects(o AABB) bool {
	return a.Min.X <= o.Max.X && a.Max.X >= o.Min.X &&
		a.Min.Y <= o.Max.Y && a.Max.Y >= o.Min.Y &&
		a.Min.Z <= o.Max.Z && a.Max.Z >= o.Min.Z
}

func (a AABB) Expand(p Vec3) AABB {
	return AABB{
		Min: Vec3{
			math.Min(a.Min.X, p.X),
			math.Min(a.Min.Y, p.Y),
			math.Min(a.Min.Z, p.Z),
		},
		Max: Vec3{
			math.Max(a.Max.X, p.X),
			math.Max(a.Max.Y, p.Y),
			math.Max(a.Max.Z, p.Z),
		},
	}
}

type Frustum struct {
	Planes [6]Vec4
}

func (f *Frustum) IntersectsAABB(aabb AABB) bool {
	center := aabb.Center()
	extent := aabb.Size().Mul(0.5)

	for _, plane := range f.Planes {
		normal := Vec3{plane.X, plane.Y, plane.Z}
		d := plane.W

		dist := normal.Dot(center) + d

		radius := math.Abs(extent.X*normal.X) +
			math.Abs(extent.Y*normal.Y) +
			math.Abs(extent.Z*normal.Z)

		if dist + radius < 0 {
			return false
		}
	}

	return true
}

func Mat4Identity() Mat4 {
	return Mat4{
		1, 0, 0, 0,
		0, 1, 0, 0,
		0, 0, 1, 0,
		0, 0, 0, 1,
	}
}

func Mat4Perspective(fov, aspect, near, far float64) Mat4 {
	f := 1.0 / math.Tan(fov/2)
	nf := 1.0 / (near - far)
	return Mat4{
		f / aspect, 0, 0, 0,
		0, f, 0, 0,
		0, 0, (far + near) * nf, -1,
		0, 0, 2 * far * near * nf, 0,
	}
}

func Mat4LookAt(eye, target, up Vec3) Mat4 {
	zAxis := eye.Sub(target).Normalize()
	xAxis := up.Cross(zAxis).Normalize()
	yAxis := zAxis.Cross(xAxis).Normalize()

	return Mat4{
		xAxis.X, yAxis.X, zAxis.X, 0,
		xAxis.Y, yAxis.Y, zAxis.Y, 0,
		xAxis.Z, yAxis.Z, zAxis.Z, 0,
		-xAxis.Dot(eye), -yAxis.Dot(eye), -zAxis.Dot(eye), 1,
	}
}

func (m Mat4) Mul(o Mat4) Mat4 {
	var result Mat4
	for i := 0; i < 4; i++ {
		for j := 0; j < 4; j++ {
			sum := 0.0
			for k := 0; k < 4; k++ {
				sum += m[i*4+k] * o[k*4+j]
			}
			result[i*4+j] = sum
		}
	}
	return result
}

func (m Mat4) MulVec4(v Vec4) Vec4 {
	return Vec4{
		m[0]*v.X + m[4]*v.Y + m[8]*v.Z + m[12]*v.W,
		m[1]*v.X + m[5]*v.Y + m[9]*v.Z + m[13]*v.W,
		m[2]*v.X + m[6]*v.Y + m[10]*v.Z + m[14]*v.W,
		m[3]*v.X + m[7]*v.Y + m[11]*v.Z + m[15]*v.W,
	}
}

func ExtractFrustum(vp Mat4) *Frustum {
	var frustum Frustum

	frustum.Planes[0] = Vec4{
		X: vp[3] - vp[0],
		Y: vp[7] - vp[4],
		Z: vp[11] - vp[8],
		W: vp[15] - vp[12],
	}
	frustum.Planes[1] = Vec4{
		X: vp[3] + vp[0],
		Y: vp[7] + vp[4],
		Z: vp[11] + vp[8],
		W: vp[15] + vp[12],
	}
	frustum.Planes[2] = Vec4{
		X: vp[3] - vp[1],
		Y: vp[7] - vp[5],
		Z: vp[11] - vp[9],
		W: vp[15] - vp[13],
	}
	frustum.Planes[3] = Vec4{
		X: vp[3] + vp[1],
		Y: vp[7] + vp[5],
		Z: vp[11] + vp[9],
		W: vp[15] + vp[13],
	}
	frustum.Planes[4] = Vec4{
		X: vp[3] - vp[2],
		Y: vp[7] - vp[6],
		Z: vp[11] - vp[10],
		W: vp[15] - vp[14],
	}
	frustum.Planes[5] = Vec4{
		X: vp[3] + vp[2],
		Y: vp[7] + vp[6],
		Z: vp[11] + vp[10],
		W: vp[15] + vp[14],
	}

	for i := 0; i < 6; i++ {
		length := math.Sqrt(frustum.Planes[i].X*frustum.Planes[i].X +
			frustum.Planes[i].Y*frustum.Planes[i].Y +
			frustum.Planes[i].Z*frustum.Planes[i].Z)
		if length > 0 {
			frustum.Planes[i].X /= length
			frustum.Planes[i].Y /= length
			frustum.Planes[i].Z /= length
			frustum.Planes[i].W /= length
		}
	}

	return &frustum
}

func LookAt(eye, target, up Vec3) Mat4 {
	return Mat4LookAt(eye, target, up)
}

func Perspective(fov, aspect, near, far float64) Mat4 {
	return Mat4Perspective(fov, aspect, near, far)
}
