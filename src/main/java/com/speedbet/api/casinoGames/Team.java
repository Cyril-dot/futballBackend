package com.speedbet.api.casinoGames;

/** Team value object. */
public record Team(String name, String shortCode, double strength) {

    static final Team[] ROSTER = {

        // ==========================
        // ENGLISH PREMIER LEAGUE
        // ==========================
        new Team("Manchester City", "MCI", 0.96),
        new Team("Arsenal", "ARS", 0.94),
        new Team("Liverpool", "LIV", 0.94),
        new Team("Chelsea", "CHE", 0.88),
        new Team("Manchester United", "MUN", 0.88),
        new Team("Tottenham Hotspur", "TOT", 0.87),
        new Team("Newcastle United", "NEW", 0.87),
        new Team("Aston Villa", "AVL", 0.86),
        new Team("Brighton", "BHA", 0.82),
        new Team("West Ham United", "WHU", 0.81),
        new Team("Crystal Palace", "CRY", 0.79),
        new Team("Everton", "EVE", 0.78),

        // ==========================
        // LA LIGA
        // ==========================
        new Team("Real Madrid", "RMA", 0.97),
        new Team("Barcelona", "BAR", 0.95),
        new Team("Atletico Madrid", "ATM", 0.92),
        new Team("Athletic Club", "ATH", 0.88),
        new Team("Real Sociedad", "RSO", 0.87),
        new Team("Villarreal", "VIL", 0.85),
        new Team("Real Betis", "BET", 0.84),
        new Team("Sevilla", "SEV", 0.83),

        // ==========================
        // SERIE A
        // ==========================
        new Team("Inter Milan", "INT", 0.93),
        new Team("AC Milan", "MIL", 0.90),
        new Team("Juventus", "JUV", 0.90),
        new Team("Napoli", "NAP", 0.89),
        new Team("AS Roma", "ROM", 0.87),
        new Team("Lazio", "LAZ", 0.86),
        new Team("Atalanta", "ATA", 0.87),
        new Team("Fiorentina", "FIO", 0.83),

        // ==========================
        // BUNDESLIGA
        // ==========================
        new Team("Bayern Munich", "BAY", 0.96),
        new Team("Bayer Leverkusen", "LEV", 0.94),
        new Team("Borussia Dortmund", "DOR", 0.90),
        new Team("RB Leipzig", "RBL", 0.89),
        new Team("Eintracht Frankfurt", "SGE", 0.86),
        new Team("VfB Stuttgart", "STU", 0.86),
        new Team("Wolfsburg", "WOB", 0.82),
        new Team("Borussia Monchengladbach", "BMG", 0.82),

        // ==========================
        // LIGUE 1
        // ==========================
        new Team("Paris Saint-Germain", "PSG", 0.94),
        new Team("Marseille", "OM", 0.87),
        new Team("Monaco", "MON", 0.88),
        new Team("Lille", "LIL", 0.86),
        new Team("Lyon", "LYO", 0.85),
        new Team("Nice", "NIC", 0.84),
        new Team("Lens", "RCL", 0.84),
        new Team("Rennes", "REN", 0.83),

        // ==========================
        // PRIMEIRA LIGA
        // ==========================
        new Team("Benfica", "BEN", 0.90),
        new Team("Sporting CP", "SCP", 0.90),
        new Team("FC Porto", "POR", 0.89),
        new Team("Braga", "BRA", 0.84),
        new Team("Vitoria Guimaraes", "VGU", 0.81),

        // ==========================
        // EREDIVISIE
        // ==========================
        new Team("Ajax", "AJA", 0.86),
        new Team("PSV Eindhoven", "PSV", 0.90),
        new Team("Feyenoord", "FEY", 0.88),
        new Team("AZ Alkmaar", "AZ", 0.84),
        new Team("FC Twente", "TWE", 0.83),

        // ==========================
        // TURKISH SUPER LIG
        // ==========================
        new Team("Galatasaray", "GAL", 0.88),
        new Team("Fenerbahce", "FEN", 0.88),
        new Team("Besiktas", "BES", 0.85),
        new Team("Trabzonspor", "TRA", 0.84),

        // ==========================
        // BELGIAN PRO LEAGUE
        // ==========================
        new Team("Club Brugge", "BRU", 0.86),
        new Team("Anderlecht", "AND", 0.84),
        new Team("Union Saint-Gilloise", "USG", 0.85),
        new Team("Genk", "GNK", 0.84),

        // ==========================
        // SCOTTISH PREMIERSHIP
        // ==========================
        new Team("Celtic", "CEL", 0.88),
        new Team("Rangers", "RAN", 0.87),
        new Team("Hearts", "HEA", 0.79),
        new Team("Aberdeen", "ABE", 0.78),

        // ==========================
        // SAUDI PRO LEAGUE
        // ==========================
        new Team("Al Hilal", "HIL", 0.90),
        new Team("Al Nassr", "NAS", 0.89),
        new Team("Al Ittihad", "ITT", 0.88),
        new Team("Al Ahli", "AHL", 0.87)
    };
}