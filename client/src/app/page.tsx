import Link from "next/link";

export default function Home() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-900 dark:to-slate-800">
      <header className="bg-white dark:bg-slate-800 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 py-6 sm:px-6 lg:px-8">
          <h1 className="text-3xl font-bold text-slate-900 dark:text-white">
            Lumina Mind
          </h1>
          <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">
            语义关联型 AI 知识中台
          </p>
        </div>
      </header>
      <main className="max-w-7xl mx-auto px-4 py-12 sm:px-6 lg:px-8">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
          <Link
            href="/notes"
            className="block p-6 bg-white dark:bg-slate-800 rounded-lg shadow hover:shadow-md transition-shadow"
          >
            <h2 className="text-xl font-semibold text-slate-900 dark:text-white mb-2">
              我的笔记
            </h2>
            <p className="text-slate-600 dark:text-slate-300">
              浏览和管理您的所有知识卡片
            </p>
          </Link>
          <Link
            href="/editor"
            className="block p-6 bg-white dark:bg-slate-800 rounded-lg shadow hover:shadow-md transition-shadow"
          >
            <h2 className="text-xl font-semibold text-slate-900 dark:text-white mb-2">
              新建笔记
            </h2>
            <p className="text-slate-600 dark:text-slate-300">
              创建新的知识卡片，体验语义雷达功能
            </p>
          </Link>
          <Link
            href="/graph"
            className="block p-6 bg-white dark:bg-slate-800 rounded-lg shadow hover:shadow-md transition-shadow"
          >
            <h2 className="text-xl font-semibold text-slate-900 dark:text-white mb-2">
              知识图谱
            </h2>
            <p className="text-slate-600 dark:text-slate-300">
              可视化查看知识卡片之间的关联
            </p>
          </Link>
        </div>
      </main>
    </div>
  );
}
