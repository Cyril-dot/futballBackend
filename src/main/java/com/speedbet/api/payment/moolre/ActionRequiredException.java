package com.speedbet.api.payment.moolre;

/**
 * Thrown by moolreDirectCharge() when Moolre returns status=1 but includes
 * an action-required message (e.g. "Please complete the verification process
 * sent to you via SMS and try again.").
 *
 * This is a soft, user-actionable state — NOT a system failure.
 * Callers catch this separately from RuntimeException so they can return
 * HTTP 200 with { actionRequired: true } instead of a 400 Bad Request,
 * allowing the frontend to guide the user through the SMS/verification step.
 *
 * Place this file in: com/speedbet/api/payment/moolre/ActionRequiredException.java
 */
public class ActionRequiredException extends RuntimeException {
    public ActionRequiredException(String message) {
        super(message);
    }
}