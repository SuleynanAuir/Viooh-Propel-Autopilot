package com.autoproject.service;

import java.util.Map;

/**
 * Opt-in and VIOOH Select local CPM values from frame-details exports, keyed by frame id (uppercase).
 */
public record FrameDetailsLookup(
        Map<String, String> optinByFrameId,
        Map<String, Double> cpmLocalByFrameId
) {
}
