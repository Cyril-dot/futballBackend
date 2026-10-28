package com.speedbet.api.casinoGames;

import com.speedbet.api.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns the Mines layout mode. The read path never throws: a missing row
 * (or an empty positions column) means AUTOMATIC, so a fresh database
 * plays exactly like before this feature existed. The write path validates
 * strictly — a MANUAL setting with no usable tiles can never be saved.
 */
@Service
@RequiredArgsConstructor
public class MinesSettingsService {

    private final MinesSettingRepository settingRepo;

    /** Settings view for the super-admin panel (full detail). */
    public record SettingsView(MinesMode mode, List<Integer> bombPositions) {}

    /**
     * Public view for the game page: the mode plus, in MANUAL, the fixed
     * bomb COUNT only. Positions are never exposed to players.
     */
    public record ModeView(MinesMode mode, Integer bombCount) {}

    @Transactional(readOnly = true)
    public SettingsView get() {
        MinesSetting row = settingRepo.findById(MinesSetting.SINGLETON_ID).orElse(null);
        if (row == null) return new SettingsView(MinesMode.AUTOMATIC, List.of());
        return new SettingsView(row.getMode(), parse(row.getBombPositions()));
    }

    @Transactional(readOnly = true)
    public ModeView modeView() {
        SettingsView s = get();
        return new ModeView(s.mode(), s.mode() == MinesMode.MANUAL ? s.bombPositions().size() : null);
    }

    @Transactional
    public SettingsView save(MinesMode mode, List<Integer> bombPositions, String updatedBy) {
        MinesMode effective = mode == null ? MinesMode.AUTOMATIC : mode;
        List<Integer> positions = effective == MinesMode.MANUAL
                ? validatePositions(bombPositions)
                : List.of();
        MinesSetting row = settingRepo.findById(MinesSetting.SINGLETON_ID)
                .orElseGet(() -> MinesSetting.builder().id(MinesSetting.SINGLETON_ID).build());
        row.setMode(effective);
        row.setBombPositions(format(positions));
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(updatedBy);
        settingRepo.save(row);
        return new SettingsView(effective, positions);
    }

    /** MANUAL needs 1–24 distinct tiles in 0–24, returned sorted. */
    static List<Integer> validatePositions(List<Integer> positions) {
        if (positions == null || positions.isEmpty()) {
            throw ApiException.unprocessable("Manual mode needs at least one bomb tile");
        }
        Set<Integer> seen = new HashSet<>();
        for (Integer p : positions) {
            if (p == null || p < 0 || p >= MinesGameService.GRID_SIZE || !seen.add(p)) {
                throw ApiException.unprocessable("Bomb tiles must be distinct numbers between 0 and " + (MinesGameService.GRID_SIZE - 1));
            }
        }
        if (seen.size() > MinesGameService.MAX_BOMBS) {
            throw ApiException.unprocessable("At most " + MinesGameService.MAX_BOMBS + " bomb tiles");
        }
        return seen.stream().sorted().toList();
    }

    /** Lenient read: skips corrupt or out-of-range entries so a bad stored
     *  value can never break the game page or round start. */
    static List<Integer> parse(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                int v = Integer.parseInt(t);
                if (v >= 0 && v < MinesGameService.GRID_SIZE) out.add(v);
            } catch (NumberFormatException ignored) {
                // Skip corrupt entries; validated on save.
            }
        }
        return out;
    }

    static String format(List<Integer> positions) {
        return positions.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
