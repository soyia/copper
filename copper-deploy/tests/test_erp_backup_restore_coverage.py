#!/usr/bin/env python3
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class ErpBackupRestoreCoverageTest(unittest.TestCase):
    def test_v8_v24_table_contract_is_complete_and_unique(self):
        tables = [
            line.strip()
            for line in (ROOT / "config/erp-v8-v24-tables.txt").read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
        self.assertEqual(56, len(tables))
        self.assertEqual(56, len(set(tables)))
        for table in (
            "inventory_transfer_order", "purchase_return_order", "business_posting_entry",
            "accounting_voucher", "accounting_ledger_entry", "tax_invoice",
            "bank_reconciliation_match", "accounting_period_close_run", "crm_quote",
        ):
            self.assertIn(table, tables)

    def test_business_totals_cover_each_new_erp_domain(self):
        sql = (ROOT / "scripts/verification-sql.sh").read_text(encoding="utf-8")
        for metric in (
            "inventory_transfer.total_quantity.",
            "inventory_stocktake.cost_difference.",
            "purchase_return.payable_reduction.",
            "inventory_cost_adjustment.difference_amount.",
            "business_posting_entry.amount.",
            "accounting_voucher.debit.",
            "accounting_ledger.credit.total",
            "tax_invoice.total_amount.",
            "bank_match.amount.",
            "period_close.net_profit.",
            "crm_opportunity.estimated_amount.",
            "crm_quote.total_amount.",
            "inventory_package.remaining_base_quantity.",
            "inventory_package_usage.base_quantity.",
            "inventory_non_trade_item.quantity.",
            "inventory_non_trade_item.amount_cost.total",
            "inventory_non_trade.total_amount_cost.",
        ):
            self.assertIn(metric, sql)

    def test_erp18_opening_migration_table_contract_is_complete_and_unique(self):
        tables = [
            line.strip()
            for line in (ROOT / "config/erp18-opening-migration-tables.txt").read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]
        self.assertEqual(
            [
                "data_migration_batch",
                "data_migration_row",
                "data_migration_event",
                "data_migration_check_run",
                "data_migration_check_issue",
                "data_migration_fund_opening",
            ],
            tables,
        )
        self.assertEqual(len(tables), len(set(tables)))

    def test_runtime_checks_contract_before_backup_and_after_switch(self):
        runtime = (ROOT / "tests/m2-restore-runtime.sh").read_text(encoding="utf-8")
        self.assertGreaterEqual(runtime.count("assert_v8_v24_tables_present"), 3)
        self.assertIn("v8_v24_restore_modules=passed", runtime)
        self.assertGreaterEqual(runtime.count("assert_erp18_opening_migration_tables_present"), 3)
        self.assertGreaterEqual(runtime.count("assert_erp18_opening_migration_anchor_rows"), 3)
        self.assertIn("erp18_opening_migration_restore=passed", runtime)

    def test_new_module_file_evidence_is_part_of_backup_verification(self):
        sql = (ROOT / "scripts/verification-sql.sh").read_text(encoding="utf-8")
        for source in (
            "business_evidence_attachment",
            "tax_invoice_import_batch",
            "bank_statement_import_batch",
            "accounting_archive_batch",
            "electronic_credential",
            "electronic_credential_verification",
            "product_import_job",
            "document_intake_batch",
        ):
            self.assertIn(f"SELECT '{source}'", sql)


if __name__ == "__main__":
    unittest.main()
