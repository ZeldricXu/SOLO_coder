import { getDatabase } from './index';
import type { NoteLink, GraphData, GraphNode, GraphEdge } from '../../shared/types';
import { randomUUID } from 'crypto';

export const LinkService = {
  getAll(): NoteLink[] {
    const db = getDatabase();
    const rows = db.prepare('SELECT * FROM links').all();
    return rows.map(mapRowToLink);
  },

  getBacklinks(noteId: string): NoteLink[] {
    const db = getDatabase();
    const rows = db.prepare(
      'SELECT * FROM links WHERE target_id = ?'
    ).all(noteId);
    return rows.map(mapRowToLink);
  },

  getForwardLinks(noteId: string): NoteLink[] {
    const db = getDatabase();
    const rows = db.prepare(
      'SELECT * FROM links WHERE source_id = ?'
    ).all(noteId);
    return rows.map(mapRowToLink);
  },

  addLink(link: Omit<NoteLink, 'id' | 'createdAt'>): NoteLink {
    const db = getDatabase();
    const id = randomUUID();
    const now = Date.now();
    
    db.prepare(`
      INSERT INTO links (id, source_id, target_id, source_path, target_path, link_text, context, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run(id, link.sourceId || null, link.targetId || null, link.sourcePath, link.targetPath, link.linkText, link.context || null, now);
    
    return {
      id,
      sourceId: link.sourceId || '',
      targetId: link.targetId || '',
      sourcePath: link.sourcePath,
      targetPath: link.targetPath,
      linkText: link.linkText,
      context: link.context || '',
      createdAt: now,
    };
  },

  clearLinksForNote(noteId: string): void {
    const db = getDatabase();
    db.prepare('DELETE FROM links WHERE source_id = ?').run(noteId);
  },

  clearLinksBySourcePath(sourcePath: string): void {
    const db = getDatabase();
    db.prepare('DELETE FROM links WHERE source_path = ?').run(sourcePath);
  },

  updateTargetIdsForPath(targetPath: string, targetId: string): void {
    const db = getDatabase();
    db.prepare('UPDATE links SET target_id = ? WHERE target_path = ?').run(targetId, targetPath);
  },

  getGraphData(): GraphData {
    const db = getDatabase();
    
    const notes = db.prepare('SELECT id, title, path, tags FROM notes').all() as any[];
    const links = db.prepare(`
      SELECT source_id, target_id, source_path, target_path, COUNT(*) as weight
      FROM links 
      WHERE source_id IS NOT NULL AND target_id IS NOT NULL
      GROUP BY source_id, target_id
    `).all() as any[];
    
    const nodes: GraphNode[] = notes.map(note => ({
      id: note.id,
      label: note.title,
      path: note.path,
      tags: JSON.parse(note.tags || '[]'),
      size: 10,
      cluster: this.determineCluster(note, links),
    }));
    
    const edges: GraphEdge[] = links
      .filter(link => link.source_id && link.target_id)
      .map((link, index) => ({
        id: `edge-${index}`,
        source: link.source_id,
        target: link.target_id,
        weight: link.weight || 1,
      }));
    
    const nodeIds = new Set(nodes.map(n => n.id));
    const filteredEdges = edges.filter(e => nodeIds.has(e.source) && nodeIds.has(e.target));
    
    nodes.forEach(node => {
      const degree = filteredEdges.filter(
        e => e.source === node.id || e.target === node.id
      ).length;
      node.size = Math.max(8, Math.min(30, 10 + degree * 1.5));
    });
    
    return { nodes, edges: filteredEdges };
  },

  determineCluster(note: any, links: any[]): string {
    const tags: string[] = JSON.parse(note.tags || '[]');
    if (tags.length > 0) {
      return tags[0];
    }
    
    const outgoingLinks = links.filter(l => l.source_id === note.id);
    if (outgoingLinks.length > 3) {
      return 'hub';
    }
    
    return 'default';
  },
};

function mapRowToLink(row: any): NoteLink {
  return {
    id: row.id,
    sourceId: row.source_id || '',
    targetId: row.target_id || '',
    sourcePath: row.source_path,
    targetPath: row.target_path,
    linkText: row.link_text,
    context: row.context || '',
    createdAt: row.created_at,
  };
}
