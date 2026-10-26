package com.soyia.copperv2.controller;

import com.soyia.copperv2.dto.ApiResponse;
import com.soyia.copperv2.dto.OperationalLiteDtos;
import com.soyia.copperv2.service.OperationalLiteSuiteService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
public class OperationalLiteSuiteController {
    private final OperationalLiteSuiteService service;

    public OperationalLiteSuiteController(OperationalLiteSuiteService service) {
        this.service = service;
    }

    @GetMapping("/purchase-orders/{purchaseOrderId}/followup")
    public ApiResponse<OperationalLiteDtos.FollowupSummary> purchaseFollowup(
            @PathVariable Long purchaseOrderId) {
        return ApiResponse.ok(service.purchaseFollowup(purchaseOrderId));
    }

    @PostMapping("/purchase-orders/{purchaseOrderId}/followup/events")
    public ApiResponse<OperationalLiteDtos.FollowupSummary> addPurchaseFollowup(
            @PathVariable Long purchaseOrderId,
            @Valid @RequestBody OperationalLiteDtos.FollowupEventSave request) {
        return ApiResponse.ok(service.addPurchaseFollowup(purchaseOrderId, request));
    }

    @GetMapping("/operations/cash-window")
    public ApiResponse<OperationalLiteDtos.CashWindow> cashWindow() {
        return ApiResponse.ok(service.cashWindow());
    }

    @GetMapping("/external-confirmation-packets")
    public ApiResponse<List<OperationalLiteDtos.ConfirmationPacketRow>> confirmationPackets(
            @RequestParam String packetType, @RequestParam Long sourceId) {
        return ApiResponse.ok(service.confirmationPackets(packetType, sourceId));
    }

    @PostMapping("/external-confirmation-packets")
    public ApiResponse<OperationalLiteDtos.ConfirmationPacketCreated> createConfirmationPacket(
            @Valid @RequestBody OperationalLiteDtos.ConfirmationPacketCreate request) {
        return ApiResponse.ok(service.createConfirmationPacket(request));
    }

    @PostMapping("/external-confirmation-packets/{id}/revoke")
    public ApiResponse<Map<String, Long>> revokeConfirmationPacket(
            @PathVariable Long id, @RequestBody Map<String, String> request) {
        return ApiResponse.ok(Map.of("id", service.revokeConfirmationPacket(id, request.get("reason"))));
    }

    @GetMapping("/public/confirmations/{token}")
    public ApiResponse<OperationalLiteDtos.PublicConfirmationPacket> publicConfirmation(
            @PathVariable String token) {
        return ApiResponse.ok(service.publicConfirmation(token));
    }

    @PostMapping("/public/confirmations/{token}")
    public ApiResponse<OperationalLiteDtos.PublicConfirmationPacket> confirmPublic(
            @PathVariable String token,
            @Valid @RequestBody OperationalLiteDtos.PublicConfirmationSave request) {
        return ApiResponse.ok(service.confirmPublic(token, request));
    }

    @GetMapping("/document-intakes")
    public ApiResponse<List<OperationalLiteDtos.IntakeBatch>> intakeBatches(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.intakeBatches(status));
    }

    @GetMapping("/document-intakes/{id}")
    public ApiResponse<OperationalLiteDtos.IntakeBatch> intakeBatch(@PathVariable Long id) {
        return ApiResponse.ok(service.intakeBatch(id));
    }

    @PostMapping(value = "/document-intakes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OperationalLiteDtos.IntakeBatch> createIntake(
            @RequestParam("documentType") String documentType,
            @RequestParam(value = "providedText", required = false) String providedText,
            @RequestParam(value = "expectedProductId", required = false) Long expectedProductId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(service.createIntake(
                documentType, providedText, expectedProductId, file));
    }

    @PostMapping("/document-intakes/{id}/review")
    public ApiResponse<OperationalLiteDtos.IntakeBatch> reviewIntake(
            @PathVariable Long id, @Valid @RequestBody OperationalLiteDtos.IntakeReview request) {
        return ApiResponse.ok(service.reviewIntake(id, request));
    }

    @GetMapping("/document-intakes/{id}/content")
    public ResponseEntity<byte[]> intakeContent(@PathVariable Long id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(service.intakeContent(id));
    }
}
