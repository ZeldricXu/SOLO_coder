import type { SketchfabModel, SketchfabSearchResponse } from '@/types/sketchfab';

export const createMockSketchfabModel = (
  uid: string,
  overrides: Partial<SketchfabModel> = {}
): SketchfabModel => ({
  uid,
  name: `Test Model ${uid}`,
  description: 'A test 3D model',
  user: {
    username: 'testuser',
    displayName: 'Test User',
    avatar: { images: [{ url: 'https://example.com/avatar.jpg', size: 100 }] },
  },
  categories: [{ name: 'Furniture', slug: 'furniture' }],
  tags: [{ slug: 'chair' }, { slug: 'wood' }],
  thumbnails: {
    images: [
      { url: 'https://example.com/thumb-large.jpg', size: 512, width: 512, height: 512 },
      { url: 'https://example.com/thumb-small.jpg', size: 64, width: 64, height: 64 },
    ],
  },
  vertexCount: 10000,
  faceCount: 5000,
  isDownloadable: true,
  license: { label: 'CC BY', slug: 'cc-by' },
  viewCount: 12345,
  likeCount: 678,
  publishedAt: '2024-01-01T00:00:00Z',
  animationCount: 0,
  archive: {
    size: 1024000,
    source: {
      size: 2048000,
      url: 'https://example.com/model.glb',
    },
  },
  ...overrides,
});

export const createMockSketchfabSearchResponse = (
  count: number = 12,
  hasNext: boolean = true
): SketchfabSearchResponse => {
  const results: SketchfabModel[] = Array.from({ length: count }, (_, i) =>
    createMockSketchfabModel(`model-${i + 1}`, {
      name: `Model ${i + 1}`,
      vertexCount: (i + 1) * 1000,
      viewCount: (i + 1) * 100,
    })
  );

  return {
    results,
    previous: undefined,
    next: hasNext ? 'cursor-next' : undefined,
    cursors: {
      next: hasNext ? 'cursor-next' : undefined,
      previous: undefined,
    },
  };
};

export const sketchfabTestFixtures = {
  createMockSketchfabModel,
  createMockSketchfabSearchResponse,
};

export default sketchfabTestFixtures;
