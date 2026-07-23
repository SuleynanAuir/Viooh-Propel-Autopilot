package com.autoproject.service.pics;

import com.autoproject.service.summary.ProposalSummaryRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Expands Proposal rows into independently traceable venue combinations. */
final class ProposalImageRequestParser {
    private ProposalImageRequestParser() {
    }

    static List<ProposalImageRequest> parse(List<ProposalSummaryRow> proposalRows) {
        return parse(proposalRows, VenueTypeDictionary.loadConfigured()).requests();
    }

    static ParseResult parse(List<ProposalSummaryRow> proposalRows, VenueTypeDictionary dictionary) {
        if (proposalRows == null || proposalRows.isEmpty()) {
            return new ParseResult(List.of(), List.of());
        }
        VenueTypeDictionary activeDictionary = dictionary == null
                ? VenueTypeDictionary.loadConfigured()
                : dictionary;
        Map<String, ProposalImageRequest> unique = new LinkedHashMap<>();
        Map<String, VenueTypeDictionary.MissingVenueType> missing = new LinkedHashMap<>();
        for (int i = 0; i < proposalRows.size(); i++) {
            ProposalSummaryRow row = proposalRows.get(i);
            if (row == null || VenueTypeParser.isBlank(row.getAddressIso3CountryCode())
                    || VenueTypeParser.isBlank(row.getMarket())) {
                continue;
            }
            VenueTypeDictionary.MatchResult match = activeDictionary.match(row.getVenueTaxonomyValue());
            for (VenueTypeDictionary.MissingVenueType item : match.missingVenueTypes()) {
                missing.putIfAbsent(item.original() + "\0" + item.normalized(), item);
            }
            for (String venue : match.standardVenueTypes()) {
                ProposalImageRequest request = new ProposalImageRequest(
                        row.getAddressIso3CountryCode().trim(),
                        row.getMarket().trim(),
                        venue,
                        i + 2); // Proposal header is Excel row 1.
                String key = VenueTypeParser.normalize(request.country()) + "\0"
                        + VenueTypeParser.normalize(request.market()) + "\0"
                        + VenueTypeParser.normalize(request.venueType());
                unique.putIfAbsent(key, request);
            }
        }
        return new ParseResult(new ArrayList<>(unique.values()), new ArrayList<>(missing.values()));
    }

    record ParseResult(
            List<ProposalImageRequest> requests,
            List<VenueTypeDictionary.MissingVenueType> missingVenueTypes) {
    }
}
