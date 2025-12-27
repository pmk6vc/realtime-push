/**
  NOTE: Migration files in this directory are governed by Flyway.
  They all assume that the Citus extension has already been created in the database.

  The Citus extension needs to be created only once per database, not per migration.
  For this reason, the initial migration that creates the Citus extension is not included here.
  Instead, it is run via a separate setup script found <root>/db/init. This setup script is mounted
  into a special location in the DB container (see docker-compose.yml) so that it runs only once
  when the database is first created.
 */

-- Users reference table
CREATE TABLE users (
    user_id      uuid PRIMARY KEY,
    created_at   timestamptz NOT NULL DEFAULT now()
);
SELECT create_reference_table('users');

-- Channels reference table
CREATE TABLE channels (
    channel_id      uuid PRIMARY KEY,
    channel_name    text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
SELECT create_reference_table('channels');

-- Distributed chat messages table - distribution key is channel_id to localize messages of the same conversation
CREATE TABLE IF NOT EXISTS messages (
    channel_id      uuid NOT NULL,
    message_id      uuid NOT NULL DEFAULT gen_random_uuid(),
    sender_user_id  uuid NOT NULL,
    sent_at         timestamptz NOT NULL DEFAULT now(),
    body            text NOT NULL,

    -- In Citus, primary/unique constraints generally need to include the distribution column.
    PRIMARY KEY (channel_id, message_id)
);
SELECT create_distributed_table('messages', 'channel_id');

-- FK constraints
ALTER TABLE messages
    ADD CONSTRAINT fk_messages_sender_user
    FOREIGN KEY (sender_user_id)
    REFERENCES users(user_id)
    ON DELETE CASCADE;
ALTER TABLE messages
    ADD CONSTRAINT fk_messages_channel
    FOREIGN KEY (channel_id)
    REFERENCES channels(channel_id)
    ON DELETE CASCADE;

-- Indexes for performance optimization
CREATE INDEX idx_messages_sender_user ON messages(sender_user_id);
CREATE INDEX idx_messages_channel_sent_at ON messages(channel_id, sent_at);
