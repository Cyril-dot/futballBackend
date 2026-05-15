package com.speedbet.api.match;

import com.speedbet.api.sportsdata.EspnFootballDataService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

@Component
public class EspnCupConverter implements Converter<String, EspnFootballDataService.EspnCup> {

    @Override
    public EspnFootballDataService.EspnCup convert(String source) {
        return Arrays.stream(EspnFootballDataService.EspnCup.values())
                .filter(c -> c.displayName().equalsIgnoreCase(source)
                        || c.slug().equalsIgnoreCase(source)
                        || c.name().equalsIgnoreCase(source))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown cup: '" + source + "'. Valid values: " +
                                Arrays.stream(EspnFootballDataService.EspnCup.values())
                                        .map(EspnFootballDataService.EspnCup::displayName)
                                        .toList()
                ));
    }
}