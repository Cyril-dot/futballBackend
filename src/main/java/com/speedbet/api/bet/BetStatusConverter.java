package com.speedbet.api.bet;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class BetStatusConverter implements AttributeConverter<BetStatus, String> {

    @Override
    public String convertToDatabaseColumn(BetStatus status) {
        return status == null ? null : status.name();
    }

    @Override
    public BetStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : BetStatus.valueOf(dbData);
    }
}