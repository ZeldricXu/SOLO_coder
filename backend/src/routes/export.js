const express = require('express');
const router = express.Router();
const exportService = require('../services/ExportService');

router.get('/html/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { version_number, include_toc = 'true', include_comments = 'false' } = req.query;
    
    const result = await exportService.exportToHTML(doc_id, {
      version_number: version_number ? parseInt(version_number) : undefined,
      include_toc: include_toc === 'true',
      include_comments: include_comments === 'true'
    });
    
    res.set('Content-Type', 'text/html; charset=utf-8');
    res.set('Content-Disposition', `attachment; filename="${encodeURIComponent(result.filename)}"`);
    res.send(result.content);
  } catch (error) {
    console.error('Export HTML error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Document or version not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to export to HTML',
      error: error.message
    });
  }
});

router.get('/pdf/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { 
      version_number, 
      include_toc = 'true', 
      include_comments = 'false',
      format = 'A4',
      orientation = 'portrait'
    } = req.query;
    
    const result = await exportService.exportToPDF(doc_id, {
      version_number: version_number ? parseInt(version_number) : undefined,
      include_toc: include_toc === 'true',
      include_comments: include_comments === 'true',
      format,
      orientation,
      margin: {
        top: '20mm',
        right: '20mm',
        bottom: '20mm',
        left: '20mm'
      }
    });
    
    res.set('Content-Type', 'application/pdf');
    res.set('Content-Disposition', `attachment; filename="${encodeURIComponent(result.filename)}"`);
    res.send(result.content);
  } catch (error) {
    console.error('Export PDF error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Document or version not found'
      });
    }
    
    if (error.message.includes('Puppeteer')) {
      return res.status(503).json({
        code: 503,
        message: 'PDF export service is unavailable. Please try HTML export instead.',
        error: error.message
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to export to PDF',
      error: error.message
    });
  }
});

router.get('/preview/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { version_number, include_toc = 'true' } = req.query;
    
    const result = await exportService.exportToHTML(doc_id, {
      version_number: version_number ? parseInt(version_number) : undefined,
      include_toc: include_toc === 'true',
      include_comments: false
    });
    
    res.set('Content-Type', 'text/html; charset=utf-8');
    res.send(result.content);
  } catch (error) {
    console.error('Preview error:', error);
    
    if (error.message.includes('not found')) {
      return res.status(404).json({
        code: 404,
        message: 'Document or version not found'
      });
    }
    
    res.status(500).json({
      code: 500,
      message: 'Failed to generate preview',
      error: error.message
    });
  }
});

router.post('/batch', async (req, res) => {
  try {
    const { doc_ids, format = 'html', options = {} } = req.body;
    
    if (!doc_ids || !Array.isArray(doc_ids) || doc_ids.length === 0) {
      return res.status(400).json({
        code: 400,
        message: 'doc_ids must be a non-empty array'
      });
    }
    
    const results = await exportService.batchExport(doc_ids, format, options);
    
    const successResults = results.filter(r => r.success);
    const failedResults = results.filter(r => !r.success);
    
    res.json({
      code: 200,
      data: {
        total: results.length,
        success_count: successResults.length,
        failed_count: failedResults.length,
        results,
        format
      }
    });
  } catch (error) {
    console.error('Batch export error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to execute batch export',
      error: error.message
    });
  }
});

router.get('/formats', async (req, res) => {
  try {
    res.json({
      code: 200,
      data: {
        formats: [
          {
            id: 'html',
            name: 'HTML',
            description: 'Export as HTML document with styling',
            supported_options: ['include_toc', 'include_comments', 'version_number']
          },
          {
            id: 'pdf',
            name: 'PDF',
            description: 'Export as PDF document',
            supported_options: ['include_toc', 'include_comments', 'version_number', 'format', 'orientation']
          }
        ],
        paper_formats: ['A4', 'Letter', 'Legal', 'Tabloid', 'Ledger', 'A0', 'A1', 'A2', 'A3', 'A5', 'A6'],
        orientations: ['portrait', 'landscape']
      }
    });
  } catch (error) {
    console.error('Get formats error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get export formats',
      error: error.message
    });
  }
});

router.post('/cleanup-temp', async (req, res) => {
  try {
    exportService.cleanupTempFiles();
    
    res.json({
      code: 200,
      data: {
        message: 'Temp files cleanup initiated'
      }
    });
  } catch (error) {
    console.error('Cleanup temp files error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to cleanup temp files',
      error: error.message
    });
  }
});

module.exports = router;
