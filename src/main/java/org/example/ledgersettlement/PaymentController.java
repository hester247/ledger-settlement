package org.example.ledgersettlement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final SettlementService settlementService;

    public PaymentController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    public record RecordPaymentRequest(
            @NotBlank String merchantId,
            @Positive long amountMinor,
            @NotBlank String currency
    ) {
    }

    public record PaymentResponse(
            String id,
            String merchantId,
            long amountMinor,
            String currency,
            LocalDateTime recordedAt
    ) {
    }

    public record SettlementResponse(long amountOwed) {
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> recordPayment(
            @Valid @RequestBody RecordPaymentRequest request) {

        PaymentEntity payment = new PaymentEntity();

        payment.setId(UUID.randomUUID().toString());
        payment.setMerchantId(request.merchantId());
        payment.setAmountMinor(request.amountMinor());
        payment.setCurrency(request.currency());
        payment.setRecordedAt(LocalDateTime.now());

        PaymentEntity savedPayment =
                settlementService.recordPayment(payment);

        PaymentResponse response = new PaymentResponse(
                savedPayment.getId(),
                savedPayment.getMerchantId(),
                savedPayment.getAmountMinor(),
                savedPayment.getCurrency(),
                savedPayment.getRecordedAt()
        );

        URI location = URI.create("/payments/" + savedPayment.getId());

        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/settlement")
    public ResponseEntity<SettlementResponse> getSettlement(
            @RequestParam String merchantId) {

        long amountOwed =
                settlementService.calculateSettlement(merchantId);

        return ResponseEntity.ok(
                new SettlementResponse(amountOwed)
        );
    }


}