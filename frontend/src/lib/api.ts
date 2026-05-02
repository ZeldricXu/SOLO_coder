import axios from "axios";
import type { 
  Document, 
  UploadResponse, 
  QueryResponse, 
  CollectionInfo, 
  SourceNode
} from "@/types";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8000";

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    "Content-Type": "application/json",
  },
});

export const documentApi = {
  upload: async (
    file: File, 
    collectionName: string = "default",
    onProgress?: (progress: number) => void
  ): Promise<UploadResponse> => {
    const formData = new FormData();
    formData.append("file", file);
    
    const response = await api.post<UploadResponse>(
      `/api/v1/documents/upload?collection_name=${encodeURIComponent(collectionName)}`,
      formData,
      {
        headers: {
          "Content-Type": "multipart/form-data",
        },
        onUploadProgress: (progressEvent) => {
          if (onProgress && progressEvent.total) {
            const progress = Math.round((progressEvent.loaded * 100) / progressEvent.total);
            onProgress(progress);
          }
        },
      }
    );
    return response.data;
  },

  list: async (collectionName?: string): Promise<Document[]> => {
    const url = collectionName
      ? `/api/v1/documents/?collection_name=${encodeURIComponent(collectionName)}`
      : "/api/v1/documents/";
    
    const response = await api.get<Document[]>(url);
    return response.data;
  },

  get: async (id: string): Promise<Document> => {
    const response = await api.get<Document>(`/api/v1/documents/${id}`);
    return response.data;
  },

  delete: async (id: string): Promise<void> => {
    await api.delete(`/api/v1/documents/${id}`);
  },
};

export const queryApi = {
  query: async (
    query: string,
    knowledgeBaseId: string = "default",
    topK: number = 5
  ): Promise<QueryResponse> => {
    const response = await api.post<QueryResponse>("/api/v1/query/", {
      query,
      knowledge_base_id: knowledgeBaseId,
      top_k: topK,
      stream: false,
    });
    return response.data;
  },

  search: async (
    query: string,
    knowledgeBaseId: string = "default",
    topK: number = 5
  ): Promise<{ results: SourceNode[] }> => {
    const response = await api.post<{ results: SourceNode[] }>("/api/v1/query/search", {
      query,
      knowledge_base_id: knowledgeBaseId,
      top_k: topK,
      stream: false,
    });
    return response.data;
  },

  queryStream: async function* (
    query: string,
    knowledgeBaseId: string = "default",
    topK: number = 5
  ): AsyncGenerator<{ type: string; content: string | any[]; index?: number }> {
    const response = await fetch(`${API_BASE_URL}/api/v1/query/`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        query,
        knowledge_base_id: knowledgeBaseId,
        top_k: topK,
        stream: true,
      }),
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const reader = response.body?.getReader();
    if (!reader) {
      throw new Error("No response body");
    }

    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n\n");
      buffer = lines.pop() || "";

      for (const line of lines) {
        if (line.startsWith("data: ")) {
          const data = line.slice(6);
          if (data === "[DONE]") {
            return;
          }
          try {
            const parsed = JSON.parse(data);
            if (parsed.token !== undefined) {
              yield { type: "token", content: parsed.token, index: parsed.index };
            } else if (parsed.answer !== undefined) {
              yield { type: "answer", content: parsed.answer };
            } else if (parsed.sources !== undefined) {
              yield { type: "sources", content: parsed.sources };
            }
          } catch (e) {
            console.error("Failed to parse SSE data:", e);
          }
        }
      }
    }
  },
};

export const collectionApi = {
  list: async (): Promise<CollectionInfo[]> => {
    const response = await api.get<CollectionInfo[]>("/api/v1/documents/collections/info");
    return response.data;
  },

  create: async (name: string): Promise<{ message: string; name: string }> => {
    const response = await api.post(`/api/v1/documents/collections/create?name=${encodeURIComponent(name)}`);
    return response.data;
  },

  rename: async (oldName: string, newName: string): Promise<{ message: string; old_name: string; new_name: string }> => {
    const response = await api.post(
      `/api/v1/documents/collections/rename?old_name=${encodeURIComponent(oldName)}&new_name=${encodeURIComponent(newName)}`
    );
    return response.data;
  },

  delete: async (name: string): Promise<{ message: string; name: string }> => {
    const response = await api.delete(`/api/v1/documents/collections/${encodeURIComponent(name)}`);
    return response.data;
  },
};

export interface APIConfigResponse {
  id: string;
  name: string;
  base_url: string;
  model: string;
  embedding_model: string;
  is_active: boolean;
  created_at: string;
  updated_at: string;
}

export interface APIConfigCreate {
  name: string;
  api_key: string;
  base_url?: string;
  model?: string;
  embedding_model?: string;
}

export interface APIConfigUpdate {
  name?: string;
  api_key?: string;
  base_url?: string;
  model?: string;
  embedding_model?: string;
}

export const apiConfigApi = {
  list: async (): Promise<APIConfigResponse[]> => {
    const response = await api.get<APIConfigResponse[]>("/api/v1/api-config/");
    return response.data;
  },

  getActive: async (): Promise<APIConfigResponse> => {
    const response = await api.get<APIConfigResponse>("/api/v1/api-config/active");
    return response.data;
  },

  create: async (data: APIConfigCreate): Promise<APIConfigResponse> => {
    const response = await api.post<APIConfigResponse>("/api/v1/api-config/", {
      name: data.name,
      api_key: data.api_key,
      base_url: data.base_url || "https://api.openai.com/v1",
      model: data.model || "gpt-3.5-turbo",
      embedding_model: data.embedding_model || "text-embedding-3-small",
    });
    return response.data;
  },

  update: async (id: string, data: APIConfigUpdate): Promise<APIConfigResponse> => {
    const response = await api.put<APIConfigResponse>(`/api/v1/api-config/${id}`, data);
    return response.data;
  },

  activate: async (id: string): Promise<{ message: string; config_id: string }> => {
    const response = await api.post(`/api/v1/api-config/${id}/activate`);
    return response.data;
  },

  delete: async (id: string): Promise<{ message: string; config_id: string }> => {
    const response = await api.delete(`/api/v1/api-config/${id}`);
    return response.data;
  },
};
