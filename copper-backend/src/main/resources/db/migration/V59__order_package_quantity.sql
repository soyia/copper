ALTER TABLE purchase_order_item
  ADD COLUMN package_quantity decimal(24,6) NOT NULL DEFAULT 0.000000 AFTER pricing_quantity;

ALTER TABLE sales_order_item
  ADD COLUMN package_quantity decimal(24,6) NOT NULL DEFAULT 0.000000 AFTER pricing_quantity;
