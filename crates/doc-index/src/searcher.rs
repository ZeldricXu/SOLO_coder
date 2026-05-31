use anyhow::Result;
use tantivy::{collector::TopDocs, query::QueryParser, TantivyDocument, schema::Value};
use uuid::Uuid;
use crate::models::{SearchQuery, SearchResult, UserContext, DocumentSource};
use crate::indexer::DocumentIndexer;
use crate::permission::PermissionFilter;

pub struct DocumentSearcher;

impl DocumentSearcher {
    pub fn search(query: SearchQuery, indexer: &DocumentIndexer) -> Result<Vec<SearchResult>> {
        let reader = indexer.index.reader()?;
        let searcher = reader.searcher();
        let title_field = indexer.schema.get_field("title").unwrap();
        let content_field = indexer.schema.get_field("content").unwrap();
        let tags_field = indexer.schema.get_field("tags").unwrap();
        let id_field = indexer.schema.get_field("id").unwrap();
        let _team_owner_field = indexer.schema.get_field("team_owner").unwrap();
        let query_parser = QueryParser::for_index(
            &indexer.index,
            vec![title_field, content_field, tags_field],
        );
        let mut full_query = query.keyword.clone();
        if let Some(source) = &query.source_filter {
            let source_str = match source {
                DocumentSource::Confluence => "confluence",
                DocumentSource::Notion => "notion",
                DocumentSource::GitLabWiki => "gitlabwiki",
                DocumentSource::GitHubWiki => "githubwiki",
                DocumentSource::Markdown => "markdown",
            };
            full_query.push_str(&format!(" AND source:{}", source_str));
        }
        if let Some(team) = &query.team_filter {
            full_query.push_str(&format!(" AND team_owner:{}", team));
        }
        for tag in &query.tag_filter {
            full_query.push_str(&format!(" AND tags:{}", tag));
        }
        let tantivy_query = query_parser.parse_query(&full_query)?;
        let top_docs = searcher.search(
            &tantivy_query,
            &TopDocs::with_limit(query.page * query.page_size),
        )?;
        let mut results = Vec::new();
        for (score, doc_address) in top_docs {
            let retrieved_doc: TantivyDocument = searcher.doc(doc_address)?;
            if let Some(id_value) = retrieved_doc.get_first(id_field) {
                if let Some(id_str) = id_value.as_str() {
                    if let Ok(doc_id) = Uuid::parse_str(id_str) {
                        if let Some(doc_ref) = indexer.doc_store.get(&doc_id) {
                            results.push(SearchResult {
                                document: doc_ref.clone(),
                                score: score as f32,
                            });
                        }
                    }
                }
            }
        }
        let filtered = Self::apply_permission_filter(results, &query.user_context);
        let start = (query.page - 1) * query.page_size;
        let paginated: Vec<SearchResult> = filtered.into_iter().skip(start).take(query.page_size).collect();
        Ok(paginated)
    }

    fn apply_permission_filter(results: Vec<SearchResult>, user: &UserContext) -> Vec<SearchResult> {
        results.into_iter()
            .filter(|result| PermissionFilter::can_read(&result.document, user))
            .collect()
    }
}
