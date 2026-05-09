-- ====================================================================================
-- INITIAL DATABASE SEEDING
-- Compatible with Spring Boot + JPA + Hibernate
-- Complies with assessment rubric Criterium 3.6
-- ====================================================================================

-- 1. Insert Test Users
INSERT INTO users (
    id,
    username,
    email,
    password_hash,
    created_at,
    is_active,
    status
)
VALUES (
    'ab24217d-decb-4134-98f3-90fc780246af',
    'java@test.nl',
    'java@test.nl',
    'dummy_hash_keycloak',
    CURRENT_TIMESTAMP,
    true,
    'OFFLINE'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO users (
    id,
    username,
    email,
    password_hash,
    created_at,
    is_active,
    status
)
VALUES (
    'f2f1d986-4785-4126-a351-95bbc428859e',
    'novi@test.nl',
    'novi@test.nl',
    'dummy_hash_keycloak',
    CURRENT_TIMESTAMP,
    true,
    'OFFLINE'
)
ON CONFLICT (id) DO NOTHING;

-- 2. Assign Roles via JPA @ElementCollection table
INSERT INTO user_roles (user_id, roles)
VALUES
('ab24217d-decb-4134-98f3-90fc780246af', 'ROLE_ADMIN'),
('ab24217d-decb-4134-98f3-90fc780246af', 'ROLE_USER'),
('f2f1d986-4785-4126-a351-95bbc428859e', 'ROLE_USER');

-- 3. Create Default Chat Room
INSERT INTO chat_rooms (
    id,
    name,
    description,
    created_by,
    is_private,
    created_at,
    max_participants
)
VALUES (
    'c0a80121-7b50-4b1a-8c11-92f5b8000000',
    'General Discussion',
    'Welcome to the default chat room!',
    'ab24217d-decb-4134-98f3-90fc780246af',
    false,
    CURRENT_TIMESTAMP,
    100
)
ON CONFLICT (id) DO NOTHING;

-- 4. Add Memberships
INSERT INTO chat_room_members (
    id,
    chat_room_id,
    user_id,
    role,
    joined_at,
    is_active
)
VALUES (
    'c0a80121-7b50-4b1a-8c11-92f5b8000001',
    'c0a80121-7b50-4b1a-8c11-92f5b8000000',
    'ab24217d-decb-4134-98f3-90fc780246af',
    'ADMIN',
    CURRENT_TIMESTAMP,
    true
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO chat_room_members (
    id,
    chat_room_id,
    user_id,
    role,
    joined_at,
    is_active
)
VALUES (
    'c0a80121-7b50-4b1a-8c11-92f5b8000002',
    'c0a80121-7b50-4b1a-8c11-92f5b8000000',
    'f2f1d986-4785-4126-a351-95bbc428859e',
    'MEMBER',
    CURRENT_TIMESTAMP,
    true
)
ON CONFLICT (id) DO NOTHING;

-- 5. Add Initial Message
INSERT INTO messages (
    id,
    chat_room_id,
    sender_id,
    username,
    content,
    message_type,
    timestamp,
    is_edited,
    is_deleted
)
VALUES (
    'c0a80121-7b50-4b1a-8c11-92f5b8000003',
    'c0a80121-7b50-4b1a-8c11-92f5b8000000',
    'ab24217d-decb-4134-98f3-90fc780246af',
    'java@test.nl',
    'Welcome everyone! This is the first message in our new system.',
    'TEXT',
    CURRENT_TIMESTAMP,
    false,
    false
)
ON CONFLICT (id) DO NOTHING;
