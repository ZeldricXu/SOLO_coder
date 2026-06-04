import { NextRequest, NextResponse } from 'next/server';
import { WebSocketServer, WebSocket } from 'ws';
import { validateJWT, generateUserColor } from '@/lib/collab/utils';
import { yjsServer } from '@/lib/collab/YjsWebSocketServer';
import { prisma } from '@/lib/prisma';
import { SpaceVisibility } from '@prisma/client';
import type { CollabUser, DocumentPermissions } from '@/lib/collab/types';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

let wss: WebSocketServer | null = null;

function getWebSocketServer(): WebSocketServer {
  if (!wss) {
    wss = new WebSocketServer({ noServer: true });
    
    wss.on('connection', async (ws: WebSocket, request: Request) => {
      try {
        const url = new URL(request.url || '');
        const documentId = url.searchParams.get('documentId');
        const token = url.searchParams.get('token') || 
          request.headers.get('authorization')?.replace('Bearer ', '');

        if (!documentId) {
          ws.send(JSON.stringify({ type: 'error', message: 'documentId is required' }));
          ws.close();
          return;
        }

        if (!token) {
          ws.send(JSON.stringify({ type: 'error', message: 'Authentication required' }));
          ws.close();
          return;
        }

        const jwtSecret = process.env.JWT_SECRET || 'fallback-secret';
        const userPayload = validateJWT(token, jwtSecret);

        if (!userPayload) {
          ws.send(JSON.stringify({ type: 'error', message: 'Invalid token' }));
          ws.close();
          return;
        }

        const user = await prisma.user.findUnique({
          where: { id: userPayload.userId },
          select: {
            id: true,
            name: true,
            email: true,
            avatar: true,
          },
        });

        if (!user) {
          ws.send(JSON.stringify({ type: 'error', message: 'User not found' }));
          ws.close();
          return;
        }

        const document = await prisma.document.findUnique({
          where: { id: documentId },
          select: {
            id: true,
            spaceId: true,
            createdById: true,
          },
        });

        if (!document) {
          ws.send(JSON.stringify({ type: 'error', message: 'Document not found' }));
          ws.close();
          return;
        }

        const permissions = await getDocumentPermissions(
          user.id,
          document.spaceId,
          document.createdById,
          documentId
        );

        if (!permissions.canView) {
          ws.send(JSON.stringify({ type: 'error', message: 'Permission denied' }));
          ws.close();
          return;
        }

        const userColor = generateUserColor(user.id);
        const collabUser: CollabUser = {
          id: user.id,
          name: user.name,
          avatar: user.avatar || undefined,
          color: userColor.primary,
        };

        await yjsServer.handleConnection(ws, documentId, collabUser, permissions);

      } catch (error) {
        console.error('[Collab Route] Connection error:', error);
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ 
            type: 'error', 
            message: error instanceof Error ? error.message : 'Connection failed' 
          }));
        }
        ws.close();
      }
    });

    wss.on('error', (error) => {
      console.error('[Collab Route] WebSocket Server error:', error);
    });
  }

  return wss;
}

async function getDocumentPermissions(
  userId: string,
  spaceId: string,
  documentOwnerId: string,
  documentId: string
): Promise<DocumentPermissions> {
  const isOwner = userId === documentOwnerId;

  if (isOwner) {
    return {
      canView: true,
      canEdit: true,
      canComment: true,
      isOwner: true,
    };
  }

  const spaceMember = await prisma.spaceMember.findUnique({
    where: {
      spaceId_userId: {
        spaceId,
        userId,
      },
    },
    select: {
      role: true,
    },
  });

  if (spaceMember) {
    const role = spaceMember.role;
    return {
      canView: true,
      canEdit: role === 'ADMIN' || role === 'EDITOR',
      canComment: true,
      isOwner: false,
    };
  }

  const shareLink = await prisma.shareLink.findFirst({
    where: {
      documentId,
      expiresAt: {
        gt: new Date(),
      },
    },
    select: {
      canEdit: true,
    },
  });

  if (shareLink) {
    return {
      canView: true,
      canEdit: shareLink.canEdit,
      canComment: true,
      isOwner: false,
    };
  }

  const space = await prisma.space.findUnique({
    where: { id: spaceId },
    select: { visibility: true },
  });

  if (space?.visibility === SpaceVisibility.PUBLIC) {
    return {
      canView: true,
      canEdit: false,
      canComment: false,
      isOwner: false,
    };
  }

  return {
    canView: false,
    canEdit: false,
    canComment: false,
    isOwner: false,
  };
}

export async function GET(request: NextRequest) {
  const upgradeHeader = request.headers.get('upgrade');
  
  if (upgradeHeader !== 'websocket') {
    return NextResponse.json(
      { error: 'Expected WebSocket upgrade' },
      { status: 426 }
    );
  }

  try {
    const server = getWebSocketServer();
    const [response, socket, head] = await (request as any).upgrade();
    
    server.handleUpgrade(request as any, socket, head, (ws: WebSocket) => {
      server.emit('connection', ws, request);
    });

    return response;
  } catch (error) {
    console.error('[Collab Route] Upgrade error:', error);
    return NextResponse.json(
      { error: 'WebSocket upgrade failed' },
      { status: 500 }
    );
  }
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { action, documentId, token } = body;

    if (!token) {
      return NextResponse.json(
        { error: 'Authentication required' },
        { status: 401 }
      );
    }

    const jwtSecret = process.env.JWT_SECRET || 'fallback-secret';
    const userPayload = validateJWT(token, jwtSecret);

    if (!userPayload) {
      return NextResponse.json(
        { error: 'Invalid token' },
        { status: 401 }
      );
    }

    switch (action) {
      case 'getOnlineUsers': {
        if (!documentId) {
          return NextResponse.json(
            { error: 'documentId is required' },
            { status: 400 }
          );
        }
        const users = yjsServer.getOnlineUsers(documentId);
        return NextResponse.json({ users });
      }

      case 'getRoomInfo': {
        if (!documentId) {
          return NextResponse.json(
            { error: 'documentId is required' },
            { status: 400 }
          );
        }
        const info = yjsServer.getRoomInfo(documentId);
        return NextResponse.json({ info });
      }

      case 'forceSave': {
        if (!documentId) {
          return NextResponse.json(
            { error: 'documentId is required' },
            { status: 400 }
          );
        }
        const entry = (yjsServer as any).docs.get(documentId);
        if (entry) {
          await yjsServer.saveDocument(entry, documentId, { force: true });
          return NextResponse.json({ success: true });
        }
        return NextResponse.json(
          { error: 'Document not active' },
          { status: 404 }
        );
      }

      default:
        return NextResponse.json(
          { error: 'Unknown action' },
          { status: 400 }
        );
    }
  } catch (error) {
    console.error('[Collab Route] POST error:', error);
    return NextResponse.json(
      { error: error instanceof Error ? error.message : 'Internal server error' },
      { status: 500 }
    );
  }
}
