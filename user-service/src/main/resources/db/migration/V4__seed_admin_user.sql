INSERT INTO users (id, email, password_hash, first_name, last_name, created_at, updated_at)
VALUES (
           '00000000-0000-0000-0000-000000000000',
           'admin@bitesync.com',
           '$2a$10$wO8yV1a5qYt/1K.Y9L0x4.XoJhwGtHUX2CuONuUyMzNEAzC6nKQCK',
           'System',
           'Admin',
           CURRENT_TIMESTAMP,
           CURRENT_TIMESTAMP
       );

INSERT INTO user_roles (user_id, role)
VALUES ('00000000-0000-0000-0000-000000000000', 'ADMIN'),
       ('00000000-0000-0000-0000-000000000000', 'CUSTOMER');