-- Update ordering index to include message_id for deterministic tie-breaking
-- This prevents out-of-order messages when timestamps are identical or close due to clock skew

DROP INDEX IF EXISTS idx_messages_channel_sent_at;

-- New index includes message_id to ensure consistent ordering even when sent_at values collide
-- Queries should use: ORDER BY sent_at ASC, message_id ASC
CREATE INDEX idx_messages_channel_ordering ON messages(channel_id, sent_at, message_id);