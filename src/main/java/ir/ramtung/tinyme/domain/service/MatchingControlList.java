package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.MatchingOutcome;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Trade;
import ir.ramtung.tinyme.domain.service.MatchingControl;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedList;
import java.util.List;

@Component
public class MatchingControlList {
    @Autowired
    private List<MatchingControl> controlList;

    public MatchingOutcome canStartMatching(Order order) {
        for (MatchingControl control : controlList) {
            MatchingOutcome outcome = control.canStartMatching(order);
            if (outcome != MatchingOutcome.NO_PROBLEM)
                return outcome;
        }
        return MatchingOutcome.NO_PROBLEM;
    }
    public void matchingStarted(Order order) {
        for (MatchingControl control : controlList) {
            control.matchingStarted(order);
        }
    }
    public MatchingOutcome canAcceptMatching(Order order, MatchResult result) {
        for (MatchingControl control : controlList) {
            MatchingOutcome outcome = control.canAcceptMatching(order, result);
            if (outcome != MatchingOutcome.NO_PROBLEM) {
                return outcome;
            }
        }
        return MatchingOutcome.NO_PROBLEM;
    }
    public void matchingAccepted(Order order, MatchResult result) {
        for (MatchingControl control : controlList) {
            control.matchingAccepted(order, result);
        }
    }

    public MatchingOutcome canTrade(Order newOrder, Trade trade) { return MatchingOutcome.NO_PROBLEM; }

    public void tradeAccepted(Order newOrder, Trade trade) {
        for (MatchingControl control : controlList) {
            control.tradeAccepted(newOrder, trade);
        }
    }

    public void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        for (MatchingControl control2 : controlList) {
            control2.rollbackTrades(newOrder, trades);
        }

    }

}
