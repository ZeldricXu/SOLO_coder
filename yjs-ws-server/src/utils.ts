import * as Y from 'yjs';
import { PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();

export function uint8ArrayToBase64(arr: Uint8Array): string {
  return Buffer.from(arr).toString('base64');
}

export function base64ToUint8Array(base64: string): Uint8Array {
  return new Uint8Array(Buffer.from(base64, 'base64'));
}

export function encodeYDocState(doc: Y.Doc): Uint8Array {
  return Y.encodeStateAsUpdate(doc);
}

export function decodeYDocState(update: Uint8Array, doc: Y.Doc): void {
  Y.applyUpdate(doc, update);
}

export function markdownToYDoc(markdown: string, doc: Y.Doc): void {
  const ytext = doc.getText('content');
  ytext.insert(0, markdown);
}

export function yDocToMarkdown(doc: Y.Doc): string {
  const ytext = doc.getText('content');
  return ytext.toString();
}

export async function loadDocumentState(
  documentId: string,
  doc: Y.Doc
): Promise<{ version: number; lastSaved: Date }> {
  const document = await prisma.document.findUnique({
    where: { id: documentId },
    select: {
      content: true,
      yjsState: true,
      version: true,
      updatedAt: true,
    },
  });

  if (!document) {
    throw new Error(`Document not found: ${documentId}`);
  }

  const version = document.version || 1;
  const lastSaved = document.updatedAt || new Date();

  if (document.yjsState) {
    try {
      const stateBytes = base64ToUint8Array(document.yjsState);
      decodeYDocState(stateBytes, doc);
      return { version, lastSaved };
    } catch (e) {
      console.warn(
        '[YjsServer] Failed to load Yjs state, falling back to content'
      );
    }
  }

  if (document.content) {
    markdownToYDoc(document.content, doc);
  }

  return { version, lastSaved };
}

export async function saveDocumentState(
  documentId: string,
  doc: Y.Doc,
  version: number
): Promise<void> {
  const state = encodeYDocState(doc);
  const stateBase64 = uint8ArrayToBase64(state);
  const content = yDocToMarkdown(doc);

  await prisma.document.update({
    where: { id: documentId },
    data: {
      content,
      yjsState: stateBase64,
      version,
      updatedAt: new Date(),
    },
  });
}

export function debounce<T extends (...args: any[]) => any>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeout: NodeJS.Timeout | null = null;

  return (...args: Parameters<T>) => {
    if (timeout) {
      clearTimeout(timeout);
    }
    timeout = setTimeout(() => func(...args), wait);
  };
}
