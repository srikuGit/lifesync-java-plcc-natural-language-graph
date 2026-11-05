package com.lifesync.poc.offers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.domain.Models.Offer;
import com.lifesync.poc.domain.Models.OfferResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** POC-only stand-in for the external Offers Flow REST API. File is reloaded on every call. */
@RestController
@RequestMapping("/mock-offers/api")
public class MockOffersController {
    private final ObjectMapper mapper;
    private final String inputDir;
    public MockOffersController(ObjectMapper mapper,@Value("${lifesync.input-dir}") String inputDir) {
        this.mapper=mapper;this.inputDir=inputDir;
    }
    @GetMapping("/offers")
    public OfferResponse offers(@RequestParam String customerId,@RequestParam String merchantIds) throws Exception {
        // Explicit fixture only; never query real banking data here.
        OfferResponse mock=mapper.readValue(Path.of(inputDir,"offers-mock.json").toFile(),OfferResponse.class);
        List<String> ids=Arrays.stream(merchantIds.split(",")).filter(x->x.matches("M[0-9]{3,7}"))
                .limit(5).toList();
        return new OfferResponse(OffersClient.eligible(mock.offers(),customerId,ids,LocalDate.now()));
    }
}
