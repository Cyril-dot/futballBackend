package com.speedbet.api.payment.akwapay;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PhoneAttemptTrackerRepository extends JpaRepository<PhoneAttemptTracker, String> {
}