INSERT OR IGNORE INTO order_fulfillment_status
    (status_id, status_code, display_name, description, terminal_status)
VALUES
    (1, 'ORDER_PLACED', 'Order placed', 'The order was accepted.', FALSE),
    (2, 'PACKAGED_BY_MERCHANT', 'Packaged by merchant', 'The merchant packaged the order.', FALSE),
    (3, 'SHIPPED_BY_MERCHANT', 'Shipped by merchant', 'The merchant handed the order to the carrier.', FALSE),
    (4, 'RECEIVED_AT_SOURCE_SORTING_FACILITY', 'Received at source sorting facility', 'The source facility received the shipment.', FALSE),
    (5, 'SHIPPED_TO_TARGET_SORTING_FACILITY', 'Shipped to target sorting facility', 'The shipment is moving to the target facility.', FALSE),
    (6, 'RECEIVED_AT_TARGET_SORTING_FACILITY', 'Received at target sorting facility', 'The target facility received the shipment.', FALSE),
    (7, 'OUT_FOR_DELIVERY', 'Out for delivery', 'A delivery agent is carrying the shipment.', FALSE),
    (8, 'DELIVERED', 'Delivered', 'The shipment was delivered.', TRUE),
    (9, 'AWAITING_INVENTORY_REFRESH', 'Awaiting inventory refresh', 'Fulfillment is waiting for merchant inventory.', FALSE),
    (10, 'DELIVERY_AGENT_UNREACHABLE', 'Delivery agent unreachable', 'The assigned delivery agent cannot currently be reached.', FALSE),
    (11, 'LOST_IN_TRANSIT', 'Lost in transit', 'The carrier has declared the shipment lost.', TRUE),
    (12, 'CANCELLED', 'Cancelled', 'The order was cancelled.', TRUE);

INSERT OR IGNORE INTO customer_order (order_id, customer_id, order_start_date)
VALUES
    ('ORD-1001', 'CUST-1001', '2026-10-25 09:30:00.000'),
    ('ORD-1002', 'CUST-1001', '2026-10-28 14:15:00.000'),
    ('ORD-1003', 'CUST-1002', '2026-10-20 11:00:00.000'),
    ('ORD-1004', 'CUST-1002', '2026-11-01 16:45:00.000'),
    ('ORD-1005', 'CUST-1003', '2026-10-18 08:00:00.000'),
    ('ORD-1006', 'CUST-1004', '2026-11-02 09:00:00.000');

INSERT OR IGNORE INTO order_fulfillment
    (fulfillment_id, order_id, status_id, status_update_timestamp, details)
VALUES
    (1, 'ORD-1001', 1, '2026-10-25 09:30:00.000', 'Order accepted by the marketplace.'),
    (2, 'ORD-1001', 2, '2026-10-25 18:00:00.000', 'Merchant completed packaging.'),
    (3, 'ORD-1001', 3, '2026-10-26 10:10:00.000', 'Carrier pickup completed.'),
    (4, 'ORD-1001', 4, '2026-10-26 20:40:00.000', 'Scanned at Bengaluru source facility.'),
    (5, 'ORD-1001', 5, '2026-10-27 03:15:00.000', 'Departed for target city.'),
    (6, 'ORD-1001', 6, '2026-10-28 06:20:00.000', 'Scanned at Mumbai target facility.'),
    (7, 'ORD-1001', 7, '2026-10-28 09:05:00.000', 'Assigned to the local delivery route.'),
    (8, 'ORD-1001', 8, '2026-10-28 14:32:00.000', 'Delivered to the customer.'),
    (9, 'ORD-1002', 1, '2026-10-28 14:15:00.000', 'Order accepted by the marketplace.'),
    (10, 'ORD-1002', 9, '2026-10-28 14:16:00.000', 'Merchant stock reconciliation is pending.'),
    (11, 'ORD-1003', 1, '2026-10-20 11:00:00.000', 'Order accepted by the marketplace.'),
    (12, 'ORD-1003', 2, '2026-10-20 17:30:00.000', 'Merchant completed packaging.'),
    (13, 'ORD-1003', 3, '2026-10-21 08:15:00.000', 'Carrier pickup completed.'),
    (14, 'ORD-1003', 10, '2026-10-22 19:10:00.000', 'Carrier operations cannot reach the assigned agent.'),
    (15, 'ORD-1004', 1, '2026-11-01 16:45:00.000', 'Order accepted by the marketplace.'),
    (16, 'ORD-1004', 2, '2026-11-02 10:00:00.000', 'Merchant completed packaging.'),
    (17, 'ORD-1004', 3, '2026-11-02 15:30:00.000', 'Carrier pickup completed.'),
    (18, 'ORD-1004', 4, '2026-11-03 01:20:00.000', 'Scanned at source facility.'),
    (19, 'ORD-1004', 5, '2026-11-03 05:40:00.000', 'Departed for target facility.'),
    (20, 'ORD-1005', 1, '2026-10-18 08:00:00.000', 'Order accepted by the marketplace.'),
    (21, 'ORD-1005', 2, '2026-10-18 13:00:00.000', 'Merchant completed packaging.'),
    (22, 'ORD-1005', 3, '2026-10-18 17:20:00.000', 'Carrier pickup completed.'),
    (23, 'ORD-1005', 11, '2026-10-21 12:00:00.000', 'Shipment declared lost after carrier investigation.'),
    (24, 'ORD-1006', 8, '2026-11-05 15:00:00.000', 'Delivered to the customer.');
