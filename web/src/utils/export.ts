import jsPDF from 'jspdf';
import type { Stroke, Shape, ExportOptions, BoundingBox, Artboard, Point, ArrowHeadStyle } from '../types';

function computeStarPoints(cx: number, cy: number, outerRadius: number, innerRadius: number, numPoints: number, rotationDeg: number): Point[] {
  const points: Point[] = [];
  const rotation = (rotationDeg * Math.PI) / 180;
  const step = Math.PI / numPoints;

  for (let i = 0; i < numPoints * 2; i++) {
    const r = i % 2 === 0 ? outerRadius : innerRadius;
    const angle = i * step - Math.PI / 2 + rotation;
    points.push({
      x: cx + r * Math.cos(angle),
      y: cy + r * Math.sin(angle),
    });
  }

  return points;
}

function createArrowHeadPath(endX: number, endY: number, dirX: number, dirY: number, size: number, style: ArrowHeadStyle): Path2D {
  const path = new Path2D();
  if (style === 'none' || size <= 0) return path;

  const angle = Math.atan2(dirY, dirX);
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);

  if (style === 'triangle') {
    const p1x = endX - size * cos - size * 0.5 * sin;
    const p1y = endY - size * sin + size * 0.5 * cos;
    const p2x = endX - size * cos + size * 0.5 * sin;
    const p2y = endY - size * sin - size * 0.5 * cos;
    path.moveTo(endX, endY);
    path.lineTo(p1x, p1y);
    path.lineTo(p2x, p2y);
    path.closePath();
  } else if (style === 'diamond') {
    const halfSize = size * 0.6;
    const cx = endX - size * 0.5 * cos;
    const cy = endY - size * 0.5 * sin;
    const leftX = cx - halfSize * sin;
    const leftY = cy + halfSize * cos;
    const rightX = cx + halfSize * sin;
    const rightY = cy - halfSize * cos;
    const backX = endX - size * cos;
    const backY = endY - size * sin;
    path.moveTo(endX, endY);
    path.lineTo(rightX, rightY);
    path.lineTo(backX, backY);
    path.lineTo(leftX, leftY);
    path.closePath();
  }

  return path;
}

function arrowHeadSvg(endX: number, endY: number, dirX: number, dirY: number, size: number, style: ArrowHeadStyle, fill: string, stroke: string, strokeWidth: number): string {
  if (style === 'none' || size <= 0) return '';

  const angle = Math.atan2(dirY, dirX);
  const cos = Math.cos(angle);
  const sin = Math.sin(angle);

  let points = '';
  if (style === 'triangle') {
    const p1x = endX - size * cos - size * 0.5 * sin;
    const p1y = endY - size * sin + size * 0.5 * cos;
    const p2x = endX - size * cos + size * 0.5 * sin;
    const p2y = endY - size * sin - size * 0.5 * cos;
    points = `${endX},${endY} ${p1x},${p1y} ${p2x},${p2y}`;
  } else if (style === 'diamond') {
    const halfSize = size * 0.6;
    const cx = endX - size * 0.5 * cos;
    const cy = endY - size * 0.5 * sin;
    const leftX = cx - halfSize * sin;
    const leftY = cy + halfSize * cos;
    const rightX = cx + halfSize * sin;
    const rightY = cy - halfSize * cos;
    const backX = endX - size * cos;
    const backY = endY - size * sin;
    points = `${endX},${endY} ${rightX},${rightY} ${backX},${backY} ${leftX},${leftY}`;
  }

  if (!points) return '';
  return `<polygon points="${points}" fill="${fill}" stroke="${stroke}" stroke-width="${strokeWidth}"/>`;
}

function computeContentBounds(strokes: Stroke[], shapes: Shape[], artboard?: Artboard): BoundingBox {
  if (artboard) {
    return {
      minX: artboard.x,
      minY: artboard.y,
      maxX: artboard.x + artboard.width,
      maxY: artboard.y + artboard.height,
    };
  }

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

function filterByArtboard<T extends { id: string }>(items: T[], artboard?: Artboard): T[] {
  if (!artboard || artboard.objectIds.length === 0) return items;
  return items.filter((item) => artboard.objectIds.includes(item.id));
}

function renderStrokeToCanvas(ctx: CanvasRenderingContext2D, stroke: Stroke, offsetX: number, offsetY: number) {
  if (stroke.points.length < 2) return;

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
  ctx.moveTo(stroke.points[0].x + offsetX, stroke.points[0].y + offsetY);

  for (let i = 1; i < stroke.points.length; i++) {
    ctx.lineTo(stroke.points[i].x + offsetX, stroke.points[i].y + offsetY);
  }

  ctx.stroke();
  ctx.restore();
}

function renderShapeToCanvas(ctx: CanvasRenderingContext2D, shape: Shape, offsetX: number, offsetY: number) {
  ctx.save();
  ctx.globalAlpha = shape.style.opacity ?? 1;

  const x = shape.x + offsetX;
  const y = shape.y + offsetY;

  if (shape.rotation) {
    const centerX = x + shape.width / 2;
    const centerY = y + shape.height / 2;
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
      ctx.rect(x, y, shape.width, shape.height);
      break;
    case 'ellipse':
      ctx.ellipse(
        x + shape.width / 2,
        y + shape.height / 2,
        Math.abs(shape.width) / 2,
        Math.abs(shape.height) / 2,
        0,
        0,
        Math.PI * 2
      );
      break;
    case 'line':
      ctx.moveTo(x, y);
      ctx.lineTo(x + shape.width, y + shape.height);
      break;
    case 'arrow': {
      const x1 = x;
      const y1 = y;
      const x2 = x + shape.width;
      const y2 = y + shape.height;
      ctx.moveTo(x1, y1);
      ctx.lineTo(x2, y2);
      const dx = x2 - x1;
      const dy = y2 - y1;
      const len = Math.sqrt(dx * dx + dy * dy);
      const dirX = len > 0 ? dx / len : 1;
      const dirY = len > 0 ? dy / len : 0;

      const arrowCfg = shape.arrowConfig || { headStyle: 'triangle', tailStyle: 'none', headSize: 12, tailSize: 12 };

      const headPath = createArrowHeadPath(x2, y2, dirX, dirY, arrowCfg.headSize, arrowCfg.headStyle);
      const tailPath = createArrowHeadPath(x1, y1, -dirX, -dirY, arrowCfg.tailSize, arrowCfg.tailStyle);

      ctx.stroke();

      const fillColor = shape.style.stroke || '#000000';
      ctx.fillStyle = fillColor;
      ctx.fill(headPath);
      ctx.fill(tailPath);
      ctx.restore();
      return;
    }
    case 'triangle':
      ctx.moveTo(x + shape.width / 2, y);
      ctx.lineTo(x + shape.width, y + shape.height);
      ctx.lineTo(x, y + shape.height);
      ctx.closePath();
      break;
    case 'polygon':
      if (shape.points && shape.points.length > 0) {
        ctx.moveTo(shape.points[0].x + offsetX, shape.points[0].y + offsetY);
        for (let i = 1; i < shape.points.length; i++) {
          ctx.lineTo(shape.points[i].x + offsetX, shape.points[i].y + offsetY);
        }
        ctx.closePath();
      }
      break;
    case 'star': {
      const cx = x + shape.width / 2;
      const cy = y + shape.height / 2;
      const starCfg = shape.starConfig || {
        outerRadius: Math.min(shape.width, shape.height) / 2,
        innerRadius: Math.min(shape.width, shape.height) / 4,
        numPoints: 5,
        rotation: 0,
      };
      const starPoints = computeStarPoints(cx, cy, starCfg.outerRadius, starCfg.innerRadius, starCfg.numPoints, starCfg.rotation);
      ctx.moveTo(starPoints[0].x, starPoints[0].y);
      for (let i = 1; i < starPoints.length; i++) {
        ctx.lineTo(starPoints[i].x, starPoints[i].y);
      }
      ctx.closePath();
      break;
    }
    case 'rich-text': {
      if (shape.richTextConfig) {
        const cfg = shape.richTextConfig;
        const padding = cfg.padding;
        const contentWidth = shape.width - padding * 2;
        const contentHeight = shape.height - padding * 2;

        if (cfg.backgroundColor && cfg.backgroundColor !== 'transparent') {
          ctx.fillStyle = cfg.backgroundColor;
          ctx.fillRect(x, y, shape.width, shape.height);
        }

        ctx.fillStyle = cfg.fontColor;
        ctx.font = `${cfg.fontSize}px ${cfg.fontFamily}`;
        ctx.textAlign = cfg.textAlign as CanvasTextAlign;
        ctx.textBaseline = 'top';

        let textX = x + padding;
        if (cfg.textAlign === 'center') textX = x + shape.width / 2;
        if (cfg.textAlign === 'right') textX = x + shape.width - padding;
        if (cfg.textAlign === 'justify') textX = x + padding;

        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = cfg.contentHtml || '富文本内容';
        const plainText = tempDiv.textContent || tempDiv.innerText || '';
        const lines = plainText.split('\n');

        const lineHeight = cfg.fontSize * 1.2;
        let currentY = y + padding;
        for (const line of lines) {
          if (currentY + cfg.fontSize > y + shape.height - padding) break;
          if (cfg.textAlign === 'justify') {
            const words = line.split(' ');
            if (words.length > 1) {
              let testLine = '';
              const lineWords: string[] = [];
              for (const word of words) {
                const testLineWidth = ctx.measureText(testLine + (testLine ? ' ' : '') + word).width;
                if (testLineWidth > contentWidth && lineWords.length > 0) {
                  const spaceWidth = (contentWidth - ctx.measureText(lineWords.join('')).width) / Math.max(1, lineWords.length - 1);
                  let drawX = textX;
                  for (let wi = 0; wi < lineWords.length; wi++) {
                    ctx.fillText(lineWords[wi], drawX, currentY, contentWidth);
                    drawX += ctx.measureText(lineWords[wi]).width + spaceWidth;
                  }
                  currentY += lineHeight;
                  lineWords.length = 0;
                  testLine = word;
                } else {
                  lineWords.push(word);
                  testLine = testLine ? testLine + ' ' + word : word;
                }
              }
              if (lineWords.length > 0) {
                ctx.fillText(lineWords.join(' '), textX, currentY, contentWidth);
                currentY += lineHeight;
              }
            } else {
              ctx.fillText(line, textX, currentY, contentWidth);
              currentY += lineHeight;
            }
          } else {
            let remaining = line;
            while (remaining && currentY + cfg.fontSize <= y + contentHeight + padding) {
              let slice = remaining;
              let sliceWidth = ctx.measureText(slice).width;
              if (sliceWidth > contentWidth) {
                let low = 0;
                let high = remaining.length;
                while (low < high) {
                  const mid = Math.floor((low + high) / 2);
                  const w = ctx.measureText(remaining.slice(0, mid)).width;
                  if (w <= contentWidth) low = mid + 1;
                  else high = mid;
                }
                slice = remaining.slice(0, Math.max(1, low - 1));
              }
              ctx.fillText(slice, textX, currentY, contentWidth);
              currentY += lineHeight;
              remaining = remaining.slice(slice.length);
            }
          }
        }
      } else {
        ctx.rect(x, y, shape.width, shape.height);
      }
      ctx.restore();
      return;
    }
  }

  if (shape.style.fill && shape.style.fill !== 'transparent') {
    ctx.fill();
  }
  if (shape.style.stroke) {
    ctx.stroke();
  }

  ctx.restore();
}

function renderToCanvas(
  strokes: Stroke[],
  shapes: Shape[],
  options: ExportOptions,
  artboard?: Artboard
): HTMLCanvasElement {
  const filteredStrokes = filterByArtboard(strokes, artboard);
  const filteredShapes = filterByArtboard(shapes, artboard);

  const bounds = computeContentBounds(filteredStrokes, filteredShapes, artboard);
  const width = Math.ceil(bounds.maxX - bounds.minX);
  const height = Math.ceil(bounds.maxY - bounds.minY);
  const scale = options.scale ?? 2;

  const canvas = document.createElement('canvas');
  canvas.width = width * scale;
  canvas.height = height * scale;

  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('Failed to get canvas context');

  ctx.scale(scale, scale);

  if (artboard?.background || (options.includeBackground && options.background)) {
    ctx.fillStyle = artboard?.background || options.background || '#ffffff';
    ctx.fillRect(0, 0, width, height);
  }

  const offsetX = -bounds.minX;
  const offsetY = -bounds.minY;

  for (const stroke of filteredStrokes) {
    renderStrokeToCanvas(ctx, stroke, offsetX, offsetY);
  }

  for (const shape of filteredShapes) {
    renderShapeToCanvas(ctx, shape, offsetX, offsetY);
  }

  return canvas;
}

export async function exportAsPNG(
  strokes: Stroke[],
  shapes: Shape[],
  options: ExportOptions
): Promise<void> {
  const pages = options.pages ?? [undefined];
  for (let i = 0; i < pages.length; i++) {
    const artboard = pages[i];
    const canvas = renderToCanvas(strokes, shapes, options, artboard);
    const quality = options.quality ?? 0.92;
    const dataUrl = canvas.toDataURL('image/png', quality);
    const link = document.createElement('a');
    link.download = pages.length > 1 ? `whiteboard-page-${i + 1}-${Date.now()}.png` : `whiteboard-${Date.now()}.png`;
    link.href = dataUrl;
    link.click();
  }
}

export async function exportAsSVG(
  strokes: Stroke[],
  shapes: Shape[],
  options: ExportOptions
): Promise<void> {
  const pages = options.pages ?? [undefined];

  for (let pageIdx = 0; pageIdx < pages.length; pageIdx++) {
    const artboard = pages[pageIdx];
    const filteredStrokes = filterByArtboard(strokes, artboard);
    const filteredShapes = filterByArtboard(shapes, artboard);

    const bounds = computeContentBounds(filteredStrokes, filteredShapes, artboard);
    const width = Math.ceil(bounds.maxX - bounds.minX);
    const height = Math.ceil(bounds.maxY - bounds.minY);

    let svgContent = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">`;

    if (artboard?.background || (options.includeBackground && options.background)) {
      svgContent += `<rect width="100%" height="100%" fill="${artboard?.background || options.background}"/>`;
    }

    const offsetX = -bounds.minX;
    const offsetY = -bounds.minY;

    for (const stroke of filteredStrokes) {
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

    for (const shape of filteredShapes) {
      const x = shape.x + offsetX;
      const y = shape.y + offsetY;
      const opacity = shape.style.opacity ?? 1;
      const transform = shape.rotation
        ? `transform="rotate(${shape.rotation} ${x + shape.width / 2} ${y + shape.height / 2})"`
        : '';

      const fill = shape.style.fill && shape.style.fill !== 'transparent'
        ? `fill="${shape.style.fill}"`
        : 'fill="none"';
      const strokeAttr = shape.style.stroke ? `stroke="${shape.style.stroke}"` : '';
      const strokeWidth = shape.style.strokeWidth != null ? `stroke-width="${shape.style.strokeWidth}"` : '';

      let element = '';
      switch (shape.type) {
        case 'rectangle':
          element = `<rect x="${x}" y="${y}" width="${shape.width}" height="${shape.height}" opacity="${opacity}" ${transform}`;
          break;
        case 'ellipse':
          element = `<ellipse cx="${x + shape.width / 2}" cy="${y + shape.height / 2}" rx="${Math.abs(shape.width) / 2}" ry="${Math.abs(shape.height) / 2}" opacity="${opacity}" ${transform}`;
          break;
        case 'line':
          element = `<line x1="${x}" y1="${y}" x2="${x + shape.width}" y2="${y + shape.height}" opacity="${opacity}" ${transform}`;
          break;
        case 'arrow': {
          const x1 = x;
          const y1 = y;
          const x2 = x + shape.width;
          const y2 = y + shape.height;
          const dx = x2 - x1;
          const dy = y2 - y1;
          const len = Math.sqrt(dx * dx + dy * dy);
          const dirX = len > 0 ? dx / len : 1;
          const dirY = len > 0 ? dy / len : 0;

          const arrowCfg = shape.arrowConfig || { headStyle: 'triangle', tailStyle: 'none', headSize: 12, tailSize: 12 };
          const headFill = shape.style.stroke || '#000000';

          const headSvg = arrowHeadSvg(x2, y2, dirX, dirY, arrowCfg.headSize, arrowCfg.headStyle, headFill, headFill, 1);
          const tailSvg = arrowHeadSvg(x1, y1, -dirX, -dirY, arrowCfg.tailSize, arrowCfg.tailStyle, headFill, headFill, 1);

          element = `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" opacity="${opacity}" ${transform}`;
          svgContent += `${element} ${strokeAttr} ${strokeWidth}/>`;
          svgContent += headSvg;
          svgContent += tailSvg;
          continue;
        }
        case 'triangle': {
          const points = `${x + shape.width / 2},${y} ${x + shape.width},${y + shape.height} ${x},${y + shape.height}`;
          element = `<polygon points="${points}" opacity="${opacity}" ${transform}`;
          break;
        }
        case 'polygon':
          if (shape.points && shape.points.length > 0) {
            const polyPoints = shape.points
              .map((p) => `${p.x + offsetX},${p.y + offsetY}`)
              .join(' ');
            element = `<polygon points="${polyPoints}" opacity="${opacity}" ${transform}`;
          }
          break;
        case 'star': {
          const cx = x + shape.width / 2;
          const cy = y + shape.height / 2;
          const starCfg = shape.starConfig || {
            outerRadius: Math.min(shape.width, shape.height) / 2,
            innerRadius: Math.min(shape.width, shape.height) / 4,
            numPoints: 5,
            rotation: 0,
          };
          const starPoints = computeStarPoints(cx, cy, starCfg.outerRadius, starCfg.innerRadius, starCfg.numPoints, starCfg.rotation);
          const polyPoints = starPoints.map((p) => `${p.x},${p.y}`).join(' ');
          element = `<polygon points="${polyPoints}" opacity="${opacity}" ${transform}`;
          break;
        }
        case 'rich-text': {
          if (shape.richTextConfig) {
            const cfg = shape.richTextConfig;
            const textColor = cfg.fontColor;
            const bgColor = cfg.backgroundColor;
            const textAlign = cfg.textAlign;
            const padding = cfg.padding;

            let bgRect = '';
            if (bgColor && bgColor !== 'transparent') {
              bgRect = `<rect x="${x}" y="${y}" width="${shape.width}" height="${shape.height}" fill="${bgColor}"/>`;
            }

            const contentHtml = cfg.contentHtml || '富文本内容';

            const foreignObject = `
              <foreignObject x="${x + padding}" y="${y + padding}" width="${shape.width - padding * 2}" height="${shape.height - padding * 2}">
                <div xmlns="http://www.w3.org/1999/xhtml" style="
                  font-family: ${cfg.fontFamily};
                  font-size: ${cfg.fontSize}px;
                  color: ${textColor};
                  text-align: ${textAlign};
                  width: 100%;
                  height: 100%;
                  overflow: hidden;
                  word-wrap: break-word;
                ">${contentHtml}</div>
              </foreignObject>
            `;

            svgContent += bgRect + foreignObject;
            continue;
          } else {
            element = `<rect x="${x}" y="${y}" width="${shape.width}" height="${shape.height}" opacity="${opacity}" ${transform}`;
          }
          break;
        }
      }

      if (!element) continue;
      svgContent += `${element} ${fill} ${strokeAttr} ${strokeWidth}/>`;
    }

    svgContent += '</svg>';

    const blob = new Blob([svgContent], { type: 'image/svg+xml' });
    const url = URL.createObjectURL(blob);

    const link = document.createElement('a');
    link.download = pages.length > 1 ? `whiteboard-page-${pageIdx + 1}-${Date.now()}.svg` : `whiteboard-${Date.now()}.svg`;
    link.href = url;
    link.click();

    URL.revokeObjectURL(url);
  }
}

export async function exportAsPDF(
  strokes: Stroke[],
  shapes: Shape[],
  options: ExportOptions
): Promise<void> {
  const pages = options.pages && options.pages.length > 0 ? options.pages : [undefined];

  let doc: jsPDF | null = null;

  for (let i = 0; i < pages.length; i++) {
    const artboard = pages[i];
    const canvas = renderToCanvas(strokes, shapes, { ...options, scale: options.scale ?? 2 }, artboard);
    const imgData = canvas.toDataURL('image/png');

    const imgWidthPx = canvas.width;
    const imgHeightPx = canvas.height;

    const pdfWidth = imgWidthPx / 96 * 72;
    const pdfHeight = imgHeightPx / 96 * 72;

    if (!doc) {
      doc = new jsPDF({
        orientation: pdfWidth > pdfHeight ? 'landscape' : 'portrait',
        unit: 'pt',
        format: [pdfWidth, pdfHeight],
      });
    } else {
      doc.addPage([pdfWidth, pdfHeight], pdfWidth > pdfHeight ? 'landscape' : 'portrait');
    }

    doc.addImage(imgData, 'PNG', 0, 0, pdfWidth, pdfHeight);
  }

  if (!doc) {
    doc = new jsPDF();
  }

  doc.save(`whiteboard-${Date.now()}.pdf`);
}

export default {
  exportAsPNG,
  exportAsSVG,
  exportAsPDF,
};
