package com.autoproject.service;

import com.autoproject.model.FrameData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VioohSelectOptinResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldKeepCsvValuesWhenNoDetailsFileProvided() throws Exception {
        FrameData frame = frame("FRAME-A", "No");
        VioohSelectOptinResolver.apply(List.of(frame), List.of());
        assertEquals("No", frame.getVioohSelectOptin());
    }

    @Test
    void shouldOverrideOptinFromDetailsCsvAndDefaultMissingFramesToYes() throws Exception {
        Path details = tempDir.resolve("frames-details.csv");
        Files.writeString(details, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN",
                "FRAME-A,No",
                "FRAME-B,Yes"
        ));

        FrameData matchedNo = frame("FRAME-A", "Yes");
        FrameData matchedYes = frame("FRAME-B", "No");
        FrameData missing = frame("FRAME-C", "No");

        VioohSelectOptinResolver.apply(List.of(matchedNo, matchedYes, missing), List.of(details.toString()));

        assertEquals("No", matchedNo.getVioohSelectOptin());
        assertEquals("Yes", matchedYes.getVioohSelectOptin());
        assertEquals("Yes", missing.getVioohSelectOptin());
    }

    @Test
    void shouldUseRouteFrameCodeColumnWhenFrameIdMissing() throws Exception {
        Path details = tempDir.resolve("frames-details.csv");
        Files.writeString(details, String.join("\n",
                "Route frame code,VIOOH_SELECTED",
                "ROUTE-1,No"
        ));

        FrameData frame = frame("ROUTE-1", "Yes");
        VioohSelectOptinResolver.apply(List.of(frame), List.of(details.toString()));
        assertEquals("No", frame.getVioohSelectOptin());
    }

    @Test
    void shouldPreferNewerDetailsFileOnConflict() throws Exception {
        Path older = tempDir.resolve("frames-details_2026-06-01.csv");
        Files.writeString(older, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN",
                "FRAME-A,No"
        ));
        Path newer = tempDir.resolve("frames-details_2026-06-09.csv");
        Files.writeString(newer, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN",
                "FRAME-A,Yes"
        ));

        FrameData frame = frame("FRAME-A", "No");
        VioohSelectOptinResolver.apply(List.of(frame), List.of(older.toString(), newer.toString()));
        assertEquals("Yes", frame.getVioohSelectOptin());
    }

    @Test
    void shouldPreferYesWhenSameDateDetailsFilesConflict() throws Exception {
        Path first = tempDir.resolve("frames-details_2026-06-09_a.csv");
        Files.writeString(first, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN",
                "FRAME-A,No"
        ));
        Path second = tempDir.resolve("frames-details_2026-06-09_b.csv");
        Files.writeString(second, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN",
                "FRAME-A,No"
        ));

        FrameData frame = frame("FRAME-A", "No");
        VioohSelectOptinResolver.apply(List.of(frame), List.of(first.toString(), second.toString()));
        assertEquals("No", frame.getVioohSelectOptin());

        Path third = tempDir.resolve("frames-details_2026-06-09_c.csv");
        Files.writeString(third, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN",
                "FRAME-A,Yes"
        ));
        FrameData frameWithYes = frame("FRAME-A", "No");
        VioohSelectOptinResolver.apply(
                List.of(frameWithYes),
                List.of(first.toString(), third.toString())
        );
        assertEquals("Yes", frameWithYes.getVioohSelectOptin());
    }

    private static FrameData frame(String assetUuid, String csvOptin) {
        FrameData data = new FrameData();
        data.setAssetUuid(assetUuid);
        data.setVioohSelectOptin(csvOptin);
        return data;
    }
}
