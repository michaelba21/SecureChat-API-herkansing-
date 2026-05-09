-- Add status column to users table for tracking user online status
-- This column stores the user's current status: ONLINE, OFFLINE, or AWAY
-- Required for proper user presence management and real-time updates
ALTER TABLE users
ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'OFFLINE';
