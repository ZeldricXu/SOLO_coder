import { WebSocket, WebSocketServer } from 'ws';
import { v4 as uuidv4 } from 'uuid';
import {
  SessionState,
  Segment,
  TranscribeResult,
  TranslationUpdate,
  ChunkAck,
  WebSocketMessage,
  StartSessionMessage,
  EndSessionMessage,
  AudioChunkMessage,
  PendingTranslation,
} from '../types';
import { SpeechRecognitionService } from '../services/speechRecognition';
import { TranslationService } from '../services/translation';
import { DatabaseService } from '../services/database';
import { DeduplicationService } from '../services/deduplication';

interface ConnectedClient {
  ws: WebSocket;
  sessionId?: string;
}

interface AudioChunkQueueItem {
  clientId: string;
  message: AudioChunkMessage;
  receivedTime: number;
}

interface SessionExtension {
  lastRecognizedText: string;
  processingQueue: number[];
  pendingTranslations: Map<number, PendingTranslation>;
  overlapDuration: number;
}

interface ExtendedSessionState extends SessionState {
  extension: SessionExtension;
}

const MAX_CONCURRENT_PROCESSING = 4;
const OVERLAP_DURATION = 0.5;
const STEP_DURATION = 1.5;

export class WebSocketHandler {
  private wss: WebSocketServer;
  private clients: Map<string, ConnectedClient> = new Map();
  private sessions: Map<string, ExtendedSessionState> = new Map();
  
  private audioQueue: AudioChunkQueueItem[] = [];
  private isProcessing = false;
  private concurrentProcessing = 0;

  constructor(wss: WebSocketServer) {
    this.wss = wss;
    this.setupHandlers();
  }

  private setupHandlers(): void {
    this.wss.on('connection', (ws) => {
      const clientId = uuidv4();
      this.clients.set(clientId, { ws });

      console.log(`Client connected: ${clientId}`);

      ws.on('message', (data) => {
        this.handleMessage(clientId, data);
      });

      ws.on('close', () => {
        this.handleDisconnect(clientId);
        console.log(`Client disconnected: ${clientId}`);
      });

      ws.on('error', (error) => {
        console.error(`WebSocket error for client ${clientId}:`, error);
      });
    });
  }

  private handleMessage(clientId: string, data: Buffer): void {
    try {
      const message = JSON.parse(data.toString()) as WebSocketMessage;
      const client = this.clients.get(clientId);

      if (!client) return;

      switch (message.action) {
        case 'start_session':
          this.handleStartSession(clientId, message as StartSessionMessage);
          break;
        case 'audio_chunk':
          this.queueAudioChunk(clientId, message as AudioChunkMessage);
          break;
        case 'end_session':
          this.handleEndSession(clientId, message as EndSessionMessage);
          break;
        default:
          console.warn(`Unknown action: ${message.action}`);
      }
    } catch (error) {
      console.error('Error parsing message:', error);
    }
  }

  private handleStartSession(clientId: string, message: StartSessionMessage): void {
    const client = this.clients.get(clientId);
    if (!client) return;

    const sessionId = message.data.sessionId || uuidv4();
    const sessionState: ExtendedSessionState = {
      sessionId,
      audioLanguage: message.data.audioLanguage || 'auto',
      targetLanguage: message.data.targetLanguage,
      enableTranslation: message.data.enableTranslation || false,
      startTime: Date.now(),
      segments: [],
      isActive: true,
      extension: {
        lastRecognizedText: '',
        processingQueue: [],
        pendingTranslations: new Map(),
        overlapDuration: OVERLAP_DURATION,
      },
    };

    this.sessions.set(sessionId, sessionState);
    client.sessionId = sessionId;

    console.log(`Session started: ${sessionId}`);
    this.sendToClient(clientId, {
      event: 'session_started',
      data: { sessionId },
    });
  }

  private queueAudioChunk(clientId: string, message: AudioChunkMessage): void {
    const client = this.clients.get(clientId);
    if (!client || !client.sessionId) return;

    const session = this.sessions.get(client.sessionId);
    if (!session || !session.isActive) return;

    const sequence = message.data.sequence;

    this.sendToClient(clientId, {
      event: 'chunk_ack',
      data: {
        sequence,
        received: true,
      },
    } as ChunkAck);

    const queueItem: AudioChunkQueueItem = {
      clientId,
      message,
      receivedTime: Date.now(),
    };

    this.audioQueue.push(queueItem);
    this.processQueue();
  }

  private async processQueue(): Promise<void> {
    if (this.isProcessing || this.concurrentProcessing >= MAX_CONCURRENT_PROCESSING) {
      return;
    }

    if (this.audioQueue.length === 0) {
      return;
    }

    this.isProcessing = true;

    while (this.audioQueue.length > 0 && this.concurrentProcessing < MAX_CONCURRENT_PROCESSING) {
      const item = this.audioQueue.shift();
      if (!item) break;

      this.concurrentProcessing++;
      
      this.processAudioChunk(item)
        .catch((error) => {
          console.error('Error processing audio chunk:', error);
        })
        .finally(() => {
          this.concurrentProcessing--;
          if (this.audioQueue.length > 0) {
            this.processQueue();
          } else {
            this.isProcessing = false;
          }
        });
    }
  }

  private async processAudioChunk(item: AudioChunkQueueItem): Promise<void> {
    const { clientId, message } = item;
    const client = this.clients.get(clientId);
    
    if (!client || !client.sessionId) return;

    const session = this.sessions.get(client.sessionId);
    if (!session || !session.isActive) return;

    try {
      const audioData = Buffer.from(message.data.audioData, 'base64');
      const sequence = message.data.sequence;
      const timestamp = message.data.timestamp || Date.now();

      const recognitionResult = await SpeechRecognitionService.recognize(
        audioData,
        session.audioLanguage
      );

      if (!recognitionResult.text.trim()) {
        return;
      }

      const rawText = recognitionResult.text.trim();
      const previousText = session.extension.lastRecognizedText;
      
      const deduplicationResult = DeduplicationService.deduplicate(
        previousText,
        rawText,
        session.extension.overlapDuration
      );

      if (deduplicationResult.isDuplicate) {
        console.log(`Duplicate detected for sequence ${sequence}, skipping`);
        return;
      }

      const finalText = deduplicationResult.text.trim();
      if (!finalText) {
        console.log(`Empty text after deduplication for sequence ${sequence}`);
        return;
      }

      session.extension.lastRecognizedText = DeduplicationService.mergeTexts(
        previousText,
        rawText,
        deduplicationResult
      );

      const confidence = recognitionResult.confidence;
      const status = SpeechRecognitionService.isConfidenceHigh(confidence)
        ? 'confirmed'
        : 'pending';

      const startTime = this.calculateStartTime(session, timestamp);
      const endTime = startTime + STEP_DURATION;

      const segment: Segment = {
        segmentId: sequence,
        startTime,
        endTime,
        originalText: finalText,
        confidence,
        status,
      };

      session.segments.push(segment);

      const result: TranscribeResult = {
        event: 'transcribe_result',
        data: {
          segmentId: sequence,
          text: finalText,
          confidence,
          status,
          startTime,
          endTime,
        },
      };

      this.sendToClient(clientId, result);

      if (session.enableTranslation && session.targetLanguage) {
        session.extension.pendingTranslations.set(sequence, {
          segmentId: sequence,
          originalText: finalText,
          targetLanguage: session.targetLanguage,
          sourceLanguage: recognitionResult.language,
        });

        this.processPendingTranslation(clientId, session, sequence)
          .catch((error) => {
            console.error('Translation error:', error);
          });
      }
    } catch (error) {
      console.error('Error processing audio chunk:', error);
      this.sendToClient(clientId, {
        event: 'error',
        data: { message: 'Failed to process audio chunk' },
      });
    }
  }

  private async processPendingTranslation(
    clientId: string,
    session: ExtendedSessionState,
    segmentId: number
  ): Promise<void> {
    const pending = session.extension.pendingTranslations.get(segmentId);
    if (!pending) return;

    session.extension.pendingTranslations.delete(segmentId);

    try {
      const translationResult = await TranslationService.translate(
        pending.originalText,
        pending.targetLanguage,
        pending.sourceLanguage
      );

      const translatedText = translationResult.translatedText;

      const segmentIndex = session.segments.findIndex(s => s.segmentId === segmentId);
      if (segmentIndex !== -1) {
        session.segments[segmentIndex].translatedText = translatedText;
      }

      const update: TranslationUpdate = {
        event: 'translation_update',
        data: {
          segmentId,
          translatedText,
        },
      };

      this.sendToClient(clientId, update);
    } catch (error) {
      console.error('Translation failed for segment', segmentId, ':', error);
    }
  }

  private async handleEndSession(clientId: string, message: EndSessionMessage): Promise<void> {
    const client = this.clients.get(clientId);
    if (!client || !client.sessionId) return;

    const session = this.sessions.get(client.sessionId);
    if (!session) return;

    session.isActive = false;

    let waitCount = 0;
    const maxWait = 100;
    while ((this.audioQueue.length > 0 || this.concurrentProcessing > 0) && waitCount < maxWait) {
      await this.delay(100);
      waitCount++;
    }

    const totalDuration = Math.floor((Date.now() - session.startTime) / 1000);

    const record = DatabaseService.createRecord({
      sessionId: session.sessionId,
      audioLanguage: session.audioLanguage,
      targetLanguage: session.targetLanguage,
      segments: session.segments,
      totalDuration,
    });

    console.log(`Session ended: ${session.sessionId}, saved as ${record.transcribeId}`);

    this.sendToClient(clientId, {
      event: 'session_ended',
      data: {
        transcribeId: record.transcribeId,
        totalDuration,
        segmentCount: session.segments.length,
      },
    });

    this.sessions.delete(client.sessionId);
    client.sessionId = undefined;
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  private handleDisconnect(clientId: string): void {
    const client = this.clients.get(clientId);
    if (!client) return;

    if (client.sessionId) {
      const session = this.sessions.get(client.sessionId);
      if (session && session.isActive) {
        this.handleEndSession(clientId, {
          action: 'end_session',
          data: { sessionId: client.sessionId },
        });
      }
    }

    this.clients.delete(clientId);
  }

  private calculateStartTime(session: ExtendedSessionState, timestamp: number): number {
    if (session.segments.length === 0) {
      return 0;
    }

    const lastSegment = session.segments[session.segments.length - 1];
    return lastSegment.endTime;
  }

  private sendToClient(clientId: string, message: unknown): void {
    const client = this.clients.get(clientId);
    if (!client || client.ws.readyState !== WebSocket.OPEN) return;

    try {
      client.ws.send(JSON.stringify(message));
    } catch (error) {
      console.error('Error sending message to client:', error);
    }
  }

  public getActiveSessions(): SessionState[] {
    return Array.from(this.sessions.values()).filter(s => s.isActive);
  }

  public getSession(sessionId: string): SessionState | undefined {
    return this.sessions.get(sessionId);
  }

  public getQueueSize(): number {
    return this.audioQueue.length;
  }

  public getConcurrentProcessing(): number {
    return this.concurrentProcessing;
  }
}
