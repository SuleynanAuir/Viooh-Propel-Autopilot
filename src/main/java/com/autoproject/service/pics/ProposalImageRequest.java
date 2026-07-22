package com.autoproject.service.pics;

/** One expanded Proposal Country x MARKET x Venue Type sub-category combination. */
record ProposalImageRequest(String country, String market, String venueType, int proposalRow) {
}
