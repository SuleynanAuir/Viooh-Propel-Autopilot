package com.autoproject.service;

import com.autoproject.model.FrameData;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VioohSelectOptinResolver {
    private static final String DEFAULT_OPTIN = "Yes";

    private VioohSelectOptinResolver() {
    }

    public static void apply(List<FrameData> frames, List<String> detailsFilePaths) throws Exception {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        if (detailsFilePaths == null || detailsFilePaths.isEmpty()) {
            return;
        }

        FrameDetailsReader reader = new FrameDetailsReader();
        apply(frames, reader.readMergedLookup(detailsFilePaths));
    }

    public static void apply(List<FrameData> frames, Map<String, String> optinLookup) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        if (optinLookup == null || optinLookup.isEmpty()) {
            return;
        }

        System.out.println(
                "Notice: applying VIOOH Select opt-in from frame-details; mapped frames="
                        + optinLookup.size()
        );

        for (FrameData frame : frames) {
            String assetUuid = normalizeAssetUuid(frame.getAssetUuid());
            if (assetUuid == null) {
                frame.setVioohSelectOptin(DEFAULT_OPTIN);
                continue;
            }
            String optin = optinLookup.get(assetUuid.toUpperCase(Locale.ROOT));
            frame.setVioohSelectOptin(optin != null ? optin : DEFAULT_OPTIN);
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
}
