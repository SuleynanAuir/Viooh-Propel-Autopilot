package com.autoproject.service.pics;

/**
 * Optional progress / cancellation for PICS sheet generation when fetching images from links.
 */
public interface PicsLinkProgress {

    /** Called once before processing groups; {@code totalGroups} matches {@link #onGroupDone} second argument. */
    default void onStart(int totalGroups) {
    }

    /**
     * Called after each PICS group row is written.
     *
     * @param completedOneBased 1 .. totalGroups
     * @param totalGroups       total number of groups
     * @param note              short status (e.g. venue type)
     */
    default void onGroupDone(int completedOneBased, int totalGroups, String note) {
    }

    /**
     * When true, link fetchers should stop the <em>current</em> batch of HTTP requests only (do not permanently disable
     * link mode for subsequent venue groups).
     * Typical implementations mirror {@link javax.swing.ProgressMonitor#isCanceled()} (closing the dialog counts as cancel).
     */
    default boolean isCancelled() {
        return false;
    }

    static PicsLinkProgress noop() {
        return new PicsLinkProgress() {
        };
    }
}
