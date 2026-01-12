-- =========================
-- Directories table
-- =========================
CREATE TABLE IF NOT EXISTS directories (
    directory_id BIGSERIAL PRIMARY KEY,
    path TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT directories_path_unique UNIQUE (path)
);

-- =========================
-- Files table
-- =========================
CREATE TABLE IF NOT EXISTS files (
    file_id BIGSERIAL PRIMARY KEY,
    disc_channel_id VARCHAR(255) NOT NULL,
    disc_message_id VARCHAR(255) NOT NULL,
    directory_id BIGINT NOT NULL
        REFERENCES directories(directory_id)
        ON DELETE RESTRICT,
    file_name TEXT NOT NULL,
    file_description TEXT,
    size BIGINT,
    mime_type TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT files_unique_name_per_directory
        UNIQUE (directory_id, file_name)
);

-- =========================
-- File partials table
-- =========================
CREATE TABLE IF NOT EXISTS file_partials (
    partial_id BIGSERIAL PRIMARY KEY,
    disc_channel_id VARCHAR(255) NOT NULL,
    disc_message_id VARCHAR(255) NOT NULL,
    directory_id BIGINT NOT NULL
        REFERENCES directories(directory_id)
        ON DELETE RESTRICT,
    part_name TEXT NOT NULL,
    part_number INTEGER NOT NULL,
    part_size BIGINT NOT NULL,
    original_filename TEXT NOT NULL,
    file_description TEXT,
    mime_type TEXT DEFAULT 'application/octet-stream',
    uploaded_via_webhook BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT partials_unique_name_per_directory
        UNIQUE (directory_id, part_name)
);

-- =========================
-- Indexes
-- =========================

CREATE INDEX IF NOT EXISTS idx_directories_path
ON directories (path);

CREATE INDEX IF NOT EXISTS idx_files_directory
ON files (directory_id);

CREATE INDEX IF NOT EXISTS idx_partials_directory
ON file_partials (directory_id);

CREATE INDEX IF NOT EXISTS idx_partials_original_filename
ON file_partials (original_filename);


INSERT INTO directories (path)
VALUES ('')
ON CONFLICT (path) DO NOTHING;
