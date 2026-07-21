package com.autoproject.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameDetailsFileDetectorTest {

    @Test
    void shouldDetectDetailsFilesByNameAndExtension() {
        assertTrue(FrameDetailsFileDetector.isDetailsFile("frames-details_2026-06-09.xlsx"));
        assertTrue(FrameDetailsFileDetector.isDetailsFile("frames-details_2026-06-09.xls"));
        assertTrue(FrameDetailsFileDetector.isDetailsFile("frame-details.csv"));
        assertTrue(FrameDetailsFileDetector.isDetailsFile("FRAME-DETAILS.tsv"));
        assertTrue(FrameDetailsFileDetector.isDetailsFile("VS CPM-JCD at-Frames.csv"));
        assertTrue(FrameDetailsFileDetector.isDetailsFile("VS_CPM_export.csv"));
        assertTrue(FrameDetailsFileDetector.isDetailsFile("VS-CPM-frames.tsv"));
        assertFalse(FrameDetailsFileDetector.isDetailsFile("Beijing_3km.csv"));
        assertFalse(FrameDetailsFileDetector.isDetailsFile("all frames-JCD at.csv"));
        assertFalse(FrameDetailsFileDetector.isDetailsFile("proposal.xlsx"));
        assertFalse(FrameDetailsFileDetector.isDetailsFile("details.txt"));
    }
}
