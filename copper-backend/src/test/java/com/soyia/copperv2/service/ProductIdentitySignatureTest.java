package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ProductIdentitySignatureTest {
    @Test
    void lengthPrefixedHashChainCannotBeConfusedByDisplaySeparators() {
        String left = ProductIdentitySignature.calculate(3, List.of(
                new ProductIdentitySignature.Part("A", "x|B=y"),
                new ProductIdentitySignature.Part("B", "z")));
        String right = ProductIdentitySignature.calculate(3, List.of(
                new ProductIdentitySignature.Part("A", "x"),
                new ProductIdentitySignature.Part("B", "y|B=z")));

        assertNotEquals(left, right);
        assertEquals(left, ProductIdentitySignature.calculate(3, List.of(
                new ProductIdentitySignature.Part("A", "x|B=y"),
                new ProductIdentitySignature.Part("B", "z"))));
    }

    @Test
    void utf8ByteLengthAndSchemaVersionParticipateInSignature() {
        List<ProductIdentitySignature.Part> parts = List.of(
                new ProductIdentitySignature.Part("材质", "无氧铜"));

        assertNotEquals(
                ProductIdentitySignature.calculate(1, parts),
                ProductIdentitySignature.calculate(2, parts));
        assertNotEquals(
                ProductIdentitySignature.calculate(1, parts),
                ProductIdentitySignature.calculate(1, List.of(
                        new ProductIdentitySignature.Part("材质", "无 氧铜"))));
    }
}
