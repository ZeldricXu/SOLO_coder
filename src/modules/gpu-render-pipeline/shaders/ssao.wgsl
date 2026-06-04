struct Uniforms {
  viewProj: mat4x4<f32>,
  invViewProj: mat4x4<f32>,
  eye: vec4<f32>,
  screenSize: vec4<f32>,
  lights: array<vec4<f32>, 8>,
};

@group(0) @binding(0) var<uniform> uniforms: Uniforms;
@group(1) @binding(0) var depthTexture: texture_2d<f32>;
@group(1) @binding(1) var normalTexture: texture_2d<f32>;
@group(1) @binding(2) var noiseTexture: texture_2d<f32>;
@group(2) @binding(0) var outputTexture: texture_storage_2d<rgba8unorm, write>;

const SSAO_KERNEL_SIZE = 16u;
const SSAO_RADIUS = 2.0;
const SSAO_BIAS = 0.025;

var<private> kernel: array<vec3<f32>, 16> = array<vec3<f32>, 16>(
  vec3<f32>( 0.024,  0.054,  0.019), vec3<f32>(-0.032,  0.078,  0.063),
  vec3<f32>( 0.089, -0.021,  0.041), vec3<f32>(-0.067, -0.045,  0.082),
  vec3<f32>( 0.054,  0.032, -0.015), vec3<f32>(-0.091,  0.056,  0.034),
  vec3<f32>( 0.043, -0.087,  0.023), vec3<f32>(-0.012,  0.098, -0.041),
  vec3<f32>( 0.076,  0.012,  0.067), vec3<f32>(-0.054, -0.065,  0.091),
  vec3<f32>( 0.021,  0.087, -0.054), vec3<f32>(-0.078,  0.043,  0.012),
  vec3<f32>( 0.065, -0.034,  0.078), vec3<f32>(-0.043,  0.076, -0.023),
  vec3<f32>( 0.032, -0.091,  0.054), vec3<f32>(-0.087, -0.012,  0.065)
);

@compute @workgroup_size(8, 8)
fn computeMain(@builtin(global_invocation_id) gid: vec3<u32>) {
  let pixelCoords = vec2<u32>(gid.xy);
  let dims = vec2<f32>(textureDimensions(depthTexture));
  if (f32(pixelCoords.x) >= dims.x || f32(pixelCoords.y) >= dims.y) {
    return;
  }

  let uv = (vec2<f32>(pixelCoords) + 0.5) / dims;

  let depthValue = textureLoad(depthTexture, pixelCoords, 0).r;
  if (depthValue >= 1.0) {
    textureStore(outputTexture, pixelCoords, vec4<f32>(1.0, 1.0, 1.0, 1.0));
    return;
  }

  let normalValue = textureLoad(normalTexture, pixelCoords, 0).xyz;
  let normal = normalValue * 2.0 - 1.0;

  let ndcX = uv.x * 2.0 - 1.0;
  let ndcY = (1.0 - uv.y) * 2.0 - 1.0;
  let worldPos = ndcToWorld(vec3<f32>(ndcX, ndcY, depthValue));

  let noiseSize = vec2<f32>(textureDimensions(noiseTexture));
  let noiseUv = vec2<f32>(pixelCoords) / noiseSize;
  let randomVec = textureLoad(noiseTexture, vec2<u32>(pixelCoords % vec2<u32>(u32(noiseSize.x), u32(noiseSize.y))), 0).xyz;

  let tangent = normalize(randomVec - normal * dot(randomVec, normal));
  let bitangent = cross(normal, tangent);
  let TBN = mat3x3<f32>(tangent, bitangent, normal);

  var occlusion = 0.0;
  for (var i = 0u; i < SSAO_KERNEL_SIZE; i++) {
    let sampleDir = TBN * kernel[i];
    let samplePos = worldPos + sampleDir * SSAO_RADIUS;

    let sampleClip = uniforms.viewProj * vec4<f32>(samplePos, 1.0);
    let sampleNdc = sampleClip.xyz / sampleClip.w;
    let sampleUv = (sampleNdc.xy + 1.0) * 0.5;
    sampleUv.y = 1.0 - sampleUv.y;

    if (sampleUv.x < 0.0 || sampleUv.x > 1.0 || sampleUv.y < 0.0 || sampleUv.y > 1.0) {
      continue;
    }

    let sampleCoords = vec2<u32>(sampleUv * dims);
    let sampleDepth = textureLoad(depthTexture, sampleCoords, 0).r;

    let sampleWorldZ = ndcToWorld(vec3<f32>(sampleNdc.x, sampleNdc.y, sampleDepth)).z;

    let rangeCheck = smoothstep(0.0, 1.0, SSAO_RADIUS / abs(worldPos.z - sampleWorldZ));
    if (sampleDepth <= sampleNdc.z + SSAO_BIAS) {
      occlusion += rangeCheck;
    }
  }

  occlusion = 1.0 - (occlusion / f32(SSAO_KERNEL_SIZE));
  occlusion = pow(occlusion, 1.5);

  textureStore(outputTexture, pixelCoords, vec4<f32>(occlusion, occlusion, occlusion, 1.0));
}

fn ndcToWorld(ndc: vec3<f32>) -> vec3<f32> {
  let world = uniforms.invViewProj * vec4<f32>(ndc, 1.0);
  return world.xyz / world.w;
}

fn smoothstep(edge0: f32, edge1: f32, x: f32) -> f32 {
  let t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
  return t * t * (3.0 - 2.0 * t);
}
