import { router } from '../trpc';
import { authRouter } from './auth.router';
import { spaceRouter } from './space.router';
import { documentRouter } from './document.router';
import { searchRouter } from './search.router';
import { tagRouter } from './tag.router';
import { commentRouter } from './comment.router';
import { reviewRouter } from './review.router';
import { syncRouter } from './sync.router';
import { recommendationRouter } from './recommendation.router';
import { aiqaRouter } from './aiqa.router';

export const appRouter = router({
  auth: authRouter,
  space: spaceRouter,
  document: documentRouter,
  search: searchRouter,
  tag: tagRouter,
  comment: commentRouter,
  review: reviewRouter,
  sync: syncRouter,
  recommendation: recommendationRouter,
  aiqa: aiqaRouter,
});

export type AppRouter = typeof appRouter;
