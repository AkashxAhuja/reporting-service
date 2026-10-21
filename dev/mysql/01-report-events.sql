-- Local development fixture only. Historical rows are treated as immutable during an export.
SET NAMES utf8mb4;

CREATE TABLE report_events (
    id BIGINT NOT NULL PRIMARY KEY,
    occurred_at DATETIME(6) NOT NULL COMMENT 'UTC',
    reference VARCHAR(255) CHARACTER SET utf8mb4 NULL,
    amount DECIMAL(18,2) NULL,
    status VARCHAR(32) NOT NULL,
    INDEX idx_report_events_occurred_id (occurred_at, id)
);

INSERT INTO report_events (id, occurred_at, reference, amount, status) VALUES
(1, '2026-09-30 23:59:59.999999', 'Before requested day', 10.00, 'SETTLED'),
(2, '2026-10-01 00:00:00.000000', 'At inclusive start', 100.00, 'SETTLED'),
(3, '2026-10-01 12:00:00.123456', 'Order, with comma', 200.50, 'SETTLED'),
(4, '2026-10-01 12:00:00.123456', 'Customer "quoted"', -25.00, 'REFUNDED'),
(5, '2026-10-01 18:00:00.000000', CONCAT('Line one', CHAR(10), 'Line two'), 0.00, 'PENDING'),
(6, '2026-10-01 23:59:59.999999', 'Unicode: café', 42.10, 'SETTLED'),
(7, '2026-10-02 00:00:00.000000', 'At exclusive end', 300.00, 'SETTLED'),
(8, '2026-10-02 10:00:00.000000', NULL, NULL, 'PENDING');

CREATE USER 'report_reader'@'%' IDENTIFIED BY 'report-reader-local';
GRANT SELECT ON reporting_source.report_events TO 'report_reader'@'%';
