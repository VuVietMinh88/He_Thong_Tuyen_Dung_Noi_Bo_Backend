package vn.ttcs.recruitment.candidatemerge;

/** Internal persistence failures; HTTP mapping and authorization belong to the future merge API. */
public final class MergeAuditException extends RuntimeException {
    public enum Reason { INVALID_REQUEST, EMPTY_OR_MISSING_GROUP, STALE_SNAPSHOT, REQUEST_KEY_REUSED,
        READ_ONLY_TRANSACTION, UNSUPPORTED_ISOLATION }
    private final Reason reason;

    public MergeAuditException(Reason reason) {
        super("Candidate merge storage rejected the operation: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
