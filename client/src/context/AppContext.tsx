import React, { createContext, useContext, useReducer, useCallback, useEffect, useRef } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { 
  Segment, 
  AppSettings, 
  DEFAULT_SETTINGS, 
  TranscribeResult,
  TranslationUpdate,
  SessionState
} from '../types';
import { wsService } from '../services/websocket';
import { audioRecorderService, AudioChunk } from '../services/audioRecorder';
import { audioBufferService } from '../services/audioBuffer';

interface AppState {
  settings: AppSettings;
  session: SessionState | null;
  segments: Segment[];
  isRecording: boolean;
  isConnected: boolean;
  volumeLevel: number;
  error: string | null;
}

type AppAction =
  | { type: 'SET_SETTINGS'; payload: Partial<AppSettings> }
  | { type: 'START_SESSION'; payload: { sessionId: string; config: SessionState['config'] } }
  | { type: 'END_SESSION'; payload: { transcribeId: string; totalDuration: number } }
  | { type: 'ADD_SEGMENT'; payload: TranscribeResult['data'] }
  | { type: 'UPDATE_SEGMENT_TRANSLATION'; payload: TranslationUpdate['data'] }
  | { type: 'SET_RECORDING'; payload: boolean }
  | { type: 'SET_CONNECTED'; payload: boolean }
  | { type: 'SET_VOLUME'; payload: number }
  | { type: 'SET_ERROR'; payload: string | null }
  | { type: 'CLEAR_SEGMENTS' };

const initialState: AppState = {
  settings: DEFAULT_SETTINGS,
  session: null,
  segments: [],
  isRecording: false,
  isConnected: false,
  volumeLevel: 0,
  error: null,
};

function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    case 'SET_SETTINGS':
      return {
        ...state,
        settings: { ...state.settings, ...action.payload },
      };
    case 'START_SESSION':
      return {
        ...state,
        session: {
          sessionId: action.payload.sessionId,
          isActive: true,
          startTime: Date.now(),
          segments: [],
          config: action.payload.config,
        },
        segments: [],
      };
    case 'END_SESSION':
      return {
        ...state,
        session: state.session ? { ...state.session, isActive: false } : null,
        isRecording: false,
      };
    case 'ADD_SEGMENT': {
      const segment: Segment = {
        segmentId: action.payload.segmentId,
        startTime: action.payload.startTime,
        endTime: action.payload.endTime,
        originalText: action.payload.text,
        translatedText: action.payload.translatedText,
        confidence: action.payload.confidence,
        status: action.payload.status,
      };
      return {
        ...state,
        segments: [...state.segments, segment],
      };
    }
    case 'UPDATE_SEGMENT_TRANSLATION': {
      const { segmentId, translatedText } = action.payload;
      return {
        ...state,
        segments: state.segments.map(segment => 
          segment.segmentId === segmentId
            ? { ...segment, translatedText }
            : segment
        ),
      };
    }
    case 'SET_RECORDING':
      return { ...state, isRecording: action.payload };
    case 'SET_CONNECTED':
      return { ...state, isConnected: action.payload };
    case 'SET_VOLUME':
      return { ...state, volumeLevel: action.payload };
    case 'SET_ERROR':
      return { ...state, error: action.payload };
    case 'CLEAR_SEGMENTS':
      return { ...state, segments: [] };
    default:
      return state;
  }
}

interface AppContextType {
  state: AppState;
  dispatch: React.Dispatch<AppAction>;
  startRecording: () => Promise<void>;
  stopRecording: () => Promise<void>;
  updateSettings: (settings: Partial<AppSettings>) => void;
  clearSegments: () => void;
}

const AppContext = createContext<AppContextType | null>(null);

export function AppProvider({ children }: { children: React.ReactNode }) {
  const [state, dispatch] = useReducer(appReducer, initialState);
  const unmountedRef = useRef(false);

  useEffect(() => {
    const setupWebSocket = async () => {
      try {
        await wsService.connect();
        dispatch({ type: 'SET_CONNECTED', payload: true });
      } catch (error) {
        dispatch({ type: 'SET_ERROR', payload: '无法连接到服务器' });
      }
    };

    setupWebSocket();

    const unsub1 = wsService.onOpen(() => {
      dispatch({ type: 'SET_CONNECTED', payload: true });
    });

    const unsub2 = wsService.onClose(() => {
      dispatch({ type: 'SET_CONNECTED', payload: false });
    });

    const unsub3 = wsService.onError(() => {
      dispatch({ type: 'SET_ERROR', payload: 'WebSocket连接错误' });
    });

    const unsub4 = wsService.onTranscribeResult((data) => {
      dispatch({ type: 'ADD_SEGMENT', payload: data });
    });

    const unsub5 = wsService.onSessionStarted((data) => {
      console.log('Session started:', data.sessionId);
    });

    const unsub6 = wsService.onSessionEnded((data) => {
      dispatch({ 
        type: 'END_SESSION', 
        payload: { 
          transcribeId: data.transcribeId, 
          totalDuration: data.totalDuration 
        } 
      });
    });

    const unsub7 = wsService.onTranslationUpdate((data) => {
      dispatch({ type: 'UPDATE_SEGMENT_TRANSLATION', payload: data });
    });

    const unsub8 = wsService.onChunkAck((data) => {
      console.log(`Chunk ${data.sequence} acknowledged: ${data.received}`);
    });

    return () => {
      unmountedRef.current = true;
      unsub1();
      unsub2();
      unsub3();
      unsub4();
      unsub5();
      unsub6();
      unsub7();
      unsub8();
      wsService.disconnect();
    };
  }, []);

  useEffect(() => {
    let intervalId: number;

    if (state.isRecording) {
      intervalId = window.setInterval(() => {
        const volume = audioRecorderService.getVolumeLevel();
        dispatch({ type: 'SET_VOLUME', payload: volume });
      }, 100);
    }

    return () => {
      if (intervalId) clearInterval(intervalId);
    };
  }, [state.isRecording]);

  const startRecording = useCallback(async () => {
    try {
      dispatch({ type: 'SET_ERROR', payload: null });

      if (!wsService.isConnected()) {
        await wsService.connect();
        dispatch({ type: 'SET_CONNECTED', payload: true });
      }

      await audioRecorderService.initialize();

      const sessionId = uuidv4();

      const audioChunkUnsub = audioRecorderService.onAudioData((chunk: AudioChunk) => {
        audioBufferService.addChunk(chunk);
      });

      const bufferReadyUnsub = audioBufferService.onBufferReady((base64Data, sequence, metadata) => {
        wsService.sendAudioChunk(base64Data, sequence, metadata);
      });

      audioRecorderService.start();
      audioBufferService.reset();

      wsService.startSession(
        sessionId,
        state.settings.audioLanguage,
        state.settings.targetLanguage,
        state.settings.enableTranslation
      );

      dispatch({
        type: 'START_SESSION',
        payload: {
          sessionId,
          config: {
            audioLanguage: state.settings.audioLanguage,
            targetLanguage: state.settings.enableTranslation ? state.settings.targetLanguage : undefined,
            enableTranslation: state.settings.enableTranslation,
          },
        },
      });
      dispatch({ type: 'SET_RECORDING', payload: true });

    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '无法开始录音';
      dispatch({ type: 'SET_ERROR', payload: errorMessage });
      throw error;
    }
  }, [state.settings]);

  const stopRecording = useCallback(async () => {
    try {
      audioRecorderService.stop();
      audioBufferService.flush();

      if (state.session) {
        wsService.endSession(state.session.sessionId);
      }

      setTimeout(() => {
        audioRecorderService.dispose();
      }, 1000);

    } catch (error) {
      console.error('Error stopping recording:', error);
    }
  }, [state.session]);

  const updateSettings = useCallback((settings: Partial<AppSettings>) => {
    dispatch({ type: 'SET_SETTINGS', payload: settings });
  }, []);

  const clearSegments = useCallback(() => {
    dispatch({ type: 'CLEAR_SEGMENTS' });
  }, []);

  const value = {
    state,
    dispatch,
    startRecording,
    stopRecording,
    updateSettings,
    clearSegments,
  };

  return (
    <AppContext.Provider value={value}>
      {children}
    </AppContext.Provider>
  );
}

export function useAppContext() {
  const context = useContext(AppContext);
  if (!context) {
    throw new Error('useAppContext must be used within an AppProvider');
  }
  return context;
}
