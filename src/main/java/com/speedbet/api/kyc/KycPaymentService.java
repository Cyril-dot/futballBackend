package com.speedbet.api.kyc;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RoyalBet KYC / gate-fee payment records. Fully isolated from wallet
 * deposits: nothing here reads or writes wallets, transactions or any
 * deposit queue — confirming a payment only flips its own status.
 */
@Service
@RequiredArgsConstructor
public class KycPaymentService {

    private final KycPaymentRepository payments;

    @Transactional
    public KycPayment create(User user, KycPaymentDtos.CreateKycPaymentRequest req) {
        KycPayment p = KycPayment.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .amount(req.getAmount())
                .currency(req.getCurrency() == null || req.getCurrency().isBlank()
                        ? "GHS" : req.getCurrency().trim().toUpperCase())
                .method(req.getMethod().trim().toUpperCase())
                .reference(req.getReference())
                .proofUrl(req.getProofUrl())
                .build();
        return payments.save(p);
    }

    @Transactional(readOnly = true)
    public List<KycPayment> mine(User user) {
        return payments.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional(readOnly = true)
    public List<KycPayment> list(String status) {
        if (status != null && !status.isBlank()) {
            KycPaymentStatus parsed;
            try {
                parsed = KycPaymentStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown KYC payment status: " + status);
            }
            return payments.findByStatusOrderByCreatedAtDesc(parsed);
        }
        return payments.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public KycPayment review(User admin, UUID paymentId, boolean confirm, String note) {
        String cleanNote = note == null ? "" : note.trim();
        if (!confirm && cleanNote.isEmpty()) {
            throw ApiException.badRequest("A rejection reason is required.");
        }
        KycPayment p = payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("KYC payment not found: " + paymentId));
        if (p.getStatus() != KycPaymentStatus.PENDING) {
            throw ApiException.conflict("This payment was already " + p.getStatus().name().toLowerCase() + ".");
        }
        p.setStatus(confirm ? KycPaymentStatus.CONFIRMED : KycPaymentStatus.REJECTED);
        p.setAdminNote(cleanNote.isEmpty() ? null : cleanNote);
        p.setReviewedBy(admin.getId());
        p.setReviewedAt(Instant.now());
        return payments.save(p);
    }
}
