import { create } from 'zustand';
import type { ToolRegistryEntry, ToolType } from '../types';
import StrokeTool from '../components/Toolbar/StrokeTool';
import ShapeTool from '../components/Toolbar/ShapeTool';
import StarTool from '../components/Toolbar/StarTool';
import ArrowTool from '../components/Toolbar/ArrowTool';
import TextTool from '../components/Toolbar/TextTool';

interface ToolRegistryState {
  tools: Map<string, ToolRegistryEntry>;
  toolToComponent: Map<ToolType, React.ComponentType>;
  registerTool: (entry: ToolRegistryEntry, component?: React.ComponentType) => void;
  unregisterTool: (id: string) => void;
  getTool: (id: string) => ToolRegistryEntry | undefined;
  getToolsByCategory: (category: ToolRegistryEntry['category']) => ToolRegistryEntry[];
  getAllTools: () => ToolRegistryEntry[];
  getComponentForTool: (toolType: ToolType) => React.ComponentType | undefined;
}

const builtInTools: Array<{ entry: ToolRegistryEntry; toolType?: ToolType; component?: React.ComponentType }> = [
  { entry: { id: 'select', name: '选择', category: 'interaction', icon: '▢' }, toolType: 'select' },
  { entry: { id: 'pen', name: '画笔', category: 'drawing', icon: '✎' }, toolType: 'pen', component: StrokeTool },
  { entry: { id: 'eraser', name: '橡皮擦', category: 'drawing', icon: '⌫' }, toolType: 'eraser' },
  { entry: { id: 'shape', name: '图形', category: 'shape', icon: '○' }, toolType: 'shape', component: ShapeTool },
  { entry: { id: 'star', name: '星形', category: 'shape', icon: '★' }, toolType: 'star', component: StarTool },
  { entry: { id: 'arrow', name: '箭头', category: 'shape', icon: '→' }, toolType: 'arrow', component: ArrowTool },
  { entry: { id: 'rich-text', name: '富文本', category: 'text', icon: '📝' }, toolType: 'rich-text', component: TextTool },
  { entry: { id: 'text', name: '文字', category: 'text', icon: 'T' }, toolType: 'text', component: TextTool },
  { entry: { id: 'comment', name: '评论', category: 'interaction', icon: '💬' }, toolType: 'comment' },
  { entry: { id: 'pan', name: '平移', category: 'utility', icon: '✋' }, toolType: 'pan' },
];

const initialTools = new Map<string, ToolRegistryEntry>();
const initialToolToComponent = new Map<ToolType, React.ComponentType>();

for (const item of builtInTools) {
  initialTools.set(item.entry.id, item.entry);
  if (item.toolType && item.component) {
    initialToolToComponent.set(item.toolType, item.component);
  }
}

export const useToolRegistry = create<ToolRegistryState>((set, get) => ({
  tools: initialTools,
  toolToComponent: initialToolToComponent,

  registerTool: (entry, component) =>
    set((state) => {
      const newTools = new Map(state.tools);
      newTools.set(entry.id, entry);

      const newToolToComponent = new Map(state.toolToComponent);
      if (component) {
        newToolToComponent.set(entry.id as ToolType, component);
      }

      return {
        tools: newTools,
        toolToComponent: newToolToComponent,
      };
    }),

  unregisterTool: (id) =>
    set((state) => {
      const newTools = new Map(state.tools);
      newTools.delete(id);

      const newToolToComponent = new Map(state.toolToComponent);
      newToolToComponent.delete(id as ToolType);

      return {
        tools: newTools,
        toolToComponent: newToolToComponent,
      };
    }),

  getTool: (id) => get().tools.get(id),

  getToolsByCategory: (category) => {
    const result: ToolRegistryEntry[] = [];
    get().tools.forEach((entry) => {
      if (entry.category === category) {
        result.push(entry);
      }
    });
    return result;
  },

  getAllTools: () => {
    const result: ToolRegistryEntry[] = [];
    get().tools.forEach((entry) => {
      result.push(entry);
    });
    return result;
  },

  getComponentForTool: (toolType) => get().toolToComponent.get(toolType),
}));

export default useToolRegistry;
