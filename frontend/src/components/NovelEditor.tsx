"use client";

import { useNovelStore, Chapter } from "@/store/novelStore";
import { useEditor, EditorContent } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import Placeholder from "@tiptap/extension-placeholder";
import { useEffect, useState } from "react";
import { Bold, Italic, List, ListOrdered, Quote, Save, Wand2, Trash2 } from "lucide-react";

export function NovelEditor() {
  const {
    currentNovel,
    currentVolumeId,
    currentChapterId,
    updateChapterContent,
    deleteChapter,
  } = useNovelStore();

  const [isSaving, setIsSaving] = useState(false);
  const [wordCount, setWordCount] = useState(0);
  const [charCount, setCharCount] = useState(0);

  const currentChapter = currentNovel?.volumes
    .find((v) => v.id === currentVolumeId)
    ?.chapters.find((c) => c.id === currentChapterId);

  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: {
          levels: [1, 2, 3],
        },
      }),
      Placeholder.configure({
        placeholder: ({ node }) => {
          if (node.type.name === "heading") {
            return "标题";
          }
          return "开始创作你的故事...";
        },
      }),
    ],
    content: currentChapter?.content || "",
    onUpdate: ({ editor }) => {
      const html = editor.getHTML();
      if (currentChapterId) {
        updateChapterContent(currentChapterId, html);
      }

      const text = editor.getText();
      setWordCount(text.split(/\s+/).filter(Boolean).length);
      setCharCount(text.length);
    },
    editorProps: {
      attributes: {
        class:
          "prose prose-sm sm:prose lg:prose-lg xl:prose-xl focus:outline-none min-h-[500px] p-4",
      },
    },
  });

  useEffect(() => {
    if (editor && currentChapter) {
      const currentContent = editor.getHTML();
      if (currentContent !== currentChapter.content) {
        editor.commands.setContent(currentChapter.content || "");
        
        const text = editor.getText();
        setWordCount(text.split(/\s+/).filter(Boolean).length);
        setCharCount(text.length);
      }
    }
  }, [currentChapterId, currentChapter, editor]);

  const handleSave = async () => {
    if (!currentChapterId) return;
    setIsSaving(true);
    
    // 模拟保存操作
    await new Promise((resolve) => setTimeout(resolve, 500));
    
    setIsSaving(false);
  };

  const handleAIAssist = () => {
    // TODO: 实现 AI 辅助功能
    alert("AI 辅助功能开发中...");
  };

  if (!currentChapter) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-muted-foreground">
        <Trash2 className="w-16 h-16 mb-4 opacity-20" />
        <h3 className="text-xl font-medium mb-2">选择或创建章节</h3>
        <p className="text-sm max-w-md text-center">
          从左侧目录树选择一个章节开始编辑，或创建新的章节。
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-background">
      {/* 工具栏 */}
      <div className="flex items-center justify-between px-4 py-2 border-b border-border bg-card">
        <div className="flex items-center gap-1">
          <button
            onClick={() => editor?.chain().focus().toggleBold().run()}
            disabled={!editor?.can().chain().focus().toggleBold().run()}
            className={`p-2 rounded hover:bg-muted ${
              editor?.isActive("bold") ? "bg-muted" : ""
            }`}
            title="粗体"
          >
            <Bold className="w-4 h-4" />
          </button>
          <button
            onClick={() => editor?.chain().focus().toggleItalic().run()}
            disabled={!editor?.can().chain().focus().toggleItalic().run()}
            className={`p-2 rounded hover:bg-muted ${
              editor?.isActive("italic") ? "bg-muted" : ""
            }`}
            title="斜体"
          >
            <Italic className="w-4 h-4" />
          </button>
          <button
            onClick={() => editor?.chain().focus().toggleBulletList().run()}
            disabled={!editor?.can().chain().focus().toggleBulletList().run()}
            className={`p-2 rounded hover:bg-muted ${
              editor?.isActive("bulletList") ? "bg-muted" : ""
            }`}
            title="无序列表"
          >
            <List className="w-4 h-4" />
          </button>
          <button
            onClick={() => editor?.chain().focus().toggleOrderedList().run()}
            disabled={!editor?.can().chain().focus().toggleOrderedList().run()}
            className={`p-2 rounded hover:bg-muted ${
              editor?.isActive("orderedList") ? "bg-muted" : ""
            }`}
            title="有序列表"
          >
            <ListOrdered className="w-4 h-4" />
          </button>
          <button
            onClick={() => editor?.chain().focus().toggleBlockquote().run()}
            disabled={!editor?.can().chain().focus().toggleBlockquote().run()}
            className={`p-2 rounded hover:bg-muted ${
              editor?.isActive("blockquote") ? "bg-muted" : ""
            }`}
            title="引用"
          >
            <Quote className="w-4 h-4" />
          </button>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handleAIAssist}
            className="flex items-center gap-2 px-3 py-1.5 text-sm font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors"
          >
            <Wand2 className="w-4 h-4" />
            AI 辅助
          </button>
          <button
            onClick={handleSave}
            disabled={isSaving}
            className="flex items-center gap-2 px-3 py-1.5 text-sm font-medium bg-secondary text-secondary-foreground rounded-md hover:bg-secondary/80 transition-colors disabled:opacity-50"
          >
            <Save className="w-4 h-4" />
            {isSaving ? "保存中..." : "保存"}
          </button>
        </div>
      </div>

      {/* 章节标题 */}
      <div className="px-8 py-4 border-b border-border bg-card/50">
        <h1 className="text-2xl font-bold">{currentChapter.title}</h1>
      </div>

      {/* 编辑器内容 */}
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-4xl mx-auto">
          <EditorContent editor={editor} />
        </div>
      </div>

      {/* 状态栏 */}
      <div className="flex items-center justify-between px-4 py-2 text-xs text-muted-foreground border-t border-border bg-card">
        <div className="flex items-center gap-4">
          <span>字数: {wordCount}</span>
          <span>字符: {charCount}</span>
        </div>
        <div className="flex items-center gap-4">
          <span>
            最后编辑: {currentChapter.updatedAt.toLocaleString("zh-CN")}
          </span>
        </div>
      </div>
    </div>
  );
}
