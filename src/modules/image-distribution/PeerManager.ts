import logger from '../../utils/logger';
import { eventBus } from '../../utils/eventBus';
import { currentTimestamp, generateId } from '../../utils/helpers';
import { P2PPeer } from './types';
import { IPeerManager } from './interfaces';

export class PeerManager implements IPeerManager {
  private peers: Map<string, P2PPeer> = new Map();
  private layerToPeers: Map<string, Set<string>> = new Map();
  private discoveryTimer?: NodeJS.Timeout;
  private readonly maxPeers: number = 20;

  constructor(private p2pEnabled: boolean = true) {}

  startDiscovery(): void {
    if (!this.p2pEnabled) return;

    this.discoveryTimer = setInterval(() => {
      this.discoverNewPeer();
    }, 10000);

    logger.info('Peer discovery started', { maxPeers: this.maxPeers });
  }

  private discoverNewPeer(): void {
    const mockPeer: P2PPeer = {
      id: generateId('peer_'),
      address: `192.168.1.${Math.floor(Math.random() * 255)}:6881`,
      availableLayers: new Set(),
      bandwidth: Math.floor(Math.random() * 1000) + 100,
      lastSeen: currentTimestamp(),
    };

    this.addPeer(mockPeer);
  }

  addPeer(peer: P2PPeer): void {
    this.peers.set(peer.id, peer);
    peer.availableLayers.forEach(digest => {
      this.updateLayerIndex(digest, peer.id);
    });

    if (this.peers.size > this.maxPeers) {
      const oldestKey = this.peers.keys().next().value;
      if (oldestKey !== undefined) {
        this.removePeer(oldestKey);
      }
    }
  }

  private removePeer(peerId: string): void {
    const peer = this.peers.get(peerId);
    if (peer) {
      peer.availableLayers.forEach(digest => {
        this.removeFromLayerIndex(digest, peerId);
      });
      this.peers.delete(peerId);
    }
  }

  updatePeerLayers(peerId: string, layerDigests: string[]): void {
    const peer = this.peers.get(peerId);
    if (!peer) return;

    const oldLayers = new Set(peer.availableLayers);
    layerDigests.forEach(digest => {
      if (!oldLayers.has(digest)) {
        peer.availableLayers.add(digest);
        this.updateLayerIndex(digest, peerId);
      }
    });
  }

  private updateLayerIndex(digest: string, peerId: string): void {
    if (!this.layerToPeers.has(digest)) {
      this.layerToPeers.set(digest, new Set());
    }
    this.layerToPeers.get(digest)!.add(peerId);
  }

  private removeFromLayerIndex(digest: string, peerId: string): void {
    const peers = this.layerToPeers.get(digest);
    if (peers) {
      peers.delete(peerId);
      if (peers.size === 0) {
        this.layerToPeers.delete(digest);
      }
    }
  }

  findPeersWithLayer(digest: string): P2PPeer[] {
    if (!this.p2pEnabled) return [];

    const peerIds = this.layerToPeers.get(digest);
    if (!peerIds || peerIds.size === 0) return [];

    const result: P2PPeer[] = [];
    for (const peerId of peerIds) {
      const peer = this.peers.get(peerId);
      if (peer) {
        result.push(peer);
      }
    }
    return result;
  }

  announceLayer(digest: string): void {
    eventBus.emit('p2p.layer.available', { digest });
  }

  getAllPeers(): Map<string, P2PPeer> {
    return this.peers;
  }

  getPeerCount(): number {
    return this.peers.size;
  }

  isP2PEnabled(): boolean {
    return this.p2pEnabled;
  }

  stop(): void {
    if (this.discoveryTimer) {
      clearInterval(this.discoveryTimer);
    }
    logger.info('Peer manager stopped');
  }
}
