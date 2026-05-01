"use client";

import { useEffect, useState, useRef } from "react";
import Link from "next/link";
import { graphApi, cardApi } from "@/lib/api";
import { GraphData, KnowledgeCard } from "@/types";
import dynamic from "next/dynamic";

const ForceGraph2D = dynamic(() => import("react-force-graph-2d"), {
  ssr: false,
});

export default function GraphPage() {
  const [graphData, setGraphData] = useState<GraphData>({ nodes: [], links: [] });
  const [cards, setCards] = useState<KnowledgeCard[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedNode, setSelectedNode] = useState<{
    id: string;
    label: string;
    card?: KnowledgeCard;
  } | null>(null);
  const [hoveredNode, setHoveredNode] = useState<string | null>(null);
  const graphRef = useRef<any>(null);

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    try {
      setLoading(true);
      const [graphResult, cardsResult] = await Promise.all([
        graphApi.getSubgraph(),
        cardApi.getAll(),
      ]);
      setGraphData(graphResult);
      setCards(cardsResult);
    } catch (err) {
      setError("加载图谱数据失败");
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const handleNodeClick = (node: any) => {
    const card = cards.find((c) => c.id === node.id);
    setSelectedNode({
      id: node.id,
      label: node.label,
      card,
    });
  };

  const handleNodeHover = (node: any) => {
    setHoveredNode(node?.id || null);
    if (graphRef.current) {
      const canvas = graphRef.current.ctx();
      canvas.style.cursor = node ? "pointer" : "default";
    }
  };

  const getNodeColor = (node: any) => {
    if (selectedNode?.id === node.id) {
      return "#4F46E5";
    }
    if (hoveredNode === node.id) {
      return "#6366F1";
    }
    return "#818CF8";
  };

  const getNodeSize = (node: any) => {
    const connections = graphData.links.filter(
      (l) => l.source === node.id || l.target === node.id ||
             (typeof l.source === "object" && l.source.id === node.id) ||
             (typeof l.target === "object" && l.target.id === node.id)
    ).length;
    return 5 + connections * 2;
  };

  const getRelationLabel = (type: string) => {
    switch (type) {
      case "SUPPORTS":
        return "支持";
      case "CONTRADICTS":
        return "矛盾";
      case "EXPLAINS":
        return "解释";
      default:
        return "关联";
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-900 dark:to-slate-800 flex items-center justify-center">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-indigo-600"></div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-900 dark:to-slate-800">
      <header className="bg-white dark:bg-slate-800 shadow-sm sticky top-0 z-10">
        <div className="max-w-7xl mx-auto px-4 py-4 sm:px-6 lg:px-8 flex items-center justify-between">
          <div className="flex items-center gap-4">
            <Link
              href="/"
              className="text-sm text-slate-600 dark:text-slate-300 hover:text-indigo-600 dark:hover:text-indigo-400"
            >
              ← 返回首页
            </Link>
            <h1 className="text-2xl font-bold text-slate-900 dark:text-white">
              知识图谱
            </h1>
          </div>
          <button
            onClick={fetchData}
            className="px-4 py-2 text-sm border border-slate-300 dark:border-slate-600 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
          >
            刷新数据
          </button>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 py-6 sm:px-6 lg:px-8">
        {error ? (
          <div className="text-center py-20">
            <p className="text-red-600 dark:text-red-400 mb-4">{error}</p>
            <button
              onClick={fetchData}
              className="px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors"
            >
              重试
            </button>
          </div>
        ) : graphData.nodes.length === 0 ? (
          <div className="text-center py-20">
            <div className="text-6xl mb-4">🕸️</div>
            <h2 className="text-xl font-semibold text-slate-900 dark:text-white mb-2">
              暂无图谱数据
            </h2>
            <p className="text-slate-600 dark:text-slate-300 mb-6">
              创建更多笔记后，知识图谱将自动构建
            </p>
            <Link
              href="/editor"
              className="inline-block px-6 py-3 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors"
            >
              创建笔记
            </Link>
          </div>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <div className="lg:col-span-2">
              <div className="bg-white dark:bg-slate-800 rounded-lg shadow overflow-hidden">
                <div className="p-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between">
                  <span className="text-sm text-slate-600 dark:text-slate-300">
                    {graphData.nodes.length} 个节点 · {graphData.links.length} 条关联
                  </span>
                  <div className="flex items-center gap-4 text-xs text-slate-500 dark:text-slate-400">
                    <span className="flex items-center gap-1">
                      <span className="w-3 h-3 rounded-full bg-indigo-400"></span>
                      节点
                    </span>
                    <span className="flex items-center gap-1">
                      <span className="w-8 h-0.5 bg-slate-300 dark:bg-slate-600"></span>
                      关联
                    </span>
                  </div>
                </div>
                <div className="h-96 lg:h-[600px] bg-slate-50 dark:bg-slate-900/50">
                  <ForceGraph2D
                    ref={graphRef}
                    graphData={{
                      nodes: graphData.nodes.map((n) => ({
                        ...n,
                        color: getNodeColor(n),
                        size: getNodeSize(n),
                      })),
                      links: graphData.links.map((l) => ({
                        ...l,
                        color: "#CBD5E1",
                      })),
                    }}
                    nodeLabel="label"
                    nodeColor={(node: any) => node.color}
                    nodeVal={(node: any) => node.size}
                    linkColor={(link: any) => "#CBD5E1"}
                    onNodeClick={handleNodeClick}
                    onNodeHover={handleNodeHover}
                    backgroundColor={undefined}
                    linkWidth={1}
                    nodeCanvasObject={(node: any, ctx: CanvasRenderingContext2D, globalScale: number) => {
                      const label = node.label;
                      const fontSize = 12 / globalScale;
                      ctx.font = `${fontSize}px Sans-Serif`;
                      ctx.textAlign = "center";
                      ctx.textBaseline = "middle";
                      ctx.fillStyle = node.color;
                      ctx.beginPath();
                      ctx.arc(node.x, node.y, node.size, 0, 2 * Math.PI);
                      ctx.fill();

                      if (globalScale >= 1) {
                        ctx.fillStyle = "#374151";
                        ctx.fillText(label.substring(0, 15) + (label.length > 15 ? "..." : ""), node.x, node.y + node.size + fontSize + 2);
                      }
                    }}
                    cooldownTicks={100}
                  />
                </div>
              </div>
            </div>

            <div className="lg:col-span-1">
              <div className="bg-white dark:bg-slate-800 rounded-lg shadow sticky top-24">
                <div className="p-4 border-b border-slate-200 dark:border-slate-700">
                  <h3 className="font-semibold text-slate-900 dark:text-white">
                    节点详情
                  </h3>
                </div>

                {!selectedNode ? (
                  <div className="p-8 text-center">
                    <div className="text-4xl mb-3">👆</div>
                    <p className="text-sm text-slate-500 dark:text-slate-400">
                      点击图谱中的节点查看详情
                    </p>
                  </div>
                ) : (
                  <div className="p-4">
                    <div className="mb-4">
                      <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                        节点 ID
                      </p>
                      <p className="text-xs text-slate-500 dark:text-slate-400 font-mono bg-slate-50 dark:bg-slate-700 p-2 rounded">
                        {selectedNode.id}
                      </p>
                    </div>

                    {selectedNode.card && (
                      <>
                        <div className="mb-4">
                          <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                            内容
                          </p>
                          <p className="text-sm text-slate-600 dark:text-slate-200 bg-slate-50 dark:bg-slate-700 p-3 rounded max-h-40 overflow-auto">
                            {selectedNode.card.content}
                          </p>
                        </div>

                        {selectedNode.card.tags.length > 0 && (
                          <div className="mb-4">
                            <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                              标签
                            </p>
                            <div className="flex flex-wrap gap-2">
                              {selectedNode.card.tags.map((tag, idx) => (
                                <span
                                  key={idx}
                                  className="px-2 py-1 bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600 dark:text-indigo-400 text-xs rounded"
                                >
                                  #{tag}
                                </span>
                              ))}
                            </div>
                          </div>
                        )}

                        {selectedNode.card.related_nodes.length > 0 && (
                          <div className="mb-4">
                            <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                              关联关系 ({selectedNode.card.related_nodes.length})
                            </p>
                            <div className="space-y-2">
                              {selectedNode.card.related_nodes.map((rel, idx) => {
                                const targetCard = cards.find((c) => c.id === rel.target_id);
                                return (
                                  <div
                                    key={idx}
                                    className="p-2 bg-slate-50 dark:bg-slate-700/50 rounded text-sm"
                                  >
                                    <span
                                      className={`inline-block px-1.5 py-0.5 text-xs rounded mr-2 ${
                                        rel.relation_type === "supports"
                                          ? "bg-green-100 text-green-700"
                                          : rel.relation_type === "contradicts"
                                          ? "bg-red-100 text-red-700"
                                          : "bg-blue-100 text-blue-700"
                                      }`}
                                    >
                                      {getRelationLabel(rel.relation_type.toUpperCase())}
                                    </span>
                                    <p className="text-slate-600 dark:text-slate-200 text-xs mt-1">
                                      {rel.reason || "无描述"}
                                    </p>
                                  </div>
                                );
                              })}
                            </div>
                          </div>
                        )}

                        <Link
                          href={`/editor?id=${selectedNode.id}`}
                          className="block w-full text-center px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors text-sm"
                        >
                          编辑此笔记
                        </Link>
                      </>
                    )}

                    {!selectedNode.card && (
                      <p className="text-sm text-slate-500 dark:text-slate-400 italic">
                        此节点暂无详细数据
                      </p>
                    )}
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
