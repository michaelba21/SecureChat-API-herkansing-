-- ====================================================================================
-- V12: Gebruikersschema afstemmen met JPA Entity Definition
-- ====================================================================================

-- Bio en avatar_url kolommen toevoegen (ontbraken in V1)
ALTER TABLE users ADD COLUMN IF NOT EXISTS bio TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500);

-- Status kolom constraints (toegevoegd in V10, nu versterkt)
ALTER TABLE users ALTER COLUMN status SET DEFAULT 'OFFLINE';
ALTER TABLE users ALTER COLUMN status SET NOT NULL;

-- ====================================================================================
-- Verificatie: users tabel heeft nu alle kolommen uit User.java:
-- id, username, email, password_hash, created_at, last_login, is_active, bio, avatar_url, status
-- ====================================================================================