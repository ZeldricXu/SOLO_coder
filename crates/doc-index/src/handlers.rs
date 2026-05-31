use anyhow::Result;
use uuid::Uuid;
use crate::models::{Document, SearchQuery, SearchResult, AggregationJob, IndexStatus};
use crate::indexer::DocumentIndexer;
use crate::searcher::DocumentSearcher;
use crate::aggregator::SourceAggregator;

pub fn index_document(indexer: &DocumentIndexer, doc: Document) -> Result<()> {
    indexer.add_document(&doc)?;
    indexer.commit()?;
    Ok(())
}

pub fn delete_document(indexer: &DocumentIndexer, id: Uuid) -> Result<()> {
    indexer.delete_document(id)?;
    indexer.commit()?;
    Ok(())
}

pub fn search_documents(
    indexer: &DocumentIndexer,
    query: SearchQuery,
) -> Result<Vec<SearchResult>> {
    DocumentSearcher::search(query, indexer)
}

pub fn add_source(aggregator: &SourceAggregator, job: AggregationJob) -> Result<()> {
    aggregator.add_source(job)
}

pub fn list_sources(aggregator: &SourceAggregator) -> Result<Vec<AggregationJob>> {
    aggregator.list_sources()
}

pub fn sync_source(
    aggregator: &SourceAggregator,
    id: Uuid,
    status: IndexStatus,
) -> Result<()> {
    aggregator.update_sync_status(id, status)
}
