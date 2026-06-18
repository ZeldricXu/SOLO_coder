import type { Note, NoteLink, GraphData, GraphNode, GraphEdge } from '@shared/types';

export const createMockNote = (overrides: Partial<Note> = {}): Note => ({
  id: `note-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
  title: 'Test Note',
  path: 'test/test-note.md',
  content: '# Test Note\n\nThis is a test note content.',
  frontmatter: {},
  tags: [],
  createdAt: Date.now(),
  updatedAt: Date.now(),
  ...overrides,
});

export const createMockLink = (overrides: Partial<NoteLink> = {}): NoteLink => ({
  id: `link-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
  sourceId: 'source-note',
  targetId: 'target-note',
  sourcePath: 'source.md',
  targetPath: 'target.md',
  linkText: 'Target Note',
  context: 'Some context with [[Target Note]] link.',
  createdAt: Date.now(),
  ...overrides,
});

export const createMockGraphData = (nodeCount = 3, edgeCount = 2): GraphData => {
  const nodes: GraphNode[] = [];
  const edges: GraphEdge[] = [];
  const clusters = ['ideas', 'work', 'study', 'personal'];
  
  for (let i = 0; i < nodeCount; i++) {
    nodes.push({
      id: `node-${i}`,
      label: `Node ${i}`,
      path: `node-${i}.md`,
      tags: [clusters[i % clusters.length]],
      size: 10 + Math.random() * 20,
      cluster: clusters[i % clusters.length],
    });
  }
  
  let edgeIdx = 0;
  
  for (let i = 0; i < nodeCount - 1 && edgeIdx < edgeCount; i++) {
    edges.push({
      id: `edge-${edgeIdx}`,
      source: nodes[i].id,
      target: nodes[i + 1].id,
      weight: 1 + Math.random(),
    });
    edgeIdx++;
  }
  
  let skip = 2;
  while (edgeIdx < edgeCount && skip < nodeCount) {
    for (let i = 0; i < nodeCount - skip && edgeIdx < edgeCount; i++) {
      edges.push({
        id: `edge-${edgeIdx}`,
        source: nodes[i].id,
        target: nodes[i + skip].id,
        weight: 0.5 + Math.random(),
      });
      edgeIdx++;
    }
    skip++;
  }
  
  return { nodes, edges };
};

export const generateLargeNote = (wordCount = 100000): string => {
  const words = [
    '知识', '图谱', '笔记', '链接', '数据', '网络', '学习', '算法', '模型', '分析',
    '系统', '设计', '开发', '测试', '部署', '优化', '性能', '安全', '用户', '体验',
  ];
  
  let content = '# Large Note Test\n\n';
  let wordCounter = 0;
  
  while (wordCounter < wordCount) {
    const lineWords = [];
    const lineLength = 8 + Math.floor(Math.random() * 15);
    
    for (let i = 0; i < lineLength && wordCounter < wordCount; i++) {
      lineWords.push(words[Math.floor(Math.random() * words.length)]);
      wordCounter++;
    }
    
    content += lineWords.join(' ') + '。\n\n';
    
    if (wordCounter % 500 === 0) {
      content += `## Section ${Math.floor(wordCounter / 500)}\n\n`;
    }
    
    if (wordCounter % 1000 === 0) {
      content += '```javascript\nconst code = "example";\n```\n\n';
    }
  }
  
  return content;
};

export const generateBatchNotes = (count = 500): Note[] => {
  const notes: Note[] = [];
  const categories = ['ideas', 'work', 'study', 'personal', 'projects'];
  
  for (let i = 0; i < count; i++) {
    const category = categories[i % categories.length];
    notes.push({
      id: `note-${i}`,
      title: `Note ${i}: ${categories[i % categories.length]} topic`,
      path: `${category}/note-${i}.md`,
      content: `# Note ${i}\n\nThis is the content of note ${i}. It discusses ${categories[i % categories.length]} related topics.`,
      frontmatter: { category, index: i },
      tags: [category, `tag-${i % 10}`],
      createdAt: Date.now() - i * 1000,
      updatedAt: Date.now() - i * 500,
    });
  }
  
  return notes;
};

export const generateLargeGraph = (nodeCount = 2000): GraphData => {
  const nodes: GraphNode[] = [];
  const edges: GraphEdge[] = [];
  const clusters = ['cluster-a', 'cluster-b', 'cluster-c', 'cluster-d', 'cluster-e'];
  
  for (let i = 0; i < nodeCount; i++) {
    nodes.push({
      id: `large-node-${i}`,
      label: `Large Node ${i}`,
      path: `large/node-${i}.md`,
      tags: [clusters[i % clusters.length]],
      size: 8 + Math.random() * 20,
      cluster: clusters[i % clusters.length],
    });
  }
  
  for (let i = 0; i < nodeCount; i++) {
    const connections = 2 + Math.floor(Math.random() * 4);
    for (let j = 0; j < connections; j++) {
      const targetIdx = (i + 1 + Math.floor(Math.random() * Math.min(10, nodeCount - i - 1))) % nodeCount;
      if (targetIdx !== i) {
        edges.push({
          id: `large-edge-${i}-${j}`,
          source: nodes[i].id,
          target: nodes[targetIdx].id,
          weight: 0.5 + Math.random() * 2,
        });
      }
    }
  }
  
  return { nodes, edges };
};

export const testFrontmatterCases = [
  {
    name: '空 frontmatter',
    input: '---\n---\n# Content',
    expectedFrontmatter: {},
    expectedContent: '# Content',
  },
  {
    name: '单行 frontmatter',
    input: '---\ntitle: Test\n---\n# Content',
    expectedFrontmatter: { title: 'Test' },
    expectedContent: '# Content',
  },
  {
    name: '多行 value',
    input: '---\ntitle: |\n  Line 1\n  Line 2\ntags:\n  - tag1\n  - tag2\n---\n# Content',
    expectedFrontmatter: {
      title: 'Line 1\nLine 2\n',
      tags: ['tag1', 'tag2'],
    },
    expectedContent: '# Content',
  },
  {
    name: '嵌套数组',
    input: '---\ntitle: Nested Test\nitems:\n  - name: item1\n    value: 1\n  - name: item2\n    value: 2\n---\n# Content',
    expectedFrontmatter: {
      title: 'Nested Test',
      items: [
        { name: 'item1', value: 1 },
        { name: 'item2', value: 2 },
      ],
    },
    expectedContent: '# Content',
  },
  {
    name: '各种数据类型',
    input: '---\nstring: "value"\nnumber: 42\nboolean: true\nnullValue: null\narray: [1, 2, 3]\nobject: { key: "value" }\n---\n# Content',
    expectedFrontmatter: {
      string: 'value',
      number: 42,
      boolean: true,
      nullValue: null,
      array: [1, 2, 3],
      object: { key: 'value' },
    },
    expectedContent: '# Content',
  },
  {
    name: '无 frontmatter',
    input: '# Content\n\nNormal content.',
    expectedFrontmatter: {},
    expectedContent: '# Content\n\nNormal content.',
  },
];

export const testMarkdownCases = [
  {
    name: '代码块',
    input: '```javascript\nconst x = 1;\n```',
    expected: { type: 'code-block', language: 'javascript' },
  },
  {
    name: '内嵌图片',
    input: '![Alt text](/path/to/image.png)',
    expected: { type: 'image', alt: 'Alt text', src: '/path/to/image.png' },
  },
  {
    name: '双链语法',
    input: '[[目标笔记]] 和 [[目标笔记|显示文本]]',
    expected: {
      links: [
        { target: '目标笔记', displayText: '目标笔记' },
        { target: '目标笔记', displayText: '显示文本' },
      ],
    },
  },
  {
    name: '表格',
    input: '| Header 1 | Header 2 |\n|----------|----------|\n| Cell 1   | Cell 2   |',
    expected: { type: 'table' },
  },
  {
    name: '任务列表',
    input: '- [ ] Task 1\n- [x] Task 2\n- [ ] Task 3',
    expected: { type: 'task-list', completed: 1, total: 3 },
  },
];
