import type { Document } from '@/shared/types';

export const testDocuments: Document[] = [
  {
    id: 'doc-001',
    title: 'TypeScript 入门教程',
    content: `
TypeScript 是 JavaScript 的超集，添加了类型系统。

## 基础类型

TypeScript 支持多种基础类型：
- string
- number
- boolean
- array

TypeScript 可以编译为纯 JavaScript 代码。
    `,
    tags: ['TypeScript', '前端', '教程'],
    filename: 'typescript-tutorial.md',
    filePath: '/docs/typescript-tutorial.md',
    wordCount: 50,
    hash: 'hash-001',
    createdAt: new Date('2024-01-01'),
    updatedAt: new Date('2024-01-15'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-002',
    title: 'JavaScript 高级编程',
    content: `
JavaScript 是一门动态编程语言。

## 闭包

闭包是 JavaScript 中的重要概念。
JavaScript 也可以用于后端开发（Node.js）。
    `,
    tags: ['JavaScript', '前端', '高级'],
    filename: 'javascript-advanced.md',
    filePath: '/docs/javascript-advanced.md',
    wordCount: 40,
    hash: 'hash-002',
    createdAt: new Date('2024-01-05'),
    updatedAt: new Date('2024-01-20'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-003',
    title: 'Electron 桌面应用开发',
    content: `
Electron 是一个桌面应用开发框架。

## 核心概念

- 主进程
- 渲染进程
- IPC 通信

使用 Electron 可以开发跨平台的桌面应用。
    `,
    tags: ['Electron', '桌面应用', '框架'],
    filename: 'electron-development.md',
    filePath: '/docs/electron-development.md',
    wordCount: 45,
    hash: 'hash-003',
    createdAt: new Date('2024-01-10'),
    updatedAt: new Date('2024-02-01'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-004',
    title: 'React 组件设计模式',
    content: `
React 是一个用于构建用户界面的 JavaScript 库。

## 设计模式

- 高阶组件
- Render Props
- 自定义 Hooks

React 由 Facebook 开发和维护。
    `,
    tags: ['React', 'JavaScript', '前端'],
    filename: 'react-patterns.md',
    filePath: '/docs/react-patterns.md',
    wordCount: 55,
    hash: 'hash-004',
    createdAt: new Date('2024-01-15'),
    updatedAt: new Date('2024-02-10'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-005',
    title: '数据库设计基础',
    content: `
数据库是现代应用的核心组件。

## SQL 基础

- SELECT 查询
- INSERT 插入
- UPDATE 更新
- DELETE 删除

MySQL 和 PostgreSQL 是流行的关系型数据库。
    `,
    tags: ['数据库', 'SQL', '后端'],
    filename: 'database-design.md',
    filePath: '/docs/database-design.md',
    wordCount: 60,
    hash: 'hash-005',
    createdAt: new Date('2024-01-20'),
    updatedAt: new Date('2024-02-15'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-006',
    title: 'Git 版本控制指南',
    content: `
Git 是一个分布式版本控制系统。

## 常用命令

- git init
- git add
- git commit
- git push

Git 由 Linus Torvalds 创建。
    `,
    tags: ['Git', '版本控制', '工具'],
    filename: 'git-guide.md',
    filePath: '/docs/git-guide.md',
    wordCount: 35,
    hash: 'hash-006',
    createdAt: new Date('2024-01-25'),
    updatedAt: new Date('2024-02-20'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-007',
    title: 'TypeScript 与 React 结合',
    content: `
TypeScript 可以与 React 完美结合。

## 类型定义

在 React 组件中使用 TypeScript 类型：
- Props 类型
- State 类型
- Hooks 类型

TypeScript 提升了 React 开发的安全性。
    `,
    tags: ['TypeScript', 'React', '前端'],
    filename: 'typescript-react.md',
    filePath: '/docs/typescript-react.md',
    wordCount: 50,
    hash: 'hash-007',
    createdAt: new Date('2024-02-01'),
    updatedAt: new Date('2024-02-25'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-008',
    title: '前端性能优化',
    content: `
性能优化是前端开发的重要课题。

## 优化策略

- 代码分割
- 懒加载
- 图片优化
- 缓存策略

提升用户体验的关键在于性能。
    `,
    tags: ['性能优化', '前端', 'JavaScript'],
    filename: 'frontend-performance.md',
    filePath: '/docs/frontend-performance.md',
    wordCount: 42,
    hash: 'hash-008',
    createdAt: new Date('2024-02-05'),
    updatedAt: new Date('2024-03-01'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-009',
    title: 'Node.js 服务端开发',
    content: `
Node.js 是基于 Chrome V8 引擎的 JavaScript 运行时。

## 核心模块

- http
- fs
- path
- events

Node.js 适合构建高性能的网络应用。
    `,
    tags: ['Node.js', 'JavaScript', '后端'],
    filename: 'nodejs-server.md',
    filePath: '/docs/nodejs-server.md',
    wordCount: 48,
    hash: 'hash-009',
    createdAt: new Date('2024-02-10'),
    updatedAt: new Date('2024-03-05'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-010',
    title: '桌面应用架构设计',
    content: `
现代桌面应用需要良好的架构设计。

## 设计原则

- 模块化
- 松耦合
- 可测试性

良好的架构是维护性的基础。
    `,
    tags: ['架构', '桌面应用', '设计模式'],
    filename: 'desktop-architecture.md',
    filePath: '/docs/desktop-architecture.md',
    wordCount: 38,
    hash: 'hash-010',
    createdAt: new Date('2024-02-15'),
    updatedAt: new Date('2024-03-10'),
    backlinks: [],
  },
];
