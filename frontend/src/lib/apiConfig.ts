import type { APIConfig } from "@/types";

const STORAGE_KEY = "ai-pkss-api-configs";
const ACTIVE_KEY = "ai-pkss-active-api";

export const DEFAULT_API_CONFIG: Omit<APIConfig, "id" | "createdAt"> = {
  name: "OpenAI 默认",
  apiKey: "",
  baseUrl: "https://api.openai.com/v1",
  model: "gpt-3.5-turbo",
  embeddingModel: "text-embedding-3-small",
};

function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).substr(2);
}

export function getAllAPIConfigs(): APIConfig[] {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) {
      return JSON.parse(stored);
    }
  } catch (e) {
    console.error("Failed to load API configs:", e);
  }
  
  const defaultConfig: APIConfig = {
    ...DEFAULT_API_CONFIG,
    id: generateId(),
    createdAt: Date.now(),
  };
  
  saveAPIConfigs([defaultConfig]);
  setActiveAPI(defaultConfig.id);
  
  return [defaultConfig];
}

export function saveAPIConfigs(configs: APIConfig[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(configs));
  } catch (e) {
    console.error("Failed to save API configs:", e);
  }
}

export function addAPIConfig(config: Omit<APIConfig, "id" | "createdAt">): APIConfig {
  const newConfig: APIConfig = {
    ...config,
    id: generateId(),
    createdAt: Date.now(),
  };
  
  const configs = getAllAPIConfigs();
  configs.push(newConfig);
  saveAPIConfigs(configs);
  
  return newConfig;
}

export function updateAPIConfig(id: string, updates: Partial<Omit<APIConfig, "id" | "createdAt">>): APIConfig | null {
  const configs = getAllAPIConfigs();
  const index = configs.findIndex((c) => c.id === id);
  
  if (index === -1) return null;
  
  configs[index] = { ...configs[index], ...updates };
  saveAPIConfigs(configs);
  
  return configs[index];
}

export function deleteAPIConfig(id: string): boolean {
  const configs = getAllAPIConfigs();
  
  if (configs.length <= 1) {
    console.warn("Cannot delete the last API config");
    return false;
  }
  
  const newConfigs = configs.filter((c) => c.id !== id);
  saveAPIConfigs(newConfigs);
  
  const activeId = getActiveAPIId();
  if (activeId === id) {
    setActiveAPI(newConfigs[0].id);
  }
  
  return true;
}

export function getActiveAPIId(): string | null {
  try {
    return localStorage.getItem(ACTIVE_KEY);
  } catch (e) {
    console.error("Failed to get active API ID:", e);
    return null;
  }
}

export function getActiveAPIConfig(): APIConfig {
  const configs = getAllAPIConfigs();
  const activeId = getActiveAPIId();
  
  const activeConfig = configs.find((c) => c.id === activeId);
  if (activeConfig) {
    return activeConfig;
  }
  
  if (configs.length > 0) {
    setActiveAPI(configs[0].id);
    return configs[0];
  }
  
  const defaultConfig: APIConfig = {
    ...DEFAULT_API_CONFIG,
    id: generateId(),
    createdAt: Date.now(),
  };
  
  saveAPIConfigs([defaultConfig]);
  setActiveAPI(defaultConfig.id);
  
  return defaultConfig;
}

export function setActiveAPI(id: string): void {
  try {
    localStorage.setItem(ACTIVE_KEY, id);
  } catch (e) {
    console.error("Failed to set active API:", e);
  }
}
