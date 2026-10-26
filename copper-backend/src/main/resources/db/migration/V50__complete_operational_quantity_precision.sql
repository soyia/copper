-- Complete the fixed-point quantity contract started in V49.
-- Only operational quantities, physical packaging quantities and unit-conversion
-- factors are widened here. Accounting amounts, prices, tax rates and percentages
-- deliberately retain their existing precision.

ALTER TABLE inbound_item
  MODIFY requested_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  MODIFY pricing_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;

ALTER TABLE inbound_review_snapshot_item
  MODIFY requested_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  MODIFY pricing_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;

ALTER TABLE outbound_item
  MODIFY requested_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  MODIFY pricing_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;

ALTER TABLE purchase_order_item
  MODIFY requested_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  MODIFY pricing_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;

ALTER TABLE sales_order_item
  MODIFY requested_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  MODIFY pricing_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;

ALTER TABLE inbound_package_instance
  MODIFY actual_base_quantity decimal(24,6) NOT NULL;

ALTER TABLE inventory_package_balance
  MODIFY quantity decimal(24,6) NOT NULL DEFAULT 0.000000;

ALTER TABLE inventory_package_instance
  MODIFY actual_base_quantity decimal(24,6) NOT NULL;

ALTER TABLE inventory_package_movement
  MODIFY from_quantity decimal(24,6) NOT NULL DEFAULT 0.000000,
  MODIFY to_quantity decimal(24,6) NOT NULL DEFAULT 0.000000;

-- The same column stores quantity/weight allocation bases (and amount bases).
-- Widening its integer capacity prevents a valid 24,6 inbound quantity from
-- failing when the landed-cost method is QUANTITY, without changing its scale.
ALTER TABLE landed_cost_allocation_item
  MODIFY basis_value decimal(24,6) NOT NULL;

ALTER TABLE product_package_rule
  MODIFY fixed_child_quantity decimal(24,6) DEFAULT NULL,
  MODIFY reference_base_quantity decimal(24,6) DEFAULT NULL;

ALTER TABLE product_series_package_unit
  MODIFY exchange_rate decimal(24,6) DEFAULT NULL,
  MODIFY volume decimal(24,6) DEFAULT NULL;

ALTER TABLE product_series_price_unit
  MODIFY exchange_rate decimal(24,6) DEFAULT NULL;
