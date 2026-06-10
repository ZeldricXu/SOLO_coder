'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  LayoutDashboard,
  Box,
  FlaskConical,
  Database,
  SplitSquareVertical,
  AlertTriangle,
  Activity,
  ChevronLeft,
  ChevronRight,
  GitMerge,
  Search,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAppStore } from '@/store/app';

const navigation = [
  { name: 'Dashboard', href: '/', icon: LayoutDashboard, page: 'dashboard' },
  { name: 'Model Registry', href: '/models', icon: Box, page: 'models' },
  { name: 'Experiments', href: '/experiments', icon: FlaskConical, page: 'experiments' },
  { name: 'Feature Store', href: '/features', icon: Database, page: 'features' },
  { name: 'A/B Testing', href: '/abtests', icon: SplitSquareVertical, page: 'abtests' },
  { name: 'Monitoring', href: '/monitoring', icon: Activity, page: 'monitoring' },
  { name: 'Alerts', href: '/alerts', icon: AlertTriangle, page: 'alerts' },
  { name: 'Pipelines', href: '/pipelines', icon: GitMerge, page: 'pipelines' },
  { name: 'Vector Search', href: '/vector-search', icon: Search, page: 'vector-search' },
];

export function Sidebar() {
  const pathname = usePathname();
  const { sidebarOpen, setCurrentPage } = useAppStore();

  return (
    <aside
      className={cn(
        'fixed left-0 top-0 z-40 h-screen bg-white border-r border-gray-200 transition-all duration-300',
        sidebarOpen ? 'w-64' : 'w-16'
      )}
    >
      <div className="h-16 flex items-center justify-between px-4 border-b border-gray-200">
        {sidebarOpen && (
          <Link href="/" className="flex items-center gap-2" onClick={() => setCurrentPage('dashboard')}>
            <div className="w-8 h-8 bg-gradient-to-br from-primary-500 to-primary-700 rounded-lg flex items-center justify-center">
              <Box className="w-5 h-5 text-white" />
            </div>
            <span className="font-bold text-lg">MLOps</span>
          </Link>
        )}
        <button
          onClick={useAppStore.getState().toggleSidebar}
          className="p-1.5 rounded-lg hover:bg-gray-100 transition-colors"
        >
          {sidebarOpen ? <ChevronLeft className="w-5 h-5" /> : <ChevronRight className="w-5 h-5" />}
        </button>
      </div>

      <nav className="p-2 space-y-1">
        {navigation.map((item) => {
          const isActive = pathname === item.href;
          return (
            <Link
              key={item.name}
              href={item.href}
              onClick={() => setCurrentPage(item.page)}
              className={cn(
                'flex items-center gap-3 px-3 py-2.5 rounded-lg transition-all',
                isActive
                  ? 'bg-primary-50 text-primary-600'
                  : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
              )}
              title={item.name}
            >
              <item.icon className="w-5 h-5 flex-shrink-0" />
              {sidebarOpen && <span className="font-medium">{item.name}</span>}
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}
