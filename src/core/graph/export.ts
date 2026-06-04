import type { GraphData, GraphNode, GraphLink } from './parser';

export async function exportGraphAsPNG(
  svgElement: SVGSVGElement,
  options?: {
    width?: number;
    height?: number;
    scale?: number;
    backgroundColor?: string;
  }
): Promise<string> {
  const {
    width = svgElement.clientWidth,
    height = svgElement.clientHeight,
    scale = 2,
    backgroundColor = '#0F172A',
  } = options || {};

  const clonedSvg = svgElement.cloneNode(true) as SVGSVGElement;
  clonedSvg.setAttribute('width', String(width * scale));
  clonedSvg.setAttribute('height', String(height * scale));
  clonedSvg.setAttribute('xmlns', 'http://www.w3.org/2000/svg');

  const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
  const style = document.createElementNS('http://www.w3.org/2000/svg', 'style');
  style.textContent = `
    * { font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif; }
  `;
  defs.appendChild(style);
  clonedSvg.insertBefore(defs, clonedSvg.firstChild);

  const bgRect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
  bgRect.setAttribute('width', '100%');
  bgRect.setAttribute('height', '100%');
  bgRect.setAttribute('fill', backgroundColor);
  clonedSvg.insertBefore(bgRect, clonedSvg.firstChild?.nextSibling || null);

  const serializer = new XMLSerializer();
  let svgString = serializer.serializeToString(clonedSvg);
  svgString = '<?xml version="1.0" encoding="UTF-8"?>' + svgString;

  const svgBlob = new Blob([svgString], { type: 'image/svg+xml;charset=utf-8' });
  const url = URL.createObjectURL(svgBlob);

  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    
    img.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = width * scale;
      canvas.height = height * scale;
      
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        reject(new Error('Failed to get canvas context'));
        return;
      }
      
      ctx.fillStyle = backgroundColor;
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      
      try {
        const dataUrl = canvas.toDataURL('image/png');
        resolve(dataUrl);
      } catch (e) {
        reject(e);
      } finally {
        URL.revokeObjectURL(url);
      }
    };
    
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('Failed to load SVG image'));
    };
    
    img.src = url;
  });
}

export async function downloadPNG(
  svgElement: SVGSVGElement,
  filename: string = 'knowledge-graph.png',
  options?: {
    width?: number;
    height?: number;
    scale?: number;
    backgroundColor?: string;
  }
): Promise<void> {
  const dataUrl = await exportGraphAsPNG(svgElement, options);
  
  const link = document.createElement('a');
  link.download = filename;
  link.href = dataUrl;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

export function exportGraphAsJSON(graph: GraphData): string {
  return JSON.stringify(graph, null, 2);
}

export function exportGraphAsCSV(graph: GraphData): { nodes: string; links: string } {
  const nodeHeaders = ['id', 'type', 'label', 'path', 'tags'];
  const nodeRows = graph.nodes.map(n => [
    n.id,
    n.type,
    `"${n.label.replace(/"/g, '""')}"`,
    n.path || '',
    n.tags ? `"${n.tags.join(', ')}"` : '',
  ].join(','));
  
  const linkHeaders = ['source', 'target', 'type', 'value'];
  const linkRows = graph.links.map(l => {
    const sourceId = typeof l.source === 'string' ? l.source : l.source.id;
    const targetId = typeof l.target === 'string' ? l.target : l.target.id;
    return [sourceId, targetId, l.type, l.value || 1].join(',');
  });
  
  return {
    nodes: [nodeHeaders.join(','), ...nodeRows].join('\n'),
    links: [linkHeaders.join(','), ...linkRows].join('\n'),
  };
}

export function exportGraphAsGraphML(graph: GraphData): string {
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
    '  <graph id="G" edgedefault="undirected">',
    '    <key id="d0" for="node" attr.name="type" attr.type="string"/>',
    '    <key id="d1" for="node" attr.name="label" attr.type="string"/>',
    '    <key id="d2" for="node" attr.name="path" attr.type="string"/>',
    '    <key id="d3" for="node" attr.name="tags" attr.type="string"/>',
    '    <key id="d4" for="edge" attr.name="type" attr.type="string"/>',
    '    <key id="d5" for="edge" attr.name="value" attr.type="double"/>',
  ];
  
  for (const node of graph.nodes) {
    lines.push(`    <node id="${node.id}">`);
    lines.push(`      <data key="d0">${node.type}</data>`);
    lines.push(`      <data key="d1"><![CDATA[${node.label}]]></data>`);
    if (node.path) lines.push(`      <data key="d2">${node.path}</data>`);
    if (node.tags?.length) lines.push(`      <data key="d3">${node.tags.join(',')}</data>`);
    lines.push('    </node>');
  }
  
  let edgeId = 0;
  for (const link of graph.links) {
    const sourceId = typeof link.source === 'string' ? link.source : link.source.id;
    const targetId = typeof link.target === 'string' ? link.target : link.target.id;
    lines.push(`    <edge id="e${edgeId++}" source="${sourceId}" target="${targetId}">`);
    lines.push(`      <data key="d4">${link.type}</data>`);
    lines.push(`      <data key="d5">${link.value || 1}</data>`);
    lines.push('    </edge>');
  }
  
  lines.push('  </graph>');
  lines.push('</graphml>');
  
  return lines.join('\n');
}

export function downloadData(data: string, filename: string, mimeType: string = 'text/plain'): void {
  const blob = new Blob([data], { type: mimeType });
  const url = URL.createObjectURL(blob);
  
  const link = document.createElement('a');
  link.download = filename;
  link.href = url;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  
  URL.revokeObjectURL(url);
}
