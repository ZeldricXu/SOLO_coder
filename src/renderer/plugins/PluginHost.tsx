import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import type { PluginDefinition, PluginCommand, SidebarWidget, PluginContext } from '@shared/types';
import { useAppStore } from '../stores/appStore';

interface PluginRegistry {
  plugins: Map<string, PluginDefinition>;
  renderers: Map<string, any>;
  commands: PluginCommand[];
  sidebarWidgets: SidebarWidget[];
}

interface PluginHostContextType {
  registry: PluginRegistry;
  activatePlugin: (plugin: PluginDefinition) => void;
  deactivatePlugin: (pluginId: string) => void;
  executeCommand: (commandId: string) => void | Promise<void>;
  getCommands: () => PluginCommand[];
  getSidebarWidgets: () => SidebarWidget[];
  getRenderer: (type: string) => any;
}

const PluginHostContext = createContext<PluginHostContextType | null>(null);

export const PluginHostProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [registry, setRegistry] = useState<PluginRegistry>({
    plugins: new Map(),
    renderers: new Map(),
    commands: [],
    sidebarWidgets: [],
  });
  
  const settings = useAppStore(state => state.settings);
  
  const registerRenderer = useCallback((type: string, renderer: any) => {
    setRegistry(prev => ({
      ...prev,
      renderers: new Map(prev.renderers).set(type, renderer),
    }));
  }, []);
  
  const registerCommand = useCallback((command: PluginCommand) => {
    setRegistry(prev => ({
      ...prev,
      commands: [...prev.commands, command],
    }));
  }, []);
  
  const registerSidebarWidget = useCallback((widget: SidebarWidget) => {
    setRegistry(prev => ({
      ...prev,
      sidebarWidgets: [...prev.sidebarWidgets, widget],
    }));
  }, []);
  
  const activatePlugin = useCallback((plugin: PluginDefinition) => {
    const context: PluginContext = {
      registerRenderer,
      registerCommand,
      registerSidebarWidget,
      store: useAppStore,
      ipc: window.api,
    };
    
    try {
      plugin.activate(context);
      setRegistry(prev => ({
        ...prev,
        plugins: new Map(prev.plugins).set(plugin.id, plugin),
      }));
    } catch (err) {
      console.error(`Failed to activate plugin ${plugin.id}:`, err);
    }
  }, [registerRenderer, registerCommand, registerSidebarWidget]);
  
  const deactivatePlugin = useCallback((pluginId: string) => {
    setRegistry(prev => {
      const plugin = prev.plugins.get(pluginId);
      if (plugin?.deactivate) {
        try {
          plugin.deactivate();
        } catch (err) {
          console.error(`Failed to deactivate plugin ${pluginId}:`, err);
        }
      }
      
      const newPlugins = new Map(prev.plugins);
      newPlugins.delete(pluginId);
      
      return {
        ...prev,
        plugins: newPlugins,
      };
    });
  }, []);
  
  const executeCommand = useCallback((commandId: string) => {
    const command = registry.commands.find(c => c.id === commandId);
    if (command) {
      return command.execute();
    }
  }, [registry.commands]);
  
  const getCommands = useCallback(() => registry.commands, [registry.commands]);
  const getSidebarWidgets = useCallback(() => registry.sidebarWidgets, [registry.sidebarWidgets]);
  const getRenderer = useCallback((type: string) => registry.renderers.get(type), [registry.renderers]);
  
  const value: PluginHostContextType = {
    registry,
    activatePlugin,
    deactivatePlugin,
    executeCommand,
    getCommands,
    getSidebarWidgets,
    getRenderer,
  };
  
  return (
    <PluginHostContext.Provider value={value}>
      {children}
    </PluginHostContext.Provider>
  );
};

export function usePluginHost() {
  const context = useContext(PluginHostContext);
  if (!context) {
    throw new Error('usePluginHost must be used within PluginHostProvider');
  }
  return context;
}
