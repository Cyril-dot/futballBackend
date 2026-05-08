package com.speedbet.api.wallet;

public enum WithdrawalStatus {
    PENDING,      // Submitted by user, awaiting admin review
    APPROVED,     // Admin approved, awaiting super admin settlement
    REJECTED,     // Admin rejected, funds released back to user
    SETTLED,      // Super admin has processed the payout, wallet permanently debited
    FAILED        // Super admin marked as failed, funds released
}
