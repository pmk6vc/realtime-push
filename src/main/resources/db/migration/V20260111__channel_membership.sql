-- Channel membership: adds owner to channels and creates channel_members table

-- Enable sequential mode for all DDL operations involving reference tables
SET LOCAL citus.multi_shard_modify_mode TO 'sequential';

-- Step 1: Add owner_user_id to channels (nullable initially to handle existing data)
ALTER TABLE channels ADD COLUMN owner_user_id uuid;

-- Step 2: Remove any existing channels without owners (they're invalid without ownership)
DELETE FROM channels WHERE owner_user_id IS NULL;

-- Step 3: Make owner_user_id NOT NULL
ALTER TABLE channels ALTER COLUMN owner_user_id SET NOT NULL;

-- Step 4: Add foreign key constraint (RESTRICT prevents deleting users who own channels)
ALTER TABLE channels
    ADD CONSTRAINT fk_channels_owner
    FOREIGN KEY (owner_user_id)
    REFERENCES users(user_id)
    ON DELETE RESTRICT;

-- Step 5: Create channel_members table (reference table since channels is reference table)
CREATE TABLE channel_members (
    channel_id  uuid NOT NULL,
    user_id     uuid NOT NULL,
    joined_at   timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (channel_id, user_id)
);
SELECT create_reference_table('channel_members');

-- Step 6: Add foreign keys for channel_members
ALTER TABLE channel_members
    ADD CONSTRAINT fk_channel_members_channel
    FOREIGN KEY (channel_id)
    REFERENCES channels(channel_id)
    ON DELETE CASCADE;

ALTER TABLE channel_members
    ADD CONSTRAINT fk_channel_members_user
    FOREIGN KEY (user_id)
    REFERENCES users(user_id)
    ON DELETE CASCADE;

-- Step 7: Indexes for membership lookups
-- Find all channels a user belongs to
CREATE INDEX idx_channel_members_user ON channel_members(user_id);
-- Find all members of a channel (explicit index for clarity, PK covers this but makes intent clear)
CREATE INDEX idx_channel_members_channel ON channel_members(channel_id);

-- Reset sequential mode (auto-resets at transaction end, but explicit for clarity)
RESET citus.multi_shard_modify_mode;