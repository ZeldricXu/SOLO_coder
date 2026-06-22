from sqlalchemy import text
from typing import Optional, List, Tuple
from app.core.database import engine


FTS_TABLE_NAME = "snippets_fts"


def ensure_fts5_table():
    with engine.begin() as conn:
        create_sql = f"""
        CREATE VIRTUAL TABLE IF NOT EXISTS {FTS_TABLE_NAME} USING fts5(
            snippet_id UNINDEXED,
            title,
            description,
            code,
            tags UNINDEXED,
            tokenize='unicode61 remove_diacritics 2'
        )
        """
        conn.execute(text(create_sql))


def rebuild_fts_index(db):
    db.execute(text(f"DELETE FROM {FTS_TABLE_NAME}"))
    rows = db.execute(
        text("""
        SELECT s.id, s.title, COALESCE(s.description, ''), s.code,
               GROUP_CONCAT(t.name, ' ') as tags
        FROM snippets s
        LEFT JOIN snippet_tags st ON s.id = st.snippet_id
        LEFT JOIN tags t ON st.tag_id = t.id
        WHERE s.is_deleted = 0
        GROUP BY s.id
        """)
    ).fetchall()
    for row in rows:
        db.execute(
            text(f"""
            INSERT INTO {FTS_TABLE_NAME} (rowid, snippet_id, title, description, code, tags)
            VALUES (:rowid, :snippet_id, :title, :description, :code, :tags)
            """),
            {
                "rowid": row[0],
                "snippet_id": row[0],
                "title": row[1],
                "description": row[2],
                "code": row[3],
                "tags": row[4] or "",
            }
        )
    db.commit()


def upsert_fts_entry(db, snippet_id: int, title: str, description: str, code: str, tags_str: str):
    existing = db.execute(
        text(f"SELECT rowid FROM {FTS_TABLE_NAME} WHERE snippet_id = :sid"),
        {"sid": snippet_id}
    ).fetchone()
    desc = description or ""
    tags = tags_str or ""
    if existing:
        db.execute(
            text(f"""
            UPDATE {FTS_TABLE_NAME}
            SET title = :title, description = :description, code = :code, tags = :tags
            WHERE snippet_id = :sid
            """),
            {"sid": snippet_id, "title": title, "description": desc, "code": code, "tags": tags}
        )
    else:
        db.execute(
            text(f"""
            INSERT INTO {FTS_TABLE_NAME} (rowid, snippet_id, title, description, code, tags)
            VALUES (:rowid, :sid, :title, :description, :code, :tags)
            """),
            {"rowid": snippet_id, "sid": snippet_id, "title": title, "description": desc, "code": code, "tags": tags}
        )


def delete_fts_entry(db, snippet_id: int):
    db.execute(
        text(f"DELETE FROM {FTS_TABLE_NAME} WHERE snippet_id = :sid"),
        {"sid": snippet_id}
    )


def search_fts(
    db,
    query: str,
    user_id: Optional[int] = None,
    language: Optional[str] = None,
    author_username: Optional[str] = None,
    tag_filters: Optional[List[str]] = None,
    sort_by: str = "relevance",
    sort_order: str = "desc",
    page: int = 1,
    page_size: int = 20,
) -> Tuple[List[Tuple[int, float]], int]:
    if not query or not query.strip():
        return _fallback_search(
            db, user_id, language, author_username, tag_filters,
            sort_by, sort_order, page, page_size
        )

    tokens = _tokenize_query(query)
    if not tokens:
        return _fallback_search(
            db, user_id, language, author_username, tag_filters,
            sort_by, sort_order, page, page_size
        )

    fts_query = " OR ".join(f'"{t}"*' for t in tokens)

    from sqlalchemy import select
    from app.models.models import Snippet, User, TeamMember

    visibility_filter = _build_visibility_filter(user_id)

    where_clauses = ["s.is_deleted = 0"]
    where_clauses.append(visibility_filter)
    params: dict = {"ftsq": fts_query, "q": query, "q_lower": query.lower()}

    if language:
        where_clauses.append("s.language = :language")
        params["language"] = language
    if author_username:
        where_clauses.append("u.username = :author_username")
        params["author_username"] = author_username

    tag_join = ""
    if tag_filters:
        tag_join = f"""
        INNER JOIN (
            SELECT st2.snippet_id
            FROM snippet_tags st2
            JOIN tags t2 ON st2.tag_id = t2.id
            WHERE t2.name IN :tag_names
            GROUP BY st2.snippet_id
            HAVING COUNT(DISTINCT t2.id) = :tag_count
        ) tf ON s.id = tf.snippet_id
        """
        params["tag_names"] = tuple(tag_filters)
        params["tag_count"] = len(tag_filters)

    where_sql = " AND ".join(where_clauses)

    score_expr = """
    (
        CASE WHEN f.title MATCH :ftsq THEN 10.0
             WHEN f.title LIKE '%' || :q || '%' THEN 8.0
             ELSE 0.0 END
        +
        CASE WHEN f.description MATCH :ftsq THEN 3.0
             WHEN f.description LIKE '%' || :q || '%' THEN 2.0
             ELSE 0.0 END
        +
        CASE WHEN f.code MATCH :ftsq THEN 2.0
             WHEN f.code LIKE '%' || :q || '%' THEN 1.0
             ELSE 0.0 END
        +
        CASE WHEN f.tags LIKE '%' || :q_lower || '%' THEN 5.0 ELSE 0.0 END
        +
        bm25(f) * 0.1
        +
        CAST(s.stars_count AS REAL) * 0.05
        +
        CAST(s.forks_count AS REAL) * 0.03
    )
    """

    count_sql = f"""
    SELECT COUNT(*)
    FROM {FTS_TABLE_NAME} f
    JOIN snippets s ON f.snippet_id = s.id
    JOIN users u ON s.author_id = u.id
    {tag_join}
    WHERE f MATCH :ftsq AND {where_sql}
    """

    try:
        total = db.execute(text(count_sql), params).scalar() or 0
    except Exception:
        total = 0

    if sort_by == "relevance":
        order_sql = f"{score_expr} {'DESC' if sort_order == 'desc' else 'ASC'}"
    else:
        col = sort_by if sort_by != "relevance" else "updated_at"
        direction = "DESC" if sort_order == "desc" else "ASC"
        order_sql = f"s.{col} {direction}"

    offset = (page - 1) * page_size

    results_sql = f"""
    SELECT s.id, {score_expr} AS score
    FROM {FTS_TABLE_NAME} f
    JOIN snippets s ON f.snippet_id = s.id
    JOIN users u ON s.author_id = u.id
    {tag_join}
    WHERE f MATCH :ftsq AND {where_sql}
    ORDER BY {order_sql}
    LIMIT :limit OFFSET :offset
    """
    params["limit"] = page_size
    params["offset"] = offset

    try:
        results = db.execute(text(results_sql), params).fetchall()
        return [(row[0], float(row[1])) for row in results], total
    except Exception:
        return _fallback_search(
            db, user_id, language, author_username, tag_filters,
            sort_by, sort_order, page, page_size
        )


def _build_visibility_filter(user_id: Optional[int]) -> str:
    if not user_id:
        return "s.visibility = 'public'"
    return f"""
    (s.visibility = 'public'
     OR s.author_id = {user_id}
     OR (s.visibility = 'team' AND s.team_id IN (
         SELECT tm.team_id FROM team_members tm WHERE tm.user_id = {user_id}
     )))
    """


def _fallback_search(
    db,
    user_id: Optional[int],
    language: Optional[str],
    author_username: Optional[str],
    tag_filters: Optional[List[str]],
    sort_by: str,
    sort_order: str,
    page: int,
    page_size: int,
) -> Tuple[List[Tuple[int, float]], int]:
    from sqlalchemy import select, or_, and_, desc, asc, func
    from app.models.models import Snippet, User, TeamMember, Tag, SnippetTag

    query = select(Snippet.id, text("0.0 AS score")).join(User).where(Snippet.is_deleted == False)

    if user_id:
        team_ids = select(TeamMember.team_id).where(TeamMember.user_id == user_id)
        query = query.where(
            or_(
                Snippet.visibility == "public",
                Snippet.author_id == user_id,
                and_(
                    Snippet.visibility == "team",
                    Snippet.team_id.in_(team_ids),
                ),
            )
        )
    else:
        query = query.where(Snippet.visibility == "public")

    if language:
        query = query.where(Snippet.language == language)
    if author_username:
        query = query.where(User.username == author_username)
    if tag_filters:
        tag_count = len(tag_filters)
        tag_subq = (
            select(SnippetTag.snippet_id)
            .join(Tag)
            .where(Tag.name.in_([t.lower() for t in tag_filters]))
            .group_by(SnippetTag.snippet_id)
            .having(func.count(SnippetTag.tag_id) == tag_count)
            .subquery()
        )
        query = query.where(Snippet.id.in_(tag_subq))

    total = db.execute(select(func.count()).select_from(query.subquery())).scalar() or 0

    sort_col_name = sort_by if sort_by != "relevance" else "updated_at"
    sort_col = getattr(Snippet, sort_col_name)
    ordered = query.order_by(desc(sort_col) if sort_order == "desc" else asc(sort_col))
    ordered = ordered.limit(page_size).offset((page - 1) * page_size)
    results = db.execute(ordered).fetchall()
    return [(row[0], 0.0) for row in results], total


def _tokenize_query(query: str) -> List[str]:
    import re
    cleaned = re.sub(r'[^\w\u4e00-\u9fff]+', ' ', query.lower())
    tokens = [t for t in cleaned.split() if len(t) >= 1]
    return tokens


def expand_tag_aliases(db, tag_names: List[str]) -> List[str]:
    from app.models.models import TagAlias, Tag

    expanded = set()
    for name in tag_names:
        lower_name = name.strip().lower()
        expanded.add(lower_name)
        alias_row = db.query(TagAlias).filter(TagAlias.alias == lower_name).first()
        if alias_row:
            canonical = db.query(Tag).filter(Tag.id == alias_row.canonical_tag_id).first()
            if canonical:
                expanded.add(canonical.name)
        canonical_row = db.query(Tag).filter(Tag.name == lower_name).first()
        if canonical_row:
            for a in canonical_row.aliases:
                expanded.add(a.alias)

    return list(expanded)
