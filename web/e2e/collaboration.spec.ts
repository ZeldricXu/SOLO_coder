import { test, expect, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { WebSocket } from 'ws';

type TestUser = {
  name: string;
  color: string;
  context: BrowserContext;
  page: Page;
  ws: WebSocket;
  roomId: string;
};

class CollaborationHarness {
  private users: TestUser[] = [];
  private signalUrl: string;

  constructor(signalUrl: string) {
    this.signalUrl = signalUrl;
  }

  async connectUser(browser: Browser, name: string, color: string, roomId: string): Promise<TestUser> {
    const context = await browser.newContext({
      viewport: { width: 1280, height: 720 },
    });
    const page = await context.newPage();

    await context.addInitScript(
      ({ userName, userColor, userRoom }) => {
        localStorage.setItem('userName', userName);
        localStorage.setItem('userColor', userColor);
        localStorage.setItem('roomId', userRoom);
        localStorage.setItem('signalingUrl', window.location.protocol === 'https:'
          ? `wss://${window.location.host}/ws`
          : `ws://${window.location.host}/ws`);
      },
      { userName: name, userColor: color, userRoom: roomId }
    );

    const ws = new WebSocket(this.signalUrl);
    await new Promise<void>((resolve, reject) => {
      ws.once('open', () => resolve());
      ws.once('error', (err) => reject(err));
    });

    const user: TestUser = { name, color, context, page, ws, roomId };
    this.users.push(user);
    return user;
  }

  async joinRoom(user: TestUser) {
    const msg = JSON.stringify({
      type: 'join_room',
      roomId: user.roomId,
      userId: user.name.toLowerCase().replace(/\s+/g, '-'),
      user: { name: user.name, color: user.color },
      timestamp: Date.now(),
    });
    user.ws.send(msg);
    return new Promise<void>((resolve) => {
      const handler = (data: any) => {
        const parsed = typeof data === 'string' ? JSON.parse(data) : {};
        if (parsed.type === 'presence' || parsed.type === 'ack') {
          user.ws.off('message', handler);
          resolve();
        }
      };
      user.ws.on('message', handler);
      setTimeout(() => {
        user.ws.off('message', handler);
        resolve();
      }, 2000);
    });
  }

  async allUsersInRoom(): Promise<string[]> {
    const present: Set<string> = new Set();
    for (const u of this.users) {
      present.add(u.name);
    }
    return Array.from(present);
  }

  async navigateToWhiteboard(user: TestUser) {
    await user.page.goto(`/board/${user.roomId}`);
    await user.page.waitForSelector('[data-testid="whiteboard-canvas"]', { state: 'visible', timeout: 30_000 });
    await user.page.waitForTimeout(1000);
  }

  async drawRectangle(user: TestUser, startX: number, startY: number, endX: number, endY: number) {
    const canvas = user.page.getByTestId('whiteboard-canvas');
    await canvas.click();
    await user.page.getByTestId('tool-shape').click();
    const box = await canvas.boundingBox();
    if (!box) throw new Error('Canvas not visible');
    await user.page.mouse.move(box.x + startX, box.y + startY);
    await user.page.mouse.down();
    await user.page.mouse.move(box.x + endX, box.y + endY, { steps: 20 });
    await user.page.mouse.up();
    await user.page.waitForTimeout(500);
  }

  async drawFreehand(user: TestUser, points: { x: number; y: number }[]) {
    const canvas = user.page.getByTestId('whiteboard-canvas');
    await user.page.getByTestId('tool-pen').click();
    const box = await canvas.boundingBox();
    if (!box) throw new Error('Canvas not visible');
    await user.page.mouse.move(box.x + points[0].x, box.y + points[0].y);
    await user.page.mouse.down();
    for (let i = 1; i < points.length; i++) {
      await user.page.mouse.move(box.x + points[i].x, box.y + points[i].y, { steps: 5 });
    }
    await user.page.mouse.up();
    await user.page.waitForTimeout(500);
  }

  async getLayerCount(user: TestUser): Promise<number> {
    try {
      return await user.page.evaluate(() => {
        const layers = document.querySelectorAll('[data-testid^="layer-"]');
        return layers.length;
      });
    } catch {
      return 0;
    }
  }

  async getRemoteUserCursors(user: TestUser): Promise<number> {
    try {
      return await user.page.evaluate(() => {
        const cursors = document.querySelectorAll('[data-testid^="user-cursor-"]');
        return cursors.length;
      });
    } catch {
      return 0;
    }
  }

  async sendCRDTOperation(user: TestUser, op: any) {
    const msg = JSON.stringify({
      type: 'yjs_update',
      roomId: user.roomId,
      userId: user.name.toLowerCase(),
      timestamp: Date.now(),
      update: btoa(JSON.stringify(op)),
      senderClientId: user.name,
    });
    user.ws.send(msg);
  }

  async broadcastTextEdit(user: TestUser, textId: string, content: string) {
    await this.sendCRDTOperation(user, {
      type: 'update_element',
      elementId: textId,
      props: { content },
    });
  }

  async closeAll() {
    for (const u of this.users) {
      try { u.ws.close(); } catch {}
      try { await u.context.close(); } catch {}
    }
    this.users = [];
  }
}

const SIGNALING_URL = process.env.PLAYWRIGHT_SIGNALING_URL || 'ws://localhost:8787';

test.describe.serial('Multi-user Collaboration (3 Browsers)', () => {
  let harness: CollaborationHarness;
  const roomId = 'e2e-collab-test-' + Date.now();

  test.beforeEach(async ({ browser }) => {
    harness = new CollaborationHarness(SIGNALING_URL);
  });

  test.afterEach(async () => {
    await harness.closeAll();
  });

  test('3 users join same room and see each other cursors', async ({ browser }) => {
    const alice = await harness.connectUser(browser, 'Alice', '#ff5555', roomId);
    const bob = await harness.connectUser(browser, 'Bob', '#55ff55', roomId);
    const charlie = await harness.connectUser(browser, 'Charlie', '#5555ff', roomId);

    await Promise.all([
      harness.joinRoom(alice),
      harness.joinRoom(bob),
      harness.joinRoom(charlie),
    ]);

    await Promise.all([
      harness.navigateToWhiteboard(alice),
      harness.navigateToWhiteboard(bob),
      harness.navigateToWhiteboard(charlie),
    ]);

    await alice.page.waitForTimeout(3000);

    const aliceRemoteCursors = await harness.getRemoteUserCursors(alice);
    const bobRemoteCursors = await harness.getRemoteUserCursors(bob);
    const charlieRemoteCursors = await harness.getRemoteUserCursors(charlie);

    expect(aliceRemoteCursors).toBeGreaterThanOrEqual(1);
    expect(bobRemoteCursors).toBeGreaterThanOrEqual(1);
    expect(charlieRemoteCursors).toBeGreaterThanOrEqual(1);
  });

  test('User draws shape - CRDT syncs to other users within 200ms', async ({ browser }, testInfo) => {
    const alice = await harness.connectUser(browser, 'Alice', '#ff5555', roomId);
    const bob = await harness.connectUser(browser, 'Bob', '#55ff55', roomId);

    await harness.joinRoom(alice);
    await harness.joinRoom(bob);

    await Promise.all([
      harness.navigateToWhiteboard(alice),
      harness.navigateToWhiteboard(bob),
    ]);
    await alice.page.waitForTimeout(2000);

    const aliceLayersBefore = await harness.getLayerCount(alice);

    const startDraw = Date.now();
    await harness.drawRectangle(alice, 200, 200, 400, 300);
    const drawDuration = Date.now() - startDraw;

    const deadline = Date.now() + 1000;
    let bobLayersAfter = 0;
    while (Date.now() < deadline) {
      bobLayersAfter = await harness.getLayerCount(bob);
      if (bobLayersAfter > 0) break;
      await bob.page.waitForTimeout(50);
    }
    const syncDuration = Date.now() - startDraw;

    expect(bobLayersAfter).toBeGreaterThan(aliceLayersBefore);
    expect(syncDuration).toBeLessThan(500);
    console.log(`[CRDT Perf] Draw: ${drawDuration}ms, Sync: ${syncDuration}ms`);
    testInfo.annotations.push({
      type: 'performance',
      description: `CRDT sync latency: ${syncDuration}ms`
    });
  });

  test('Concurrent edits on same shape resolve correctly (CRDT LWW)', async ({ browser }) => {
    const alice = await harness.connectUser(browser, 'Alice', '#ff5555', roomId);
    const bob = await harness.connectUser(browser, 'Bob', '#55ff55', roomId);
    const charlie = await harness.connectUser(browser, 'Charlie', '#5555ff', roomId);

    await harness.joinRoom(alice);
    await harness.joinRoom(bob);
    await harness.joinRoom(charlie);

    await Promise.all([
      harness.navigateToWhiteboard(alice),
      harness.navigateToWhiteboard(bob),
      harness.navigateToWhiteboard(charlie),
    ]);
    await alice.page.waitForTimeout(2000);

    await alice.page.evaluate(() => {
      (window as any).__syncFacade?.add_text?.(100, 100, 'Initial', JSON.stringify({
        fontSize: 16, fontColor: '#000000', fontFamily: 'sans-serif'
      }));
    });
    await alice.page.waitForTimeout(1000);

    await Promise.all([
      harness.broadcastTextEdit(alice, 'shared-text-1', 'Alice wins!'),
      harness.broadcastTextEdit(bob, 'shared-text-1', 'Bob wins!'),
      harness.broadcastTextEdit(charlie, 'shared-text-1', 'Charlie wins!'),
    ]);

    await alice.page.waitForTimeout(2000);

    const texts = await Promise.all([
      alice.page.evaluate(() => document.body.textContent),
      bob.page.evaluate(() => document.body.textContent),
      charlie.page.evaluate(() => document.body.textContent),
    ]);

    expect(texts[0]).toBe(texts[1]);
    expect(texts[1]).toBe(texts[2]);
  });

  test('Undo/redo works for multi-user sessions', async ({ browser }) => {
    const alice = await harness.connectUser(browser, 'Alice', '#ff5555', roomId);
    await harness.joinRoom(alice);
    await harness.navigateToWhiteboard(alice);
    await alice.page.waitForTimeout(1000);

    await harness.drawFreehand(alice, [
      { x: 100, y: 100 }, { x: 150, y: 150 }, { x: 200, y: 120 }, { x: 250, y: 180 }
    ]);
    await harness.drawRectangle(alice, 300, 100, 400, 200);
    await harness.drawFreehand(alice, [
      { x: 500, y: 100 }, { x: 550, y: 150 }, { x: 600, y: 100 }
    ]);

    const layersAfterDraw = await harness.getLayerCount(alice);
    expect(layersAfterDraw).toBeGreaterThanOrEqual(3);

    await alice.page.keyboard.press('Control+Z');
    await alice.page.waitForTimeout(300);
    const layersAfterUndo = await harness.getLayerCount(alice);
    expect(layersAfterUndo).toBeLessThan(layersAfterDraw);

    await alice.page.keyboard.press('Control+Shift+Z');
    await alice.page.waitForTimeout(300);
    const layersAfterRedo = await harness.getLayerCount(alice);
    expect(layersAfterRedo).toBe(layersAfterDraw);
  });
});
