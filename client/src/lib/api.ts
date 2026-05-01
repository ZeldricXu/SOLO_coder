import axios from "axios";
import { KnowledgeCard, SemanticSuggestion, GraphData } from "@/types";

const apiClient = axios.create({
  baseURL: "/api/v1",
  headers: {
    "Content-Type": "application/json",
  },
});

export const cardApi = {
  ingest: async (data: { content: string; source_url?: string; tags?: string[] }) => {
    const response = await apiClient.post<KnowledgeCard>("/cards/ingest", data);
    return response.data;
  },

  suggest: async (text: string) => {
    const response = await apiClient.post<SemanticSuggestion[]>("/cards/suggest", { text });
    return response.data;
  },

  getById: async (id: string) => {
    const response = await apiClient.get<KnowledgeCard>(`/cards/${id}`);
    return response.data;
  },

  getAll: async () => {
    const response = await apiClient.get<KnowledgeCard[]>("/cards");
    return response.data;
  },
};

export const graphApi = {
  getSubgraph: async (centerId?: string) => {
    const params = centerId ? { center_id: centerId } : {};
    const response = await apiClient.get<GraphData>("/graph/subgraph", { params });
    return response.data;
  },
};

export default apiClient;
