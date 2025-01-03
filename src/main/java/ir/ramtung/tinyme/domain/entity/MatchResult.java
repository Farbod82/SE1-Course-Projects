package ir.ramtung.tinyme.domain.entity;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public final class MatchResult {
    private final MatchingOutcome outcome;
    private final Order remainder;
    private final LinkedList<Trade> trades;

    private final int openingPrice;

    public static MatchResult executed(Order remainder, List<Trade> trades) {
        return new MatchResult(MatchingOutcome.EXECUTED, remainder, new LinkedList<>(trades));
    }
    public static MatchResult stopLimitAccepted(){
        return new MatchResult(MatchingOutcome.STOP_LIMIT_ORDER_ACCEPTED, null, new LinkedList<>());
    }
    public static MatchResult notEnoughCredit() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_CREDIT, null, new LinkedList<>());
    }
    public static MatchResult notEnoughPositions() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_POSITIONS, null, new LinkedList<>());
    }
    public static MatchResult minimumExecutionQuantityNotPassed() {
        return new MatchResult(MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_NOT_PASSED, null, new LinkedList<>());
    }
    public static MatchResult stopLimitOrderNotAccepted() {
        return new MatchResult(MatchingOutcome.STOP_LIMIT_ORDER_NOT_ACCEPTED, null, new LinkedList<>());
    }
    public static MatchResult queuedForAuction(int openingPrice){
        return new MatchResult(MatchingOutcome.QUEUED_FOR_AUCTION, null, new LinkedList<>(),openingPrice);
    }

    private MatchResult(MatchingOutcome outcome, Order remainder, LinkedList<Trade> trades, int openingPrice) {
        this.outcome = outcome;
        this.remainder = remainder;
        this.trades = trades;
        this.openingPrice = openingPrice;
    }

    public MatchResult(MatchingOutcome outcome, Order remainder, LinkedList<Trade> trades) {
        this.outcome = outcome;
        this.remainder = remainder;
        this.trades = trades;
        this.openingPrice = 0;
    }

    public MatchingOutcome outcome() {
        return outcome;
    }
    public Order remainder() {
        return remainder;
    }

    public LinkedList<Trade> trades() {
        return trades;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MatchResult) obj;
        return Objects.equals(this.remainder, that.remainder) &&
                Objects.equals(this.trades, that.trades);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remainder, trades);
    }

    @Override
    public String toString() {
        return "MatchResult[" +
                "remainder=" + remainder + ", " +
                "trades=" + trades + ']';
    }


}
