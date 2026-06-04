import React, { useState } from 'react';
import { Sidebar } from '../components/sidebar/Sidebar';
import { Header } from '../components/layout/Header';
import { useTheme } from '../contexts/ThemeContext';
import { useAppStore } from '../stores/appStore';

export const MainLayout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { resolvedTheme } = useTheme();
  const sidebarCollapsed = useAppStore((state) => state.sidebarCollapsed);

  return (
    <div
      className={`h-full flex flex-col ${resolvedTheme}`}
      style={{ backgroundColor: 'var(--background-color)', color: 'var(--foreground-color)' }}
    >
      <Header />
      <div className="flex-1 flex overflow-hidden">
        <Sidebar />
        <main
          className={`flex-1 overflow-hidden transition-all duration-300`}
          style={{
            marginLeft: sidebarCollapsed ? '48px' : '256px',
          }}
        >
          {children}
        </main>
      </div>
    </div>
  );
};
