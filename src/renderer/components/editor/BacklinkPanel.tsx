import React, { useCallback } from 'react';
import type { Note } from '@shared/types';
import { BrokenLinkFixer } from '../BrokenLinkFixer';
import { useEditorStore } from '../../stores/editorStore';
import { parseMarkdownToSlate, slateToMarkdown } from '../../utils/slateConverter';
import { Transforms } from 'slate';

interface BacklinkPanelProps {
  note: Note;
  allNotes: Note[];
  onSave?: (content: string) => void;
}

export const BacklinkPanel: React.FC<BacklinkPanelProps> = ({ note, allNotes, onSave }) => {
  const { editorInstance } = useEditorStore();

  const handleFixLink = useCallback(async (brokenLink: any, newTargetNoteId: string): Promise<boolean> => {
    if (!note) return false;

    try {
      const currentContent = slateToMarkdown((editorInstance as any).children);
      const result = await window.api.notes.updateLinkTarget(
        note.id,
        brokenLink.target,
        newTargetNoteId
      );

      if (result && result.success && result.newContent) {
        const newValue = parseMarkdownToSlate(result.newContent);
        Transforms.deselect(editorInstance as any);
        (editorInstance as any).children = newValue as any;
        (editorInstance as any).onChange();
        onSave?.(result.newContent);
        return true;
      }

      return false;
    } catch (err) {
      console.error('Error fixing link:', err);
      return false;
    }
  }, [note, editorInstance, onSave]);

  return (
    <BrokenLinkFixer
      note={note}
      allNotes={allNotes}
      onFixLink={handleFixLink}
    />
  );
};
