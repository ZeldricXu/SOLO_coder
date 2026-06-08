import * as THREE from 'three';

export interface SketchfabSearchParams {
  q?: string;
  categories?: string;
  licenses?: string;
  downloadable?: boolean;
  animated?: boolean;
  staffpicked?: boolean;
  sort_by?:
    | 'relevance'
    | 'likes'
    | 'views'
    | 'recent'
    | 'downloads';
  cursor?: string;
  per_page?: number;
}

export interface SketchfabModelThumbnail {
  url: string;
  size: number;
  width?: number;
  height?: number;
}

export interface SketchfabModel {
  uid: string;
  name: string;
  description?: string;
  user: {
    username: string;
    displayName: string;
    avatar?: { images: SketchfabModelThumbnail[] };
  };
  categories?: { name: string; slug: string }[];
  tags?: { slug: string }[];
  thumbnails: { images: SketchfabModelThumbnail[] };
  vertexCount: number;
  faceCount: number;
  isDownloadable: boolean;
  license: { label: string; slug: string };
  viewCount: number;
  likeCount: number;
  publishedAt: string;
  animationCount?: number;
  archive?: {
    size: number;
    source: {
      size: number;
      url?: string;
    };
  };
}

export interface SketchfabSearchResponse {
  results: SketchfabModel[];
  previous?: string;
  next?: string;
  cursors: {
    next?: string;
    previous?: string;
  };
}

export interface SketchfabImportOptions {
  autoScale?: boolean;
  targetSize?: number;
  rotateX?: number;
  centerModel?: boolean;
}

export interface SketchfabImportResult {
  uid: string;
  name: string;
  group: THREE.Group;
  boundingBox: THREE.Box3;
  originalSize: THREE.Vector3;
  scaledSize: THREE.Vector3;
}

export const SKETCHFAB_CATEGORIES = [
  { slug: '', name: '全部' },
  { slug: 'furniture', name: '家具' },
  { slug: 'architecture', name: '建筑' },
  { slug: 'interior', name: '室内' },
  { slug: 'electronics', name: '电子产品' },
  { slug: 'plants', name: '植物' },
  { slug: 'kitchen', name: '厨房' },
  { slug: 'bathroom', name: '卫浴' },
  { slug: 'lighting', name: '灯具' },
  { slug: 'decor', name: '装饰' },
] as const;
