export interface RelatedNode {
  target_id: string;
  relation_type: "contradicts" | "supports" | "explains";
  reason: string;
}

export interface KnowledgeCard {
  id: string;
  content: string;
  source_url?: string;
  tags: string[];
  vector_embedding?: number[];
  related_nodes: RelatedNode[];
  created_at?: string;
  updated_at?: string;
}

export interface SemanticSuggestion {
  card: KnowledgeCard;
  similarity: number;
  reason: string;
}

export interface GraphNode {
  id: string;
  label: string;
  color?: string;
  size?: number;
}

export interface GraphLink {
  source: string;
  target: string;
  relation: string;
}

export interface GraphData {
  nodes: GraphNode[];
  links: GraphLink[];
}
