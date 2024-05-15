package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;
    private long openingPrice;
    private long maxQuantityInAuctionState;

    public OrderBook() {
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
    }

    public void enqueue(Order order) {
        List<Order> queue = getQueue(order.getSide());
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        order.queue();
        it.add(order);
    }

    private LinkedList<Order> getQueue(Side side) {
        return side == Side.BUY ? buyQueue : sellQueue;
    }

    public Order findByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean removeByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public Order matchWithFirst(Order newOrder) {
        var queue = getQueue(newOrder.getSide().opposite());
        if (newOrder.matches(queue.getFirst()))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide());
        order.queue();
        queue.addFirst(order);
    }

    public void pushBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide());
        order.queue();
        queue.add(order);
    }

    public void restoreOrder(Order order) {
        removeByOrderId(order.getSide(), order.getOrderId());
        putBack(order);
    }

    public boolean hasOrderOfType(Side side) {
        return !getQueue(side).isEmpty();
    }

    public void removeFirst(Side side) {
        getQueue(side).removeFirst();
    }

    public int totalSellQuantityByShareholder(Shareholder shareholder) {
        return sellQueue.stream()
                .filter(order -> order.getShareholder().equals(shareholder))
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    public long findQuantityOfAllTrades(long indicativeOpeningPrice){
        LinkedList<Order> newSellQueue = sellQueue.stream()
                .filter(order -> order.getPrice() <= indicativeOpeningPrice)
                .collect(Collectors.toCollection(LinkedList::new));
        LinkedList<Order> newBuyQueue = buyQueue.stream()
                .filter(order -> order.getPrice() >= indicativeOpeningPrice)
                .collect(Collectors.toCollection(LinkedList::new));

        long sumSellQueue = newSellQueue.stream().mapToLong(Order::getQuantity).sum();
        long sumBuyQueue = newBuyQueue.stream().mapToLong(Order::getQuantity).sum();
        return Math.min(sumSellQueue, sumBuyQueue);
    }

    public long findMaximumPriceOfSellOrder(){
        return sellQueue.stream().mapToLong(Order::getPrice).max().orElse(0);
    }

    public long findMinimumPriceOfBuyOrder(){
        return buyQueue.stream().mapToLong(Order::getPrice).min().orElse(0);
    }

    public void updateCurrentOpeningPriceAndMaxQuantity(long lastTradeValue){
        long maxOfInterval = findMaximumPriceOfSellOrder();
        long minOfInterval = findMinimumPriceOfBuyOrder();

        List<Map.Entry<Long, Long>> pairsOfIOPAndQuantity = LongStream.rangeClosed(minOfInterval, maxOfInterval)
                .mapToObj(num -> Map.entry(findQuantityOfAllTrades(num), num))
                .toList();

        Optional<Map.Entry<Long, Long>> priceAndMaxQuantityPair = pairsOfIOPAndQuantity.stream()
                .max(Map.Entry.<Long, Long>comparingByKey().reversed()
                        .thenComparingLong(entry -> Math.abs(entry.getValue() - lastTradeValue)).reversed()
                        .thenComparingLong(entry -> entry.getValue()));
        if(priceAndMaxQuantityPair.isPresent()) {
            openingPrice = priceAndMaxQuantityPair.get().getValue();
            maxQuantityInAuctionState = priceAndMaxQuantityPair.get().getKey();
        }
    }

}
