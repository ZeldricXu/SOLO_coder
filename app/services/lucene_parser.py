"""Lucene 风格查询语法解析器，将用户输入的查询字符串转换为 Elasticsearch Query DSL。

支持的语法：
- 逻辑运算符: AND, OR, NOT
- 字段限定查询: field:value（如 service:payment, level:ERROR）
- 范围查询: field:[start TO end]（含边界）, field:{start TO end}（不含边界）
- 通配符: *（多字符）, ?（单字符）
- 短语精确匹配: "exact phrase"

使用方式：
    from app.services.lucene_parser import lucene_parser
    dsl = lucene_parser.parse("service:payment AND level:ERROR")

对外接口：
    - lucene_parser.parse(query_str): 解析查询字符串，返回 Elasticsearch Query DSL 字典

依赖：
    - pyparsing 库
"""

from typing import Dict, Any, Optional
from pyparsing import (
    Word, alphas, alphanums, nums, quotedString,
    removeQuotes, oneOf, opAssoc, infixNotation,
    Keyword, Group, Combine, Optional as Opt,
    Suppress, Literal, Regex, ParseResults
)


class LuceneQueryParser:
    def __init__(self):
        self.parser = self._build_parser()

    def _build_parser(self):
        field_name = Word(alphas, alphanums + "_")
        colon = Suppress(Literal(":"))

        number = Combine(Opt("-") + Word(nums) + Opt("." + Word(nums)))
        number.setParseAction(lambda t: float(t[0]) if "." in t[0] else int(t[0]))

        term = Word(alphanums + "_-*?")
        phrase = quotedString.setParseAction(removeQuotes)

        value = phrase | term | number

        range_open = oneOf("[ {")
        range_close = oneOf("] }")
        range_to = Keyword("TO", caseless=True)

        range_query = (
            field_name + colon +
            Group(range_open + value + range_to + value + range_close)
        )

        field_query = field_name + colon + value

        basic_term = range_query | field_query | value

        and_ = Keyword("AND", caseless=True)
        or_ = Keyword("OR", caseless=True)
        not_ = Keyword("NOT", caseless=True)

        expr = infixNotation(
            basic_term,
            [
                (not_, 1, opAssoc.RIGHT),
                (and_, 2, opAssoc.LEFT),
                (or_, 2, opAssoc.LEFT),
            ],
        )

        return expr

    def parse(self, query_str: str) -> Dict[str, Any]:
        if not query_str or not query_str.strip():
            return {"match_all": {}}

        try:
            parsed = self.parser.parseString(query_str, parseAll=True)
            return self._convert_to_es_dsl(parsed)
        except Exception as e:
            print(f"Lucene parse error: {e}")
            return {"match": {"message": query_str}}

    def _convert_to_es_dsl(self, parsed) -> Dict[str, Any]:
        if isinstance(parsed, str) or isinstance(parsed, (int, float)):
            return {"match": {"message": str(parsed)}}

        if isinstance(parsed, ParseResults):
            if len(parsed) == 1:
                return self._convert_to_es_dsl(parsed[0])

            if parsed[0] == "NOT":
                inner = self._convert_to_es_dsl(parsed[1])
                return {"bool": {"must_not": [inner]}}

            if parsed[1] == "AND":
                left = self._convert_to_es_dsl(parsed[0])
                right = self._convert_to_es_dsl(parsed[2])
                return {"bool": {"must": [left, right]}}

            if parsed[1] == "OR":
                left = self._convert_to_es_dsl(parsed[0])
                right = self._convert_to_es_dsl(parsed[2])
                return {"bool": {"should": [left, right], "minimum_should_match": 1}}

            if len(parsed) == 2 and parsed[1].get(0) in ("[", "{"):
                field = parsed[0]
                range_info = parsed[1]
                start = range_info[1]
                end = range_info[3]
                start_inclusive = range_info[0] == "["
                end_inclusive = range_info[4] == "]"

                range_query = {"range": {field: {}}}
                if start_inclusive:
                    range_query["range"][field]["gte"] = start
                else:
                    range_query["range"][field]["gt"] = start
                if end_inclusive:
                    range_query["range"][field]["lte"] = end
                else:
                    range_query["range"][field]["lt"] = end
                return range_query

            if len(parsed) == 2:
                field = parsed[0]
                value = parsed[1]
                if isinstance(value, str) and ("*" in value or "?" in value):
                    return {"wildcard": {field: value}}
                return {"term": {field: value}}

        return {"match": {"message": str(parsed)}}


class SimpleLuceneParser:
    def parse(self, query_str: str) -> Dict[str, Any]:
        if not query_str or not query_str.strip():
            return {"match_all": {}}

        tokens = self._tokenize(query_str)
        return self._parse_tokens(tokens)

    def _tokenize(self, query_str: str) -> list:
        tokens = []
        current = ""
        in_quote = False
        i = 0

        while i < len(query_str):
            c = query_str[i]

            if c == '"':
                if in_quote:
                    if current:
                        tokens.append(('PHRASE', current))
                        current = ""
                    in_quote = False
                else:
                    in_quote = True
                i += 1
                continue

            if not in_quote and c.isspace():
                if current:
                    tokens.append(('TERM', current))
                    current = ""
                i += 1
                continue

            if not in_quote and c == ':':
                if current:
                    tokens.append(('FIELD', current))
                    tokens.append(('COLON', ':'))
                    current = ""
                i += 1
                continue

            if not in_quote and c in '[]{}':
                if current:
                    tokens.append(('TERM', current))
                    current = ""
                tokens.append(('RANGE_BRACKET', c))
                i += 1
                continue

            remaining = query_str[i:]
            remaining_upper = remaining.upper()

            if not in_quote and not current and (remaining_upper.startswith('AND ') or remaining_upper == 'AND'):
                tokens.append(('OP', 'AND'))
                i += 3
                continue

            if not in_quote and not current and (remaining_upper.startswith('NOT ') or remaining_upper == 'NOT'):
                tokens.append(('OP', 'NOT'))
                i += 3
                continue

            if not in_quote and not current and (remaining_upper.startswith('OR ') or remaining_upper == 'OR'):
                tokens.append(('OP', 'OR'))
                i += 2
                continue

            if not in_quote and not current and remaining_upper.startswith('TO '):
                tokens.append(('TO', 'TO'))
                i += 3
                continue

            current += c
            i += 1

        if current:
            tokens.append(('TERM', current))

        return tokens

    def _parse_tokens(self, tokens: list) -> Dict[str, Any]:
        must_clauses = []
        should_clauses = []
        must_not_clauses = []
        last_op = None
        i = 0
        last_clause = None

        while i < len(tokens):
            token_type, token_value = tokens[i]

            if token_type == 'OP':
                last_op = token_value.upper()
                i += 1
                continue

            clause = self._parse_clause(tokens, i)
            if clause:
                clause, i = clause

                if last_op == 'OR':
                    if last_clause:
                        if last_clause in must_clauses:
                            must_clauses.remove(last_clause)
                            should_clauses.append(last_clause)
                        elif last_clause in must_not_clauses:
                            must_not_clauses.remove(last_clause)
                            should_clauses.append({"bool": {"must_not": [last_clause]}})
                    should_clauses.append(clause)
                elif last_op == 'NOT':
                    must_not_clauses.append(clause)
                else:
                    must_clauses.append(clause)

                last_clause = clause

            last_op = None
            i += 1

        bool_query = {}
        if must_clauses:
            bool_query["must"] = must_clauses
        if should_clauses:
            bool_query["should"] = should_clauses
            bool_query["minimum_should_match"] = 1
        if must_not_clauses:
            bool_query["must_not"] = must_not_clauses

        if not bool_query:
            return {"match_all": {}}

        return {"bool": bool_query}

    def _parse_clause(self, tokens: list, i: int):
        if i >= len(tokens):
            return None

        token_type, token_value = tokens[i]

        if token_type == 'FIELD' and i + 1 < len(tokens) and tokens[i+1][0] == 'COLON':
            field_name = token_value
            i += 2

            if i < len(tokens) and tokens[i][0] == 'RANGE_BRACKET':
                return self._parse_range_clause(tokens, i, field_name)

            if i < len(tokens):
                _, value = tokens[i]
                if "*" in value or "?" in value:
                    return {"wildcard": {field_name: value}}, i
                return {"term": {field_name: value}}, i

        if token_type == 'PHRASE':
            return {"match_phrase": {"message": token_value}}, i

        if token_type == 'TERM':
            return {"match": {"message": token_value}}, i

        return None

    def _parse_range_clause(self, tokens: list, i: int, field_name: str):
        open_bracket = tokens[i][1]
        i += 1

        start_value = tokens[i][1] if i < len(tokens) else "*"
        i += 1

        if i < len(tokens) and tokens[i][0] == 'TO':
            i += 1

        end_value = tokens[i][1] if i < len(tokens) else "*"
        i += 1

        close_bracket = tokens[i][1] if i < len(tokens) else "]"

        range_query = {"range": {field_name: {}}}

        if start_value != "*":
            if open_bracket == "[":
                range_query["range"][field_name]["gte"] = self._to_number(start_value)
            else:
                range_query["range"][field_name]["gt"] = self._to_number(start_value)

        if end_value != "*":
            if close_bracket == "]":
                range_query["range"][field_name]["lte"] = self._to_number(end_value)
            else:
                range_query["range"][field_name]["lt"] = self._to_number(end_value)

        return range_query, i

    def _to_number(self, value: str):
        try:
            if "." in value:
                return float(value)
            return int(value)
        except ValueError:
            return value


lucene_parser = SimpleLuceneParser()
