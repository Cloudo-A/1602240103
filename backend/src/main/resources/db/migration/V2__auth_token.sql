CREATE TABLE auth_token (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at DATETIME NOT NULL COMMENT '北京时间',
    INDEX idx_auth_token_expiry (expires_at),
    CONSTRAINT fk_auth_token_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
