import { describe, it, expect, beforeEach, vi } from 'vitest';
import * as THREE from 'three';
import { SketchfabAPIService } from '@/services/sketchfabAPI';
import { createMockSketchfabModel, createMockSketchfabSearchResponse } from '../fixtures/sketchfabFixtures';

describe('Sketchfab API服务 - 正常路径测试', () => {
  let apiService: SketchfabAPIService;

  beforeEach(() => {
    apiService = new SketchfabAPIService();
    vi.restoreAllMocks();
  });

  it('应该构建正确的搜索URL参数', async () => {
    const mockResponse = createMockSketchfabSearchResponse(6);
    const fetchSpy = vi
      .spyOn(global, 'fetch')
      .mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockResponse),
      } as unknown as Response);

    const result = await apiService.searchModels({
      q: 'chair',
      categories: 'furniture',
      downloadable: true,
      sort_by: 'downloads',
      per_page: 6,
    });

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const callUrl = fetchSpy.mock.calls[0][0] as string;
    expect(callUrl).toContain('q=chair');
    expect(callUrl).toContain('categories=furniture');
    expect(callUrl).toContain('downloadable=true');
    expect(callUrl).toContain('sort_by=downloads');
    expect(callUrl).toContain('per_page=6');

    expect(result.results).toHaveLength(6);
  });

  it('没有设置token时请求头不应包含Authorization', async () => {
    const mockResponse = createMockSketchfabSearchResponse(3);
    const fetchSpy = vi
      .spyOn(global, 'fetch')
      .mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockResponse),
      } as unknown as Response);

    await apiService.searchModels({ q: 'sofa' });

    const callOptions = fetchSpy.mock.calls[0][1] as RequestInit;
    expect(callOptions.headers).toBeDefined();
    const headers = callOptions.headers as Record<string, string>;
    expect(headers['Authorization']).toBeUndefined();
  });

  it('设置token后请求头应该包含Authorization', async () => {
    const testToken = 'test-api-token-12345';
    apiService.setToken(testToken);

    const mockResponse = createMockSketchfabSearchResponse(3);
    const fetchSpy = vi
      .spyOn(global, 'fetch')
      .mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockResponse),
      } as unknown as Response);

    await apiService.searchModels({ q: 'table' });

    const callOptions = fetchSpy.mock.calls[0][1] as RequestInit;
    const headers = callOptions.headers as Record<string, string>;
    expect(headers['Authorization']).toBe(`Token ${testToken}`);
  });

  it('应该正确获取单个模型详情', async () => {
    const mockModel = createMockSketchfabModel('model-abc-123', {
      name: 'Vintage Chair',
      vertexCount: 50000,
    });
    const fetchSpy = vi
      .spyOn(global, 'fetch')
      .mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockModel),
      } as unknown as Response);

    const result = await apiService.getModel('model-abc-123');

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/models/model-abc-123'),
      expect.any(Object)
    );
    expect(result.uid).toBe('model-abc-123');
    expect(result.name).toBe('Vintage Chair');
    expect(result.vertexCount).toBe(50000);
  });

  it('应该正确获取模型下载URL', async () => {
    const mockDownload = {
      gltf: { url: 'https://download.sketchfab.com/models/model-xyz.glb' },
      url: 'https://download.sketchfab.com/models/model-xyz.glb',
    };
    const fetchSpy = vi
      .spyOn(global, 'fetch')
      .mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockDownload),
      } as unknown as Response);

    const url = await apiService.getModelDownloadUrl('model-xyz');

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/models/model-xyz/download'),
      expect.any(Object)
    );
    expect(url).toContain('.glb');
  });
});

describe('Sketchfab API服务 - 异常路径测试', () => {
  let apiService: SketchfabAPIService;

  beforeEach(() => {
    apiService = new SketchfabAPIService();
    vi.restoreAllMocks();
  });

  it('API返回404应该抛出错误', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
    } as unknown as Response);

    await expect(apiService.getModel('nonexistent')).rejects.toThrow(/404/);
  });

  it('API返回401应该抛出认证错误', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
    } as unknown as Response);

    await expect(apiService.searchModels({ q: 'test' })).rejects.toThrow(/401/);
  });

  it('不可下载的模型应该抛出错误', async () => {
    vi.spyOn(global, 'fetch').mockResolvedValue({
      ok: false,
      status: 403,
      statusText: 'Forbidden',
    } as unknown as Response);

    await expect(apiService.getModelDownloadUrl('protected-model')).rejects.toThrow();
  });

  it('搜索没有结果时应该返回空数组', async () => {
    const mockEmptyResponse = createMockSketchfabSearchResponse(0, false);
    vi.spyOn(global, 'fetch').mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockEmptyResponse),
    } as unknown as Response);

    const result = await apiService.searchModels({ q: 'nonexistent-xyz-12345' });
    expect(result.results).toHaveLength(0);
  });
});
