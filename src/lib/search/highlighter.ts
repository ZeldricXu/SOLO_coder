import { SearchResultItem, HighlightFragment, HighlightConfig, DEFAULT_HIGHLIGHT_CONFIG } from './types';

export function escapeHtml(text: string): string {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

export function escapeHtmlServer(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
    .replace(/`/g, '&#96;')
    .replace(/\//g, '&#x2F;');
}

export function isBrowser(): boolean {
  return typeof window !== 'undefined' && typeof document !== 'undefined';
}

export function escapeHtmlSafe(text: string): string {
  if (isBrowser()) {
    return escapeHtml(text);
  }
  return escapeHtmlServer(text);
}

export function extractMatchedTerms(query: string): string[] {
  const trimmedQuery = query.trim();
  if (!trimmedQuery) return [];

  const terms: string[] = [];
  const chineseRegex = /[\u4e00-\u9fa5]+/g;
  const englishRegex = /[a-zA-Z0-9]+/g;

  const chineseMatches = trimmedQuery.match(chineseRegex);
  if (chineseMatches) {
    for (const match of chineseMatches) {
      for (let i = 0; i < match.length; i++) {
        terms.push(match[i]);
        if (i < match.length - 1) {
          terms.push(match.substring(i, i + 2));
        }
      }
      terms.push(match);
    }
  }

  const englishMatches = trimmedQuery.match(englishRegex);
  if (englishMatches) {
    terms.push(...englishMatches.map((t) => t.toLowerCase()));
  }

  const uniqueTerms = [...new Set(terms)].filter((t) => t.length > 0);

  uniqueTerms.sort((a, b) => b.length - a.length);

  return uniqueTerms;
}

export function escapeRegExp(string: string): string {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function highlightText(
  text: string,
  query: string,
  config: Required<HighlightConfig> = DEFAULT_HIGHLIGHT_CONFIG
): string {
  if (!text || !query) return text;

  const safeText = escapeHtmlSafe(text);
  const terms = extractMatchedTerms(query);

  if (terms.length === 0) return safeText;

  let highlighted = safeText;

  const processedPositions: Array<{ start: number; end: number }> = [];

  for (const term of terms) {
    const regex = new RegExp(escapeRegExp(term), 'gi');
    let match: RegExpExecArray | null;

    while ((match = regex.exec(safeText)) !== null) {
      const start = match.index;
      const end = start + match[0].length;

      const overlaps = processedPositions.some(
        (pos) => start < pos.end && end > pos.start
      );

      if (!overlaps) {
        processedPositions.push({ start, end });
      }
    }
  }

  processedPositions.sort((a, b) => a.start - b.start);

  const overlapMerged: Array<{ start: number; end: number }> = [];
  for (const pos of processedPositions) {
    const last = overlapMerged[overlapMerged.length - 1];
    if (last && pos.start <= last.end) {
      last.end = Math.max(last.end, pos.end);
    } else {
      overlapMerged.push({ ...pos });
    }
  }

  let offset = 0;
  for (const pos of overlapMerged) {
    const start = pos.start + offset;
    const end = pos.end + offset;
    const matchedText = highlighted.substring(start, end);
    const replacement = `${config.preTag}${matchedText}${config.postTag}`;
    highlighted = highlighted.substring(0, start) + replacement + highlighted.substring(end);
    offset += replacement.length - (end - start);
  }

  return highlighted;
}

export function extractFragments(
  text: string,
  query: string,
  config: Required<HighlightConfig> = DEFAULT_HIGHLIGHT_CONFIG
): HighlightFragment[] {
  if (!text || !query) return [];

  const fragments: HighlightFragment[] = [];
  const terms = extractMatchedTerms(query);

  if (terms.length === 0) return [];

  const positions: Array<{ start: number; end: number; term: string }> = [];

  for (const term of terms) {
    const regex = new RegExp(escapeRegExp(term), 'gi');
    let match: RegExpExecArray | null;

    while ((match = regex.exec(text)) !== null) {
      positions.push({
        start: match.index,
        end: match.index + match[0].length,
        term,
      });
    }
  }

  positions.sort((a, b) => a.start - b.start);

  if (positions.length === 0) return [];

  const contextSize = Math.floor(config.fragmentSize / 2);
  const usedPositions: Set<number> = new Set();
  let fragmentCount = 0;

  for (let i = 0; i < positions.length && fragmentCount < config.maxFragments; i++) {
    const pos = positions[i];
    if (usedPositions.has(i)) continue;

    let fragmentStart = Math.max(0, pos.start - contextSize);
    let fragmentEnd = Math.min(text.length, pos.end + contextSize);

    if (fragmentStart > 0) {
      const spaceAfter = text.indexOf(' ', fragmentStart);
      if (spaceAfter !== -1 && spaceAfter < pos.start) {
        fragmentStart = spaceAfter + 1;
      }
    }

    if (fragmentEnd < text.length) {
      const spaceBefore = text.lastIndexOf(' ', fragmentEnd);
      if (spaceBefore !== -1 && spaceBefore > pos.end) {
        fragmentEnd = spaceBefore;
      }
    }

    const matchedTermsInFragment: string[] = [];
    for (let j = i; j < positions.length; j++) {
      const otherPos = positions[j];
      if (otherPos.start >= fragmentStart && otherPos.end <= fragmentEnd) {
        usedPositions.add(j);
        if (!matchedTermsInFragment.includes(otherPos.term)) {
          matchedTermsInFragment.push(otherPos.term);
        }
      } else if (otherPos.start > fragmentEnd) {
        break;
      }
    }

    let fragmentText = text.substring(fragmentStart, fragmentEnd);
    if (fragmentStart > 0) fragmentText = '...' + fragmentText;
    if (fragmentEnd < text.length) fragmentText = fragmentText + '...';

    const field: 'title' | 'content' = fragmentStart < 200 ? 'title' : 'content';

    fragments.push({
      field,
      fragment: fragmentText,
      startOffset: fragmentStart,
      endOffset: fragmentEnd,
      matchedTerms: matchedTermsInFragment,
    });

    fragmentCount++;
  }

  return fragments;
}

export function highlightSearchResults(
  items: SearchResultItem[],
  query: string,
  config: Required<HighlightConfig> = DEFAULT_HIGHLIGHT_CONFIG
): SearchResultItem[] {
  return items.map((item) => {
    const highlightedTitle = item.highlightedTitle || highlightText(item.title, query, config);
    const highlightedContent = item.highlightedContent || highlightText(item.content, query, config);

    const titleFragments = extractFragments(item.title, query, config);
    const contentFragments = extractFragments(item.content, query, config);
    const allFragments = [...titleFragments, ...contentFragments].slice(0, config.maxFragments);

    return {
      ...item,
      highlightedTitle,
      highlightedContent,
      highlights: allFragments.length > 0 ? allFragments : undefined,
    };
  });
}

export function findMatchPositions(text: string, query: string): Array<{ start: number; end: number; term: string }> {
  if (!text || !query) return [];

  const positions: Array<{ start: number; end: number; term: string }> = [];
  const terms = extractMatchedTerms(query);

  for (const term of terms) {
    const regex = new RegExp(escapeRegExp(term), 'gi');
    let match: RegExpExecArray | null;

    while ((match = regex.exec(text)) !== null) {
      positions.push({
        start: match.index,
        end: match.index + match[0].length,
        term,
      });
    }
  }

  positions.sort((a, b) => a.start - b.start);

  const merged: Array<{ start: number; end: number; term: string }> = [];
  for (const pos of positions) {
    const last = merged[merged.length - 1];
    if (last && pos.start <= last.end) {
      last.end = Math.max(last.end, pos.end);
      if (!last.term.includes(pos.term)) {
        last.term += `,${pos.term}`;
      }
    } else {
      merged.push({ ...pos });
    }
  }

  return merged;
}
