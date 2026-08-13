public enum Status {
    ACCEPTED, ARRIVED, PICKED_UP, FULFILLED, CANCELED;

    public boolean isTerminal() { return this == FULFILLED || this == CANCELED; }

    /** Tie-break for same-timestamp events: open before close, so one order's
     *  ACCEPTED and FULFILLED at the same instant can't arrive reversed. */
    int rank() { return isTerminal() ? 3 : ordinal(); }
}