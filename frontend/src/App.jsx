import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/Layout/AppLayout';
import DocumentsPage from './pages/DocumentsPage';
import DocumentEditPage from './pages/DocumentEditPage';
import SearchPage from './pages/SearchPage';
import CategoriesPage from './pages/CategoriesPage';
import FavoritesPage from './pages/FavoritesPage';
import RecentPage from './pages/RecentPage';
import VersionPage from './pages/VersionPage';
import SharePage from './pages/SharePage';

function App() {
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<Navigate to="/documents" replace />} />
        <Route path="/documents" element={<DocumentsPage />} />
        <Route path="/documents/:docId" element={<DocumentEditPage />} />
        <Route path="/edit/:docId" element={<DocumentEditPage />} />
        <Route path="/search" element={<SearchPage />} />
        <Route path="/categories" element={<CategoriesPage />} />
        <Route path="/favorites" element={<FavoritesPage />} />
        <Route path="/recent" element={<RecentPage />} />
        <Route path="/versions/:docId" element={<VersionPage />} />
        <Route path="/share/:docId" element={<SharePage />} />
      </Routes>
    </AppLayout>
  );
}

export default App;
