const express = require('express');
const router = express.Router();

const documentsRouter = require('./documents');
const versionsRouter = require('./versions');
const searchRouter = require('./search');
const categoriesRouter = require('./categories');
const sharesRouter = require('./shares');
const commentsRouter = require('./comments');
const favoritesRouter = require('./favorites');

router.use('/docs', documentsRouter);
router.use('/versions', versionsRouter);
router.use('/search', searchRouter);
router.use('/categories', categoriesRouter);
router.use('/shares', sharesRouter);
router.use('/comments', commentsRouter);
router.use('/favorites', favoritesRouter);

router.get('/health', (req, res) => {
  res.json({
    code: 200,
    data: {
      status: 'ok',
      timestamp: new Date().toISOString()
    }
  });
});

module.exports = router;
