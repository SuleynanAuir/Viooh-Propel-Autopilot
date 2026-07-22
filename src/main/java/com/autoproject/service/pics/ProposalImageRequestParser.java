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
        if (proposalRows == null || proposalRows.isEmpty()) {
            return List.of();
        }
        Map<String, ProposalImageRequest> unique = new LinkedHashMap<>();
        for (int i = 0; i < proposalRows.size(); i++) {
            ProposalSummaryRow row = proposalRows.get(i);
            if (row == null || VenueTypeParser.isBlank(row.getAddressIso3CountryCode())
                    || VenueTypeParser.isBlank(row.getMarket())) {
                continue;
            }
            for (String venue : VenueTypeParser.splitDisplayValues(row.getVenueTaxonomyValue())) {
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
        return new ArrayList<>(unique.values());
    }
}
