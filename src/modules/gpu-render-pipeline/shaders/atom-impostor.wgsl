struct AtomData {
  position: vec4<f32>,
  color_radius: vec4<f32>,
  extra: vec4<f32>,
};

struct Uniforms {
  viewProj: mat4x4<f32>,
  invViewProj: mat4x4<f32>,
  eye: vec4<f32>,
  screenSize: vec4<f32>,
  renderParams: vec4<f32>,
  lights: array<vec4<f32>, 8>,
};

@group(0) @binding(0) var<uniform> uniforms: Uniforms;
@group(0) @binding(1) var<storage, read> atoms: array<AtomData>;
@group(0) @binding(2) var<storage, read> visibleIndices: array<u32>;
@group(0) @binding(3) var<storage, read> visibleCount: atomic<u32>;

struct VertexOutput {
  @builtin(position) position: vec4<f32>,
  @location(0) worldPos: vec3<f32>,
  @location(1) sphereCenter: vec3<f32>,
  @location(2) sphereRadius: f32,
  @location(3) albedo: vec3<f32>,
  @location(4) ndcDepth: f32,
  @location(5) atomAlpha: f32,
  @location(6) bFactor: f32,
};

fn bFactorToColor(bFactor: f32) -> vec3<f32> {
  let t = clamp(bFactor / 100.0, 0.0, 1.0);
  if (t < 0.25) {
    return mix(vec3<f32>(0.0, 0.0, 1.0), vec3<f32>(0.0, 1.0, 1.0), t * 4.0);
  } else if (t < 0.5) {
    return mix(vec3<f32>(0.0, 1.0, 1.0), vec3<f32>(0.0, 1.0, 0.0), (t - 0.25) * 4.0);
  } else if (t < 0.75) {
    return mix(vec3<f32>(0.0, 1.0, 0.0), vec3<f32>(1.0, 1.0, 0.0), (t - 0.5) * 4.0);
  } else {
    return mix(vec3<f32>(1.0, 1.0, 0.0), vec3<f32>(1.0, 0.0, 0.0), (t - 0.75) * 4.0);
  }
}

fn chainColor(chainId: f32) -> vec3<f32> {
  let palette = array<vec3<f32>, 8>(
    vec3<f32>(0.0, 0.83, 0.67),
    vec3<f32>(0.94, 0.65, 0.0),
    vec3<f32>(0.29, 0.56, 0.89),
    vec3<f32>(0.86, 0.08, 0.24),
    vec3<f32>(0.55, 0.0, 0.55),
    vec3<f32>(0.0, 0.6, 0.0),
    vec3<f32>(0.98, 0.38, 0.0),
    vec3<f32>(0.4, 0.4, 0.4),
  );
  let idx = u32(chainId) % 8u;
  return palette[idx];
}

@vertex
fn vertexMain(@builtin(vertex_index) vertexIndex: u32, @builtin(instance_index) instanceIndex: u32) -> VertexOutput {
  var out: VertexOutput;

  let idx = visibleIndices[instanceIndex];
  let atom = atoms[idx];
  let center = atom.position.xyz;
  let radius = atom.color_radius.w;
  let elementColor = atom.color_radius.xyz;
  let alpha = atom.extra.x;
  let bFactor = atom.extra.y;
  let chainId = atom.extra.z;

  let colorMode = uniforms.renderParams.x;
  var baseColor: vec3<f32>;
  if (colorMode < 0.5) {
    baseColor = elementColor;
  } else if (colorMode < 1.5) {
    baseColor = bFactorToColor(bFactor);
  } else if (colorMode < 2.5) {
    baseColor = chainColor(chainId);
  } else {
    baseColor = elementColor;
  }

  let viewPos = (uniforms.viewProj * vec4<f32>(center, 1.0));
  let clipW = viewPos.w;
  let screenRadius = radius * uniforms.screenSize.w / clipW;

  let offsets = array<vec2<f32>, 6>(
    vec2<f32>(-1.0, -1.0),
    vec2<f32>( 1.0, -1.0),
    vec2<f32>(-1.0,  1.0),
    vec2<f32>(-1.0,  1.0),
    vec2<f32>( 1.0, -1.0),
    vec2<f32>( 1.0,  1.0),
  );

  let offset = offsets[vertexIndex % 6u] * (screenRadius + 2.0);
  let ndcXY = viewPos.xy / clipW;

  out.position = vec4<f32>(ndcXY + offset * 2.0 / uniforms.screenSize.xy, viewPos.z / clipW, 1.0);
  out.worldPos = center;
  out.sphereCenter = center;
  out.sphereRadius = radius;
  out.albedo = baseColor;
  out.ndcDepth = viewPos.z / clipW;
  out.atomAlpha = alpha;
  out.bFactor = bFactor;

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

  let oc = rayOrigin - input.sphereCenter;
  let b = dot(oc, rayDir);
  let c = dot(oc, oc) - input.sphereRadius * input.sphereRadius;
  let disc = b * b - c;

  if (disc < 0.0) {
    discard;
  }

  let sqrtDisc = sqrt(disc);
  let t = -b - sqrtDisc;
  let t2 = -b + sqrtDisc;

  var hitT: f32;
  if (t > 0.001) {
    hitT = t;
  } else if (t2 > 0.001) {
    hitT = t2;
  } else {
    discard;
  }

  let hitPoint = rayOrigin + rayDir * hitT;
  let normal = normalize(hitPoint - input.sphereCenter);

  let metallic = 0.1;
  let roughness = 0.4;

  let V = -rayDir;
  let litColor = pbrShading(input.albedo, metallic, roughness, normal, V);

  let clipPos = uniforms.viewProj * vec4<f32>(hitPoint, 1.0);

  var out: FragmentOutput;
  out.position = vec4<f32>(clipPos.xy / clipPos.w, clipPos.z / clipPos.w, 1.0);
  out.color = vec4<f32>(litColor, input.atomAlpha);
  out.normal = vec4<f32>(normal * 0.5 + 0.5, input.atomAlpha);
  return out;
}

