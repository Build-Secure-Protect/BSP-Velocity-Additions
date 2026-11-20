-- BSP-Server Shop: the bsp_shop database, schema version 1.
-- BSP-Server Shop owns this schema: it runs this file at every start (every statement is IF NOT EXISTS, so a second run
-- changes nothing) and keeps the version in shop_schema. Velocity Additions uses the same SQL and refuses to start if
-- shop_schema holds a newer version than it understands.

CREATE TABLE IF NOT EXISTS shop_schema (version INT NOT NULL);

-- Used by Velocity Additions only
CREATE TABLE IF NOT EXISTS shop_events (
  event_id      VARCHAR(64)  PRIMARY KEY,          -- Fourthwall envelope id, used for dedupe
  type          VARCHAR(48)  NOT NULL,
  test_mode     BOOLEAN      NOT NULL,
  body          MEDIUMTEXT   NOT NULL,             -- raw JSON
  received_at   DATETIME(3)  NOT NULL,
  processed_at  DATETIME(3)  NULL,
  error         TEXT         NULL
);

-- Used by Velocity Additions only
CREATE TABLE IF NOT EXISTS shop_orders (
  order_id      VARCHAR(64)  PRIMARY KEY,
  friendly_id   VARCHAR(32)  NOT NULL,
  email         VARCHAR(255) NOT NULL,
  username_raw  VARCHAR(255) NULL,                 -- exactly what the buyer typed
  player_uuid   CHAR(36)     NULL,
  player_name   VARCHAR(16)  NULL,
  state         VARCHAR(24)  NOT NULL,             -- AWAITING_PLAYER | ASSIGNED | CANCELLED
  test_mode     BOOLEAN      NOT NULL,
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  INDEX (friendly_id), INDEX (email), INDEX (player_uuid)
);

-- Used by Velocity Additions only
CREATE TABLE IF NOT EXISTS shop_members (
  member_id     VARCHAR(64)  PRIMARY KEY,
  email         VARCHAR(255) NOT NULL,
  nickname      VARCHAR(255) NULL,
  sub_type      VARCHAR(16)  NOT NULL,             -- ACTIVE | CANCELLED | SUSPENDED | NONE
  tier_id       VARCHAR(64)  NULL,
  granted_tier  VARCHAR(64)  NULL,                 -- tier whose rewards are currently applied
  updated_at    DATETIME(3)  NOT NULL,
  INDEX (email)
);

-- Used by Velocity Additions only
CREATE TABLE IF NOT EXISTS shop_links (
  player_uuid   CHAR(36)     PRIMARY KEY,
  player_name   VARCHAR(16)  NOT NULL,
  email         VARCHAR(255) NOT NULL UNIQUE,      -- lower-cased and trimmed
  linked_by     VARCHAR(16)  NOT NULL,             -- player | staff
  linked_at     DATETIME(3)  NOT NULL
);

-- Shared: Velocity Additions writes rows, BSP-Server Shop delivers them
CREATE TABLE IF NOT EXISTS shop_rewards (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  dedupe_key     VARCHAR(191) NOT NULL UNIQUE,     -- e.g. ORDER:<orderId>:<sku>:<lineIdx>:<unit>:GRANT
  source_type    VARCHAR(16)  NOT NULL,            -- ORDER | MEMBERSHIP | MANUAL | TEST
  source_id      VARCHAR(64)  NOT NULL,
  reward_key     VARCHAR(64)  NOT NULL,            -- SKU or tier key from the rewards config
  display_name   VARCHAR(128) NOT NULL,            -- shown to the player on delivery
  action         VARCHAR(8)   NOT NULL,            -- GRANT | REVOKE
  player_uuid    CHAR(36)     NOT NULL,
  player_name    VARCHAR(16)  NOT NULL,
  target         VARCHAR(64)  NOT NULL,            -- 'any' or a Velocity server name
  require_online BOOLEAN      NOT NULL,
  safe_to_retry  BOOLEAN      NOT NULL,
  commands       TEXT         NOT NULL,            -- JSON array of strings, placeholders already filled in
  status         VARCHAR(16)  NOT NULL,            -- PENDING | CLAIMED | DELIVERED | FAILED | CANCELLED
  claimed_by     VARCHAR(64)  NULL,
  claimed_at     DATETIME(3)  NULL,
  attempts       INT          NOT NULL DEFAULT 0,
  last_error     TEXT         NULL,
  test_mode      BOOLEAN      NOT NULL,
  created_at     DATETIME(3)  NOT NULL,
  delivered_at   DATETIME(3)  NULL,
  INDEX (status, target), INDEX (player_uuid)
);
