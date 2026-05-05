import React, { createContext, useContext, useState } from 'react';

const AppContext = createContext();

export const AppProvider = ({ children }) => {
  const [currentUser, setCurrentUser] = useState(() => {
    return localStorage.getItem('userId') || 'user_001';
  });
  
  const [currentDoc, setCurrentDoc] = useState(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [loading, setLoading] = useState(false);
  const [notification, setNotification] = useState(null);

  const showNotification = (type, message, description = '') => {
    setNotification({ type, message, description });
    setTimeout(() => setNotification(null), 3000);
  };

  const loginAs = (userId) => {
    setCurrentUser(userId);
    localStorage.setItem('userId', userId);
    showNotification('success', '登录成功', `当前用户: ${userId}`);
  };

  const value = {
    currentUser,
    currentDoc,
    setCurrentDoc,
    sidebarCollapsed,
    setSidebarCollapsed,
    loading,
    setLoading,
    notification,
    showNotification,
    loginAs
  };

  return (
    <AppContext.Provider value={value}>
      {children}
    </AppContext.Provider>
  );
};

export const useApp = () => {
  const context = useContext(AppContext);
  if (!context) {
    throw new Error('useApp must be used within AppProvider');
  }
  return context;
};
