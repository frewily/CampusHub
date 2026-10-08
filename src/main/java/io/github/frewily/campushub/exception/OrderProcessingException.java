package io.github.frewily.campushub.exception;

/** Safe classification only: never infer whether a timed-out DB transaction committed. */
public class OrderProcessingException extends IllegalStateException {
    public enum Reason { LOCK_BUSY, DATABASE_STOCK_UNAVAILABLE, ORDER_INSERT_FAILED, ORDER_ID_CONFLICT }
    private final Reason reason;

    public OrderProcessingException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
