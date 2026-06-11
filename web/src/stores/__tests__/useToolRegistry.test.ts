import { describe, it, expect, beforeEach } from 'vitest';
import { useToolRegistry } from '../useToolRegistry';
import type { ToolRegistryEntry } from '../../types';

describe('useToolRegistry store', () => {
  beforeEach(() => {
    useToolRegistry.setState(useToolRegistry.getInitialState());
  });

  it('initializes with default tools', () => {
    const state = useToolRegistry.getState();
    const allTools = state.getAllTools();
    expect(allTools.length).toBeGreaterThan(5);
    expect(state.getTool('pen')).toBeTruthy();
    expect(state.getTool('star')).toBeTruthy();
    expect(state.getTool('arrow')).toBeTruthy();
    expect(state.getTool('rich-text')).toBeTruthy();
    expect(state.getTool('text')).toBeTruthy();
  });

  it('can register new tool plugin', () => {
    const state = useToolRegistry.getState();
    const before = state.getAllTools().length;

    const newTool: ToolRegistryEntry = {
      id: 'custom-highlighter',
      name: 'Highlighter',
      category: 'drawing',
      icon: '🖍️',
    };
    state.registerTool(newTool);

    const after = useToolRegistry.getState().getAllTools().length;
    expect(after).toBe(before + 1);
    expect(useToolRegistry.getState().getTool('custom-highlighter')).toBeTruthy();
  });

  it('can group tools by category', () => {
    const state = useToolRegistry.getState();
    const drawing = state.getToolsByCategory('drawing');
    expect(drawing.length).toBeGreaterThan(0);
    drawing.forEach(t => expect(t.category).toBe('drawing'));

    const shape = state.getToolsByCategory('shape');
    expect(shape.length).toBeGreaterThan(0);
    shape.forEach(t => expect(t.category).toBe('shape'));

    const text = state.getToolsByCategory('text');
    expect(text.length).toBeGreaterThan(0);
    text.forEach(t => expect(t.category).toBe('text'));
  });

  it('can unregister a tool', () => {
    const state = useToolRegistry.getState();
    const before = state.getAllTools().length;
    expect(state.getTool('comment')).toBeTruthy();

    state.unregisterTool('comment');

    const after = useToolRegistry.getState().getAllTools().length;
    expect(after).toBe(before - 1);
    expect(useToolRegistry.getState().getTool('comment')).toBeUndefined();
  });

  it('getComponentForTool returns registered component', () => {
    const state = useToolRegistry.getState();
    const penComponent = state.getComponentForTool('pen');
    expect(penComponent).toBeDefined();

    const starComponent = state.getComponentForTool('star');
    expect(starComponent).toBeDefined();
  });

  it('registerTool with component updates toolToComponent map', () => {
    const state = useToolRegistry.getState();
    const MockComponent = () => null;

    const newTool: ToolRegistryEntry = {
      id: 'custom-tool',
      name: 'Custom',
      category: 'drawing',
      icon: '✨',
    };
    state.registerTool(newTool, MockComponent);

    const registeredComponent = useToolRegistry.getState().getComponentForTool('custom-tool' as any);
    expect(registeredComponent).toBe(MockComponent);
  });
});
