struct AtomData {
  position: vec4<f32>,
  color_radius: vec4<f32>,
};

struct BondData {
  atom1Index: u32,
  atom2Index: u32,
  bondOrder: f32,
  _pad: f32,
};

struct Uniforms {
  viewProj: mat4x4<f32>,
  invViewProj: mat4x4<f32>,
  eye: vec4<f32>,
  screenSize: vec4<f32>,
  lights: array<vec4<f32>, 8>,
};

@group(0) @binding(0) var<uniform> uniforms: Uniforms;
@group(0) @binding(1) var<storage, read> atoms: array<AtomData>;
@group(0) @binding(2) var<storage, read> bonds: array<BondData>;
@group(0) @binding(3) var<storage, read> visibleBondIndices: array<u32>;

struct VertexOutput {
  @builtin(position) position: vec4<f32>,
  @location(0) worldPos: vec3<f32>,
  @location(1) cylStart: vec3<f32>,
  @location(2) cylEnd: vec3<f32>,
  @location(3) cylRadius: f32,
  @location(4) albedo: vec3<f32>,
};

@vertex
fn vertexMain(@builtin(vertex_index) vertexIndex: u32, @builtin(instance_index) instanceIndex: u32) -> VertexOutput {
  var out: VertexOutput;

  let bondIdx = visibleBondIndices[instanceIndex];
  let bond = bonds[bondIdx];
  let atom1 = atoms[bond.atom1Index];
  let atom2 = atoms[bond.atom2Index];

  let p1 = atom1.position.xyz;
  let p2 = atom2.position.xyz;
  let bondRadius = 0.15;

  let bondDir = normalize(p2 - p1);
  let midPoint = (p1 + p2) * 0.5;
  let bondLen = distance(p1, p2);

  let viewMid = uniforms.viewProj * vec4<f32>(midPoint, 1.0);
  let clipW = viewMid.w;
  let screenRadius = bondRadius * uniforms.screenSize.w / clipW;

  let offsets = array<vec2<f32>, 6>(
    vec2<f32>(-1.0, -1.0),
    vec2<f32>( 1.0, -1.0),
    vec2<f32>(-1.0,  1.0),
    vec2<f32>(-1.0,  1.0),
    vec2<f32>( 1.0, -1.0),
    vec2<f32>( 1.0,  1.0),
  );

  let offset = offsets[vertexIndex % 6u] * (screenRadius + 2.0);
  let ndcXY = viewMid.xy / clipW;

  out.position = vec4<f32>(ndcXY + offset * 2.0 / uniforms.screenSize.xy, viewMid.z / clipW, 1.0);
  out.worldPos = midPoint;
  out.cylStart = p1;
  out.cylEnd = p2;
  out.cylRadius = bondRadius;
  out.albedo = (atom1.color_radius.xyz + atom2.color_radius.xyz) * 0.5;

  return out;
}

struct FragmentOutput {
  @builtin(position) position: vec4<f32>,
  @location(0) color: vec4<f32>,
  @location(1) normal: vec4<f32>,
};

@fragment
fn fragmentMain(input: VertexOutput) -> FragmentOutput {
  let rayOrigin = uniforms.eye.xyz;
  let rayDir = normalize(input.worldPos - rayOrigin);

  let pa = input.cylStart - rayOrigin;
  let ba = input.cylEnd - input.cylStart;

  let baba = dot(ba, ba);
  let paba = dot(pa, ba);
  let raba = dot(rayDir, ba);

  let a = baba - raba * raba;
  let b = baba * dot(pa, rayDir) - paba * raba;
  let c = baba * dot(pa, pa) - paba * paba - input.cylRadius * input.cylRadius * baba;

  let disc = b * b - a * c;
  if (disc < 0.0) {
    discard;
  }

  let sqrtDisc = sqrt(disc);
  var t = (-b - sqrtDisc) / a;
  if (t < 0.001) {
    t = (-b + sqrtDisc) / a;
  }
  if (t < 0.001) {
    discard;
  }

  let hitPoint = rayOrigin + rayDir * t;
  let projection = dot(hitPoint - input.cylStart, ba) / baba;

  if (projection < 0.0 || projection > 1.0) {
    discard;
  }

  let centerOnAxis = input.cylStart + ba * projection;
  let normal = normalize(hitPoint - centerOnAxis);

  let metallic = 0.1;
  let roughness = 0.5;
  let V = -rayDir;
  let litColor = pbrShading(input.albedo, metallic, roughness, normal, V);

  let clipPos = uniforms.viewProj * vec4<f32>(hitPoint, 1.0);

  var out: FragmentOutput;
  out.position = vec4<f32>(clipPos.xy / clipPos.w, clipPos.z / clipPos.w, 1.0);
  out.color = vec4<f32>(litColor, 1.0);
  out.normal = vec4<f32>(normal * 0.5 + 0.5, 1.0);
  return out;
}
