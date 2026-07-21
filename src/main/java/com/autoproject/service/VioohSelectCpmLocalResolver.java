package com.autoproject.service;

import com.autoproject.model.FrameData;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * When frame list VS is Yes, replaces effective floor CPM with detail file {@code VIOOHSELECTCPMLOCAL}
 * for the matching frame id. If no detail row is found, effective CPM stays the frame-list value.
 */
public final class VioohSelectCpmLocalResolver {

    private VioohSelectCpmLocalResolver() {
    }

    public static void apply(List<FrameData> frames, Map<String, Double> cpmLocalLookup) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        boolean hasLookup = cpmLocalLookup != null && !cpmLocalLookup.isEmpty();
        if (hasLookup) {
            System.out.println(
                    "Notice: applying VIOOH Select local CPM from frame-details; mapped frames="
                            + cpmLocalLookup.size()
            );
        }
        for (FrameData frame : frames) {
            Double frameListCpm = frame.getFloorCpm();
            frame.setEffectiveFloorCpm(frameListCpm);
            if (!hasLookup || !isYes(frame.getVioohSelectOptin())) {
                continue;
            }
            String assetUuid = normalizeAssetUuid(frame.getAssetUuid());
            if (assetUuid == null) {
                continue;
            }
            Double detailCpm = cpmLocalLookup.get(assetUuid.toUpperCase(Locale.ROOT));
            if (detailCpm != null && detailCpm > 0) {
                frame.setEffectiveFloorCpm(detailCpm);
            }
        }
    }

    private static String normalizeAssetUuid(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("null")
                || trimmed.equals("\\N")
                || trimmed.equals("-")) {
            return null;
        }
        return trimmed;
    }

    private static boolean isYes(String value) {
        if (value == null) {
            return false;
        }
        return "YES".equals(value.trim().toUpperCase(Locale.ROOT));
    }
}
