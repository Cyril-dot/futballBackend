package com.speedbet.api.match;

import com.speedbet.api.sportsdata.EspnFootballDataService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

@Component
public class EspnLeagueConverter implements Converter<String, EspnFootballDataService.EspnLeague> {

    @Override
    public EspnFootballDataService.EspnLeague convert(String source) {
        return Arrays.stream(EspnFootballDataService.EspnLeague.values())
                .filter(l -> l.displayName().equalsIgnoreCase(source)
                        || l.slug().equalsIgnoreCase(source)
                        || l.name().equalsIgnoreCase(source))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown league: '" + source + "'. Valid values: " +
                                Arrays.stream(EspnFootballDataService.EspnLeague.values())
                                        .map(EspnFootballDataService.EspnLeague::displayName)
                                        .toList()
                ));
    }
}