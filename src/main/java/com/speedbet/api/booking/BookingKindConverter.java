package com.speedbet.api.booking;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converts BookingKind ↔ the varchar/enum label stored in Postgres.
 *
 * Using an AttributeConverter instead of @JdbcTypeCode(NAMED_ENUM) avoids
 * Hibernate's EnumHelper.getEnumeratedValues() NPE that occurs when the
 * field type is String, and also avoids any dependency on a Postgres-specific
 * CREATE TYPE.  The column is treated as plain varchar on the JDBC level,
 * so this works whether the DB column is varchar OR a named enum type.
 */
@Converter(autoApply = false)
public class BookingKindConverter implements AttributeConverter<BookingKind, String> {

    @Override
    public String convertToDatabaseColumn(BookingKind kind) {
        if (kind == null) return BookingKind.MIXED.toValue();
        return kind.toValue();          // stores "1X2", "MIXED", etc.
    }

    @Override
    public BookingKind convertToEntityAttribute(String dbValue) {
        return BookingKind.from(dbValue);   // never throws
    }
}