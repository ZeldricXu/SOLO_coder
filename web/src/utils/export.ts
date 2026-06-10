import jsPDF from 'jspdf';
import type { Stroke, Shape, ExportOptions, BoundingBox } from '../types';

function computeContentBounds(strokes: Stroke[], shapes: Shape[]): BoundingBox {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;

  for (const stroke of strokes) {
    if (stroke.bounds) {
      minX = Math.min(minX, stroke.bounds.minX);
      minY = Math.min(minY, stroke.bounds.minY);
      maxX = Math.max(maxX, stroke.bounds.maxX);
      maxY = Math.max(maxY, stroke.bounds.maxY);
    } else {
      for (const point of stroke.points) {
        minX = Math.min(minX, point.x);
        minY = Math.min(minY, point.y);
        maxX = Math.max(maxX, point.x);
        maxY = Math.max(maxY, point.y);
      }
    }
  }

  for (const shape of shapes) {
    minX = Math.min(minX, shape.x);
    minY = Math.min(minY, shape.y);
    maxX = Math.max(maxX, shape.x + shape.width);
    maxY = Math.max(maxY, shape.y + shape.height);
  }

  if (!isFinite(minX)) {
    minX = 0;
    minY = 0;
    maxX = 800;
    maxY = 600;
  }

  const padding = 20;
  return {
    minX: minX - padding,
    minY: minY - padding,
    maxX: maxX + padding,
    maxY: maxY + padding,
  };
}

function renderToCanvas(
  strokes: Stroke[],
  shapes: Shape[],
  options: ExportOptions
): HTMLCanvasElement {
  const bounds = computeContentBounds(strokes, shapes);
  const width = Math.ceil(bounds.maxX - bounds.minX);
  const height = Math.ceil(bounds.maxY - bounds.minY);
  const scale = options.scale ?? 2;

  const canvas = document.createElement('canvas');
  canvas.width = width * scale;
  canvas.height = height * scale;

  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Failed to get canvas context');

  ctx.scale(scale, scale);

  if (options.includeBackground && options.background) {
    ctx.fillStyle = options.background;
    ctx.fillRect(0, 0, width, height);
  }

  ctx.translate(-bounds.minX, -bounds.minY);

  for (const stroke of strokes) {
    if (stroke.points.length < 2) continue;

    ctx.save();
    ctx.strokeStyle = stroke.style.color;
    ctx.lineWidth = stroke.style.width;
    ctx.globalAlpha = stroke.style.opacity;
    ctx.lineCap = stroke.style.cap || 'round';
    ctx.lineJoin = stroke.style.join || 'round';

    if (stroke.style.dashPattern) {
      ctx.setLineDash(stroke.style.dashPattern);
    }

    ctx.beginPath();
    ctx.moveTo(stroke.points[0].x, stroke.points[0].y);

    for (let i = 1; i < stroke.points.length; i++) {
      ctx.lineTo(stroke.points[i].x, stroke.points[i].y);
    }

    ctx.stroke();
    ctx.restore();
  }

  for (const shape of shapes) {
    ctx.save();
    ctx.globalAlpha = shape.style.opacity ?? 1;

    if (shape.rotation) {
      const centerX = shape.x + shape.width / 2;
      const centerY = shape.y + shape.height / 2;
      ctx.translate(centerX, centerY);
      ctx.rotate((shape.rotation * Math.PI) / 180);
      ctx.translate(-centerX, -centerY);
    }

    if (shape.style.fill && shape.style.fill !== 'transparent') {
      ctx.fillStyle = shape.style.fill;
    }
    if (shape.style.stroke) {
      ctx.strokeStyle = shape.style.stroke;
      ctx.lineWidth = shape.style.strokeWidth ?? 2;
    }

    ctx.beginPath();

    switch (shape.type) {
      case 'rectangle':
        ctx.rect(shape.x, shape.y, shape.width, shape.height);
        break;
      case 'ellipse':
        ctx.ellipse(
          shape.x + shape.width / 2,
          shape.y + shape.height / 2,
          Math.abs(shape.width) / 2,
          Math.abs(shape.height) / 2,
          0,
          0,
          Math.PI * 2
        );
        break;
      case 'line':
      case 'arrow':
        ctx.moveTo(shape.x, shape.y);
        ctx.lineTo(shape.x + shape.width, shape.y + shape.height);
        break;
      case 'triangle':
        ctx.moveTo(shape.x + shape.width / 2, shape.y);
        ctx.lineTo(shape.x + shape.width, shape.y + shape.height);
        ctx.lineTo(shape.x, shape.y + shape.height);
        ctx.closePath();
        break;
      case 'polygon':
        if (shape.points && shape.points.length > 0) {
          ctx.moveTo(shape.points[0].x, shape.points[0].y);
          for (let i = 1; i < shape.points.length; i++) {
            ctx.lineTo(shape.points[i].x, shape.points[i].y);
          }
          ctx.closePath();
        }
        break;
    }

    if (shape.style.fill && shape.style.fill !== 'transparent') {
      ctx.fill();
    }
    if (shape.style.stroke) {
      ctx.stroke();
    }

    ctx.restore();
  }

  return canvas;
}

export async function exportAsPNG(
  strokes: Stroke[],
  shapes: Shape[],
  options: ExportOptions
): Promise<void> {
  const canvas = renderToCanvas(strokes, shapes, options);
  const quality = options.quality ?? 0.92;

  const dataUrl = canvas.toDataURL('image/png', quality);

  const link = document.createElement('a');
  link.download = `whiteboard-${Date.now()}.png`;
  link.href = dataUrl;
  link.click();
}

export async function exportAsSVG(
  strokes: Stroke[],
  shapes: Shape[],
  options: ExportOptions
): Promise<void> {
  const bounds = computeContentBounds(strokes, shapes);
  const width = Math.ceil(bounds.maxX - bounds.minX);
  const height = Math.ceil(bounds.maxY - bounds.minY);

  let svgContent = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">`;

  if (options.includeBackground && options.background) {
    svgContent += `<rect width="100%" height="100%" fill="${options.background}"/>`;
  }

  const offsetX = -bounds.minX;
  const offsetY = -bounds.minY;

  for (const stroke of strokes) {
    if (stroke.points.length < 2) continue;

    let pathData = '';
    stroke.points.forEach((point, index) => {
      const x = point.x + offsetX;
      const y = point.y + offsetY;
      pathData += index === 0 ? `M ${x} ${y} ` : `L ${x} ${y} `;
    });

    const dashArray = stroke.style.dashPattern
      ? `stroke-dasharray="${stroke.style.dashPattern.join(',')}"`
      : '';

    svgContent += `<path d="${pathData.trim()}" fill="none" stroke="${stroke.style.color}" stroke-width="${stroke.style.width}" stroke-opacity="${stroke.style.opacity}" stroke-linecap="${stroke.style.cap || 'round'}" stroke-linejoin="${stroke.style.join || 'round'}" ${dashArray}/>`;
  }

  for (const shape of shapes) {
    const x = shape.x + offsetX;
    const y = shape.y + offsetY;
    const opacity = shape.style.opacity ?? 1;

    let element = '';
    switch (shape.type) {
      case 'rectangle':
        element = `<rect x="${x}" y="${y}" width="${shape.width}" height="${shape.height}" opacity="${opacity}"`;
        break;
      case 'ellipse':
        element = `<ellipse cx="${x + shape.width / 2}" cy="${y + shape.height / 2}" rx="${Math.abs(shape.width) / 2}" ry="${Math.abs(shape.height) / 2}" opacity="${opacity}"`;
        break;
      case 'line':
      case 'arrow':
        element = `<line x1="${x}" y1="${y}" x2="${x + shape.width}" y2="${y + shape.height}" opacity="${opacity}"`;
        break;
      case 'triangle':
        const points = `${x + shape.width / 2},${y} ${x + shape.width},${y + shape.height} ${x},${y + shape.height}`;
        element = `<polygon points="${points}" opacity="${opacity}"`;
        break;
      case 'polygon':
        if (shape.points && shape.points.length > 0) {
          const polyPoints = shape.points
            .map((p) => `${p.x + offsetX},${p.y + offsetY}`)
            .join(' ');
          element = `<polygon points="${polyPoints}" opacity="${opacity}"`;
        }
        break;
    }

    if (!element) continue;

    const fill = shape.style.fill && shape.style.fill !== 'transparent'
      ? `fill="${shape.style.fill}"`
      : 'fill="none"';
    const stroke = shape.style.stroke ? `stroke="${shape.style.stroke}"` : '';
    const strokeWidth = shape.style.strokeWidth != null ? `stroke-width="${shape.style.strokeWidth}"` : '';

    svgContent += `${element} ${fill} ${stroke} ${strokeWidth}/>`;
  }

  svgContent += '</svg>';

  const blob = new Blob([svgContent], { type: 'image/svg+xml' });
  const url = URL.createObjectURL(blob);

  const link = document.createElement('a');
  link.download = `whiteboard-${Date.now()}.svg`;
  link.href = url;
  link.click();

  URL.revokeObjectURL(url);
}

export async function exportAsPDF(
  strokes: Stroke[],
  shapes: Shape[],
  options: ExportOptions
): Promise<void> {
  const canvas = renderToCanvas(strokes, shapes, { ...options, scale: options.scale ?? 2 });

  const imgData = canvas.toDataURL('image/png');

  const imgWidth = canvas.width;
  const imgHeight = canvas.height;

  const pdfWidth = imgWidth / 96 * 72;
  const pdfHeight = imgHeight / 96 * 72;

  const doc = new jsPDF({
    orientation: pdfWidth > pdfHeight ? 'landscape' : 'portrait',
    unit: 'pt',
    format: [pdfWidth, pdfHeight],
  });

  doc.addImage(imgData, 'PNG', 0, 0, pdfWidth, pdfHeight);
  doc.save(`whiteboard-${Date.now()}.pdf`);
}

export default {
  exportAsPNG,
  exportAsSVG,
  exportAsPDF,
};
