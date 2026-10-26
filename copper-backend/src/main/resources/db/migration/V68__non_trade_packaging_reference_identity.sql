-- V65 preserved legacy document IDs as packaging references while new items
-- use their own IDs. Reserve one global positive reference namespace without
-- changing any existing business, package-usage or movement facts.
DROP PROCEDURE IF EXISTS v68_reserve_non_trade_packaging_identity;

DELIMITER //
CREATE PROCEDURE v68_reserve_non_trade_packaging_identity()
BEGIN
    DECLARE v_highest decimal(20,0) DEFAULT 0;
    DECLARE v_current_next decimal(20,0) DEFAULT 1;
    DECLARE v_next decimal(20,0) DEFAULT 1;

    IF EXISTS (SELECT 1 FROM inventory_non_trade_item WHERE packaging_ref_id=0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V68_PACKAGING_REF_ZERO_BLOCK';
    END IF;
    IF EXISTS (
        SELECT packaging_ref_id FROM inventory_non_trade_item
        GROUP BY packaging_ref_id HAVING COUNT(*)>1
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V68_PACKAGING_REF_DUPLICATE_BLOCK';
    END IF;

    SELECT GREATEST(
        COALESCE((SELECT MAX(id) FROM inventory_non_trade_item),0),
        COALESCE((SELECT MAX(packaging_ref_id) FROM inventory_non_trade_item),0),
        COALESCE((SELECT MAX(ref_id) FROM inventory_package_instance_usage
                  WHERE ref_type IN ('INVENTORY_NON_TRADE','INVENTORY_NON_TRADE_REVERSAL')),0),
        COALESCE((SELECT MAX(ref_id) FROM inventory_package_movement
                  WHERE ref_type IN ('INVENTORY_NON_TRADE','INVENTORY_NON_TRADE_REVERSAL')),0),
        COALESCE((SELECT MAX(operation_ref_id) FROM inventory_package_instance
                  WHERE operation_ref_type IN ('INVENTORY_NON_TRADE','INVENTORY_NON_TRADE_REVERSAL')),0)
    ) INTO v_highest;

    -- Read the live counter, including IDs consumed by rolled-back work.
    SET SESSION information_schema_stats_expiry=0;
    SELECT COALESCE(AUTO_INCREMENT,1) INTO v_current_next
    FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='inventory_non_trade_item';
    SET v_next=GREATEST(v_highest+1,v_current_next,1);
    IF v_next>9223372036854775807 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V68_PACKAGING_REF_JAVA_LONG_EXHAUSTED';
    END IF;

    -- Both changes are one atomic table DDL, after every data preflight.
    -- The service's temporary 0 is replaced with its item ID within the same
    -- transaction; a second transaction may wait, but cannot commit a duplicate.
    SET @v68_sql=CONCAT(
        'ALTER TABLE inventory_non_trade_item ',
        'ADD UNIQUE KEY uk_inventory_non_trade_packaging_ref (packaging_ref_id), ',
        'AUTO_INCREMENT=',CAST(v_next AS CHAR)
    );
    PREPARE v68_statement FROM @v68_sql;
    EXECUTE v68_statement;
    DEALLOCATE PREPARE v68_statement;
END//
DELIMITER ;

CALL v68_reserve_non_trade_packaging_identity();
DROP PROCEDURE v68_reserve_non_trade_packaging_identity;
