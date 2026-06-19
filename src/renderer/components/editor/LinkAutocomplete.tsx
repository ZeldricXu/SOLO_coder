import React, { useState, useEffect } from 'react';
import clsx from 'clsx';
import type { Note } from '@shared/types';
import { useEditorStore } from '../../stores/editorStore';

interface LinkAutocompleteProps {
  position: { top: number; left: number } | null;
  searchText: string;
  onSelect: (target: string) => void;
}

export const LinkAutocomplete: React.FC<LinkAutocompleteProps> = ({ position, searchText, onSelect }) => {
  const [suggestions, setSuggestions] = useState<Note[]>([]);
  const [selectedIndex, setSelectedIndex] = useState(0);

  useEffect(() => {
    const fetchSuggestions = async () => {
      if (!searchText) {
        const notes = await window.api.notes.getAll();
        setSuggestions(notes.slice(0, 10));
      } else {
        const results = await window.api.search.query(searchText, { limit: 10 });
        const notes = await Promise.all(
          results.map(async r => {
            const note = await window.api.notes.getById(r.id);
            return note;
          })
        );
        setSuggestions(notes.filter(Boolean) as Note[]);
      }
      setSelectedIndex(0);
    };

    fetchSuggestions();
  }, [searchText]);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (!position) return;

      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedIndex(prev => Math.min(prev + 1, suggestions.length - 1));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedIndex(prev => Math.max(prev - 1, 0));
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (suggestions[selectedIndex]) {
          onSelect(suggestions[selectedIndex].title);
        }
      } else if (e.key === 'Escape') {
        e.preventDefault();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [position, suggestions, selectedIndex, onSelect]);

  if (!position || suggestions.length === 0) return null;

  return (
    <div
      className="link-autocomplete"
      style={{ top: position.top, left: position.left }}
    >
      {suggestions.map((note, index) => (
        <div
          key={note.id}
          className={clsx('autocomplete-item', { selected: index === selectedIndex })}
          onClick={() => onSelect(note.title)}
          onMouseEnter={() => setSelectedIndex(index)}
        >
          <span className="autocomplete-icon">📄</span>
          <span className="autocomplete-title">{note.title}</span>
          {note.tags.length > 0 && (
            <span className="autocomplete-tags">
              {note.tags.slice(0, 2).map(t => `#${t}`).join(' ')}
            </span>
          )}
        </div>
      ))}
    </div>
  );
};
