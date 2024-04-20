package ir.ramtung.tinyme.domain.entity;

public enum MatchingOutcome {
    EXECUTED,
    NOT_ENOUGH_CREDIT,
    NOT_ENOUGH_POSITIONS,

    MINIMUM_EXECUTION_QUANTITY_NOT_PASSED,
    STOP_LIMIT_PRICE_NOT_PASSED,
    STOP_LIMIT_ORDER_ACCEPTED
}
