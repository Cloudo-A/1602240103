ALTER TABLE quarter_close
    ADD COLUMN first_confirmed_at DATETIME NULL COMMENT '首次确认时间';

UPDATE quarter_close SET first_confirmed_at = confirmed_at;

ALTER TABLE quarter_close
    MODIFY first_confirmed_at DATETIME NOT NULL COMMENT '首次确认时间';

CREATE INDEX idx_visit_arrived_at ON visit (arrived_at);
