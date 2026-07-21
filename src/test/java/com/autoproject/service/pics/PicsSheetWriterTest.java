package com.autoproject.service.pics;

import com.autoproject.model.FrameData;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PicsSheetWriterTest {

    @Test
    void shouldCollectImagesRecursivelyByMediaOwnerCountryVenueTypeFolder() throws Exception {
        Path root = Files.createTempDirectory("pics-root");
        // Disk folders are usually keyed by ISO3 country codes (e.g. DEU instead of Germany).
        Path targetFolder = root.resolve("JCDecaux\u2014DEU\u2014Airport");
        Path nested = targetFolder.resolve("nested");
        Files.createDirectories(nested);
        Files.writeString(targetFolder.resolve("a.jpg"), "x");
        Files.writeString(nested.resolve("b.jpeg"), "x");
        Files.writeString(nested.resolve("c.png"), "x");
        Files.writeString(nested.resolve("ignore.pdf"), "x");

        FrameData frame = new FrameData();
        frame.setMarket("JCDecaux");
        frame.setAddressIso3CountryCode("DEU");
        // Keep addressCountry null to ensure only ISO3-based folder is considered in this test.
        frame.setAddressCountry(null);
        frame.setVenueTaxonomyValue("Airport");

        PicsSheetWriter writer = new PicsSheetWriter();
        Map<PicsSheetWriter.GroupKey, List<Path>> result = writer.collectImagesByGroup(List.of(frame), root);

        assertEquals(1, result.size(), "should find one matching group");
        List<Path> images = result.values().iterator().next();
        assertEquals(3, images.size(), "should include only jpg/jpeg/png files");
    }

    @Test
    void shouldMatchFolderCaseInsensitiveAndVenueTaxonomyVsShortFolderName() throws Exception {
        Path root = Files.createTempDirectory("pics-root");
        Path folder = root.resolve("jcdecaux_gb\u2014gbr\u2014Train_Stations");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("x.jpg"), "x");

        FrameData frame = new FrameData();
        frame.setSsp("VIOOH");
        frame.setMarket("JCDECAUX_GB");
        frame.setAddressIso3CountryCode("GBR");
        frame.setVenueTaxonomyValue("transit.train_stations.platform");

        PicsSheetWriter writer = new PicsSheetWriter();
        Map<PicsSheetWriter.GroupKey, List<Path>> result = writer.collectImagesByGroup(List.of(frame), root);

        assertEquals(1, result.size(), "folder Train_Stations should match taxonomy segment train_stations");
        assertEquals(1, result.values().iterator().next().size());
    }

    @Test
    void countPicsGroupsShouldMatchDistinctVenueGroups() {
        FrameData a = new FrameData();
        a.setMarket("JCDECAUX_GB");
        a.setAddressIso3CountryCode("GBR");
        a.setVenueTaxonomyValue("Airport");
        FrameData b = new FrameData();
        b.setMarket("JCDECAUX_GB");
        b.setAddressIso3CountryCode("GBR");
        b.setVenueTaxonomyValue("Airport");
        FrameData c = new FrameData();
        c.setMarket("JCDECAUX_GB");
        c.setAddressIso3CountryCode("GBR");
        c.setVenueTaxonomyValue("Rail");
        PicsSheetWriter writer = new PicsSheetWriter();
        assertEquals(2, writer.countPicsGroups(List.of(a, b, c)));
        assertEquals(1, writer.countPicsGroups(List.of(a, b)));
    }

    @Test
    void parseImageLinksShouldSplitOnSemicolonPipeOrNewline() {
        List<String> semi = FrameImageLinkFetcher.parseImageLinks("http://x/a.jpg; http://y/b.png");
        assertEquals(2, semi.size());
        List<String> pipe = FrameImageLinkFetcher.parseImageLinks("http://x/a.jpg|http://y/b.png");
        assertEquals(2, pipe.size());
        List<String> single = FrameImageLinkFetcher.parseImageLinks("http://x/a.jpg");
        assertEquals(1, single.size());
    }

    @Test
    void maxFramesForLinkFetchShouldBe40ForBillboardVenueClass() {
        assertEquals(40, PicsSheetWriter.maxFramesForLinkFetchByVenue("billboard.roadside"));
        assertEquals(40, PicsSheetWriter.maxFramesForLinkFetchByVenue("Billboard"));
        assertEquals(40, PicsSheetWriter.maxFramesForLinkFetchByVenue("billboard_large_format"));
        assertEquals(20, PicsSheetWriter.maxFramesForLinkFetchByVenue("transit.train_stations.platform"));
        assertEquals(20, PicsSheetWriter.maxFramesForLinkFetchByVenue("outdoor.digital"));
    }

    @Test
    void venueMatchCandidatesShouldIncludeDotSegmentsAndUnderscoreForms() {
        List<String> c = PicsSheetWriter.venueMatchCandidates("transit.train_stations.platform");
        assertTrue(c.contains("train_stations"), "segment match for Train_Stations folder");
        assertTrue(c.contains("transit_train_stations_platform"), "full underscore form");
        assertTrue(c.contains("platform"), "last segment");
    }

    @Test
    void shouldPickTwoOrThreeImagesWhenEnoughCandidates() {
        PicsSheetWriter writer = new PicsSheetWriter();
        List<Path> candidates = List.of(
                Path.of("1.jpg"),
                Path.of("2.jpg"),
                Path.of("3.jpg"),
                Path.of("4.jpg"),
                Path.of("5.jpg")
        );
        for (int i = 0; i < 30; i++) {
            List<Path> picked = writer.pickRandomImages(candidates);
            assertTrue(picked.size() >= 2 && picked.size() <= 3, "picked count should be 2 or 3");
        }
    }
}
