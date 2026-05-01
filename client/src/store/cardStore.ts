import { create } from "zustand";
import { KnowledgeCard, SemanticSuggestion, GraphData } from "@/types";
import { cardApi, graphApi } from "@/lib/api";

interface CardStore {
  cards: KnowledgeCard[];
  selectedCard: KnowledgeCard | null;
  suggestions: SemanticSuggestion[];
  graphData: GraphData | null;
  isLoading: boolean;
  error: string | null;
  
  fetchAllCards: () => Promise<void>;
  fetchCardById: (id: string) => Promise<void>;
  createCard: (data: { content: string; source_url?: string; tags?: string[] }) => Promise<KnowledgeCard>;
  deleteCard: (id: string) => Promise<void>;
  selectCard: (card: KnowledgeCard | null) => void;
  
  getSuggestions: (text: string) => Promise<void>;
  clearSuggestions: () => void;
  
  fetchGraphData: (centerId?: string) => Promise<void>;
  clearGraphData: () => void;
  
  setError: (error: string | null) => void;
}

export const useCardStore = create<CardStore>((set, get) => ({
  cards: [],
  selectedCard: null,
  suggestions: [],
  graphData: null,
  isLoading: false,
  error: null,

  fetchAllCards: async () => {
    set({ isLoading: true, error: null });
    try {
      const cards = await cardApi.getAll();
      set({ cards, isLoading: false });
    } catch (err: any) {
      set({ error: err.message || "获取卡片列表失败", isLoading: false });
    }
  },

  fetchCardById: async (id: string) => {
    set({ isLoading: true, error: null });
    try {
      const card = await cardApi.getById(id);
      set({ selectedCard: card, isLoading: false });
    } catch (err: any) {
      set({ error: err.message || "获取卡片失败", isLoading: false });
    }
  },

  createCard: async (data) => {
    set({ isLoading: true, error: null });
    try {
      const newCard = await cardApi.ingest(data);
      const currentCards = get().cards;
      set({ 
        cards: [...currentCards, newCard], 
        isLoading: false,
        selectedCard: newCard
      });
      return newCard;
    } catch (err: any) {
      set({ error: err.message || "创建卡片失败", isLoading: false });
      throw err;
    }
  },

  deleteCard: async (id: string) => {
    set({ isLoading: true, error: null });
    try {
      await cardApi.deleteCard(id);
      
      const currentCards = get().cards;
      const currentSelected = get().selectedCard;
      
      set({
        cards: currentCards.filter(c => c.id !== id),
        selectedCard: currentSelected?.id === id ? null : currentSelected,
        isLoading: false,
      });
    } catch (err: any) {
      set({ error: err.message || "删除卡片失败", isLoading: false });
    }
  },

  selectCard: (card) => {
    set({ selectedCard: card });
  },

  getSuggestions: async (text: string) => {
    if (!text.trim()) {
      set({ suggestions: [] });
      return;
    }
    
    try {
      const suggestions = await cardApi.suggest(text);
      set({ suggestions });
    } catch (err: any) {
      console.error("获取建议失败:", err);
      set({ suggestions: [] });
    }
  },

  clearSuggestions: () => {
    set({ suggestions: [] });
  },

  fetchGraphData: async (centerId?: string) => {
    set({ isLoading: true, error: null });
    try {
      const data = await graphApi.getSubgraph(centerId);
      set({ graphData: data, isLoading: false });
    } catch (err: any) {
      set({ error: err.message || "获取图谱数据失败", isLoading: false });
    }
  },

  clearGraphData: () => {
    set({ graphData: null });
  },

  setError: (error) => {
    set({ error });
  },
}));
