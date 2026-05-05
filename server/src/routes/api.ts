import { Router, Request, Response } from 'express';
import { DatabaseService } from '../services/database';
import { WebSocketHandler } from '../websocket/handler';

export const createApiRouter = (wsHandler: WebSocketHandler): Router => {
  const router = Router();

  router.get('/health', (req: Request, res: Response) => {
    res.json({
      status: 'ok',
      timestamp: new Date().toISOString(),
      activeSessions: wsHandler.getActiveSessions().length,
    });
  });

  router.get('/v1/transcribe/history', (req: Request, res: Response) => {
    try {
      const limit = parseInt(req.query.limit as string) || 20;
      const offset = parseInt(req.query.offset as string) || 0;
      const startTime = req.query.startTime as string | undefined;
      const endTime = req.query.endTime as string | undefined;

      const result = DatabaseService.getHistory({
        limit,
        offset,
        startTime,
        endTime,
      });

      res.json({
        code: 200,
        data: {
          records: result.records.map(record => ({
            transcribe_id: record.transcribeId,
            session_id: record.sessionId,
            duration: record.totalDuration,
            segment_count: record.segments.length,
            audio_language: record.audioLanguage,
            target_language: record.targetLanguage,
            created_at: record.createdAt,
          })),
          total: result.total,
          limit,
          offset,
        },
      });
    } catch (error) {
      console.error('Error fetching history:', error);
      res.status(500).json({
        code: 500,
        error: 'Failed to fetch history',
      });
    }
  });

  router.get('/v1/transcribe/:id', (req: Request, res: Response) => {
    try {
      const { id } = req.params;
      const record = DatabaseService.getRecord(id);

      if (!record) {
        res.status(404).json({
          code: 404,
          error: 'Transcribe record not found',
        });
        return;
      }

      res.json({
        code: 200,
        data: {
          transcribe_id: record.transcribeId,
          session_id: record.sessionId,
          audio_language: record.audioLanguage,
          target_language: record.targetLanguage,
          segments: record.segments.map(segment => ({
            segment_id: segment.segmentId,
            start_time: segment.startTime,
            end_time: segment.endTime,
            original_text: segment.originalText,
            translated_text: segment.translatedText,
            confidence: segment.confidence,
            status: segment.status,
          })),
          total_duration: record.totalDuration,
          created_at: record.createdAt,
        },
      });
    } catch (error) {
      console.error('Error fetching record:', error);
      res.status(500).json({
        code: 500,
        error: 'Failed to fetch record',
      });
    }
  });

  router.delete('/v1/transcribe/:id', (req: Request, res: Response) => {
    try {
      const { id } = req.params;
      const record = DatabaseService.getRecord(id);

      if (!record) {
        res.status(404).json({
          code: 404,
          error: 'Transcribe record not found',
        });
        return;
      }

      DatabaseService.deleteRecord(id);

      res.json({
        code: 200,
        message: 'Record deleted successfully',
      });
    } catch (error) {
      console.error('Error deleting record:', error);
      res.status(500).json({
        code: 500,
        error: 'Failed to delete record',
      });
    }
  });

  router.get('/v1/sessions/active', (req: Request, res: Response) => {
    try {
      const activeSessions = wsHandler.getActiveSessions();

      res.json({
        code: 200,
        data: {
          sessions: activeSessions.map(session => ({
            session_id: session.sessionId,
            audio_language: session.audioLanguage,
            target_language: session.targetLanguage,
            enable_translation: session.enableTranslation,
            start_time: new Date(session.startTime).toISOString(),
            segment_count: session.segments.length,
          })),
          count: activeSessions.length,
        },
      });
    } catch (error) {
      console.error('Error fetching active sessions:', error);
      res.status(500).json({
        code: 500,
        error: 'Failed to fetch active sessions',
      });
    }
  });

  return router;
};
