package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

import static java.lang.Math.abs;

@Service
public class Matcher {

    @Autowired
    private MatchingControlList controls;
    public MatchResult match(Order newOrder) {

        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);

            MatchingOutcome matchingOutcome = controls.canTrade(newOrder,trade);

            if(matchingOutcome != MatchingOutcome.NO_PROBLEM){
                rollbackTrades(newOrder, trades);
                return new MatchResult(matchingOutcome, newOrder, null);
            }

//            trade.increaseSellersCredit()
            trades.add(trade);
            controls.tradeAccepted(newOrder,trade);
            applyChangesOfMatching(newOrder ,matchingOrder ,orderBook);

        }
        return MatchResult.executed(newOrder, trades);
    }

    void applyChangesOfMatching(Order newOrder ,Order matchingOrder ,OrderBook orderBook){
        if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
            newOrder.decreaseQuantity(matchingOrder.getQuantity());
            orderBook.removeFirst(matchingOrder.getSide());
            if (matchingOrder instanceof IcebergOrder icebergOrder) {
                icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                icebergOrder.replenish();
                if (icebergOrder.getQuantity() > 0)
                    orderBook.enqueue(icebergOrder);
            }
        } else {
            matchingOrder.decreaseQuantity(newOrder.getQuantity());
            newOrder.makeQuantityZero();
        }
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        if (newOrder.getSide() == Side.BUY) {
            newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));
            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getSell());
            }
        }
        else{
            newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getBuy());
            }
        }
    }

    public boolean matchedLessThanMEQ(Order order, Order originalOrder){
        return abs(order.getQuantity()- originalOrder.getQuantity()) < order.getMinimumExecutionQuantity();
    }
    public MatchResult execute(Order order) {
        Order originalOrder = order.snapshot();
        MatchingOutcome outcome = controls.canStartMatching(order);
        if (outcome != MatchingOutcome.NO_PROBLEM)
            return new MatchResult(outcome, order,null);

        MatchResult result = match(order);
        if (result.outcome() != MatchingOutcome.EXECUTED)
            return result;

        if (matchedLessThanMEQ(order,originalOrder)){
            rollbackTrades(order, result.trades());
            return MatchResult.minimumExecutionQuantityNotPassed();
        }

        outcome = controls.canAcceptMatching(order, result);
        if (outcome != MatchingOutcome.NO_PROBLEM) {
            controls.rollbackTrades(order, result.trades());
            return new MatchResult(outcome, order,null);
        }

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY){
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
            order.resetMinimumExecutionQuantity();
        }
        changeShareholdersPosition(result);
        if (!result.trades().isEmpty())
            order.getSecurity().setLatestPrice(result.trades().getLast());
        return result;
    }

    private void changeShareholdersPosition(MatchResult result){
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
        }
    }
    public MatchResult auctionMatch(OrderBook orderBook ,int openingPrice){
        LinkedList<Trade> trades = new LinkedList<>();
        MatchResult matchResults;
        while (orderBook.hasOrderOfType(Side.BUY) &&  orderBook.hasOrderOfType(Side.SELL)) {
            Order buyOrder = orderBook.getBuyQueue().getFirst();
            Order sellOrder = orderBook.matchWithFirst(buyOrder);
            if (sellOrder == null)
                break;
            if((sellOrder.getPrice() > openingPrice) || (buyOrder.getPrice() < openingPrice))
                break;
            Trade trade = new Trade(buyOrder.getSecurity(), openingPrice, Math.min(buyOrder.getQuantity(), sellOrder.getQuantity()), buyOrder, sellOrder);
            trade.increaseSellersCredit();
            trades.add(trade);
            buyOrder.getSecurity().setLatestPrice(trade);
            if(buyOrder.getPrice() > openingPrice){
                trade.returnMoneyToBuyer();
            }
            applyChangesOfAuctionMatching(buyOrder ,sellOrder ,orderBook);
        }
        MatchResult result = MatchResult.executed(null, trades);
        changeShareholdersPosition(result);
        return  result;
    }

    void applyChangesOfAuctionMatching(Order buyOrder ,Order sellOrder ,OrderBook orderBook){
        if(buyOrder.getQuantity() == sellOrder.getQuantity()){
            orderBook.removeFirst(sellOrder.getSide());
            orderBook.removeFirst(buyOrder.getSide());
            handleAddingIcebergOrderToOrderBook(orderBook , sellOrder);
            handleAddingIcebergOrderToOrderBook(orderBook , buyOrder);
        }
        else if (buyOrder.getQuantity() > sellOrder.getQuantity()) {
            buyOrder.decreaseQuantity(sellOrder.getQuantity());
            orderBook.removeFirst(sellOrder.getSide());
            handleAddingIcebergOrderToOrderBook(orderBook , sellOrder);
        } else {
            sellOrder.decreaseQuantity(buyOrder.getQuantity());
            orderBook.removeFirst(buyOrder.getSide());
            handleAddingIcebergOrderToOrderBook(orderBook , buyOrder);
        }
    }
    private void handleAddingIcebergOrderToOrderBook(OrderBook orderBook , Order order){
        if (order instanceof IcebergOrder icebergOrder) {
            icebergOrder.decreaseQuantity(order.getQuantity());
            icebergOrder.replenish();
            if (icebergOrder.getQuantity() > 0)
                orderBook.pushBack(icebergOrder);
        }
    }

}
