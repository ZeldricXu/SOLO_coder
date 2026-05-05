import re
from typing import Dict, List, Optional, Any, Iterator, Callable
from dataclasses import dataclass, field
from enum import Enum
from .parser import LogEntry, LogLevel


class SearchMode(Enum):
    KEYWORD = "keyword"
    REGEX = "regex"
    EXACT = "exact"


class MatchLocation(Enum):
    MESSAGE = "message"
    SOURCE = "source"
    LEVEL = "level"
    FIELDS = "fields"
    ALL = "all"


@dataclass
class SearchResult:
    log_entry: LogEntry
    matched_text: str
    match_location: MatchLocation
    match_start: int
    match_end: int
    groups: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "log_entry": self.log_entry.to_dict(),
            "matched_text": self.matched_text,
            "match_location": self.match_location.value,
            "match_start": self.match_start,
            "match_end": self.match_end,
            "groups": self.groups
        }


@dataclass
class SearchQuery:
    pattern: str
    mode: SearchMode = SearchMode.KEYWORD
    locations: List[MatchLocation] = field(default_factory=lambda: [MatchLocation.MESSAGE])
    case_sensitive: bool = False
    invert_match: bool = False
    min_level: Optional[LogLevel] = None


class SearchEngine:
    LEVEL_ORDER = {
        LogLevel.DEBUG: 0,
        LogLevel.INFO: 1,
        LogLevel.WARNING: 2,
        LogLevel.ERROR: 3,
        LogLevel.CRITICAL: 4,
        LogLevel.UNKNOWN: -1,
    }

    def __init__(self):
        self.compiled_patterns: Dict[str, re.Pattern] = {}

    def _compile_pattern(self, pattern: str, mode: SearchMode, case_sensitive: bool) -> re.Pattern:
        cache_key = f"{pattern}:{mode.value}:{case_sensitive}"
        
        if cache_key in self.compiled_patterns:
            return self.compiled_patterns[cache_key]

        flags = 0 if case_sensitive else re.IGNORECASE

        if mode == SearchMode.KEYWORD:
            regex_pattern = re.escape(pattern)
        elif mode == SearchMode.EXACT:
            regex_pattern = f"^{re.escape(pattern)}$"
        else:
            regex_pattern = pattern

        compiled = re.compile(regex_pattern, flags)
        self.compiled_patterns[cache_key] = compiled
        return compiled

    def _get_search_texts(self, entry: LogEntry, locations: List[MatchLocation]) -> Dict[MatchLocation, str]:
        texts = {}
        
        for loc in locations:
            if loc == MatchLocation.MESSAGE or loc == MatchLocation.ALL:
                texts[MatchLocation.MESSAGE] = entry.message
            
            if loc == MatchLocation.SOURCE or loc == MatchLocation.ALL:
                texts[MatchLocation.SOURCE] = entry.source
            
            if loc == MatchLocation.LEVEL or loc == MatchLocation.ALL:
                texts[MatchLocation.LEVEL] = entry.level.value
            
            if loc == MatchLocation.FIELDS or loc == MatchLocation.ALL:
                for key, value in entry.fields.items():
                    if isinstance(value, str):
                        texts[MatchLocation.FIELDS] = texts.get(MatchLocation.FIELDS, "") + f" {key}:{value}"
        
        return texts

    def _matches_level(self, entry: LogEntry, min_level: Optional[LogLevel]) -> bool:
        if min_level is None:
            return True
        
        entry_order = self.LEVEL_ORDER.get(entry.level, -1)
        min_order = self.LEVEL_ORDER.get(min_level, -1)
        
        if entry_order == -1 or min_order == -1:
            return False
        
        return entry_order >= min_order

    def search_entry(self, entry: LogEntry, query: SearchQuery) -> Optional[SearchResult]:
        if not self._matches_level(entry, query.min_level):
            return None

        compiled_pattern = self._compile_pattern(
            query.pattern, 
            query.mode, 
            query.case_sensitive
        )

        search_texts = self._get_search_texts(entry, query.locations)
        
        for location, text in search_texts.items():
            if not text:
                continue
            
            match = compiled_pattern.search(text)
            if match:
                result = SearchResult(
                    log_entry=entry,
                    matched_text=match.group(),
                    match_location=location,
                    match_start=match.start(),
                    match_end=match.end(),
                    groups=list(match.groups())
                )
                
                if query.invert_match:
                    return None
                return result
        
        if query.invert_match:
            return SearchResult(
                log_entry=entry,
                matched_text="",
                match_location=MatchLocation.ALL,
                match_start=0,
                match_end=0,
                groups=[]
            )
        
        return None

    def search_entries(
        self, 
        entries: Iterator[LogEntry], 
        query: SearchQuery
    ) -> Iterator[SearchResult]:
        for entry in entries:
            result = self.search_entry(entry, query)
            if result:
                yield result

    def search(
        self,
        entries: Iterator[LogEntry],
        pattern: str,
        mode: str = "keyword",
        locations: List[str] = None,
        case_sensitive: bool = False,
        invert_match: bool = False,
        min_level: str = None
    ) -> Iterator[SearchResult]:
        search_mode = SearchMode(mode.lower())
        
        if locations is None:
            match_locations = [MatchLocation.MESSAGE]
        else:
            match_locations = [MatchLocation(loc.lower()) for loc in locations]
        
        min_log_level = None
        if min_level:
            try:
                min_log_level = LogLevel(min_level.upper())
            except ValueError:
                pass
        
        query = SearchQuery(
            pattern=pattern,
            mode=search_mode,
            locations=match_locations,
            case_sensitive=case_sensitive,
            invert_match=invert_match,
            min_level=min_log_level
        )
        
        return self.search_entries(entries, query)

    def highlight_match(self, result: SearchResult, before: str = "[", after: str = "]") -> str:
        entry = result.log_entry
        text = entry.raw_line
        
        if result.match_location == MatchLocation.MESSAGE:
            start_offset = text.find(entry.message)
            if start_offset == -1:
                start_offset = 0
        else:
            start_offset = 0
        
        actual_start = start_offset + result.match_start
        actual_end = start_offset + result.match_end
        
        if actual_start >= 0 and actual_end <= len(text):
            highlighted = (
                text[:actual_start] + 
                before + 
                text[actual_start:actual_end] + 
                after + 
                text[actual_end:]
            )
            return highlighted
        
        return text


class MultiSearchEngine:
    def __init__(self):
        self.engine = SearchEngine()

    def search_all(
        self,
        entries: Iterator[LogEntry],
        patterns: List[str],
        logic: str = "OR",
        **kwargs
    ) -> Iterator[SearchResult]:
        entries_list = list(entries)
        results_map: Dict[str, SearchResult] = {}
        
        for pattern in patterns:
            for entry in entries_list:
                query = SearchQuery(
                    pattern=pattern,
                    mode=SearchMode(kwargs.get("mode", "keyword").lower()),
                    locations=[MatchLocation(loc.lower()) for loc in kwargs.get("locations", ["message"])],
                    case_sensitive=kwargs.get("case_sensitive", False),
                    invert_match=kwargs.get("invert_match", False),
                    min_level=None
                )
                
                result = self.engine.search_entry(entry, query)
                if result:
                    if logic == "OR":
                        if entry.log_id not in results_map:
                            results_map[entry.log_id] = result
                    elif logic == "AND":
                        if entry.log_id not in results_map:
                            results_map[entry.log_id] = result
        
        if logic == "AND":
            pattern_count = len(patterns)
            final_results = {}
            entry_pattern_matches: Dict[str, int] = {}
            
            for pattern in patterns:
                for entry in entries_list:
                    query = SearchQuery(
                        pattern=pattern,
                        mode=SearchMode(kwargs.get("mode", "keyword").lower()),
                        locations=[MatchLocation(loc.lower()) for loc in kwargs.get("locations", ["message"])],
                        case_sensitive=kwargs.get("case_sensitive", False),
                        invert_match=kwargs.get("invert_match", False),
                        min_level=None
                    )
                    
                    result = self.engine.search_entry(entry, query)
                    if result:
                        entry_pattern_matches[entry.log_id] = entry_pattern_matches.get(entry.log_id, 0) + 1
                        final_results[entry.log_id] = result
            
            results_map = {
                log_id: result 
                for log_id, result in final_results.items()
                if entry_pattern_matches.get(log_id, 0) >= pattern_count
            }
        
        for result in results_map.values():
            yield result


def create_search_query(
    keyword: str = None,
    regex: str = None,
    exact: str = None,
    locations: List[str] = None,
    case_sensitive: bool = False,
    invert_match: bool = False,
    min_level: str = None
) -> SearchQuery:
    if keyword:
        pattern = keyword
        mode = SearchMode.KEYWORD
    elif regex:
        pattern = regex
        mode = SearchMode.REGEX
    elif exact:
        pattern = exact
        mode = SearchMode.EXACT
    else:
        raise ValueError("Must provide keyword, regex, or exact parameter")
    
    if locations is None:
        match_locations = [MatchLocation.MESSAGE]
    else:
        match_locations = [MatchLocation(loc.lower()) for loc in locations]
    
    min_log_level = None
    if min_level:
        try:
            min_log_level = LogLevel(min_level.upper())
        except ValueError:
            pass
    
    return SearchQuery(
        pattern=pattern,
        mode=mode,
        locations=match_locations,
        case_sensitive=case_sensitive,
        invert_match=invert_match,
        min_level=min_log_level
    )
