"use client";

import { DirectoryTree } from "@/components/DirectoryTree";
import { NovelEditor } from "@/components/NovelEditor";
import { useNovelStore } from "@/store/novelStore";
import { Menu, X } from "lucide-react";
import { useState } from "react";

export default function Home() {
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const { currentNovel } = useNovelStore();

  return (
    <main className="flex h-screen bg-background overflow-hidden">
      {/* 移动端侧边栏遮罩 */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 bg-black/50 z-40 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* 侧边栏 */}
      <aside
        className={`fixed lg:static inset-y-0 left-0 z-50 w-80 lg:w-72 transform transition-transform duration-300 ease-in-out ${
          sidebarOpen ? "translate-x-0" : "-translate-x-full lg:translate-x-0"
        }`}
      >
        <DirectoryTree />
        
        {/* 移动端关闭按钮 */}
        <button
          className="absolute top-4 right-4 lg:hidden p-1 hover:bg-muted rounded"
          onClick={() => setSidebarOpen(false)}
        >
          <X className="w-5 h-5" />
        </button>
      </aside>

      {/* 主内容区 */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* 移动端顶部栏 */}
        <div className="lg:hidden flex items-center justify-between px-4 py-2 border-b border-border">
          <button
            className="p-2 hover:bg-muted rounded"
            onClick={() => setSidebarOpen(true)}
          >
            <Menu className="w-5 h-5" />
          </button>
          <h1 className="text-sm font-semibold truncate">
            {currentNovel?.title || "InkSoul AI"}
          </h1>
          <div className="w-9" />
        </div>

        {/* 编辑器 */}
        <div className="flex-1 overflow-hidden">
          <NovelEditor />
        </div>
      </div>
    </main>
  );
}
