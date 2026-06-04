-- =============================================
-- Full-Text Search Migration Script
-- =============================================

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS zhparser;

-- Add searchVector column to Document table if not exists
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'Document' AND column_name = 'searchVector'
    ) THEN
        ALTER TABLE "Document" ADD COLUMN "searchVector" tsvector;
    END IF;
END $$;

-- Create GIN indexes
CREATE INDEX IF NOT EXISTS idx_document_search_vector
    ON "Document" USING GIN("searchVector");

CREATE INDEX IF NOT EXISTS idx_document_title_trgm
    ON "Document" USING GIN (title gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_document_content_trgm
    ON "Document" USING GIN (content gin_trgm_ops);

-- Additional indexes for common filter columns
CREATE INDEX IF NOT EXISTS idx_document_user_id
    ON "Document"("userId");

CREATE INDEX IF NOT EXISTS idx_document_space_id
    ON "Document"("spaceId");

CREATE INDEX IF NOT EXISTS idx_document_is_archived
    ON "Document"("isArchived");

CREATE INDEX IF NOT EXISTS idx_document_source_type
    ON "Document"("sourceType");

CREATE INDEX IF NOT EXISTS idx_document_created_at
    ON "Document"("createdAt");

CREATE INDEX IF NOT EXISTS idx_document_updated_at
    ON "Document"("updatedAt");

-- =============================================
-- Trigger function for automatic searchVector updates
-- =============================================

CREATE OR REPLACE FUNCTION document_search_vector_update()
RETURNS TRIGGER AS $$
DECLARE
    config_name text := 'simple';
    zhparser_exists boolean;
BEGIN
    -- Check if zhparser is available
    SELECT EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'zhparser'
    ) INTO zhparser_exists;

    IF zhparser_exists THEN
        config_name := 'zhparser';
    END IF;

    -- Update searchVector with weighted terms:
    -- A weight for title, B weight for content
    NEW."searchVector" :=
        setweight(to_tsvector(config_name, coalesce(NEW.title, '')), 'A') ||
        setweight(to_tsvector(config_name, coalesce(NEW.content, '')), 'B');

    NEW."updatedAt" := CURRENT_TIMESTAMP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop existing trigger if exists
DROP TRIGGER IF EXISTS document_search_vector_trigger ON "Document";

-- Create trigger for INSERT and UPDATE
CREATE TRIGGER document_search_vector_trigger
    BEFORE INSERT OR UPDATE OF title, content
    ON "Document"
    FOR EACH ROW
    EXECUTE FUNCTION document_search_vector_update();

-- =============================================
-- Backfill existing documents
-- =============================================

DO $$
DECLARE
    config_name text := 'simple';
    zhparser_exists boolean;
    batch_size integer := 100;
    processed_count integer := 0;
    total_count integer;
BEGIN
    -- Check if zhparser is available
    SELECT EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'zhparser'
    ) INTO zhparser_exists;

    IF zhparser_exists THEN
        config_name := 'zhparser';
    END IF;

    -- Get total count
    SELECT COUNT(*) INTO total_count FROM "Document" WHERE "searchVector" IS NULL;

    IF total_count = 0 THEN
        RAISE NOTICE 'No documents need backfilling';
        RETURN;
    END IF;

    RAISE NOTICE 'Backfilling % documents...', total_count;

    -- Batch update
    WHILE processed_count < total_count LOOP
        WITH batch AS (
            SELECT id
            FROM "Document"
            WHERE "searchVector" IS NULL
            ORDER BY id
            LIMIT batch_size
        )
        UPDATE "Document" d
        SET
            "searchVector" =
                setweight(to_tsvector(config_name, coalesce(d.title, '')), 'A') ||
                setweight(to_tsvector(config_name, coalesce(d.content, '')), 'B'),
            "updatedAt" = CURRENT_TIMESTAMP
        FROM batch b
        WHERE d.id = b.id;

        GET DIAGNOSTICS processed_count = processed_count + ROW_COUNT;
        RAISE NOTICE 'Processed %/% documents', processed_count, total_count;

        COMMIT;
    END LOOP;

    RAISE NOTICE 'Backfill complete. Total: % documents', processed_count;
END $$;

-- =============================================
-- Optional: Configure zhparser for better Chinese search
-- =============================================

-- Uncomment the following lines if you have zhparser installed
-- and want to configure custom dictionaries:

-- ALTER TEXT SEARCH CONFIGURATION zhparser
--     ADD MAPPING FOR n,v,a,i,e,l WITH simple;

-- =============================================
-- Verify setup
-- =============================================

DO $$
DECLARE
    ext_count integer;
    idx_count integer;
    trig_count integer;
BEGIN
    -- Check extensions
    SELECT COUNT(*) INTO ext_count
    FROM pg_extension
    WHERE extname IN ('pg_trgm', 'zhparser');

    RAISE NOTICE 'Enabled extensions: % (expected 1 or 2)', ext_count;

    -- Check indexes
    SELECT COUNT(*) INTO idx_count
    FROM pg_indexes
    WHERE tablename = 'Document'
      AND indexname IN (
          'idx_document_search_vector',
          'idx_document_title_trgm',
          'idx_document_content_trgm'
      );

    RAISE NOTICE 'Search indexes created: % (expected 3)', idx_count;

    -- Check trigger
    SELECT COUNT(*) INTO trig_count
    FROM pg_trigger
    WHERE tgname = 'document_search_vector_trigger';

    RAISE NOTICE 'Trigger created: % (expected 1)', trig_count;

    IF ext_count >= 1 AND idx_count = 3 AND trig_count = 1 THEN
        RAISE NOTICE 'Full-text search setup completed successfully!';
    ELSE
        RAISE WARNING 'Some components may be missing. Please check the output above.';
    END IF;
END $$;
