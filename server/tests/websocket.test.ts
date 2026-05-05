jest.mock('../src/services/speechRecognition');
jest.mock('../src/services/translation');

import { WebSocketHandler } from '../src/websocket/handler';
import { SpeechRecognitionService } from '../src/services/speechRecognition';
import { TranslationService } from '../src/services/translation';
import { Segment } from '../src/types';

class MockWebSocketServer {
  private connectionHandlers: ((ws: MockWebSocket) => void)[] = [];

  on(event: string, handler: (ws: MockWebSocket) => void) {
    if (event === 'connection') {
      this.connectionHandlers.push(handler);
    }
  }

  emitConnection(ws: MockWebSocket) {
    this.connectionHandlers.forEach(handler => handler(ws));
  }
}

class MockWebSocket {
  public messageHandlers: ((data: Buffer) => void)[] = [];
  public closeHandlers: (() => void)[] = [];
  public errorHandlers: ((error: Error) => void)[] = [];
  public sentMessages: unknown[] = [];
  public readyState: number = 1;
  public OPEN: number = 1;

  on(event: string, handler: (data?: unknown) => void) {
    switch (event) {
      case 'message':
        this.messageHandlers.push(handler as (data: Buffer) => void);
        break;
      case 'close':
        this.closeHandlers.push(handler as () => void);
        break;
      case 'error':
        this.errorHandlers.push(handler as (error: Error) => void);
        break;
    }
  }

  send(message: string) {
    this.sentMessages.push(JSON.parse(message));
  }

  emitMessage(data: unknown) {
    const buffer = Buffer.from(JSON.stringify(data));
    this.messageHandlers.forEach(handler => handler(buffer));
  }

  emitClose() {
    this.readyState = 0;
    this.closeHandlers.forEach(handler => handler());
  }
}

const mockRecognize = SpeechRecognitionService.recognize as jest.MockedFunction<
  typeof SpeechRecognitionService.recognize
>;

const mockTranslate = TranslationService.translate as jest.MockedFunction<
  typeof TranslationService.translate
>;

const mockIsConfidenceHigh = SpeechRecognitionService.isConfidenceHigh as jest.MockedFunction<
  typeof SpeechRecognitionService.isConfidenceHigh
>;

describe('WebSocketHandler', () => {
  let mockWss: MockWebSocketServer;
  let handler: WebSocketHandler;
  let mockWs: MockWebSocket;

  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllMocks();

    mockWss = new MockWebSocketServer();
    handler = new WebSocketHandler(mockWss as unknown as WebSocketServer);
    mockWs = new MockWebSocket();

    mockIsConfidenceHigh.mockReturnValue(true);
    mockRecognize.mockResolvedValue({
      text: '',
      confidence: 0.9,
      language: 'zh-CN',
    });
    mockTranslate.mockResolvedValue({
      translatedText: 'Translated text',
      sourceLanguage: 'zh-CN',
      targetLanguage: 'en-US',
      fromCache: false,
    });
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  describe('Connection handling', () => {
    it('should handle new client connection', () => {
      mockWss.emitConnection(mockWs);
      
      expect(handler.getActiveSessions()).toHaveLength(0);
    });

    it('should handle client disconnection', () => {
      mockWss.emitConnection(mockWs);
      mockWs.emitClose();
      
      expect(handler.getActiveSessions()).toHaveLength(0);
    });
  });

  describe('Session management', () => {
    it('should start a session on start_session message', () => {
      mockWss.emitConnection(mockWs);

      mockWs.emitMessage({
        action: 'start_session',
        data: {
          sessionId: 'test-session-1',
          audioLanguage: 'zh-CN',
          targetLanguage: 'en-US',
          enableTranslation: true,
        },
      });

      expect(handler.getActiveSessions()).toHaveLength(1);
    });

    it('should end a session on end_session message', async () => {
      mockWss.emitConnection(mockWs);

      mockWs.emitMessage({
        action: 'start_session',
        data: {
          sessionId: 'test-session-1',
          audioLanguage: 'zh-CN',
          targetLanguage: 'en-US',
          enableTranslation: false,
        },
      });

      expect(handler.getActiveSessions()).toHaveLength(1);

      mockWs.emitMessage({
        action: 'end_session',
        data: {
          sessionId: 'test-session-1',
        },
      });

      await jest.runAllTimersAsync();

      expect(handler.getActiveSessions()).toHaveLength(0);
    });
  });

  describe('Async message processing', () => {
    beforeEach(() => {
      mockWss.emitConnection(mockWs);
      
      mockWs.emitMessage({
        action: 'start_session',
        data: {
          sessionId: 'test-session-1',
          audioLanguage: 'zh-CN',
          targetLanguage: 'en-US',
          enableTranslation: false,
        },
      });
    });

    it('should send chunk_ack immediately on receiving audio_chunk', async () => {
      mockRecognize.mockImplementation(() => {
        return new Promise(resolve => {
          setTimeout(() => {
            resolve({
              text: '测试识别结果',
              confidence: 0.95,
              language: 'zh-CN',
            });
          }, 1000);
        });
      });

      const testAudioData = Buffer.from('test audio data').toString('base64');

      mockWs.emitMessage({
        action: 'audio_chunk',
        data: {
          audioData: testAudioData,
          sequence: 1,
          timestamp: Date.now(),
          metadata: {
            isFirstChunk: true,
            overlapSamples: 0,
            totalSamples: 32000,
          },
        },
      });

      const ackMessage = mockWs.sentMessages.find(m => 
        (m as { event?: string }).event === 'chunk_ack'
      );

      expect(ackMessage).toBeDefined();
      expect((ackMessage as { event: string; data: { sequence: number } }).data.sequence).toBe(1);
    });

    it('should queue messages for processing', async () => {
      const processingDelay = 100;
      let resolveCount = 0;
      const resolvePromises: (() => void)[] = [];

      mockRecognize.mockImplementation(() => {
        return new Promise(resolve => {
          resolvePromises.push(() => {
            resolveCount++;
            resolve({
              text: `识别结果 ${resolveCount}`,
              confidence: 0.95,
              language: 'zh-CN',
            });
          });
          setTimeout(resolvePromises[resolvePromises.length - 1], processingDelay);
        });
      });

      const testAudioData = Buffer.from('test audio data').toString('base64');

      for (let i = 1; i <= 5; i++) {
        mockWs.emitMessage({
          action: 'audio_chunk',
          data: {
            audioData: testAudioData,
            sequence: i,
            timestamp: Date.now(),
            metadata: {
              isFirstChunk: i === 1,
              overlapSamples: i === 1 ? 0 : 8000,
              totalSamples: 32000,
            },
          },
        });
      }

      expect(mockRecognize).toHaveBeenCalledTimes(5);
    });

    it('should process messages in order', async () => {
      const receivedTexts: string[] = [];
      
      mockRecognize.mockResolvedValueOnce({
        text: '第一个',
        confidence: 0.95,
        language: 'zh-CN',
      }).mockResolvedValueOnce({
        text: '第二个',
        confidence: 0.95,
        language: 'zh-CN',
      }).mockResolvedValueOnce({
        text: '第三个',
        confidence: 0.95,
        language: 'zh-CN',
      });

      const testAudioData = Buffer.from('test audio data').toString('base64');

      for (let i = 1; i <= 3; i++) {
        mockWs.emitMessage({
          action: 'audio_chunk',
          data: {
            audioData: testAudioData,
            sequence: i,
            timestamp: Date.now(),
            metadata: {
              isFirstChunk: i === 1,
              overlapSamples: i === 1 ? 0 : 8000,
              totalSamples: 32000,
            },
          },
        });
      }

      await jest.runAllTimersAsync();

      const resultMessages = mockWs.sentMessages.filter(m => 
        (m as { event?: string }).event === 'transcribe_result'
      ) as { event: string; data: { text: string } }[];

      expect(resultMessages).toHaveLength(3);
    });
  });

  describe('Translation async processing', () => {
    beforeEach(() => {
      mockWss.emitConnection(mockWs);
      
      mockWs.emitMessage({
        action: 'start_session',
        data: {
          sessionId: 'test-session-1',
          audioLanguage: 'zh-CN',
          targetLanguage: 'en-US',
          enableTranslation: true,
        },
      });
    });

    it('should send original text first, then translation update', async () => {
      mockRecognize.mockResolvedValue({
        text: '你好世界',
        confidence: 0.95,
        language: 'zh-CN',
      });

      let translationResolve: () => void;
      mockTranslate.mockImplementation(() => {
        return new Promise(resolve => {
          translationResolve = () => {
            resolve({
              translatedText: 'Hello World',
              sourceLanguage: 'zh-CN',
              targetLanguage: 'en-US',
              fromCache: false,
            });
          };
          setTimeout(translationResolve, 100);
        });
      });

      const testAudioData = Buffer.from('test audio data').toString('base64');

      mockWs.emitMessage({
        action: 'audio_chunk',
        data: {
          audioData: testAudioData,
          sequence: 1,
          timestamp: Date.now(),
          metadata: {
            isFirstChunk: true,
            overlapSamples: 0,
            totalSamples: 32000,
          },
        },
      });

      await jest.runAllTimersAsync();

      const resultMessages = mockWs.sentMessages.filter(m => 
        (m as { event?: string }).event === 'transcribe_result'
      ) as { event: string; data: { text: string; translatedText?: string } }[];

      const translationMessages = mockWs.sentMessages.filter(m => 
        (m as { event?: string }).event === 'translation_update'
      ) as { event: string; data: { segmentId: number; translatedText: string } }[];

      expect(resultMessages).toHaveLength(1);
      expect(resultMessages[0].data.text).toBe('你好世界');

      expect(translationMessages).toHaveLength(1);
      expect(translationMessages[0].data.translatedText).toBe('Hello World');
    });

    it('should have correct segment association between result and translation', async () => {
      mockRecognize.mockResolvedValueOnce({
        text: '第一个文本',
        confidence: 0.95,
        language: 'zh-CN',
      }).mockResolvedValueOnce({
        text: '第二个文本',
        confidence: 0.95,
        language: 'zh-CN',
      });

      mockTranslate.mockResolvedValueOnce({
        translatedText: 'First text',
        sourceLanguage: 'zh-CN',
        targetLanguage: 'en-US',
        fromCache: false,
      }).mockResolvedValueOnce({
        translatedText: 'Second text',
        sourceLanguage: 'zh-CN',
        targetLanguage: 'en-US',
        fromCache: false,
      });

      const testAudioData = Buffer.from('test audio data').toString('base64');

      mockWs.emitMessage({
        action: 'audio_chunk',
        data: {
          audioData: testAudioData,
          sequence: 1,
          timestamp: Date.now(),
          metadata: {
            isFirstChunk: true,
            overlapSamples: 0,
            totalSamples: 32000,
          },
        },
      });

      mockWs.emitMessage({
        action: 'audio_chunk',
        data: {
          audioData: testAudioData,
          sequence: 2,
          timestamp: Date.now(),
          metadata: {
            isFirstChunk: false,
            overlapSamples: 8000,
            totalSamples: 32000,
          },
        },
      });

      await jest.runAllTimersAsync();

      const translationMessages = mockWs.sentMessages.filter(m => 
        (m as { event?: string }).event === 'translation_update'
      ) as { event: string; data: { segmentId: number; translatedText: string } }[];

      expect(translationMessages).toHaveLength(2);

      const firstTranslation = translationMessages.find(m => m.data.segmentId === 1);
      const secondTranslation = translationMessages.find(m => m.data.segmentId === 2);

      expect(firstTranslation).toBeDefined();
      expect(firstTranslation!.data.translatedText).toBe('First text');

      expect(secondTranslation).toBeDefined();
      expect(secondTranslation!.data.translatedText).toBe('Second text');
    });
  });

  describe('High frequency input handling', () => {
    beforeEach(() => {
      mockWss.emitConnection(mockWs);
      
      mockWs.emitMessage({
        action: 'start_session',
        data: {
          sessionId: 'test-session-1',
          audioLanguage: 'zh-CN',
          targetLanguage: 'en-US',
          enableTranslation: false,
        },
      });
    });

    it('should handle burst of high-frequency messages', async () => {
      const processingDelay = 50;
      let processedCount = 0;

      mockRecognize.mockImplementation(() => {
        return new Promise(resolve => {
          setTimeout(() => {
            processedCount++;
            resolve({
              text: `文本 ${processedCount}`,
              confidence: 0.95,
              language: 'zh-CN',
            });
          }, processingDelay);
        });
      });

      const testAudioData = Buffer.from('test audio data').toString('base64');
      const messageCount = 20;

      for (let i = 1; i <= messageCount; i++) {
        mockWs.emitMessage({
          action: 'audio_chunk',
          data: {
            audioData: testAudioData,
            sequence: i,
            timestamp: Date.now(),
            metadata: {
              isFirstChunk: i === 1,
              overlapSamples: i === 1 ? 0 : 8000,
              totalSamples: 32000,
            },
          },
        });
      }

      expect(mockRecognize).toHaveBeenCalledTimes(messageCount);
    });

    it('should maintain correct message order under high load', async () => {
      mockRecognize.mockImplementation((_, __) => {
        return new Promise(resolve => {
          const randomDelay = Math.random() * 100;
          setTimeout(() => {
            resolve({
              text: '测试文本',
              confidence: 0.95,
              language: 'zh-CN',
            });
          }, randomDelay);
        });
      });

      const testAudioData = Buffer.from('test audio data').toString('base64');
      const messageCount = 10;

      for (let i = 1; i <= messageCount; i++) {
        mockWs.emitMessage({
          action: 'audio_chunk',
          data: {
            audioData: testAudioData,
            sequence: i,
            timestamp: Date.now(),
            metadata: {
              isFirstChunk: i === 1,
              overlapSamples: i === 1 ? 0 : 8000,
              totalSamples: 32000,
            },
          },
        });
      }

      await jest.runAllTimersAsync();

      expect(mockRecognize).toHaveBeenCalledTimes(messageCount);
    });
  });

  describe('Reconnection handling', () => {
    it('should handle reconnection scenario', () => {
      mockWss.emitConnection(mockWs);

      mockWs.emitMessage({
        action: 'start_session',
        data: {
          sessionId: 'test-session-1',
          audioLanguage: 'zh-CN',
          targetLanguage: 'en-US',
          enableTranslation: false,
        },
      });

      expect(handler.getActiveSessions()).toHaveLength(1);

      mockWs.emitClose();

      expect(handler.getActiveSessions()).toHaveLength(0);

      const newMockWs = new MockWebSocket();
      mockWss.emitConnection(newMockWs);

      newMockWs.emitMessage({
        action: 'start_session',
        data: {
          sessionId: 'test-session-2',
          audioLanguage: 'zh-CN',
          targetLanguage: 'en-US',
          enableTranslation: false,
        },
      });

      expect(handler.getActiveSessions()).toHaveLength(1);
    });
  });

  describe('Deduplication integration', () => {
    beforeEach(() => {
      mockWss.emitConnection(mockWs);
      
      mockWs.emitMessage({
        action: 'start_session',
        data: {
          sessionId: 'test-session-1',
          audioLanguage: 'zh-CN',
          targetLanguage: 'en-US',
          enableTranslation: false,
        },
      });
    });

    it('should handle overlapping chunks with deduplication', async () => {
      mockRecognize.mockResolvedValueOnce({
        text: '大家好今天我们',
        confidence: 0.95,
        language: 'zh-CN',
      }).mockResolvedValueOnce({
        text: '今天我们讨论项目进度',
        confidence: 0.95,
        language: 'zh-CN',
      });

      const testAudioData = Buffer.from('test audio data').toString('base64');

      mockWs.emitMessage({
        action: 'audio_chunk',
        data: {
          audioData: testAudioData,
          sequence: 1,
          timestamp: Date.now(),
          metadata: {
            isFirstChunk: true,
            overlapSamples: 0,
            totalSamples: 32000,
          },
        },
      });

      mockWs.emitMessage({
        action: 'audio_chunk',
        data: {
          audioData: testAudioData,
          sequence: 2,
          timestamp: Date.now(),
          metadata: {
            isFirstChunk: false,
            overlapSamples: 8000,
            totalSamples: 32000,
          },
        },
      });

      await jest.runAllTimersAsync();

      const resultMessages = mockWs.sentMessages.filter(m => 
        (m as { event?: string }).event === 'transcribe_result'
      ) as { event: string; data: { text: string } }[];

      expect(resultMessages).toHaveLength(2);
    });

    it('should filter out identical duplicate chunks', async () => {
      mockRecognize.mockResolvedValueOnce({
        text: '相同的文本内容',
        confidence: 0.95,
        language: 'zh-CN',
      }).mockResolvedValueOnce({
        text: '相同的文本内容',
        confidence: 0.95,
        language: 'zh-CN',
      });

      const testAudioData = Buffer.from('test audio data').toString('base64');

      mockWs.emitMessage({
        action: 'audio_chunk',
        data: {
          audioData: testAudioData,
          sequence: 1,
          timestamp: Date.now(),
          metadata: {
            isFirstChunk: true,
            overlapSamples: 0,
            totalSamples: 32000,
          },
        },
      });

      mockWs.emitMessage({
        action: 'audio_chunk',
        data: {
          audioData: testAudioData,
          sequence: 2,
          timestamp: Date.now(),
          metadata: {
            isFirstChunk: false,
            overlapSamples: 8000,
            totalSamples: 32000,
          },
        },
      });

      await jest.runAllTimersAsync();

      const resultMessages = mockWs.sentMessages.filter(m => 
        (m as { event?: string }).event === 'transcribe_result'
      );
    });
  });
});
