-- T116: one way for a user to be attached to a home.
--
-- There were two. HOME_STAFF used users.home_id, a single foreign key; VIEWER used the
-- user_viewer_homes join table, many-to-many. Same relationship, two mechanisms - which is how one
-- of them silently stops being checked, because a new access path only has to remember the one its
-- author was thinking about.
--
-- Done now for the same reason returned_at became NOT NULL in V15: the database is empty, so this
-- is a copy of nothing. After real data it is a migration of live access rights, which is the worst
-- category of thing to get wrong in a system holding children's records.

CREATE TABLE user_homes (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    home_id BIGINT NOT NULL REFERENCES homes (id),
    PRIMARY KEY (user_id, home_id)
);

-- Both old mechanisms fold into the new one. Written to be correct even though both are empty
-- today, because a migration that only works on an empty database is a trap for anyone who runs it
-- anywhere else.
INSERT INTO user_homes (user_id, home_id)
SELECT user_id, home_id FROM user_viewer_homes;

INSERT INTO user_homes (user_id, home_id)
SELECT id, home_id FROM users WHERE home_id IS NOT NULL
ON CONFLICT (user_id, home_id) DO NOTHING;

DROP TABLE user_viewer_homes;

-- The single-home foreign key goes. Leaving it would leave the ambiguity this migration exists to
-- remove: two columns describing one relationship, only one of which any given check consults.
ALTER TABLE users DROP COLUMN home_id;

-- The access-control queries all start from a user, so index that direction. The primary key
-- already covers (user_id, home_id); this covers the reverse lookup ("who can see this home").
CREATE INDEX idx_user_homes_home_id ON user_homes (home_id);
