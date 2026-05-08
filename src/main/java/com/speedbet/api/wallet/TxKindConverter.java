package com.speedbet.api.wallet;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class TxKindConverter implements AttributeConverter<TxKind, String> {

    @Override
    public String convertToDatabaseColumn(TxKind kind) {
        return kind == null ? null : kind.name();
    }

    @Override
    public TxKind convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TxKind.valueOf(dbData);
    }
}