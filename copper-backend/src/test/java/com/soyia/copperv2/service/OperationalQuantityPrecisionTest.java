package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.AlignedDtos;
import com.soyia.copperv2.dto.CrmDtos;
import com.soyia.copperv2.dto.InboundCreateRequest;
import com.soyia.copperv2.dto.InventoryDtos;
import com.soyia.copperv2.dto.OutboundActionRequests;
import com.soyia.copperv2.dto.OutboundCreateRequest;
import com.soyia.copperv2.dto.PackagingDtos;
import com.soyia.copperv2.dto.PriceCenterDtos;
import com.soyia.copperv2.dto.ProductMasterDataDtos;
import com.soyia.copperv2.dto.PurchaseCreateRequest;
import com.soyia.copperv2.dto.TaxInvoiceDtos;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Digits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalQuantityPrecisionTest {
    @Test
    void everyOperationalQuantityInputUsesEighteenIntegerAndSixFractionDigits() throws Exception {
        assertQuantityDigits(InboundCreateRequest.Item.class,
                "quantity", "actualBaseQuantity", "packageQuantity");
        assertQuantityDigits(OutboundCreateRequest.Item.class,
                "quantity", "actualBaseQuantity", "packageQuantity");
        assertQuantityDigits(PurchaseCreateRequest.Item.class,
                "quantity", "actualBaseQuantity");
        assertQuantityDigits(AlignedDtos.SalesOrderCreate.Item.class,
                "quantity", "actualBaseQuantity");
        assertQuantityDigits(PackagingDtos.PlanRequest.class,
                "quantity", "actualBaseQuantity");
        assertQuantityDigits(PackagingDtos.PackageInstanceInput.class, "actualBaseQuantity");
        assertQuantityDigits(InventoryDtos.TransferItemCreate.class, "quantity");
        assertQuantityDigits(InventoryDtos.StocktakeItemCreate.class, "countedQuantity");
        assertQuantityDigits(InventoryDtos.PurchaseReturnItemCreate.class, "quantity");
        assertQuantityDigits(OutboundActionRequests.SalesReturnCreate.Item.class, "quantity");
        assertQuantityDigits(CrmDtos.QuoteItemSave.class, "quantity", "actualBaseQuantity");
        assertQuantityDigits(PriceCenterDtos.RuleSave.class,
                "minimumQuantity", "maximumQuantity");
        assertQuantityDigits(PriceCenterDtos.PreviewRequest.class, "quantity");
        assertQuantityDigits(TaxInvoiceDtos.InvoiceLineSave.class, "quantity");
        assertQuantityDigits(ProductMasterDataDtos.PackagingVariantSave.class,
                "fixedBaseQuantity", "referenceBaseQuantity", "netWeight", "grossWeight",
                "tareWeight");
        assertQuantityDigits(ProductMasterDataDtos.SupplierOfferSave.class,
                "minimumOrderQuantity", "orderMultiple");
        assertQuantityDigits(ProductMasterDataDtos.BomItemSave.class, "quantity");
        assertQuantityDigits(ProductMasterDataDtos.BomSave.class, "outputQuantity");
    }

    @ParameterizedTest(name = "{0} rejects a seventh quantity decimal")
    @MethodSource("seventhDecimalOperationalRequests")
    void beanValidationRejectsTheSeventhDecimalAcrossOperationalEntryPoints(
            String label, Object request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertFalse(validator.validate(request).isEmpty(), label);
        }
    }

    @Test
    void beanValidationAcceptsTheSmallestSixDecimalQuantity() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            PackagingDtos.PlanRequest accepted = new PackagingDtos.PlanRequest(
                    new BigDecimal("0.000001"), "kg", null, "kg", "PURCHASE_ORDER");
            assertTrue(factory.getValidator().validate(accepted).isEmpty());
        }
    }

    private static Stream<Arguments> seventhDecimalOperationalRequests() {
        BigDecimal quantity = new BigDecimal("0.0000001");
        return Stream.of(
                Arguments.of("packaging plan", new PackagingDtos.PlanRequest(
                        quantity, "kg", null, "kg", "PURCHASE_ORDER")),
                Arguments.of("inbound", new InboundCreateRequest.Item(
                        1L, null, "", "", quantity, "kg", null, null, null,
                        null, "kg", "", "", BigDecimal.ONE, List.of(), null)),
                Arguments.of("outbound", new OutboundCreateRequest.Item(
                        null, 1L, quantity, "kg", null, null, null,
                        null, "", "kg", BigDecimal.ONE, List.of())),
                Arguments.of("purchase", new PurchaseCreateRequest.Item(
                        null, 1L, quantity, "kg", null, null, null,
                        "kg", "", null, null, null, false, BigDecimal.ONE)),
                Arguments.of("sales", new AlignedDtos.SalesOrderCreate.Item(
                        null, 1L, quantity, "kg", null, null, null,
                        "kg", "", false, BigDecimal.ONE))
        );
    }

    private void assertQuantityDigits(Class<?> type, String... propertyNames) throws Exception {
        for (String propertyName : propertyNames) {
            Digits digits = type.getDeclaredMethod(propertyName).getAnnotation(Digits.class);
            assertTrue(digits != null, type.getSimpleName() + "." + propertyName
                    + " 必须声明数量定点精度");
            assertEquals(18, digits.integer(), type.getSimpleName() + "." + propertyName);
            assertEquals(6, digits.fraction(), type.getSimpleName() + "." + propertyName);
        }
    }
}
