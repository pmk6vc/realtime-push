/**
  NOTE: Migration files in this directory are governed by Flyway.
  They all assume that the Citus extension has already been created in the database.

  The Citus extension needs to be created only once per database, not per migration.
  For this reason, the initial migration that creates the Citus extension is not included here.
  Instead, it is run via a separate setup script found <root>/db/init. This setup script is mounted
  into a special location in the DB container (see docker-compose.yml) so that it runs only once
  when the database is first created.
 */

-- Local (non-distributed) users table
CREATE TABLE IF NOT EXISTS users (
    user_id      uuid PRIMARY KEY,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Distributed chat messages table
-- Pick a distribution column that matches your access patterns.
-- For chat, "conversation_id" is a common choice to keep a conversation's messages colocated.
CREATE TABLE IF NOT EXISTS chat_messages (
                                             conversation_id uuid NOT NULL,
                                             message_id      uuid NOT NULL,
                                             sender_user_id  uuid NOT NULL,     -- (no FK yet; distributed/local FKs are a bigger topic)
                                             sent_at         timestamptz NOT NULL DEFAULT now(),
    body            text NOT NULL,

    -- In Citus, primary/unique constraints generally need to include the distribution column.
    PRIMARY KEY (conversation_id, message_id)
    );

-- Distribute the table by conversation_id (hash distribution by default)
SELECT create_distributed_table('chat_messages', 'conversation_id');