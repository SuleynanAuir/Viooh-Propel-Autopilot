package com.autoproject.service;

import com.autoproject.model.FrameData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataMergerDetailsIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSplitDetailsFilesFromFrameListsDuringMerge() throws Exception {
        Path frameList = tempDir.resolve("Paris_1km.csv");
        Files.writeString(frameList, String.join("\n",
                "ASSETUUID,VIOOHSELECTOPTIN,IMPRESSIONS",
                "FRAME-A,No,100",
                "FRAME-B,No,200"
        ));
        Path details = tempDir.resolve("frames-details.csv");
        Files.writeString(details, String.join("\n",
                "Frame ID,VIOOHSELECTOPTIN",
                "FRAME-A,No",
                "FRAME-B,Yes"
        ));

        List<FrameData> merged = new DataMerger().merge(
                frameList.toString(),
                details.toString()
        );

        assertEquals(2, merged.size());
        assertEquals("No", merged.get(0).getVioohSelectOptin());
        assertEquals("Yes", merged.get(1).getVioohSelectOptin());
    }

    @Test
    void shouldKeepCsvOptinWhenNoDetailsFileProvided() throws Exception {
        Path frameList = tempDir.resolve("Paris_1km.csv");
        Files.writeString(frameList, String.join("\n",
                "ASSETUUID,VIOOHSELECTOPTIN,IMPRESSIONS",
                "FRAME-A,No,100",
                "FRAME-B,Yes,200"
        ));

        List<FrameData> merged = new DataMerger().merge(frameList.toString());

        assertEquals(2, merged.size());
        assertEquals("No", merged.get(0).getVioohSelectOptin());
        assertEquals("Yes", merged.get(1).getVioohSelectOptin());
    }

    @Test
    void shouldRequireAtLeastOneFrameListFile() {
        Path details = tempDir.resolve("frames-details.csv");
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataMerger().merge(details.toString())
        );
    }
}
