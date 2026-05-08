package com.speedbet.api.wallet;

public enum BinanceDepositStatus {
    /** Submitted by user, awaiting admin review */
    PENDING,

    /** Admin approved — wallet has been credited */
    APPROVED,

    /** Admin rejected — wallet was NOT credited */
    REJECTED
}