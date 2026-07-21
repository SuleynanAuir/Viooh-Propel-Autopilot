package com.autoproject.service;

import com.autoproject.model.FrameData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VioohSelectCpmLocalResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReplaceEffectiveCpmWhenVsYesAndDetailRowExists() {
        FrameData frame = frame("FRAME-A", "Yes", 6d);
        VioohSelectCpmLocalResolver.apply(
                List.of(frame),
                Map.of("FRAME-A", 7d)
        );
        assertEquals(6d, frame.getFloorCpm());
        assertEquals(7d, frame.getEffectiveFloorCpm());
    }

    @Test
    void shouldKeepFrameListCpmWhenVsYesButDetailRowMissing() {
        FrameData frame = frame("FRAME-A", "Yes", 6d);
        VioohSelectCpmLocalResolver.apply(List.of(frame), Map.of("FRAME-B", 7d));
        assertEquals(6d, frame.getEffectiveFloorCpm());
    }

    @Test
    void shouldNotReplaceWhenVsNo() {
        FrameData frame = frame("FRAME-A", "No", 6d);
        VioohSelectCpmLocalResolver.apply(List.of(frame), Map.of("FRAME-A", 7d));
        assertEquals(6d, frame.getEffectiveFloorCpm());
    }

    @Test
    void shouldReadCpmLocalFromDetailsCsv() throws Exception {
        Path details = tempDir.resolve("frames-details.csv");
        Files.writeString(details, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN,VIOOHSELECTCPMLOCAL",
                "FRAME-A,Yes,7",
                "FRAME-B,No,9"
        ));

        FrameDetailsLookup lookup = new FrameDetailsReader().readDualLookup(details.toString());
        assertEquals(7d, lookup.cpmLocalByFrameId().get("FRAME-A"));
        assertEquals(9d, lookup.cpmLocalByFrameId().get("FRAME-B"));
    }

    @Test
    void shouldApplyThroughDataMergerWhenDetailsProvided() throws Exception {
        Path frameList = tempDir.resolve("Paris_1km.csv");
        Files.writeString(frameList, String.join("\n",
                "ASSETUUID,VIOOHSELECTOPTIN,FLOOR_CPM,IMPRESSIONS",
                "FRAME-A,Yes,6,100"
        ));
        Path details = tempDir.resolve("frames-details.csv");
        Files.writeString(details, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN,VIOOHSELECTCPMLOCAL",
                "FRAME-A,Yes,7"
        ));

        List<FrameData> merged = new DataMerger().merge(frameList.toString(), details.toString());

        assertEquals(1, merged.size());
        assertEquals(6d, merged.get(0).getFloorCpm());
        assertEquals(7d, merged.get(0).getEffectiveFloorCpm());
    }

    private static FrameData frame(String assetUuid, String optin, Double floorCpm) {
        FrameData data = new FrameData();
        data.setAssetUuid(assetUuid);
        data.setVioohSelectOptin(optin);
        data.setFloorCpm(floorCpm);
        return data;
    }
}
