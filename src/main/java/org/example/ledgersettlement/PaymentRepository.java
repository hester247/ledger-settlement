package org.example.ledgersettlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository
        extends JpaRepository<PaymentEntity, String> {

    List<PaymentEntity> findByMerchantId(String merchantId);
}