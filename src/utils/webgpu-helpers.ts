export async function checkWebGPUSupport(): Promise<boolean> {
  if (!navigator.gpu) return false;
  const adapter = await navigator.gpu.requestAdapter();
  return adapter !== null;
}

export async function getGPUDevice(): Promise<GPUDevice | null> {
  const adapter = await navigator.gpu.requestAdapter();
  if (!adapter) return null;
  try {
    return await adapter.requestDevice();
  } catch {
    return null;
  }
}

export function createGPUBuffer(device: GPUDevice, data: ArrayBuffer, usage: GPUBufferUsageFlags): GPUBuffer {
  const buffer = device.createBuffer({
    size: data.byteLength,
    usage,
    mappedAtCreation: true,
  });
  new Uint8Array(buffer.getMappedRange()).set(new Uint8Array(data));
  buffer.unmap();
  return buffer;
}
