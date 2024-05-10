package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;

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

    public OrderBook findNewOrderBookBasedOnIOP(long indicativeOpeningPrice){
        LinkedList<Order> newSellQueue = sellQueue.stream()
                .filter(order -> order.getPrice() <= indicativeOpeningPrice)
                .collect(Collectors.toCollection(LinkedList::new));
        LinkedList<Order> newBuyQueue = buyQueue.stream()
                .filter(order -> order.getPrice() >= indicativeOpeningPrice)
                .collect(Collectors.toCollection(LinkedList::new));

        OrderBook filteredOrderBook = new OrderBook();
        newSellQueue.forEach(order -> filteredOrderBook.enqueue(order));
        newBuyQueue.forEach(order -> filteredOrderBook.enqueue(order));

        return filteredOrderBook;
    }

    public long findQuantityOfAllTrades(long indicativeOpeningPrice){
        long totalQuantity = 0;
        OrderBook filteredOrderBook = findNewOrderBookBasedOnIOP(indicativeOpeningPrice);

        while(filteredOrderBook.hasOrderOfType(Side.BUY) && filteredOrderBook.hasOrderOfType(Side.SELL)){

            Order buyOrder = filteredOrderBook.getQueue(Side.BUY).getFirst();
            Order matchingOrder = filteredOrderBook.matchWithFirst(buyOrder);
            totalQuantity = Math.min(buyOrder.getQuantity(), matchingOrder.getQuantity()) + totalQuantity;
            if (buyOrder.getQuantity() >= matchingOrder.getQuantity()) {
                buyOrder.decreaseQuantity(matchingOrder.getQuantity());
                filteredOrderBook.removeFirst(matchingOrder.getSide());
            }
            else {
                matchingOrder.decreaseQuantity(buyOrder.getQuantity());
                buyOrder.makeQuantityZero();
            }
        }

        return totalQuantity;
    }

    public long findMinimumPriceOfSellOrder(){
        return sellQueue.stream().mapToLong(Order::getPrice).min().orElse(0);
    }

    public long findMaximumPriceOfBuyOrder(){
        return buyQueue.stream().mapToLong(Order::getPrice).max().orElse(0);
    }

    public long calculateIndicativeOpeningPrice(){

        // to do
        return 0;
    }


}
