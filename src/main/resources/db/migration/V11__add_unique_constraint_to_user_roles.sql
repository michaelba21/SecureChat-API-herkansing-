-- Add UNIQUE constraint to user_roles table to support ON CONFLICT in data.sql
-- This constraint ensures that each user has only one entry per role
-- and enables PostgreSQL to detect conflicts during INSERT operations
ALTER TABLE user_roles
ADD CONSTRAINT uk_user_roles UNIQUE (user_id, roles);
