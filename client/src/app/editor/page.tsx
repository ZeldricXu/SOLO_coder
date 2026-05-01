"use client";

import { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { cardApi } from "@/lib/api";
import { SemanticSuggestion, KnowledgeCard } from "@/types";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export default function EditorPage() {
  const searchParams = useSearchParams();
  const cardId = searchParams.get("id");

  const [content, setContent] = useState("");
  const [tags, setTags] = useState("");
  const [sourceUrl, setSourceUrl] = useState("");
  const [suggestions, setSuggestions] = useState<SemanticSuggestion[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [showPreview, setShowPreview] = useState(false);
  const [debounceTimer, setDebounceTimer] = useState<NodeJS.Timeout | null>(null);

  useEffect(() => {
    if (cardId) {
      loadCard(cardId);
    }
  }, [cardId]);

  const loadCard = async (id: string) => {
    try {
      setLoading(true);
      const card = await cardApi.getById(id);
      setContent(card.content);
      setTags(card.tags.join(", "));
      setSourceUrl(card.source_url || "");
    } catch (err) {
      setError("加载笔记失败");
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const fetchSuggestions = useCallback(async (text: string) => {
    if (text.trim().length < 20) {
      setSuggestions([]);
      return;
    }

    try {
      const data = await cardApi.suggest(text);
      setSuggestions(data);
    } catch (err) {
      console.error("获取关联建议失败:", err);
    }
  }, []);

  const handleContentChange = (newContent: string) => {
    setContent(newContent);

    if (debounceTimer) {
      clearTimeout(debounceTimer);
    }

    const timer = setTimeout(() => {
        fetchSuggestions(newContent);
      }, 800);

    setDebounceTimer(timer);
  };

  const handleSave = async () => {
    if (!content.trim()) {
      setError("内容不能为空");
      return;
    }

    try {
      setSaving(true);
      setError(null);
      setSuccessMessage(null);

      const tagArray = tags
        .split(",")
        .map((t) => t.trim())
        .filter((t) => t.length > 0);

      await cardApi.ingest({
        content: content.trim(),
        source_url: sourceUrl.trim() || undefined,
        tags: tagArray.length > 0 ? tagArray : undefined,
      });

      setSuccessMessage("保存成功！");
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err) {
      setError("保存失败，请稍后重试");
      console.error(err);
    } finally {
      setSaving(false);
    }
  };

  const insertSuggestion = (suggestion: SemanticSuggestion) => {
    const insertText = `\n\n---\n\n**关联笔记：\n${suggestion.card.content}\n\n`;
    setContent((prev) => prev + insertText);
  };

  const getRelationColor = (type: string) => {
    switch (type) {
      case "supports":
        return "border-l-green-500 bg-green-50 dark:bg-green-900/20";
      case "contradicts":
        return "border-l-red-500 bg-red-50 dark:bg-red-900/20";
      case "explains":
        return "border-l-blue-500 bg-blue-50 dark:bg-blue-900/20";
      default:
        return "border-l-gray-500 bg-gray-50 dark:bg-gray-800";
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
        <div className="max-w-7xl mx-auto px-4 py-4 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <Link
                href="/"
                className="text-sm text-slate-600 dark:text-slate-300 hover:text-indigo-600 dark:hover:text-indigo-400"
              >
                ← 返回首页
              </Link>
              <h1 className="text-2xl font-bold text-slate-900 dark:text-white">
                {cardId ? "编辑笔记" : "新建笔记"}
              </h1>
            </div>
            <div className="flex items-center gap-3">
              <button
                onClick={() => setShowPreview(!showPreview)}
                className={`px-4 py-2 border rounded-lg transition-colors ${
                  showPreview
                    ? "bg-indigo-100 text-indigo-700 border-indigo-300"
                    : "border-slate-300 text-slate-700 hover:bg-slate-50"
                }`}
              >
                {showPreview ? "编辑" : "预览"}
              </button>
              <button
                onClick={handleSave}
                disabled={saving}
                className="px-6 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                {saving ? "保存中..." : "保存"}
              </button>
            </div>
          </div>

          {error && (
            <div className="mt-3 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
              {error}
            </div>
          )}

          {successMessage && (
            <div className="mt-3 p-3 bg-green-50 border border-green-200 rounded-lg text-sm text-green-700">
              {successMessage}
            </div>
          )}
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 py-6 sm:px-6 lg:px-8">
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="lg:col-span-2">
            <div className="bg-white dark:bg-slate-800 rounded-lg shadow">
              <div className="p-4 border-b border-slate-200 dark:border-slate-700">
                <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                  来源链接 (可选)
                </label>
                <input
                  type="url"
                  value={sourceUrl}
                  onChange={(e) => setSourceUrl(e.target.value)}
                  placeholder="https://example.com/article"
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-700 text-slate-900 dark:text-white focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
                />
              </div>

              <div className="p-4 border-b border-slate-200 dark:border-slate-700">
                <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                  标签 (用逗号分隔)
                </label>
                <input
                  type="text"
                  value={tags}
                  onChange={(e) => setTags(e.target.value)}
                  placeholder="人工智能, 机器学习, 笔记"
                  className="w-full px-3 py-2 border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-700 text-slate-900 dark:text-white focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
                />
              </div>

              <div className="p-4">
                <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                  内容 (支持 Markdown)
                </label>
                {showPreview ? (
                  <div className="min-h-96 p-4 border border-slate-300 dark:border-slate-600 rounded-lg bg-slate-50 dark:bg-slate-700/50 overflow-auto">
                    <div className="prose dark:prose-invert max-w-none">
                      {content ? (
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {content}
                        </ReactMarkdown>
                      ) : (
                        <p className="text-slate-400 italic">暂无内容</p>
                      )}
                    </div>
                  </div>
                ) : (
                  <textarea
                    value={content}
                    onChange={(e) => handleContentChange(e.target.value)}
                    placeholder="开始记录你的想法...

提示：输入超过20个字符以上时，系统会自动搜索相关笔记"
                    className="w-full h-96 px-3 py-2 border border-slate-300 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-700 text-slate-900 dark:text-white focus:ring-2 focus:ring-indigo-500 focus:border-transparent resize-none font-mono text-sm"
                  />
                )}
                <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
                  {content.length} 字符
                  {content.length < 20 && (
                    <span className="ml-2 text-amber-600">
                      (还需输入 {20 - content.length} 个字符以启用语义雷达)
                    </span>
                  )}
                </p>
              </div>
            </div>
          </div>

          <div className="lg:col-span-1">
            <div className="bg-white dark:bg-slate-800 rounded-lg shadow sticky top-24">
              <div className="p-4 border-b border-slate-200 dark:border-slate-700">
                <h3 className="font-semibold text-slate-900 dark:text-white flex items-center gap-2">
                  <span className="text-indigo-600">📡</span>
                  语义雷达
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
                  发现与当前内容相关的历史笔记
                </p>
              </div>

              <div className="p-4 max-h-96 overflow-auto">
                {suggestions.length === 0 ? (
                  <div className="text-center py-8">
                    <div className="text-4xl mb-3">🔍</div>
                    <p className="text-sm text-slate-500 dark:text-slate-400">
                      {content.length < 20
                        ? "继续输入以启用语义搜索..."
                        : "暂无相关笔记"}
                    </p>
                  </div>
                ) : (
                  <div className="space-y-3">
                    {suggestions.map((suggestion, index) => (
                      <div
                        key={suggestion.card.id}
                        className={`p-3 rounded-lg border-l-4 ${getRelationColor(
                          suggestion.card.related_nodes[0]?.relation_type || "explains"
                        )}`}
                      >
                        <div className="flex items-center justify-between mb-2">
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-medium text-slate-600 dark:text-slate-300">
                              相似度: {(suggestion.similarity * 100).toFixed(1)}%
                            </span>
                            {suggestion.card.related_nodes[0] && (
                              <span className="px-1.5 py-0.5 bg-white dark:bg-slate-700 text-xs rounded border border-slate-200 dark:border-slate-600">
                                {getRelationLabel(suggestion.card.related_nodes[0].relation_type)}
                              </span>
                            )}
                          </div>
                        </div>

                        <p className="text-sm text-slate-700 dark:text-slate-200 line-clamp-3 mb-2">
                          {suggestion.card.content.substring(0, 150)}
                          {suggestion.card.content.length > 150 && "..."}
                        </p>

                        {suggestion.reason && (
                          <p className="text-xs text-slate-500 dark:text-slate-400 italic mb-2">
                            💡 {suggestion.reason}
                          </p>
                        )}

                        {suggestion.card.tags.length > 0 && (
                          <div className="flex flex-wrap gap-1 mb-2">
                            {suggestion.card.tags.slice(0, 3).map((tag, tagIndex) => (
                              <span
                                key={tagIndex}
                                className="px-1.5 py-0.5 bg-white dark:bg-slate-700 text-xs text-slate-600 dark:text-slate-300 rounded"
                              >
                                #{tag}
                              </span>
                            ))}
                          </div>
                        )}

                        <button
                          onClick={() => insertSuggestion(suggestion)}
                          className="text-xs text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300"
                        >
                          插入到当前笔记 →
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
