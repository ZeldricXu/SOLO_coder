import type { Point } from '../types';

export class CanvasFacade {
  private facade: any = null;

  async init(wasmModule: any): Promise<void> {
    this.facade = new wasmModule.CanvasFacade(window.innerWidth, window.innerHeight);
  }

  pan(dx: number, dy: number): void { this.facade?.pan(dx, dy); }
  zoom(factor: number, cx: number, cy: number): void { this.facade?.zoom(factor, cx, cy); }
  zoomAt(sx: number, sy: number, factor: number): void { this.facade?.zoom_at(sx, sy, factor); }
  resetView(): void { this.facade?.reset_view(); }
  fitToContent(padding: number): void { this.facade?.fit_to_content(padding); }
  setDpr(dpr: number): void { this.facade?.set_dpr(dpr); }
  resize(width: number, height: number): void { this.facade?.resize(width, height); }
  getViewportJson(): string { return this.facade?.get_viewport_json() ?? '{}'; }
  screenToWorld(x: number, y: number): Point { return this.facade?.screen_to_world(x, y) ?? { x: 0, y: 0 }; }
  worldToScreen(x: number, y: number): Point { return this.facade?.world_to_screen(x, y) ?? { x: 0, y: 0 }; }

  createShapeLayer(name: string): string { return this.facade?.create_shape_layer(name) ?? ''; }
  createStrokeLayer(name: string): string { return this.facade?.create_stroke_layer(name) ?? ''; }
  createImageLayer(name: string): string { return this.facade?.create_image_layer(name) ?? ''; }
  createGroupLayer(name: string): string { return this.facade?.create_group_layer(name) ?? ''; }
  createTextLayer(name: string): string { return this.facade?.create_text_layer(name) ?? ''; }
  createArrowLayer(name: string): string { return this.facade?.create_arrow_layer(name) ?? ''; }
  createRichtextLayer(name: string): string { return this.facade?.create_richtext_layer(name) ?? ''; }
  removeLayer(id: string): boolean { return this.facade?.remove_layer(id) ?? false; }
  setLayerVisible(id: string, visible: boolean): boolean { return this.facade?.set_layer_visible(id, visible) ?? false; }
  setLayerOpacity(id: string, opacity: number): boolean { return this.facade?.set_layer_opacity(id, opacity) ?? false; }
  setLayerBounds(id: string, x: number, y: number, w: number, h: number): boolean { return this.facade?.set_layer_bounds(id, x, y, w, h) ?? false; }
  moveLayerUp(id: string): boolean { return this.facade?.move_layer_up(id) ?? false; }
  moveLayerDown(id: string): boolean { return this.facade?.move_layer_down(id) ?? false; }
  getLayersJson(): string { return this.facade?.get_layers_json() ?? '{}'; }

  markElementDirty(layerId: string, elementId: string, x: number, y: number, w: number, h: number): void { this.facade?.mark_element_dirty(layerId, elementId, x, y, w, h); }
  markLayerDirty(layerId: string): void { this.facade?.mark_layer_dirty(layerId); }
  markAllDirty(): void { this.facade?.mark_all_dirty(); }

  needsRedraw(): boolean { return this.facade?.needs_redraw() ?? false; }
  beginFrame(): void { this.facade?.begin_frame(); }
  endFrame(): void { this.facade?.end_frame(); }
  getDirtyRegionsJson(): string { return this.facade?.get_dirty_regions_json() ?? '[]'; }

  hitTest(sx: number, sy: number): string | undefined { return this.facade?.hit_test(sx, sy); }

  tessellateRect(x: number, y: number, w: number, h: number, fill: boolean): any { return this.facade?.tessellate_rect(x, y, w, h, fill); }
  tessellateCircle(cx: number, cy: number, r: number, fill: boolean): any { return this.facade?.tessellate_circle(cx, cy, r, fill); }
  tessellateEllipse(cx: number, cy: number, rx: number, ry: number, fill: boolean): any { return this.facade?.tessellate_ellipse(cx, cy, rx, ry, fill); }
  tessellateStar(cx: number, cy: number, outerR: number, innerR: number, points: number, rotation: number, fill: boolean): any { return this.facade?.tessellate_star(cx, cy, outerR, innerR, points, rotation, fill); }
  tessellateArrow(x1: number, y1: number, x2: number, y2: number, headSize: number, double: boolean, fill: boolean): any { return this.facade?.tessellate_arrow(x1, y1, x2, y2, headSize, double, fill); }
  tessellateLine(x1: number, y1: number, x2: number, y2: number, width: number): any { return this.facade?.tessellate_line(x1, y1, x2, y2, width); }

  toJson(): string { return this.facade?.to_json() ?? '{}'; }
  static fromJson(json: string, width: number, height: number, wasmModule: any): CanvasFacade | null {
    const f = new CanvasFacade();
    f.facade = wasmModule.CanvasFacade.from_json(json, width, height);
    return f.facade ? f : null;
  }
}

export class SyncFacade {
  private facade: any = null;

  async init(wasmModule: any, documentId: string, userId: string, username: string): Promise<void> {
    this.facade = new wasmModule.SyncFacade(documentId, userId, username);
  }

  addShape(shapeType: string, x: number, y: number, w: number, h: number, propsJson?: string): string { return this.facade?.add_shape(shapeType, x, y, w, h, propsJson) ?? ''; }
  addStroke(pointsJson: string, styleJson: string): string { return this.facade?.add_stroke(pointsJson, styleJson) ?? ''; }
  addText(x: number, y: number, content: string, propsJson?: string): string { return this.facade?.add_text(x, y, content, propsJson) ?? ''; }
  updateElement(id: string, propsJson?: string): boolean { return this.facade?.update_element(id, propsJson) ?? false; }
  deleteElement(id: string): boolean { return this.facade?.delete_element(id) ?? false; }
  moveElement(id: string, x: number, y: number): boolean { return this.facade?.move_element(id, x, y) ?? false; }

  encodeUpdate(): Uint8Array { return this.facade?.encode_update() ?? new Uint8Array(); }
  applyUpdate(data: Uint8Array): boolean { return this.facade?.apply_update(data) ?? false; }
  encodeStateVector(): Uint8Array { return this.facade?.encode_state_vector() ?? new Uint8Array(); }
  encodeDiff(sv: Uint8Array): Uint8Array { return this.facade?.encode_diff(sv) ?? new Uint8Array(); }

  canUndo(): boolean { return this.facade?.can_undo() ?? false; }
  canRedo(): boolean { return this.facade?.can_redo() ?? false; }
  undo(): string | null { return this.facade?.undo() ?? null; }
  redo(): string | null { return this.facade?.redo() ?? null; }

  getBlocksJson(): string { return this.facade?.get_blocks_json() ?? '[]'; }
  getBlockJson(id: string): string | null { return this.facade?.get_block_json(id) ?? null; }
  siteId(): string { return this.facade?.site_id() ?? ''; }
  documentId(): string { return this.facade?.document_id() ?? ''; }
}

export class ExportFacade {
  private facade: any = null;

  async init(wasmModule: any): Promise<void> {
    this.facade = new wasmModule.ExportFacade();
  }

  exportSvg(layersJson: string, viewportJson: string): string { return this.facade?.export_svg(layersJson, viewportJson) ?? ''; }

  addArtboard(name: string, x: number, y: number, w: number, h: number): string { return this.facade?.add_artboard(name, x, y, w, h) ?? ''; }
  removeArtboard(id: string): boolean { return this.facade?.remove_artboard(id) ?? false; }
  setPdfMeta(title: string, author: string, subject: string): void { this.facade?.set_pdf_meta(title, author, subject); }
  getArtboardIds(): string[] {
    const ids = this.facade?.get_artboard_ids();
    return ids ? Array.from(ids).map((v: any) => v.toString()) : [];
  }
  generatePageDescriptions(layersJson: string): any[] {
    const descs = this.facade?.generate_page_descriptions(layersJson);
    return descs ? Array.from(descs) : [];
  }

  computeContentBounds(layersJson: string): string { return this.facade?.compute_content_bounds(layersJson) ?? '{}'; }
}
