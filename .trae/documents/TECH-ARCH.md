## 1. 架构设计

本项目采用前端单页应用架构，基于React + TypeScript + Vite构建，集成Three.js进行3D渲染，Canvas API进行2D绘制，IndexedDB进行本地数据持久化，WebSocket实现多人协作。整体采用分层架构，确保各模块职责清晰、易于扩展。

```mermaid
graph TD
    subgraph "表现层 UI"
        A1["React 组件层"]
        A2["Tailwind CSS 样式系统"]
        A3["状态管理 (Zustand)"]
    end
    
    subgraph "业务逻辑层 BLL"
        B1["2D绘制引擎模块"]
        B2["3D场景管理模块"]
        B3["材质家具库模块"]
        B4["灯光模拟模块"]
        B5["渲染器模块"]
        B6["导入导出模块"]
        B7["协作同步模块"]
        B8["测量报价模块"]
    end
    
    subgraph "核心引擎层 Core"
        C1["Three.js 3D引擎"]
        C2["Canvas 2D引擎"]
        C3["几何运算库 (three-bvh)"]
        C4["CSG布尔运算 (three-csg)"]
    end
    
    subgraph "数据层 Data"
        D1["IndexedDB 本地存储"]
        D2["户型数据模型"]
        D3["材质/模型资源"]
    end
    
    subgraph "外部服务"
        E1["WebSocket 协作服务"]
        E2["glTF模型资源库"]
    end
    
    A1 --> A3
    A3 --> B1
    A3 --> B2
    A3 --> B3
    A3 --> B4
    A3 --> B5
    A3 --> B6
    A3 --> B7
    A3 --> B8
    
    B1 --> C2
    B2 --> C1
    B2 --> C3
    B2 --> C4
    B3 --> C1
    B4 --> C1
    B5 --> C1
    B6 --> D2
    B7 --> E1
    B8 --> C3
    
    C1 --> D3
    B1 --> D2
    B6 --> D1
    B7 --> D1
```

## 2. 技术描述

- **前端框架**：React 18 + TypeScript 5.4 + Vite 5.2
- **样式方案**：Tailwind CSS 3.4，CSS变量主题系统
- **状态管理**：Zustand 4.5，分模块管理户型、UI、设置状态
- **3D渲染**：Three.js r162，@react-three/fiber 8.15，@react-three/drei 9.99
- **后处理**：@react-three/postprocessing 2.16，实现SSAO、Bloom、SSR效果
- **几何运算**：three-bvh 0.7.0（碰撞检测、光线投射加速），three-csg-ts 3.2.0（墙体开洞布尔运算）
- **模型加载**：@react-three/gltfjsx 转化glTF模型为React组件
- **DXF解析**：dxf-parser 1.1.2
- **本地存储**：idb 8.0.0（IndexedDB封装）
- **图标库**：lucide-react 0.378.0
- **后端服务**：无需后端核心逻辑，仅需可选的WebSocket协作服务器（Node.js + ws 8.16）

## 3. 路由定义

| 路由 | 用途 |
|------|------|
| `/` | 编辑器主页面，包含2D/3D画布、工具栏、属性面板 |
| `/projects` | 项目列表页，展示本地存储的所有户型项目 |
| `/render` | 独立渲染页面，用于高质量路径追踪渲染输出 |

## 4. 数据模型

### 4.1 数据模型定义

```mermaid
erDiagram
    PROJECT ||--o{ WALL : contains
    PROJECT ||--o{ ROOM : contains
    PROJECT ||--o{ DOOR : contains
    PROJECT ||--o{ WINDOW : contains
    PROJECT ||--o{ FURNITURE : contains
    PROJECT ||--o{ LIGHT : contains
    PROJECT ||--o{ ANNOTATION : contains
    PROJECT ||--o{ MATERIAL : references
    
    WALL ||--|| WALL_GEOMETRY : has
    ROOM ||--o{ WALL : bounded_by
    FURNITURE ||--|| MATERIAL : uses
    LIGHT ||--|| LIGHT_PARAMS : has
    
    PROJECT {
        uuid id PK
        string name
        number version
        datetime createdAt
        datetime updatedAt
        json settings
    }
    
    WALL {
        uuid id PK
        uuid projectId FK
        string type "straight/arc"
        json points "[{x,y},...]"
        number thickness
        number height
        string materialId
    }
    
    ROOM {
        uuid id PK
        uuid projectId FK
        string name
        json boundary "闭合点数组"
        number area
        string floorMaterialId
        string ceilingMaterialId
    }
    
    DOOR {
        uuid id PK
        uuid wallId FK
        number positionX
        number width
        number height
        number swingAngle
    }
    
    WINDOW {
        uuid id PK
        uuid wallId FK
        number positionX
        number width
        number height
        number sillHeight
    }
    
    FURNITURE {
        uuid id PK
        uuid projectId FK
        string modelId
        json position "{x,y,z}"
        number rotation
        number scale
        json materials
    }
    
    LIGHT {
        uuid id PK
        uuid projectId FK
        string type "point/area/spot/ambient"
        json position "{x,y,z}"
        json color "{r,g,b}"
        number intensity
        number castShadow
    }
    
    MATERIAL {
        uuid id PK
        string name
        string type "pbr/standard"
        json properties
    }
    
    ANNOTATION {
        uuid id PK
        uuid projectId FK
        json position "{x,y,z}"
        string author
        string text
        string screenshot
        datetime createdAt
    }
```

### 4.2 核心类型定义

```typescript
// 户型数据标准格式
interface FloorPlan {
  version: '1.0.0';
  project: Project;
  walls: Wall[];
  rooms: Room[];
  openings: Opening[];
  furniture: FurnitureItem[];
  lights: LightSource[];
  materials: Material[];
  annotations: Annotation[];
}

interface Wall {
  id: string;
  type: 'straight' | 'arc';
  start: Point2D;
  end: Point2D;
  center?: Point2D;
  radius?: number;
  thickness: number;
  height: number;
  materialId: string;
}

interface Room {
  id: string;
  name: string;
  boundary: Point2D[];
  area: number;
  floorMaterialId: string;
  ceilingMaterialId: string;
}

interface FurnitureItem {
  id: string;
  modelId: string;
  position: Point3D;
  rotation: number;
  scale: number;
  materialOverrides: Record<string, string>;
}

interface LightSource {
  id: string;
  type: 'point' | 'area' | 'spot' | 'ambient';
  position: Point3D;
  target?: Point3D;
  color: RGB;
  intensity: number;
  castShadow: boolean;
  params: Record<string, number>;
}
```

## 5. 目录结构

```
DF1-72/
├── src/
│   ├── components/          # React组件
│   │   ├── editor/          # 编辑器核心组件
│   │   │   ├── Canvas2D.tsx
│   │   │   ├── Scene3D.tsx
│   │   │   ├── Toolbar.tsx
│   │   │   ├── ToolPanel.tsx
│   │   │   ├── PropertyPanel.tsx
│   │   │   └── StatusBar.tsx
│   │   ├── furniture/       # 家具库组件
│   │   │   ├── FurnitureLibrary.tsx
│   │   │   └── ModelCard.tsx
│   │   ├── lighting/        # 灯光组件
│   │   │   └── LightPanel.tsx
│   │   ├── render/          # 渲染组件
│   │   │   └── RenderDialog.tsx
│   │   └── ui/              # 通用UI组件
│   │       ├── Button.tsx
│   │       ├── Slider.tsx
│   │       └── Input.tsx
│   ├── store/               # Zustand状态管理
│   │   ├── useFloorPlanStore.ts
│   │   ├── useUIStore.ts
│   │   └── useSettingsStore.ts
│   ├── hooks/               # 自定义Hooks
│   │   ├── useWallDrawing.ts
│   │   ├── useFurnitureDrag.ts
│   │   ├── useCameraControls.ts
│   │   └── useCollaboration.ts
│   ├── engine/              # 核心引擎模块
│   │   ├── drawing/         # 2D绘制引擎
│   │   │   ├── WallDrawer.ts
│   │   │   ├── DimensionMarker.ts
│   │   │   ├── GridSystem.ts
│   │   │   └── SnapManager.ts
│   │   ├── scene/           # 3D场景管理
│   │   │   ├── SceneManager.ts
│   │   │   ├── WallBuilder.ts
│   │   │   ├── OpeningCutter.ts
│   │   │   └── RoomGenerator.ts
│   │   ├── materials/       # 材质系统
│   │   │   ├── PBRMaterialFactory.ts
│   │   │   └── MaterialLibrary.ts
│   │   ├── lighting/        # 灯光系统
│   │   │   ├── LightManager.ts
│   │   │   └── ShadowManager.ts
│   │   ├── rendering/       # 渲染器
│   │   │   ├── PathTracingRenderer.ts
│   │   │   └── ScreenshotExporter.ts
│   │   └── physics/         # 物理/碰撞
│   │       ├── CollisionDetector.ts
│   │       └── MeasurementTool.ts
│   ├── io/                  # 导入导出
│   │   ├── DXFParser.ts
│   │   ├── FloorPlanExporter.ts
│   │   └── LocalStorage.ts
│   ├── types/               # TypeScript类型定义
│   │   ├── floorplan.ts
│   │   ├── geometry.ts
│   │   └── materials.ts
│   ├── utils/               # 工具函数
│   │   ├── geometry.ts
│   │   ├── math.ts
│   │   └── csg.ts
│   ├── pages/               # 页面组件
│   │   ├── EditorPage.tsx
│   │   ├── ProjectsPage.tsx
│   │   └── RenderPage.tsx
│   ├── App.tsx
│   ├── main.tsx
│   └── index.css
├── public/
│   ├── models/              # glTF模型资源
│   ├── textures/            # 材质纹理
│   └── hdri/                # HDR环境贴图
├── api/                     # 后端服务（可选）
│   └── collaboration/       # WebSocket协作服务
├── shared/                  # 前后端共享类型
│   └── types.ts
├── package.json
├── tsconfig.json
├── vite.config.ts
├── tailwind.config.js
└── postcss.config.js
```
