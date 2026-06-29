package com.speedbet.api.superadmin;

import com.speedbet.api.common.ApiException;
import com.speedbet.api.user.User;
import com.speedbet.api.user.UserRepository;
import com.speedbet.api.wallet.ArkeselSmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SuperAdminSmsService {

    private final ArkeselSmsService arkeselSmsService;
    private final UserRepository userRepo;

    /**
     * Send a custom SMS to a user identified by their phone number.
     * The super admin supplies the phone number directly and the message body.
     */
    public void sendSmsToPhone(String phoneNumber, String message, UUID adminId) {
        log.info("sendSmsToPhone: adminId='{}' phone='{}' messageLength={}",
                adminId, phoneNumber, message != null ? message.length() : 0);

        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw ApiException.badRequest("Phone number must not be blank");
        }

        if (message == null || message.isBlank()) {
            throw ApiException.badRequest("Message must not be blank");
        }

        log.debug("sendSmsToPhone: message body → {}", message);

        try {
            arkeselSmsService.sendSms(phoneNumber, message);
            log.info("sendSmsToPhone: SMS dispatched — phone='{}' adminId='{}'", phoneNumber, adminId);
        } catch (Exception e) {
            log.error("sendSmsToPhone: FAILED — phone='{}' adminId='{}' error='{}'",
                    phoneNumber, adminId, e.getMessage(), e);
            throw new RuntimeException("Failed to send SMS: " + e.getMessage(), e);
        }
    }

    /**
     * Send a custom SMS to a user identified by their userId.
     * Looks up the user's phone number from the database.
     */
    public void sendSmsToUser(UUID userId, String message, UUID adminId) {
        log.info("sendSmsToUser: adminId='{}' userId='{}' messageLength={}",
                adminId, userId, message != null ? message.length() : 0);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        String phoneNumber = user.getPhone();

        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.warn("sendSmsToUser: SKIPPED — user '{}' has no phone number", userId);
            throw ApiException.badRequest("User does not have a phone number on file");
        }

        sendSmsToPhone(phoneNumber, message, adminId);
    }
}