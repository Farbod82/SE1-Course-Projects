package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

import static java.lang.Math.abs;

@Service
public class Matcher {
    public MatchResult match(Order newOrder) {

        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY) {
                if (trade.buyerHasEnoughCredit())
                    trade.decreaseBuyersCredit();
                else {
                    rollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }
            trade.increaseSellersCredit();
            trades.add(trade);

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
        return MatchResult.executed(newOrder, trades);
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

    public MatchResult execute(Order order) {


        Order originalOrder = order.snapshot();
        MatchResult result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT)
            return result;

        if (abs(order.getQuantity()- originalOrder.getQuantity()) < order.getMinimumExecutionQuantity()){
            rollbackTrades(order, result.trades());
            return MatchResult.minimumExecutionQuantityNotPassed();
        }
        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            order.resetMinimumExecutionQuantity();
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
            order.getSecurity().setLatestCost(result.trades().getLast());
        }
        return result;
    }


    public LinkedList<MatchResult> auctionMatch(OrderBook orderBook ,int openingPrice){

        LinkedList<Trade> trades = new LinkedList<>();
        LinkedList<MatchResult> matchResults = new LinkedList<>();
        while (orderBook.hasOrderOfType(Side.BUY) &&  orderBook.hasOrderOfType(Side.SELL)) {
            Order buyOrder = orderBook.getBuyQueue().getFirst();
            Order sellOrder = orderBook.matchWithFirst(buyOrder);
            if (sellOrder == null)
                break;
            if(sellOrder.getPrice() > openingPrice || buyOrder.getPrice() < openingPrice)
                break;
            Trade trade = new Trade(buyOrder.getSecurity(), openingPrice, Math.min(buyOrder.getQuantity(), sellOrder.getQuantity()), buyOrder, sellOrder);

            trade.increaseSellersCredit();
            trades.add(trade);
            if(buyOrder.getPrice() > openingPrice){
                trade.returnMoneyToBuyer();
            }
            if (buyOrder.getQuantity() >= sellOrder.getQuantity()) {
                buyOrder.decreaseQuantity(sellOrder.getQuantity());
                orderBook.removeFirst(sellOrder.getSide());
                addIcebergOrderToOrderBook(orderBook , sellOrder);
            } else {
                sellOrder.decreaseQuantity(buyOrder.getQuantity());
                orderBook.removeFirst(buyOrder.getSide());
                addIcebergOrderToOrderBook(orderBook , buyOrder);
            }
            matchResults.add(MatchResult.executed(buyOrder, trades));
        }
        return  matchResults;
    }

    private void addIcebergOrderToOrderBook(OrderBook orderBook ,Order order){
        if (order instanceof IcebergOrder icebergOrder) {
            icebergOrder.decreaseQuantity(order.getQuantity());
            icebergOrder.replenish();
            if (icebergOrder.getQuantity() > 0)
                orderBook.pushBack(icebergOrder);

        }
    }

}
