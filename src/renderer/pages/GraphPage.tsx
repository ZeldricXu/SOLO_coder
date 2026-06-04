import React, { useEffect, useRef, useState, useMemo, useCallback } from 'react';
import * as d3 from 'd3';
import {
  ZoomIn,
  ZoomOut,
  RotateCcw,
  Download,
  Search,
  X,
  FileText,
  Tag,
  Network,
  ArrowRightLeft,
  Hash,
  Maximize2,
} from 'lucide-react';
import { useAppStore } from '../stores/appStore';
import {
  buildGraphFromDocuments,
  filterGraphByTags,
  searchGraphNodes,
  getNodeDegree,
  type GraphData,
  type GraphNode,
} from '@core/graph/parser';
import { GraphRenderer } from '@core/graph/GraphRenderer';
import {
  createForceSimulation,
  drag,
  dragWithDrop,
  zoom,
  getNodeColor,
  getNodeRadius,
  getLinkColor,
  getLinkWidth,
  type SimulationNode,
  type SimulationLink,
  type LinkCreationState,
  type SimulationHandle,
} from '@core/graph/forceLayout';
import { downloadPNG } from '@core/graph/export';
import type { Document } from '@shared/types';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';

interface HoveredNode {
  node: SimulationNode;
  x: number;
  y: number;
}

interface DropDialogState {
  visible: boolean;
  draggedNode: SimulationNode | null;
  targetNode: SimulationNode | null;
  x: number;
  y: number;
}

interface LinkCreationDialogState {
  visible: boolean;
  sourceNode: SimulationNode | null;
  targetNode: SimulationNode | null;
  x: number;
  y: number;
}

export const GraphPage: React.FC = () => {
  const svgRef = useRef<SVGSVGElement>(null);
  const gRef = useRef<SVGGElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const simulationHandleRef = useRef<SimulationHandle | null>(null);
  const graphRendererRef = useRef<GraphRenderer | null>(null);
  const zoomRef = useRef<ReturnType<typeof zoom> | null>(null);

  const documents = useAppStore((state) => state.documents);
  const tags = useAppStore((state) => state.tags);
  const selectedTags = useAppStore((state) => state.selectedTags);
  const searchQuery = useAppStore((state) => state.searchQuery);
  const settings = useAppStore((state) => state.settings);
  const setCurrentDocument = useAppStore((state) => state.setCurrentDocument);
  const setActiveTab = useAppStore((state) => state.setActiveTab);
  const toggleTagFilter = useAppStore((state) => state.toggleTagFilter);
  const setSearchQuery = useAppStore((state) => state.setSearchQuery);
  const createDocument = useAppStore((state) => state.createDocument);

  const [nodes, setNodes] = useState<SimulationNode[]>([]);
  const [links, setLinks] = useState<SimulationLink[]>([]);
  const [selectedNode, setSelectedNode] = useState<SimulationNode | null>(null);
  const [hoveredNode, setHoveredNode] = useState<HoveredNode | null>(null);
  const [dimensions, setDimensions] = useState({ width: 800, height: 600 });
  const [isExporting, setIsExporting] = useState(false);
  const [dropDialog, setDropDialog] = useState<DropDialogState>({
    visible: false,
    draggedNode: null,
    targetNode: null,
    x: 0,
    y: 0,
  });
  const [linkCreationState, setLinkCreationState] = useState<LinkCreationState>({
    active: false,
    startNode: null,
    mouseX: 0,
    mouseY: 0,
  });
  const [linkCreationDialog, setLinkCreationDialog] = useState<LinkCreationDialogState>({
    visible: false,
    sourceNode: null,
    targetNode: null,
    x: 0,
    y: 0,
  });
  const isDraggingRef = useRef(false);
  const dragDropHandlerRef = useRef<ReturnType<typeof dragWithDrop> | null>(null);
  const potentialTargetRef = useRef<SimulationNode | null>(null);
  const linkCreationStateRef = useRef(linkCreationState);
  const hoveredNodeRef = useRef<HoveredNode | null>(null);

  const fullGraph = useMemo<GraphData>(() => {
    const docsWithContent: Array<Document & { content?: string }> = documents.map((doc) => ({
      ...doc,
      content: doc.content ?? '',
    }));
    return buildGraphFromDocuments(docsWithContent);
  }, [documents]);

  const filteredGraph = useMemo(() => {
    return filterGraphByTags(fullGraph, selectedTags);
  }, [fullGraph, selectedTags]);

  const highlightedNodeIds = useMemo(() => {
    return searchGraphNodes(filteredGraph, searchQuery);
  }, [filteredGraph, searchQuery]);

  const graphStats = useMemo(() => {
    const docNodes = filteredGraph.nodes.filter((n) => n.type === 'document');
    const tagNodes = filteredGraph.nodes.filter((n) => n.type === 'tag');
    const docLinks = filteredGraph.links.filter((l) => l.type === 'link');
    const tagLinks = filteredGraph.links.filter((l) => l.type === 'tag');

    let maxDegree = 0;
    let avgDegree = 0;
    for (const node of filteredGraph.nodes) {
      const degree = getNodeDegree(filteredGraph, node.id);
      maxDegree = Math.max(maxDegree, degree.total);
      avgDegree += degree.total;
    }
    avgDegree = filteredGraph.nodes.length > 0 ? avgDegree / filteredGraph.nodes.length : 0;

    return {
      totalNodes: filteredGraph.nodes.length,
      totalLinks: filteredGraph.links.length,
      docNodes: docNodes.length,
      tagNodes: tagNodes.length,
      docLinks: docLinks.length,
      tagLinks: tagLinks.length,
      maxDegree,
      avgDegree: avgDegree.toFixed(2),
    };
  }, [filteredGraph]);

  const selectedNodeDetails = useMemo(() => {
    if (!selectedNode) return null;
    const degree = getNodeDegree(filteredGraph, selectedNode.id);
    const doc = documents.find((d) => d.id === selectedNode.id);
    return {
      ...selectedNode,
      degree,
      doc,
    };
  }, [selectedNode, filteredGraph, documents]);

  useEffect(() => {
    const updateDimensions = () => {
      if (containerRef.current) {
        const rect = containerRef.current.getBoundingClientRect();
        setDimensions({
          width: rect.width,
          height: rect.height,
        });
      }
    };

    updateDimensions();
    window.addEventListener('resize', updateDimensions);
    return () => window.removeEventListener('resize', updateDimensions);
  }, []);

  useEffect(() => {
    linkCreationStateRef.current = linkCreationState;
  }, [linkCreationState]);

  useEffect(() => {
    hoveredNodeRef.current = hoveredNode;
  }, [hoveredNode]);

  useEffect(() => {
    if (!svgRef.current || !gRef.current || dimensions.width === 0) return;

    const svg = d3.select(svgRef.current);
    const d3G = d3.select(gRef.current);

    d3G.remove();
    const newG = svg.append('g').attr('class', 'graph-container');
    (gRef as React.MutableRefObject<SVGGElement | null>).current = newG.node();

    const zoomApi = zoom(svg, newG);
    zoomRef.current = zoomApi;

    const graphRenderer = new GraphRenderer(
      svgRef.current,
      gRef.current,
      {
        nodeRadius: settings.graphNodeSize,
        showLabels: true,
        animationDuration: 300,
      },
      {
        onNodeClick: (event, d) => {
          event.stopPropagation();
          if (linkCreationStateRef.current.active) {
            event.preventDefault();
            return;
          }
          if (d.type === 'document') {
            setSelectedNode(d);
          } else {
            toggleTagFilter(d.label);
          }
        },
        onNodeDblClick: (event, d) => {
          event.stopPropagation();
          if (linkCreationStateRef.current.active) {
            return;
          }
          if (d.type === 'document') {
            setCurrentDocument(d.id);
            setActiveTab('editor');
          }
        },
        onNodeContextMenu: (event, d) => {
          event.preventDefault();
          event.stopPropagation();
          handleNodeContextMenu(event as any, d);
        },
        onNodeMouseEnter: (event, d) => {
          if (isDraggingRef.current) {
            dragDropHandlerRef.current?.setTargetNode(d);
            potentialTargetRef.current = d;
          }
          setHoveredNode({
            node: d,
            x: event.pageX,
            y: event.pageY,
          });
        },
        onNodeMouseLeave: (event, d) => {
          if (isDraggingRef.current && potentialTargetRef.current?.id === d.id) {
            dragDropHandlerRef.current?.setTargetNode(null);
            potentialTargetRef.current = null;
          }
          setHoveredNode(null);
        },
        onSvgClick: () => {
          if (linkCreationStateRef.current.active) {
            setLinkCreationState({ active: false, startNode: null, mouseX: 0, mouseY: 0 });
            return;
          }
          setSelectedNode(null);
        },
        onSvgDblClick: (event) => {
          if (isDraggingRef.current) return;
          if (linkCreationStateRef.current.active) {
            setLinkCreationState({ active: false, startNode: null, mouseX: 0, mouseY: 0 });
            return;
          }
          handleDblClickBlank(event as any);
        },
        onSvgMouseMove: (event) => {
          if (hoveredNodeRef.current) {
            setHoveredNode({
              ...hoveredNodeRef.current,
              x: event.pageX,
              y: event.pageY,
            });
          }
          handleSvgMouseMove(event as any);
        },
      }
    );

    graphRendererRef.current = graphRenderer;

    const simulationHandle = createForceSimulation(
      filteredGraph,
      {
        width: dimensions.width,
        height: dimensions.height,
        nodeRadius: settings.graphNodeSize,
        linkDistance: settings.graphLinkDistance,
        chargeStrength: settings.graphChargeStrength,
        collideRadius: settings.graphNodeSize + 5,
      },
      (simNodes, simLinks) => {
        setNodes([...simNodes]);
        setLinks([...simLinks]);
        graphRenderer.updatePositions(simNodes, simLinks);
      }
    );

    simulationHandleRef.current = simulationHandle;

    const dragDropHandler = dragWithDrop(simulationHandle.simulation, handleNodeDrop, isDraggingRef);
    dragDropHandlerRef.current = dragDropHandler;

    graphRenderer.setDragBehavior(dragDropHandler.dragBehavior);
    graphRenderer.updateHighlight({
      selectedNodeId: selectedNode?.id ?? null,
      highlightedNodeIds: highlightedNodeIds,
    });
    graphRenderer.render(simulationHandle.nodes, simulationHandle.links, filteredGraph);

    let destroyed = false;

    return () => {
      destroyed = true;
      simulationHandle.destroy();
      graphRenderer.destroy();
      if (simulationHandleRef.current === simulationHandle) {
        simulationHandleRef.current = null;
      }
      if (graphRendererRef.current === graphRenderer) {
        graphRendererRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    if (!simulationHandleRef.current || !graphRendererRef.current) return;

    simulationHandleRef.current.updateData(filteredGraph);
    graphRendererRef.current.render(nodes, links, filteredGraph);
  }, [filteredGraph]);

  useEffect(() => {
    if (!simulationHandleRef.current) return;

    simulationHandleRef.current.updateOptions({
      width: dimensions.width,
      height: dimensions.height,
      nodeRadius: settings.graphNodeSize,
      linkDistance: settings.graphLinkDistance,
      chargeStrength: settings.graphChargeStrength,
      collideRadius: settings.graphNodeSize + 5,
    });
  }, [dimensions, settings.graphNodeSize, settings.graphLinkDistance, settings.graphChargeStrength]);

  useEffect(() => {
    if (!graphRendererRef.current) return;

    graphRendererRef.current.updateHighlight({
      selectedNodeId: selectedNode?.id ?? null,
      highlightedNodeIds: highlightedNodeIds,
    });
  }, [selectedNode, highlightedNodeIds]);

  useEffect(() => {
    if (!graphRendererRef.current || nodes.length === 0) return;
    graphRendererRef.current.updatePositions(nodes, links);
  }, [nodes, links]);

  const handleZoomIn = useCallback(() => {
    if (svgRef.current && zoomRef.current) {
      const svg = d3.select(svgRef.current);
      svg.transition().duration(300).call(zoomRef.current.zoomBehavior.scaleBy, 1.3);
    }
  }, []);

  const handleZoomOut = useCallback(() => {
    if (svgRef.current && zoomRef.current) {
      const svg = d3.select(svgRef.current);
      svg.transition().duration(300).call(zoomRef.current.zoomBehavior.scaleBy, 0.7);
    }
  }, []);

  const handleResetZoom = useCallback(() => {
    if (zoomRef.current) {
      zoomRef.current.reset();
    }
  }, []);

  const handleFitView = useCallback(() => {
    if (zoomRef.current) {
      zoomRef.current.fit(dimensions.width, dimensions.height, 80);
    }
  }, [dimensions]);

  const handleRefresh = useCallback(() => {
    if (simulationHandleRef.current) {
      simulationHandleRef.current.simulation.alpha(1).restart();
    }
  }, []);

  const handleExportPNG = useCallback(async () => {
    if (!svgRef.current || isExporting) return;
    setIsExporting(true);
    try {
      await downloadPNG(svgRef.current, 'knowledge-graph.png', {
        backgroundColor: '#0F172A',
        scale: 2,
      });
    } catch (error) {
      console.error('导出失败:', error);
    } finally {
      setIsExporting(false);
    }
  }, [isExporting]);

  const handleNodeClick = useCallback(
    (node: SimulationNode) => {
      if (node.type === 'document') {
        setCurrentDocument(node.id);
        setActiveTab('editor');
      }
    },
    [setCurrentDocument, setActiveTab]
  );

  const allTags = useMemo(() => {
    return tags.map((t) => t.name).sort((a, b) => a.localeCompare(b));
  }, [tags]);

  const handleNodeDrop = useCallback((draggedNode: SimulationNode, targetNode: SimulationNode) => {
    if (draggedNode.type !== 'document' || targetNode.type !== 'document') return;

    const rect = svgRef.current?.getBoundingClientRect();
    if (!rect) return;

    setDropDialog({
      visible: true,
      draggedNode,
      targetNode,
      x: rect.left + (draggedNode.x || rect.width / 2),
      y: rect.top + (draggedNode.y || rect.height / 2),
    });
  }, []);

  const handleMoveToSubdirectory = useCallback(async () => {
    if (!dropDialog.draggedNode || !dropDialog.targetNode) return;

    const draggedDoc = documents.find(d => d.id === dropDialog.draggedNode!.id);
    const targetDoc = documents.find(d => d.id === dropDialog.targetNode!.id);

    if (!draggedDoc || !targetDoc) {
      setDropDialog(prev => ({ ...prev, visible: false }));
      return;
    }

    try {
      const targetDir = targetDoc.filePath.substring(0, targetDoc.filePath.lastIndexOf('/'));
      const newDir = `${targetDir}/${targetDoc.title}`;
      const newPath = `${newDir}/${draggedDoc.filename}`;

      await window.electron.ipc.invoke(IPC_CHANNELS.FILE.RENAME, {
        oldPath: draggedDoc.filePath,
        newPath,
      });

      setDropDialog(prev => ({ ...prev, visible: false }));
    } catch (error) {
      console.error('移动文档失败:', error);
    }
  }, [dropDialog.draggedNode, dropDialog.targetNode, documents]);

  const handleMergeDocuments = useCallback(async () => {
    if (!dropDialog.draggedNode || !dropDialog.targetNode) return;

    const draggedDoc = documents.find(d => d.id === dropDialog.draggedNode!.id);
    const targetDoc = documents.find(d => d.id === dropDialog.targetNode!.id);

    if (!draggedDoc || !targetDoc) {
      setDropDialog(prev => ({ ...prev, visible: false }));
      return;
    }

    try {
      const mergedContent = `${targetDoc.content}\n\n---\n\n# ${draggedDoc.title}\n\n${draggedDoc.content}`;

      await window.electron.ipc.invoke(IPC_CHANNELS.DOCUMENT.UPDATE, {
        id: targetDoc.id,
        content: mergedContent,
      });

      await window.electron.ipc.invoke(IPC_CHANNELS.DOCUMENT.DELETE, draggedDoc.id);

      setDropDialog(prev => ({ ...prev, visible: false }));
    } catch (error) {
      console.error('合并文档失败:', error);
    }
  }, [dropDialog.draggedNode, dropDialog.targetNode, documents]);

  const handleCreateLink = useCallback(async () => {
    if (!linkCreationDialog.sourceNode || !linkCreationDialog.targetNode) return;

    const sourceDoc = documents.find(d => d.id === linkCreationDialog.sourceNode!.id);
    const targetDoc = documents.find(d => d.id === linkCreationDialog.targetNode!.id);

    if (!sourceDoc || !targetDoc) {
      setLinkCreationDialog(prev => ({ ...prev, visible: false }));
      return;
    }

    try {
      const newContent = `${sourceDoc.content}\n\n参考 [[${targetDoc.title}]] 了解更多。`;

      await window.electron.ipc.invoke(IPC_CHANNELS.DOCUMENT.UPDATE, {
        id: sourceDoc.id,
        content: newContent,
      });

      setLinkCreationDialog(prev => ({ ...prev, visible: false }));
    } catch (error) {
      console.error('创建链接失败:', error);
    }
  }, [linkCreationDialog.sourceNode, linkCreationDialog.targetNode, documents]);

  const handleDblClickBlank = useCallback(async (event: React.MouseEvent) => {
    if (isDraggingRef.current) return;

    const title = prompt('请输入新文档标题:', '新文档');
    if (!title) return;

    try {
      const newDoc = await createDocument(title, '', []);
      if (newDoc) {
        setCurrentDocument(newDoc.id);
        setActiveTab('editor');
      }
    } catch (error) {
      console.error('创建新文档失败:', error);
    }
  }, [createDocument, setCurrentDocument, setActiveTab]);

  const handleNodeContextMenu = useCallback((event: React.MouseEvent, node: SimulationNode) => {
    event.preventDefault();

    if (!linkCreationState.active) {
      setLinkCreationState({
        active: true,
        startNode: node,
        mouseX: event.clientX,
        mouseY: event.clientY,
      });
    } else if (linkCreationState.startNode && linkCreationState.startNode.id !== node.id) {
      const rect = svgRef.current?.getBoundingClientRect();
      if (!rect) return;

      setLinkCreationDialog({
        visible: true,
        sourceNode: linkCreationState.startNode,
        targetNode: node,
        x: event.clientX,
        y: event.clientY,
      });
      setLinkCreationState({ active: false, startNode: null, mouseX: 0, mouseY: 0 });
    } else {
      setLinkCreationState({ active: false, startNode: null, mouseX: 0, mouseY: 0 });
    }
  }, [linkCreationState]);

  const handleSvgMouseMove = useCallback((event: React.MouseEvent) => {
    if (linkCreationState.active) {
      setLinkCreationState(prev => ({
        ...prev,
        mouseX: event.clientX,
        mouseY: event.clientY,
      }));
    }
  }, [linkCreationState.active]);

  return (
    <div className="h-full flex flex-col bg-[var(--background-color)]">
      <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border-color)] bg-[var(--card-background)]">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <Network className="w-5 h-5 text-blue-500" />
            <h1 className="text-lg font-semibold">知识图谱</h1>
          </div>
          <div className="h-5 w-px bg-[var(--border-color)]" />
          <div className="relative">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              type="text"
              placeholder="搜索节点..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="input pl-9 w-64 text-sm"
            />
            {searchQuery && (
              <button
                onClick={() => setSearchQuery('')}
                className="absolute right-2 top-1/2 -translate-y-1/2 p-0.5 hover:bg-gray-200 dark:hover:bg-gray-700 rounded"
              >
                <X className="w-3 h-3 text-gray-400" />
              </button>
            )}
          </div>
        </div>

        <div className="flex items-center gap-1">
          {linkCreationState.active && (
            <div className="mr-4 px-3 py-1.5 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 text-sm rounded-lg flex items-center gap-2">
              <span className="w-2 h-2 bg-blue-500 rounded-full animate-pulse" />
              连线模式：点击另一个节点创建链接，或点击空白处取消
            </div>
          )}
          {!linkCreationState.active && (
            <div className="mr-4 px-3 py-1.5 text-xs text-gray-500 dark:text-gray-400 flex items-center gap-4">
              <span>双击空白：新建文档</span>
              <span>拖拽节点：移动/合并</span>
              <span>右键节点：创建链接</span>
            </div>
          )}
          <button
            onClick={handleZoomOut}
            className="btn-icon"
            title="缩小"
          >
            <ZoomOut className="w-4 h-4" />
          </button>
          <button
            onClick={handleZoomIn}
            className="btn-icon"
            title="放大"
          >
            <ZoomIn className="w-4 h-4" />
          </button>
          <button
            onClick={handleResetZoom}
            className="btn-icon"
            title="重置视图"
          >
            <RotateCcw className="w-4 h-4" />
          </button>
          <button
            onClick={handleFitView}
            className="btn-icon"
            title="适应视图"
          >
            <Maximize2 className="w-4 h-4" />
          </button>
          <div className="h-5 w-px bg-[var(--border-color)] mx-1" />
          <button
            onClick={handleRefresh}
            className="btn-icon"
            title="刷新图谱"
          >
            <RotateCcw className="w-4 h-4" />
          </button>
          <button
            onClick={handleExportPNG}
            disabled={isExporting}
            className="btn btn-primary flex items-center gap-2"
            title="导出为PNG"
          >
            <Download className="w-4 h-4" />
            {isExporting ? '导出中...' : '导出PNG'}
          </button>
        </div>
      </div>

      <div className="flex-1 flex overflow-hidden">
        <div className="flex-1 relative" ref={containerRef}>
          <svg
            ref={svgRef}
            width={dimensions.width}
            height={dimensions.height}
            className="bg-[var(--background-color)]"
            data-testid="graph-container"
          >
            <g ref={gRef} />
            {linkCreationState.active && linkCreationState.startNode && (
              <line
                x1={linkCreationState.startNode.x || 0}
                y1={linkCreationState.startNode.y || 0}
                x2={(() => {
                  const rect = svgRef.current?.getBoundingClientRect();
                  if (!rect) return 0;
                  return linkCreationState.mouseX - rect.left;
                })()}
                y2={(() => {
                  const rect = svgRef.current?.getBoundingClientRect();
                  if (!rect) return 0;
                  return linkCreationState.mouseY - rect.top;
                })()}
                stroke="#3b82f6"
                stroke-width="2"
                stroke-dasharray="5,5"
                stroke-opacity="0.8"
                pointer-events="none"
              />
            )}
          </svg>

          {nodes.length === 0 && (
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="text-center">
                <Network className="w-16 h-16 mx-auto text-gray-400 mb-4" />
                <p className="text-gray-500 dark:text-gray-400 mb-2">暂无图谱数据</p>
                <p className="text-sm text-gray-400 dark:text-gray-500">
                  创建文档并添加双向链接来构建知识图谱
                </p>
              </div>
            </div>
          )}

          {hoveredNode && (
            <div
              className="fixed z-50 pointer-events-none card p-3 shadow-lg min-w-48"
              style={{
                left: hoveredNode.x + 15,
                top: hoveredNode.y + 15,
              }}
            >
              <div className="flex items-center gap-2 mb-2">
                {hoveredNode.node.type === 'document' ? (
                  <FileText className="w-4 h-4 text-green-500" />
                ) : (
                  <Tag className="w-4 h-4 text-purple-500" />
                )}
                <span className="font-medium truncate max-w-48">{hoveredNode.node.label}</span>
              </div>
              <div className="text-xs text-gray-500 dark:text-gray-400 space-y-1">
                <div className="flex items-center gap-2">
                  <span className="text-gray-400">类型:</span>
                  <span>{hoveredNode.node.type === 'document' ? '文档' : '标签'}</span>
                </div>
                {hoveredNode.node.type === 'document' && (
                  <>
                    <div className="flex items-center gap-2">
                      <ArrowRightLeft className="w-3 h-3 text-gray-400" />
                      <span>
                        入度: {getNodeDegree(filteredGraph, hoveredNode.node.id).in} | 出度:{' '}
                        {getNodeDegree(filteredGraph, hoveredNode.node.id).out}
                      </span>
                    </div>
                    {hoveredNode.node.tags && hoveredNode.node.tags.length > 0 && (
                      <div className="flex flex-wrap gap-1 mt-2">
                        {hoveredNode.node.tags.slice(0, 3).map((tag) => (
                          <span key={tag} className="badge badge-primary text-[10px]">
                            #{tag}
                          </span>
                        ))}
                      </div>
                    )}
                  </>
                )}
              </div>
            </div>
          )}
        </div>

        <div className="w-72 border-l border-[var(--border-color)] bg-[var(--card-background)] overflow-y-auto">
          <div className="p-4 border-b border-[var(--border-color)]">
            <h3 className="font-semibold mb-3 flex items-center gap-2">
              <Hash className="w-4 h-4 text-purple-500" />
              标签过滤
            </h3>
            {allTags.length > 0 ? (
              <div className="flex flex-wrap gap-1.5">
                {allTags.map((tag) => (
                  <button
                    key={tag}
                    onClick={() => toggleTagFilter(tag)}
                    className={`badge cursor-pointer transition-colors ${
                      selectedTags.includes(tag)
                        ? 'badge-primary'
                        : 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-600'
                    }`}
                  >
                    #{tag}
                  </button>
                ))}
              </div>
            ) : (
              <p className="text-sm text-gray-500 dark:text-gray-400">暂无标签</p>
            )}
            {selectedTags.length > 0 && (
              <button
                onClick={() => {
                  selectedTags.forEach((tag) => toggleTagFilter(tag));
                }}
                className="mt-2 text-xs text-blue-500 hover:text-blue-600"
              >
                清除所有筛选
              </button>
            )}
          </div>

          <div className="p-4 border-b border-[var(--border-color)]">
            <h3 className="font-semibold mb-3 flex items-center gap-2">
              <Network className="w-4 h-4 text-blue-500" />
              统计信息
            </h3>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-gray-500 dark:text-gray-400">总节点数</span>
                <span className="font-medium">{graphStats.totalNodes}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500 dark:text-gray-400">总连线数</span>
                <span className="font-medium">{graphStats.totalLinks}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500 dark:text-gray-400">文档节点</span>
                <span className="font-medium text-green-500">{graphStats.docNodes}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500 dark:text-gray-400">标签节点</span>
                <span className="font-medium text-purple-500">{graphStats.tagNodes}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500 dark:text-gray-400">文档链接</span>
                <span className="font-medium">{graphStats.docLinks}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500 dark:text-gray-400">最大度数</span>
                <span className="font-medium text-orange-500">{graphStats.maxDegree}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500 dark:text-gray-400">平均度数</span>
                <span className="font-medium">{graphStats.avgDegree}</span>
              </div>
            </div>
          </div>

          {selectedNodeDetails && (
            <div className="p-4">
              <div className="flex items-center justify-between mb-3">
                <h3 className="font-semibold flex items-center gap-2">
                  {selectedNodeDetails.type === 'document' ? (
                    <FileText className="w-4 h-4 text-green-500" />
                  ) : (
                    <Tag className="w-4 h-4 text-purple-500" />
                  )}
                  节点详情
                </h3>
                <button
                  onClick={() => setSelectedNode(null)}
                  className="p-1 hover:bg-gray-200 dark:hover:bg-gray-700 rounded"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              <div className="space-y-3">
                <div>
                  <label className="text-xs text-gray-500 dark:text-gray-400 block mb-1">
                    标题
                  </label>
                  <p className="font-medium break-all">{selectedNodeDetails.label}</p>
                </div>

                {selectedNodeDetails.type === 'document' && (
                  <>
                    <div className="grid grid-cols-3 gap-2 text-center">
                      <div className="card p-2">
                        <div className="text-lg font-bold text-blue-500">
                          {selectedNodeDetails.degree.in}
                        </div>
                        <div className="text-xs text-gray-500 dark:text-gray-400">入度</div>
                      </div>
                      <div className="card p-2">
                        <div className="text-lg font-bold text-green-500">
                          {selectedNodeDetails.degree.out}
                        </div>
                        <div className="text-xs text-gray-500 dark:text-gray-400">出度</div>
                      </div>
                      <div className="card p-2">
                        <div className="text-lg font-bold text-purple-500">
                          {selectedNodeDetails.degree.total}
                        </div>
                        <div className="text-xs text-gray-500 dark:text-gray-400">总计</div>
                      </div>
                    </div>

                    {selectedNodeDetails.tags && selectedNodeDetails.tags.length > 0 && (
                      <div>
                        <label className="text-xs text-gray-500 dark:text-gray-400 block mb-1">
                          标签
                        </label>
                        <div className="flex flex-wrap gap-1">
                          {selectedNodeDetails.tags.map((tag) => (
                            <span key={tag} className="badge badge-primary text-[10px]">
                              #{tag}
                            </span>
                          ))}
                        </div>
                      </div>
                    )}

                    {selectedNodeDetails.doc && (
                      <div className="space-y-2 pt-2 border-t border-[var(--border-color)]">
                        <div>
                          <label className="text-xs text-gray-500 dark:text-gray-400 block mb-1">
                            路径
                          </label>
                          <p className="text-xs text-gray-600 dark:text-gray-300 break-all">
                            {selectedNodeDetails.doc.filePath}
                          </p>
                        </div>
                        <div>
                          <label className="text-xs text-gray-500 dark:text-gray-400 block mb-1">
                            字数
                          </label>
                          <p className="text-sm">
                            {selectedNodeDetails.doc.wordCount.toLocaleString()} 字
                          </p>
                        </div>
                      </div>
                    )}

                    <button
                      onClick={() => handleNodeClick(selectedNodeDetails)}
                      className="w-full btn btn-primary mt-2 flex items-center justify-center gap-2"
                    >
                      <FileText className="w-4 h-4" />
                      打开文档
                    </button>
                  </>
                )}
              </div>
            </div>
          )}

          {!selectedNodeDetails && (
            <div className="p-4">
              <h3 className="font-semibold mb-3 flex items-center gap-2">
                <FileText className="w-4 h-4 text-gray-400" />
                节点详情
              </h3>
              <p className="text-sm text-gray-500 dark:text-gray-400">
                点击节点查看详细信息，双击打开文档
              </p>
            </div>
          )}

          <div className="p-4 border-t border-[var(--border-color)]">
            <h3 className="font-semibold mb-3 text-sm">图例</h3>
            <div className="space-y-2 text-xs">
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full bg-green-600" />
                <span>文档节点</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full bg-purple-600" />
                <span>标签节点</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-6 h-0.5 bg-green-600/50" />
                <span>文档链接</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-6 h-0.5 bg-purple-600/50" />
                <span>标签关联</span>
              </div>
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full bg-blue-500 border-2 border-white" />
                <span>选中节点</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {dropDialog.visible && dropDialog.draggedNode && dropDialog.targetNode && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="card p-6 w-96 shadow-2xl">
            <h3 className="text-lg font-semibold mb-4">选择操作</h3>
            <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
              将 <span className="font-medium text-blue-600 dark:text-blue-400">{dropDialog.draggedNode.label}</span>
              {' -> '}
              <span className="font-medium text-green-600 dark:text-green-400">{dropDialog.targetNode.label}</span>
            </p>
            <div className="space-y-2">
              <button
                onClick={handleMoveToSubdirectory}
                className="w-full btn btn-secondary flex items-center justify-start gap-3 py-3"
              >
                <span className="text-xl">📁</span>
                <div className="text-left">
                  <div className="font-medium">移动到子目录</div>
                  <div className="text-xs text-gray-500 dark:text-gray-400">
                    将文档移动到目标文档的同名文件夹中
                  </div>
                </div>
              </button>
              <button
                onClick={handleMergeDocuments}
                className="w-full btn btn-secondary flex items-center justify-start gap-3 py-3"
              >
                <span className="text-xl">🔗</span>
                <div className="text-left">
                  <div className="font-medium">合并文档内容</div>
                  <div className="text-xs text-gray-500 dark:text-gray-400">
                    将源文档内容追加到目标文档末尾
                  </div>
                </div>
              </button>
              <button
                onClick={() => setDropDialog(prev => ({ ...prev, visible: false }))}
                className="w-full btn py-2 mt-2"
              >
                取消
              </button>
            </div>
          </div>
        </div>
      )}

      {linkCreationDialog.visible && linkCreationDialog.sourceNode && linkCreationDialog.targetNode && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="card p-6 w-96 shadow-2xl">
            <h3 className="text-lg font-semibold mb-4">创建双向链接</h3>
            <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
              在 <span className="font-medium text-blue-600 dark:text-blue-400">{linkCreationDialog.sourceNode.label}</span>
              {' -> '}
              <span className="font-medium text-green-600 dark:text-green-400">{linkCreationDialog.targetNode.label}</span>
              {' '}之间创建链接
            </p>
            <div className="bg-gray-100 dark:bg-gray-800 rounded-lg p-3 mb-4">
              <p className="text-sm font-mono text-gray-700 dark:text-gray-300">
                参考 [[{linkCreationDialog.targetNode.label}]] 了解更多。
              </p>
            </div>
            <div className="space-y-2">
              <button
                onClick={handleCreateLink}
                className="w-full btn btn-primary py-2"
              >
                确认创建
              </button>
              <button
                onClick={() => setLinkCreationDialog(prev => ({ ...prev, visible: false }))}
                className="w-full btn py-2"
              >
                取消
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
