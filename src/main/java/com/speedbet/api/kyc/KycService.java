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
 * RoyalBet KYC business logic — a self-contained sub-service.
 *
 * Isolation rules (this backend is shared by several frontends, each with
 * its own rules): this service owns ONLY the kyc_submissions table. It
 * never writes to users, wallets, transactions or any other module's
 * data, and it never calls the wallet/deposit services. The only shared
 * input is the authenticated principal produced by the existing auth
 * service — the submission's status in this module is the single source
 * of truth for "is this user KYC-verified".
 *
 * One live submission per user: re-submitting while PENDING refreshes the
 * pending record in place; re-submitting after a REJECTED creates a fresh
 * row (the rejected history stays on file); submitting while APPROVED is
 * a conflict.
 */
@Service
@RequiredArgsConstructor
public class KycService {

    private final KycSubmissionRepository submissions;

    @Transactional
    public KycSubmission submit(User user, KycDtos.SubmitKycRequest req) {
        var latest = submissions.findFirstByUserIdOrderBySubmittedAtDesc(user.getId());
        if (latest.isPresent() && latest.get().getStatus() == KycStatus.APPROVED) {
            throw ApiException.conflict("Your identity is already verified.");
        }

        String displayName = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
        if (displayName.isEmpty()) displayName = user.getEmail();

        KycSubmission target;
        if (latest.isPresent() && latest.get().getStatus() == KycStatus.PENDING) {
            target = latest.get();
        } else {
            target = KycSubmission.builder().userId(user.getId()).build();
        }
        target.setEmail(user.getEmail());
        target.setUserName(displayName);
        target.setCountry(user.getCountry());
        target.setFullName(req.getFullName().trim());
        target.setIdType(req.getIdType().trim());
        target.setIdNumber(req.getIdNumber().trim());
        target.setImageUrl(req.getImageUrl());
        target.setStatus(KycStatus.PENDING);
        target.setReviewNote(null);
        target.setReviewedBy(null);
        target.setReviewedAt(null);
        target.setSubmittedAt(Instant.now());
        return submissions.save(target);
    }

    /** The user's latest submission, or null if they never submitted. */
    @Transactional(readOnly = true)
    public KycSubmission mine(User user) {
        return submissions.findFirstByUserIdOrderBySubmittedAtDesc(user.getId()).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<KycSubmission> list(String status) {
        if (status != null && !status.isBlank()) {
            KycStatus parsed;
            try {
                parsed = KycStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown KYC status: " + status);
            }
            return submissions.findByStatusOrderBySubmittedAtDesc(parsed);
        }
        return submissions.findAllByOrderBySubmittedAtDesc();
    }

    @Transactional
    public KycSubmission review(User admin, UUID submissionId, KycDtos.ReviewKycRequest req) {
        KycStatus decision;
        try {
            decision = KycStatus.valueOf(req.getDecision().trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("decision must be APPROVED or REJECTED");
        }
        if (decision == KycStatus.PENDING) {
            throw ApiException.badRequest("decision must be APPROVED or REJECTED");
        }
        String note = req.getNote() == null ? "" : req.getNote().trim();
        if (decision == KycStatus.REJECTED && note.isEmpty()) {
            throw ApiException.badRequest("A rejection reason is required.");
        }

        KycSubmission sub = submissions.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("KYC submission not found: " + submissionId));
        sub.setStatus(decision);
        sub.setReviewNote(decision == KycStatus.REJECTED ? note : null);
        sub.setReviewedBy(admin.getId());
        sub.setReviewedAt(Instant.now());
        return submissions.save(sub);
    }
}
