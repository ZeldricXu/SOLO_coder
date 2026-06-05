import type { Material, PBRProperties } from './floorplan';

export interface FurnitureModel {
  id: string;
  name: string;
  category: string;
  subcategory: string;
  modelPath: string;
  thumbnailPath: string;
  defaultMaterials: Record<string, string>;
  boundingBox: {
    min: { x: number; y: number; z: number };
    max: { x: number; y: number; z: number };
  };
}

export interface FurnitureCategory {
  id: string;
  name: string;
  icon: string;
  subcategories: string[];
}

export const DEFAULT_MATERIALS: Material[] = [
  {
    id: 'mat-wall-white',
    name: '白色墙面',
    type: 'pbr',
    properties: {
      color: { r: 0.95, g: 0.95, b: 0.93 },
      roughness: 0.85,
      metalness: 0.0,
    } as PBRProperties,
  },
  {
    id: 'mat-wall-concrete',
    name: '清水混凝土',
    type: 'pbr',
    properties: {
      color: { r: 0.5, g: 0.5, b: 0.52 },
      roughness: 0.9,
      metalness: 0.0,
    } as PBRProperties,
  },
  {
    id: 'mat-floor-wood',
    name: '实木地板',
    type: 'pbr',
    properties: {
      color: { r: 0.72, g: 0.55, b: 0.38 },
      roughness: 0.5,
      metalness: 0.0,
    } as PBRProperties,
  },
  {
    id: 'mat-floor-marble',
    name: '白色大理石',
    type: 'pbr',
    properties: {
      color: { r: 0.95, g: 0.95, b: 0.95 },
      roughness: 0.2,
      metalness: 0.0,
    } as PBRProperties,
  },
  {
    id: 'mat-glass-clear',
    name: '透明玻璃',
    type: 'pbr',
    properties: {
      color: { r: 0.9, g: 0.95, b: 1.0 },
      roughness: 0.05,
      metalness: 0.0,
    } as PBRProperties,
  },
  {
    id: 'mat-metal-brushed',
    name: '拉丝金属',
    type: 'pbr',
    properties: {
      color: { r: 0.85, g: 0.85, b: 0.87 },
      roughness: 0.3,
      metalness: 0.9,
    } as PBRProperties,
  },
  {
    id: 'mat-fabric-grey',
    name: '灰色布艺',
    type: 'pbr',
    properties: {
      color: { r: 0.5, g: 0.5, b: 0.52 },
      roughness: 0.95,
      metalness: 0.0,
    } as PBRProperties,
  },
  {
    id: 'mat-leather-black',
    name: '黑色皮革',
    type: 'pbr',
    properties: {
      color: { r: 0.15, g: 0.15, b: 0.15 },
      roughness: 0.4,
      metalness: 0.1,
    } as PBRProperties,
  },
];

export const FURNITURE_CATEGORIES: FurnitureCategory[] = [
  {
    id: 'seating',
    name: '座椅',
    icon: 'Sofa',
    subcategories: ['沙发', '休闲椅', '餐椅', '办公椅'],
  },
  {
    id: 'bedroom',
    name: '卧室',
    icon: 'Bed',
    subcategories: ['床', '床头柜', '衣柜', '梳妆台'],
  },
  {
    id: 'bathroom',
    name: '卫浴',
    icon: 'Bath',
    subcategories: ['马桶', '洗手台', '浴缸', '淋浴房'],
  },
  {
    id: 'kitchen',
    name: '厨房',
    icon: 'ChefHat',
    subcategories: ['橱柜', '冰箱', '炉灶', '洗碗机'],
  },
  {
    id: 'living',
    name: '客厅',
    icon: 'Tv',
    subcategories: ['茶几', '电视柜', '边柜'],
  },
  {
    id: 'lighting',
    name: '灯具',
    icon: 'Lamp',
    subcategories: ['吊灯', '落地灯', '台灯', '壁灯'],
  },
  {
    id: 'office',
    name: '办公',
    icon: 'Armchair',
    subcategories: ['办公桌', '办公椅', '书柜', '文件柜'],
  },
  {
    id: 'dining',
    name: '餐厅',
    icon: 'Table',
    subcategories: ['餐桌', '餐椅', '餐边柜'],
  },
];

export const BUILTIN_FURNITURE: FurnitureModel[] = [
  {
    id: 'sofa-3seat',
    name: '三人沙发',
    category: 'living',
    subcategory: '沙发',
    modelPath: '/models/sofa-3seat.glb',
    thumbnailPath: '/models/thumbnails/sofa-3seat.png',
    defaultMaterials: { fabric: 'mat-fabric-grey' },
    boundingBox: {
      min: { x: -1.1, y: 0, z: -0.45 },
      max: { x: 1.1, y: 0.85, z: 0.45 },
    },
  },
  {
    id: 'sofa-2seat',
    name: '双人沙发',
    category: 'living',
    subcategory: '沙发',
    modelPath: '/models/sofa-2seat.glb',
    thumbnailPath: '/models/thumbnails/sofa-2seat.png',
    defaultMaterials: { fabric: 'mat-fabric-grey' },
    boundingBox: {
      min: { x: -0.85, y: 0, z: -0.45 },
      max: { x: 0.85, y: 0.85, z: 0.45 },
    },
  },
  {
    id: 'coffee-table-rect',
    name: '长方形茶几',
    category: 'living',
    subcategory: '茶几',
    modelPath: '/models/coffee-table-rect.glb',
    thumbnailPath: '/models/thumbnails/coffee-table-rect.png',
    defaultMaterials: { top: 'mat-floor-marble', frame: 'mat-metal-brushed' },
    boundingBox: {
      min: { x: -0.65, y: 0, z: -0.35 },
      max: { x: 0.65, y: 0.45, z: 0.35 },
    },
  },
  {
    id: 'bed-queen',
    name: '双人床',
    category: 'bedroom',
    subcategory: '床',
    modelPath: '/models/bed-queen.glb',
    thumbnailPath: '/models/thumbnails/bed-queen.png',
    defaultMaterials: { fabric: 'mat-leather-black', wood: 'mat-floor-wood' },
    boundingBox: {
      min: { x: -1.0, y: 0, z: -1.05 },
      max: { x: 1.0, y: 1.1, z: 1.05 },
    },
  },
  {
    id: 'dining-table-6',
    name: '六人餐桌',
    category: 'dining',
    subcategory: '餐桌',
    modelPath: '/models/dining-table-6.glb',
    thumbnailPath: '/models/thumbnails/dining-table-6.png',
    defaultMaterials: { top: 'mat-floor-wood', legs: 'mat-metal-brushed' },
    boundingBox: {
      min: { x: -0.9, y: 0, z: -1.8 },
      max: { x: 0.9, y: 0.75, z: 1.8 },
    },
  },
  {
    id: 'dining-chair',
    name: '餐椅',
    category: 'dining',
    subcategory: '餐椅',
    modelPath: '/models/dining-chair.glb',
    thumbnailPath: '/models/thumbnails/dining-chair.png',
    defaultMaterials: { seat: 'mat-fabric-grey', frame: 'mat-metal-brushed' },
    boundingBox: {
      min: { x: -0.25, y: 0, z: -0.28 },
      max: { x: 0.25, y: 0.85, z: 0.28 },
    },
  },
];

export interface FurniturePreset {
  id: string;
  name: string;
  category: string;
  thumbnail?: string;
  dimensions: string;
}

export const FURNITURE_PRESETS: FurniturePreset[] = [
  { id: 'sofa-3seat', name: '三人沙发', category: 'seating', thumbnail: '🛋️', dimensions: '2.2 × 0.9 × 0.85m' },
  { id: 'sofa-2seat', name: '双人沙发', category: 'seating', thumbnail: '🛋️', dimensions: '1.7 × 0.9 × 0.85m' },
  { id: 'armchair', name: '单人沙发', category: 'seating', thumbnail: '🪑', dimensions: '0.8 × 0.8 × 0.9m' },
  { id: 'coffee-table', name: '茶几', category: 'living', thumbnail: '🪵', dimensions: '1.3 × 0.7 × 0.45m' },
  { id: 'tv-stand', name: '电视柜', category: 'living', thumbnail: '📺', dimensions: '1.8 × 0.45 × 0.5m' },
  { id: 'bed-queen', name: '双人床', category: 'bedroom', thumbnail: '🛏️', dimensions: '2.0 × 1.8 × 0.5m' },
  { id: 'bed-single', name: '单人床', category: 'bedroom', thumbnail: '🛏️', dimensions: '2.0 × 1.0 × 0.5m' },
  { id: 'wardrobe', name: '衣柜', category: 'bedroom', thumbnail: '🚪', dimensions: '1.8 × 0.6 × 2.4m' },
  { id: 'nightstand', name: '床头柜', category: 'bedroom', thumbnail: '🗄️', dimensions: '0.5 × 0.4 × 0.55m' },
  { id: 'dining-table-6', name: '六人餐桌', category: 'dining', thumbnail: '🍽️', dimensions: '1.8 × 0.9 × 0.75m' },
  { id: 'dining-table-4', name: '四人餐桌', category: 'dining', thumbnail: '🍽️', dimensions: '1.4 × 0.8 × 0.75m' },
  { id: 'dining-chair', name: '餐椅', category: 'dining', thumbnail: '🪑', dimensions: '0.5 × 0.55 × 0.85m' },
  { id: 'desk', name: '书桌', category: 'office', thumbnail: '🖥️', dimensions: '1.4 × 0.7 × 0.75m' },
  { id: 'office-chair', name: '办公椅', category: 'office', thumbnail: '💺', dimensions: '0.6 × 0.6 × 1.1m' },
  { id: 'bookshelf', name: '书架', category: 'office', thumbnail: '📚', dimensions: '1.2 × 0.35 × 2.0m' },
  { id: 'toilet', name: '马桶', category: 'bathroom', thumbnail: '🚽', dimensions: '0.4 × 0.65 × 0.75m' },
  { id: 'sink', name: '洗手台', category: 'bathroom', thumbnail: '🚰', dimensions: '0.8 × 0.5 × 0.85m' },
  { id: 'bathtub', name: '浴缸', category: 'bathroom', thumbnail: '🛁', dimensions: '1.7 × 0.8 × 0.6m' },
  { id: 'fridge', name: '冰箱', category: 'kitchen', thumbnail: '🧊', dimensions: '0.7 × 0.7 × 1.9m' },
  { id: 'stove', name: '炉灶', category: 'kitchen', thumbnail: '🍳', dimensions: '0.8 × 0.6 × 0.85m' },
  { id: 'pendant-light', name: '吊灯', category: 'lighting', thumbnail: '💡', dimensions: 'Ø 0.5 × 1.2m' },
  { id: 'floor-lamp', name: '落地灯', category: 'lighting', thumbnail: '🪔', dimensions: '0.4 × 0.4 × 1.6m' },
  { id: 'table-lamp', name: '台灯', category: 'lighting', thumbnail: '🔦', dimensions: '0.3 × 0.3 × 0.5m' },
  { id: 'spotlight', name: '射灯', category: 'lighting', thumbnail: '💡', dimensions: 'Ø 0.1 × 0.15m' },
];
