import { Atom, Bond } from '../molecule-parser/types';
import { ResidueLabel, computeResidueLabels } from './residue-labels';
import { RibbonSegment, computeBackboneRibbon } from './backbone-ribbon';
import { HBond, detectHBonds } from './hbond-indicators';
import { PartialChargeLabel, computePartialChargeLabels } from './partial-charges';
import { BFactorSphere, computeBFactorHeatmap } from './bfactor-heatmap';
import { HBondNetworkEdge, detectHBondNetwork } from './hbond-network';

export type { ResidueLabel } from './residue-labels';
export type { RibbonSegment } from './backbone-ribbon';
export type { HBond } from './hbond-indicators';
export type { PartialChargeLabel } from './partial-charges';
export type { BFactorSphere } from './bfactor-heatmap';
export type { HBondNetworkEdge } from './hbond-network';

export interface AnnotationOpacityState {
  residueLabels: number;
  backboneRibbon: number;
  hBondIndicators: number;
  partialCharges: number;
  bFactorHeatmap: number;
  ligandHBondNetwork: number;
}

const DEFAULT_OPACITIES: AnnotationOpacityState = {
  residueLabels: 0.9,
  backboneRibbon: 0.7,
  hBondIndicators: 0.6,
  partialCharges: 0.85,
  bFactorHeatmap: 0.8,
  ligandHBondNetwork: 0.75,
};

export class AnnotationLayer {
  private residueLabelsVisible: boolean = false;
  private backboneRibbonVisible: boolean = false;
  private hBondIndicatorsVisible: boolean = false;
  private partialChargesVisible: boolean = false;
  private bFactorHeatmapVisible: boolean = false;
  private ligandHBondNetworkVisible: boolean = false;
  private opacities: AnnotationOpacityState = { ...DEFAULT_OPACITIES };

  private residueLabels: ResidueLabel[] = [];
  private ribbonSegments: RibbonSegment[] = [];
  private hBonds: HBond[] = [];
  private partialChargeLabels: PartialChargeLabel[] = [];
  private bFactorSpheres: BFactorSphere[] = [];
  private hBondNetworkEdges: HBondNetworkEdge[] = [];

  setResidueLabelsVisible(visible: boolean): void {
    this.residueLabelsVisible = visible;
  }

  setBackboneRibbonVisible(visible: boolean): void {
    this.backboneRibbonVisible = visible;
  }

  setHBondIndicatorsVisible(visible: boolean): void {
    this.hBondIndicatorsVisible = visible;
  }

  setPartialChargesVisible(visible: boolean): void {
    this.partialChargesVisible = visible;
  }

  setBFactorHeatmapVisible(visible: boolean): void {
    this.bFactorHeatmapVisible = visible;
  }

  setLigandHBondNetworkVisible(visible: boolean): void {
    this.ligandHBondNetworkVisible = visible;
  }

  setOpacity(layer: keyof AnnotationOpacityState, opacity: number): void {
    this.opacities[layer] = Math.max(0, Math.min(1, opacity));
  }

  getOpacity(layer: keyof AnnotationOpacityState): number {
    return this.opacities[layer];
  }

  getAllOpacities(): AnnotationOpacityState {
    return { ...this.opacities };
  }

  setAllOpacities(opacities: Partial<AnnotationOpacityState>): void {
    this.opacities = { ...this.opacities, ...opacities };
  }

  update(atoms: Atom[], bonds: Bond[]): void {
    this.residueLabels = computeResidueLabels(atoms);
    this.ribbonSegments = computeBackboneRibbon(atoms);
    this.hBonds = detectHBonds(atoms, bonds);
    this.partialChargeLabels = computePartialChargeLabels(atoms);
    this.bFactorSpheres = computeBFactorHeatmap(atoms);
    this.hBondNetworkEdges = detectHBondNetwork(atoms, bonds);
  }

  getVisibleResidueLabels(): ResidueLabel[] {
    return this.residueLabelsVisible ? this.residueLabels : [];
  }

  getVisibleRibbonSegments(): RibbonSegment[] {
    return this.backboneRibbonVisible ? this.ribbonSegments : [];
  }

  getVisibleHBonds(): HBond[] {
    return this.hBondIndicatorsVisible ? this.hBonds : [];
  }

  getVisiblePartialChargeLabels(): PartialChargeLabel[] {
    return this.partialChargesVisible ? this.partialChargeLabels : [];
  }

  getVisibleBFactorSpheres(): BFactorSphere[] {
    return this.bFactorHeatmapVisible ? this.bFactorSpheres : [];
  }

  getVisibleHBondNetworkEdges(): HBondNetworkEdge[] {
    return this.ligandHBondNetworkVisible ? this.hBondNetworkEdges : [];
  }

  getResidueLabelsWithOpacity(): { labels: ResidueLabel[]; opacity: number } {
    return {
      labels: this.residueLabelsVisible ? this.residueLabels : [],
      opacity: this.residueLabelsVisible ? this.opacities.residueLabels : 0,
    };
  }

  getRibbonSegmentsWithOpacity(): { segments: RibbonSegment[]; opacity: number } {
    return {
      segments: this.backboneRibbonVisible ? this.ribbonSegments : [],
      opacity: this.backboneRibbonVisible ? this.opacities.backboneRibbon : 0,
    };
  }

  getHBondsWithOpacity(): { hbonds: HBond[]; opacity: number } {
    return {
      hbonds: this.hBondIndicatorsVisible ? this.hBonds : [],
      opacity: this.hBondIndicatorsVisible ? this.opacities.hBondIndicators : 0,
    };
  }

  getPartialChargesWithOpacity(): { labels: PartialChargeLabel[]; opacity: number } {
    return {
      labels: this.partialChargesVisible ? this.partialChargeLabels : [],
      opacity: this.partialChargesVisible ? this.opacities.partialCharges : 0,
    };
  }

  getBFactorSpheresWithOpacity(): { spheres: BFactorSphere[]; opacity: number } {
    return {
      spheres: this.bFactorHeatmapVisible ? this.bFactorSpheres : [],
      opacity: this.bFactorHeatmapVisible ? this.opacities.bFactorHeatmap : 0,
    };
  }

  getHBondNetworkWithOpacity(): { edges: HBondNetworkEdge[]; opacity: number } {
    return {
      edges: this.ligandHBondNetworkVisible ? this.hBondNetworkEdges : [],
      opacity: this.ligandHBondNetworkVisible ? this.opacities.ligandHBondNetwork : 0,
    };
  }

  isLayerVisible(layer: keyof AnnotationOpacityState): boolean {
    switch (layer) {
      case 'residueLabels': return this.residueLabelsVisible;
      case 'backboneRibbon': return this.backboneRibbonVisible;
      case 'hBondIndicators': return this.hBondIndicatorsVisible;
      case 'partialCharges': return this.partialChargesVisible;
      case 'bFactorHeatmap': return this.bFactorHeatmapVisible;
      case 'ligandHBondNetwork': return this.ligandHBondNetworkVisible;
    }
  }
}

