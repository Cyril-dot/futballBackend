package com.speedbet.api.casinoGames;

public record OddsQuote(
    java.math.BigDecimal home,
    java.math.BigDecimal draw,
    java.math.BigDecimal away,
    java.math.BigDecimal over,
    java.math.BigDecimal under
) {}