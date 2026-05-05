const express = require('express');
const router = express.Router();
const Document = require('../models/Document');
const searchService = require('../services/SearchService');
const collaborationService = require('../services/CollaborationService');
const { v4: uuidv4 } = require('uuid');

const DEFAULT_USER_ID = 'user_default';

router.get('/', async (req, res) => {
  try {
    const { folder_id, limit = 20, offset = 0 } = req.query;
    
    const query = {};
    if (folder_id) {
      query.folder_id = folder_id;
    }
    
    const [documents, total] = await Promise.all([
      Document.find(query)
        .sort({ last_edited_at: -1 })
        .skip(parseInt(offset))
        .limit(parseInt(limit))
        .exec(),
      Document.countDocuments(query)
    ]);
    
    res.json({
      code: 200,
      data: {
        documents: documents.map(doc => doc.toObject()),
        total,
        offset: parseInt(offset),
        limit: parseInt(limit)
      }
    });
  } catch (error) {
    console.error('Get documents error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get documents',
      error: error.message
    });
  }
});

router.get('/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      return res.status(404).json({
        code: 404,
        message: 'Document not found'
      });
    }
    
    const collaborators = collaborationService.getActiveCollaborators(doc_id);
    
    res.json({
      code: 200,
      data: {
        ...doc.toObject(),
        active_collaborators: collaborators
      }
    });
  } catch (error) {
    console.error('Get document error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to get document',
      error: error.message
    });
  }
});

router.post('/', async (req, res) => {
  try {
    const { title, content = '', format = 'markdown', folder_id = null, created_by } = req.body;
    
    if (!title) {
      return res.status(400).json({
        code: 400,
        message: 'Title is required'
      });
    }
    
    const userId = created_by || DEFAULT_USER_ID;
    
    const document = new Document({
      doc_id: uuidv4(),
      title,
      content,
      format,
      folder_id,
      created_by: userId,
      collaborators: [userId],
      current_version: 1,
      last_edited_by: userId,
      last_edited_at: new Date(),
      is_locked: false
    });
    
    await document.save();
    
    await searchService.indexDocument(document);
    
    res.status(201).json({
      code: 201,
      data: document.toObject()
    });
  } catch (error) {
    console.error('Create document error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to create document',
      error: error.message
    });
  }
});

router.put('/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { title, content, folder_id, last_edited_by } = req.body;
    
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      return res.status(404).json({
        code: 404,
        message: 'Document not found'
      });
    }
    
    const updates = {};
    if (title !== undefined) updates.title = title;
    if (content !== undefined) updates.content = content;
    if (folder_id !== undefined) updates.folder_id = folder_id;
    
    updates.last_edited_by = last_edited_by || DEFAULT_USER_ID;
    updates.last_edited_at = new Date();
    
    const oldContent = doc.content;
    Object.assign(doc, updates);
    await doc.save();
    
    searchService.queueIncrementalUpdate(doc);
    
    res.json({
      code: 200,
      data: doc.toObject()
    });
  } catch (error) {
    console.error('Update document error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to update document',
      error: error.message
    });
  }
});

router.delete('/:doc_id', async (req, res) => {
  try {
    const { doc_id } = req.params;
    
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      return res.status(404).json({
        code: 404,
        message: 'Document not found'
      });
    }
    
    await Document.deleteOne({ doc_id });
    
    await searchService.deleteDocument(doc_id);
    
    res.json({
      code: 200,
      data: {
        deleted: true,
        doc_id
      }
    });
  } catch (error) {
    console.error('Delete document error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to delete document',
      error: error.message
    });
  }
});

router.post('/:doc_id/collaborators', async (req, res) => {
  try {
    const { doc_id } = req.params;
    const { user_id } = req.body;
    
    if (!user_id) {
      return res.status(400).json({
        code: 400,
        message: 'user_id is required'
      });
    }
    
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      return res.status(404).json({
        code: 404,
        message: 'Document not found'
      });
    }
    
    if (!doc.collaborators.includes(user_id)) {
      doc.collaborators.push(user_id);
      await doc.save();
    }
    
    res.json({
      code: 200,
      data: {
        collaborators: doc.collaborators
      }
    });
  } catch (error) {
    console.error('Add collaborator error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to add collaborator',
      error: error.message
    });
  }
});

router.delete('/:doc_id/collaborators/:user_id', async (req, res) => {
  try {
    const { doc_id, user_id } = req.params;
    
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      return res.status(404).json({
        code: 404,
        message: 'Document not found'
      });
    }
    
    if (doc.created_by === user_id) {
      return res.status(400).json({
        code: 400,
        message: 'Cannot remove document owner from collaborators'
      });
    }
    
    doc.collaborators = doc.collaborators.filter(id => id !== user_id);
    await doc.save();
    
    res.json({
      code: 200,
      data: {
        collaborators: doc.collaborators
      }
    });
  } catch (error) {
    console.error('Remove collaborator error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to remove collaborator',
      error: error.message
    });
  }
});

router.post('/:doc_id/lock', async (req, res) => {
  try {
    const { doc_id } = req.params;
    
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      return res.status(404).json({
        code: 404,
        message: 'Document not found'
      });
    }
    
    doc.is_locked = true;
    await doc.save();
    
    res.json({
      code: 200,
      data: {
        doc_id,
        is_locked: true
      }
    });
  } catch (error) {
    console.error('Lock document error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to lock document',
      error: error.message
    });
  }
});

router.post('/:doc_id/unlock', async (req, res) => {
  try {
    const { doc_id } = req.params;
    
    const doc = await Document.findOne({ doc_id });
    
    if (!doc) {
      return res.status(404).json({
        code: 404,
        message: 'Document not found'
      });
    }
    
    doc.is_locked = false;
    await doc.save();
    
    res.json({
      code: 200,
      data: {
        doc_id,
        is_locked: false
      }
    });
  } catch (error) {
    console.error('Unlock document error:', error);
    res.status(500).json({
      code: 500,
      message: 'Failed to unlock document',
      error: error.message
    });
  }
});

module.exports = router;
