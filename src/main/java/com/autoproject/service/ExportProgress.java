package com.autoproject.service;

import com.autoproject.service.pics.PicsLinkProgress;

/**
 * Optional callbacks for merge + Excel export (including PICS link fetch). Used by the desktop UI with a single
 * {@link javax.swing.ProgressMonitor}; headless/CLI passes {@code null}.
 */
public interface ExportProgress extends PicsLinkProgress {

    /** Invoked immediately before reading each input file (index is zero-based). */
    default void onMergeReadingFile(int indexZeroBased, int totalFiles, String path) {
    }

    /** Invoked after all input files are read and country fields are filled. */
    default void onMergeComplete(int mergedRowCount) {
    }

    /** Invoked before writing Unfiltered/Filtered frame rows (typically the heaviest sheet work). */
    default void onWritingFrameSheets() {
    }

    /** Invoked immediately before writing the workbook to disk. */
    default void onSavingWorkbook() {
    }

    /** Invoked after the file has been written successfully. */
    default void onExportComplete() {
    }

    static ExportProgress noop() {
        return new ExportProgress() {
        };
    }
}
