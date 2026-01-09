-- Remove DEFAULT now() from sent_at column
-- Application now controls timestamp generation using Instant.now()
ALTER TABLE messages ALTER COLUMN sent_at DROP DEFAULT;