"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { cardApi } from "@/lib/api";
import { KnowledgeCard } from "@/types";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export default function NotesPage() {
  const [cards, setCards] = useState<KnowledgeCard[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedCard, setSelectedCard] = useState<KnowledgeCard | null>(null);

  useEffect(() => {
    fetchCards();
  }, []);

  const fetchCards = async () => {
    try {
      setLoading(true);
      const data = await cardApi.getAll();
      setCards(data);
    } catch (err) {
      setError("加载笔记失败，请稍后重试");
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const getRelationColor = (type: string) => {
    switch (type) {
      case "supports":
        return "text-green-600 bg-green-50";
      case "contradicts":
        return "text-red-600 bg-red-50";
      case "explains":
        return "text-blue-600 bg-blue-50";
      default:
        return "text-gray-600 bg-gray-50";
    }
  };

  const getRelationLabel = (type: string) => {
    switch (type) {
      case "supports":
        return "支持";
      case "contradicts":
        return "矛盾";
      case "explains":
        return "解释";
      default:
        return "关联";
    }
  };

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
              我的笔记
            </h1>
          </div>
          <Link
            href="/editor"
            className="px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors"
          >
            + 新建笔记
          </Link>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 py-8 sm:px-6 lg:px-8">
        {loading ? (
          <div className="flex justify-center items-center py-20">
            <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-indigo-600"></div>
          </div>
        ) : error ? (
          <div className="text-center py-20">
            <p className="text-red-600 dark:text-red-400 mb-4">{error}</p>
            <button
              onClick={fetchCards}
              className="px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors"
            >
              重试
            </button>
          </div>
        ) : cards.length === 0 ? (
          <div className="text-center py-20">
            <div className="text-6xl mb-4">📝</div>
            <h2 className="text-xl font-semibold text-slate-900 dark:text-white mb-2">
              暂无笔记
            </h2>
            <p className="text-slate-600 dark:text-slate-300 mb-6">
              开始创建您的第一条知识卡片吧
            </p>
            <Link
              href="/editor"
              className="inline-block px-6 py-3 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors"
            >
              创建第一条笔记
            </Link>
          </div>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <div className="lg:col-span-2">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {cards.map((card) => (
                  <div
                    key={card.id}
                    onClick={() => setSelectedCard(card)}
                    className={`bg-white dark:bg-slate-800 rounded-lg shadow p-4 cursor-pointer transition-all hover:shadow-md ${
                      selectedCard?.id === card.id
                        ? "ring-2 ring-indigo-500"
                        : ""
                    }`}
                  >
                    <div className="flex items-start justify-between mb-2">
                      <div className="flex flex-wrap gap-1">
                        {card.tags.slice(0, 3).map((tag, index) => (
                          <span
                            key={index}
                            className="px-2 py-0.5 bg-slate-100 dark:bg-slate-700 text-xs text-slate-600 dark:text-slate-300 rounded"
                          >
                            {tag}
                          </span>
                        ))}
                      </div>
                    </div>
                    <p className="text-sm text-slate-700 dark:text-slate-200 line-clamp-4 mb-2">
                      {card.content.substring(0, 200)}
                      {card.content.length > 200 && "..."}
                    </p>
                    {card.related_nodes.length > 0 && (
                      <div className="flex items-center gap-1 mt-2">
                        <span className="text-xs text-slate-500 dark:text-slate-400">
                          关联:
                        </span>
                        {card.related_nodes.slice(0, 2).map((rel, idx) => (
                          <span
                            key={idx}
                            className={`px-1.5 py-0.5 text-xs rounded ${getRelationColor(
                              rel.relation_type
                            )}`}
                          >
                            {getRelationLabel(rel.relation_type)}
                          </span>
                        ))}
                        {card.related_nodes.length > 2 && (
                          <span className="text-xs text-slate-500">
                            +{card.related_nodes.length - 2}
                          </span>
                        )}
                      </div>
                    )}
                    <p className="text-xs text-slate-400 mt-2">
                      {card.created_at
                        ? new Date(card.created_at).toLocaleDateString("zh-CN")
                        : ""}
                    </p>
                  </div>
                ))}
              </div>
            </div>

            {selectedCard && (
              <div className="lg:col-span-1">
                <div className="bg-white dark:bg-slate-800 rounded-lg shadow p-6 sticky top-24">
                  <div className="flex items-center justify-between mb-4">
                    <h3 className="font-semibold text-slate-900 dark:text-white">
                      笔记详情
                    </h3>
                    <button
                      onClick={() => setSelectedCard(null)}
                      className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
                    >
                      ✕
                    </button>
                  </div>

                  <div className="prose dark:prose-invert max-w-none mb-4">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>
                      {selectedCard.content}
                    </ReactMarkdown>
                  </div>

                  {selectedCard.tags.length > 0 && (
                    <div className="mb-4">
                      <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                        标签
                      </p>
                      <div className="flex flex-wrap gap-2">
                        {selectedCard.tags.map((tag, index) => (
                          <span
                            key={index}
                            className="px-2 py-1 bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600 dark:text-indigo-400 text-sm rounded"
                          >
                            #{tag}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  {selectedCard.related_nodes.length > 0 && (
                    <div>
                      <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                        关联笔记
                      </p>
                      <div className="space-y-2">
                        {selectedCard.related_nodes.map((rel, idx) => {
                          const targetCard = cards.find((c) => c.id === rel.target_id);
                          return (
                            <div
                              key={idx}
                              className="p-2 bg-slate-50 dark:bg-slate-700/50 rounded text-sm cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700"
                              onClick={() => targetCard && setSelectedCard(targetCard)}
                            >
                              <span
                                className={`inline-block px-1.5 py-0.5 text-xs rounded mr-2 ${getRelationColor(
                                  rel.relation_type
                                )}`}
                              >
                                {getRelationLabel(rel.relation_type)}
                              </span>
                              <p className="text-slate-700 dark:text-slate-200 line-clamp-1">
                                {targetCard
                                  ? targetCard.content.substring(0, 50) + "..."
                                  : rel.target_id}
                              </p>
                              <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
                                {rel.reason}
                              </p>
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  <Link
                    href={`/editor?id=${selectedCard.id}`}
                    className="mt-6 block w-full text-center px-4 py-2 border border-indigo-600 text-indigo-600 rounded-lg hover:bg-indigo-50 dark:hover:bg-indigo-900/30 transition-colors"
                  >
                    编辑此笔记
                  </Link>
                </div>
              </div>
            )}
          </div>
        )}
      </main>
    </div>
  );
}
