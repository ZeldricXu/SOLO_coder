use anyhow::Result;
use tantivy::{Index, schema::{Schema, TextOptions, TextFieldIndexing, IndexRecordOption, STORED}, TantivyDocument};
use uuid::Uuid;
use std::path::Path;
use dashmap::DashMap;
use crate::models::Document;

pub struct DocumentIndexer {
    pub index: Index,
    pub schema: Schema,
    pub doc_store: DashMap<Uuid, Document>,
}

impl DocumentIndexer {
    pub fn new(temp_dir_path: &str) -> Result<Self> {
        let schema = Self::build_schema();
        let index_path = Path::new(temp_dir_path);
        let index = Index::create_in_dir(&index_path, schema.clone())?;
        Ok(DocumentIndexer {
            index,
            schema,
            doc_store: DashMap::new(),
        })
    }

    pub fn add_document(&self, doc: &Document) -> Result<()> {
        let mut index_writer: tantivy::IndexWriter<TantivyDocument> = self.index.writer(50_000_000)?;
        let mut tantivy_doc = TantivyDocument::default();
        let title_field = self.schema.get_field("title").unwrap();
        let content_field = self.schema.get_field("content").unwrap();
        let id_field = self.schema.get_field("id").unwrap();
        let tags_field = self.schema.get_field("tags").unwrap();
        let author_field = self.schema.get_field("author").unwrap();
        let team_owner_field = self.schema.get_field("team_owner").unwrap();
        tantivy_doc.add_text(title_field, &doc.title);
        tantivy_doc.add_text(content_field, &doc.content);
        tantivy_doc.add_text(id_field, doc.id.to_string());
        tantivy_doc.add_text(tags_field, doc.tags.join(" "));
        tantivy_doc.add_text(author_field, &doc.author);
        tantivy_doc.add_text(team_owner_field, &doc.team_owner);
        index_writer.add_document(tantivy_doc)?;
        self.doc_store.insert(doc.id, doc.clone());
        Ok(())
    }

    pub fn update_document(&self, doc: &Document) -> Result<()> {
        self.delete_document(doc.id)?;
        self.add_document(doc)?;
        Ok(())
    }

    pub fn delete_document(&self, id: Uuid) -> Result<()> {
        let mut index_writer: tantivy::IndexWriter<TantivyDocument> = self.index.writer(50_000_000)?;
        let id_field = self.schema.get_field("id").unwrap();
        let id_term = tantivy::Term::from_field_text(id_field, &id.to_string());
        index_writer.delete_term(id_term);
        self.doc_store.remove(&id);
        Ok(())
    }

    pub fn commit(&self) -> Result<()> {
        let mut index_writer: tantivy::IndexWriter<TantivyDocument> = self.index.writer(50_000_000)?;
        index_writer.commit()?;
        Ok(())
    }

    fn build_schema() -> Schema {
        let mut schema_builder = Schema::builder();
        let text_options = TextOptions::default()
            .set_indexing_options(
                TextFieldIndexing::default()
                    .set_tokenizer("en_stem")
                    .set_index_option(IndexRecordOption::WithFreqsAndPositions),
            )
            .set_stored();
        schema_builder.add_text_field("title", text_options.clone());
        schema_builder.add_text_field("content", text_options.clone());
        schema_builder.add_text_field("id", STORED);
        schema_builder.add_text_field("tags", text_options.clone());
        schema_builder.add_text_field("author", text_options.clone());
        schema_builder.add_text_field("team_owner", text_options.clone());
        schema_builder.build()
    }
}
