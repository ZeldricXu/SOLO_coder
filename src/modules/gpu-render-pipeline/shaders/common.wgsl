struct Uniforms {
  viewProj: mat4x4<f32>,
  invViewProj: mat4x4<f32>,
  eye: vec4<f32>,
  screenSize: vec4<f32>,
  lights: array<vec4<f32>, 8>,
};

@group(0) @binding(0) var<uniform> uniforms: Uniforms;

fn ndcToWorld(ndc: vec3<f32>) -> vec3<f32> {
  let world = uniforms.invViewProj * vec4<f32>(ndc, 1.0);
  return world.xyz / world.w;
}

fn packColor(c: vec3<f32>) -> u32 {
  let r = u32(clamp(c.r * 255.0, 0.0, 255.0));
  let g = u32(clamp(c.g * 255.0, 0.0, 255.0));
  let b = u32(clamp(c.b * 255.0, 0.0, 255.0));
  return (0xFFu << 24u) | (b << 16u) | (g << 8u) | r;
}

struct Light {
  direction: vec3<f32>,
  color: vec3<f32>,
  intensity: f32,
};

fn getLight(i: i32) -> Light {
  var l: Light;
  let d = uniforms.lights[i * 2];
  let c = uniforms.lights[i * 2 + 1];
  l.direction = d.xyz;
  l.intensity = d.w;
  l.color = c.xyz;
  return l;
}

fn pbrShading(
  albedo: vec3<f32>,
  metallic: f32,
  roughness: f32,
  N: vec3<f32>,
  V: vec3<f32>
) -> vec3<f32> {
  let ao = 1.0;
  let F0 = mix(vec3<f32>(0.04), albedo, metallic);

  var Lo = vec3<f32>(0.0);

  for (var i = 0; i < 3; i++) {
    let light = getLight(i);
    let L = -light.direction;
    let H = normalize(V + L);

    let NDF = distributionGGX(N, H, roughness);
    let G = geometrySmith(N, V, L, roughness);
    let F = fresnelSchlick(max(dot(H, V), 0.0), F0);

    let kD = (vec3<f32>(1.0) - F) * (1.0 - metallic);
    let numerator = NDF * G * F;
    let denominator = 4.0 * max(dot(N, V), 0.0) * max(dot(N, L), 0.0) + 0.0001;
    let specular = numerator / denominator;
    let diffuse = kD * albedo / 3.14159265;

    let NdotL = max(dot(N, L), 0.0);
    Lo = Lo + (diffuse + specular) * light.color * light.intensity * NdotL;
  }

  let ambient = vec3<f32>(0.03) * albedo * ao;
  return ambient + Lo;
}

fn distributionGGX(N: vec3<f32>, H: vec3<f32>, roughness: f32) -> f32 {
  let a = roughness * roughness;
  let a2 = a * a;
  let NdotH = max(dot(N, H), 0.0);
  let NdotH2 = NdotH * NdotH;
  let denom = NdotH2 * (a2 - 1.0) + 1.0;
  return a2 / (3.14159265 * denom * denom + 0.0001);
}

fn geometrySchlickGGX(NdotV: f32, roughness: f32) -> f32 {
  let r = roughness + 1.0;
  let k = (r * r) / 8.0;
  return NdotV / (NdotV * (1.0 - k) + k);
}

fn geometrySmith(N: vec3<f32>, V: vec3<f32>, L: vec3<f32>, roughness: f32) -> f32 {
  let NdotV = max(dot(N, V), 0.0);
  let NdotL = max(dot(N, L), 0.0);
  return geometrySchlickGGX(NdotV, roughness) * geometrySchlickGGX(NdotL, roughness);
}

fn fresnelSchlick(cosTheta: f32, F0: vec3<f32>) -> vec3<f32> {
  return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
}
