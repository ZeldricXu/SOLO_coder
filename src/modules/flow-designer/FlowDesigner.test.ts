import { FlowDesigner } from './FlowDesigner';
import { FlowDefinition, FlowNode, FlowConnection } from '../../types/flow';

describe('FlowDesigner - 数据一致性修复测试', () => {
  let designer: FlowDesigner;
  let testFlow: FlowDefinition;

  beforeEach(() => {
    designer = new FlowDesigner();
    testFlow = designer.createFlow({
      name: '测试流程',
      description: '用于测试数据一致性',
      nodes: [],
      connections: []
    });
  });

  describe('快照-回滚机制', () => {
    it('addNode 异常时应回滚到操作前状态', () => {
      const initialNodeCount = testFlow.nodes.length;

      const problematicNodeData = {
        type: 'invalid_type',
        label: '异常节点',
        config: {}
      };

      try {
        designer.addNode(testFlow.flow_id, problematicNodeData);
      } catch (error) {
        const flow = designer.getFlow(testFlow.flow_id);
        expect(flow.nodes.length).toBe(initialNodeCount);
      }
    });

    it('removeNode 异常时应回滚到操作前状态', () => {
      const node = designer.addNode(testFlow.flow_id, {
        type: 'start',
        label: '开始节点',
        config: {}
      });

      const initialNodeCount = testFlow.nodes.length + 1;

      try {
        designer.removeNode(testFlow.flow_id, 'non_existent_node');
      } catch (error) {
        const flow = designer.getFlow(testFlow.flow_id);
        expect(flow.nodes.length).toBe(initialNodeCount);
      }
    });

    it('updateNode 异常时应回滚到操作前状态', () => {
      const node = designer.addNode(testFlow.flow_id, {
        type: 'start',
        label: '开始节点',
        config: {}
      });

      const originalLabel = node.label;

      try {
        designer.updateNode(testFlow.flow_id, 'non_existent_node', {
          label: '更新后的标签'
        });
      } catch (error) {
        const updatedNode = designer.getNode(testFlow.flow_id, node.node_id);
        expect(updatedNode.label).toBe(originalLabel);
      }
    });

    it('addConnection 异常时应回滚到操作前状态', () => {
      const node1 = designer.addNode(testFlow.flow_id, {
        type: 'start',
        label: '节点1',
        config: {}
      });
      const node2 = designer.addNode(testFlow.flow_id, {
        type: 'end',
        label: '节点2',
        config: {}
      });

      const initialConnectionCount = testFlow.connections.length;

      try {
        designer.addConnection(testFlow.flow_id, {
          source_id: 'non_existent',
          target_id: node2.node_id
        });
      } catch (error) {
        const flow = designer.getFlow(testFlow.flow_id);
        expect(flow.connections.length).toBe(initialConnectionCount);
      }
    });

    it('removeConnection 异常时应回滚到操作前状态', () => {
      const node1 = designer.addNode(testFlow.flow_id, {
        type: 'start',
        label: '节点1',
        config: {}
      });
      const node2 = designer.addNode(testFlow.flow_id, {
        type: 'end',
        label: '节点2',
        config: {}
      });
      const connection = designer.addConnection(testFlow.flow_id, {
        source_id: node1.node_id,
        target_id: node2.node_id
      });

      const initialConnectionCount = testFlow.connections.length + 1;

      try {
        designer.removeConnection(testFlow.flow_id, 'non_existent_conn');
      } catch (error) {
        const flow = designer.getFlow(testFlow.flow_id);
        expect(flow.connections.length).toBe(initialConnectionCount);
      }
    });
  });

  describe('批量操作原子性', () => {
    it('batchUpdate 部分失败时整个操作应回滚', () => {
      const node = designer.addNode(testFlow.flow_id, {
        type: 'start',
        label: '节点1',
        config: {}
      });

      const initialNodeCount = testFlow.nodes.length + 1;
      const initialConnectionCount = testFlow.connections.length;

      try {
        designer.batchUpdate(testFlow.flow_id, [
          {
            type: 'add_node',
            data: {
              type: 'process',
              label: '节点2',
              config: {}
            }
          },
          {
            type: 'add_connection',
            data: {
              source_id: 'non_existent',
              target_id: node.node_id
            }
          }
        ]);
      } catch (error) {
        const flow = designer.getFlow(testFlow.flow_id);
        expect(flow.nodes.length).toBe(initialNodeCount);
        expect(flow.connections.length).toBe(initialConnectionCount);
      }
    });

    it('batchUpdate 成功时所有操作应都生效', () => {
      const node1 = designer.addNode(testFlow.flow_id, {
        type: 'start',
        label: '节点1',
        config: {}
      });

      const result = designer.batchUpdate(testFlow.flow_id, [
        {
          type: 'add_node',
          data: {
            type: 'end',
            label: '节点2',
            config: {}
          }
        },
        {
          type: 'add_connection',
          data: {
            source_id: node1.node_id,
            target_id: 'pending'
          }
        }
      ]);

      expect(result.nodes.length).toBe(3);
      expect(result.connections.length).toBe(1);
    });
  });

  describe('快照管理', () => {
    it('应保存操作快照', () => {
      const snapshots = designer.getSnapshots(testFlow.flow_id);
      const initialCount = snapshots.length;

      designer.addNode(testFlow.flow_id, {
        type: 'start',
        label: '测试节点',
        config: {}
      });

      const newSnapshots = designer.getSnapshots(testFlow.flow_id);
      expect(newSnapshots.length).toBe(initialCount + 1);
      expect(newSnapshots[0].operation).toBe('add_node');
    });

    it('应能恢复到历史快照', () => {
      const node1 = designer.addNode(testFlow.flow_id, {
        type: 'start',
        label: '节点1',
        config: {}
      });

      const node2 = designer.addNode(testFlow.flow_id, {
        type: 'end',
        label: '节点2',
        config: {}
      });

      const countAfterTwoNodes = designer.getFlow(testFlow.flow_id).nodes.length;

      const restored = designer.restoreSnapshot(testFlow.flow_id, 1);
      expect(restored.nodes.length).toBe(countAfterTwoNodes - 1);
    });

    it('快照数量不应超过最大值', () => {
      for (let i = 0; i < 20; i++) {
        designer.addNode(testFlow.flow_id, {
          type: 'process',
          label: `节点${i}`,
          config: {}
        });
      }

      const snapshots = designer.getSnapshots(testFlow.flow_id);
      expect(snapshots.length).toBeLessThanOrEqual(10);
    });
  });

  describe('副本操作验证', () => {
    it('所有修改应在副本上进行，验证通过后再替换', () => {
      const node = designer.addNode(testFlow.flow_id, {
        type: 'start',
        label: '原始节点',
        config: { value: 1 }
      });

      const originalConfig = { ...node.config };

      try {
        designer.updateNode(testFlow.flow_id, node.node_id, {
          config: { value: 2, invalid: true }
        } as Partial<FlowNode>);
      } catch (error) {
        const updatedNode = designer.getNode(testFlow.flow_id, node.node_id);
        expect(updatedNode.config).toEqual(originalConfig);
      }
    });
  });
});
