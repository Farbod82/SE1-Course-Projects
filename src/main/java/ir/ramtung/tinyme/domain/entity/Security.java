package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import lombok.Builder;
import lombok.Getter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();

    @Builder.Default
    private LinkedList<Order>  stopOrderList = new LinkedList<>();
    @Builder.Default
    private HashMap<Long, EnterOrderRq> requestIDs = new HashMap<>();
    @Builder.Default
    private LinkedList<Order>  activeStopOrderList = new LinkedList<>();
    int latestPrice;
    EnterOrderRq lastProcessedReqID;

    public void setLatestCost(Trade trade){
        latestPrice = trade.getPrice();
    }



    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();
        Order order;
        if (enterOrderRq.getStopPrice() > 0){
            if(enterOrderRq.getPeakSize() > 0 || enterOrderRq.getMinimumExecutionQuantity() > 0)
                return MatchResult.stopLimitOrderNotAccepted();

            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity(),false, enterOrderRq.getStopPrice());
            if ((order.getStopPrice() <= latestPrice && enterOrderRq.getSide() == BUY) || (order.getStopPrice() >= latestPrice && enterOrderRq.getSide() == Side.SELL)){
                order.isActive = true;
                if(order.getSide() == BUY){
                    if(!order.getBroker().hasEnoughCredit(order.getValue())) {
                        return MatchResult.notEnoughCredit();
                    }
                    order.getBroker().decreaseCreditBy(order.getValue());
                }
                return matcher.execute(order);
            }
            else{
                stopOrderList.add(order);
                requestIDs.put(order.getOrderId(),enterOrderRq);
                if(order.getSide() == BUY){
                     if(!order.getBroker().hasEnoughCredit(order.getValue())) {
                        return MatchResult.notEnoughCredit();
                    }
                    order.getBroker().decreaseCreditBy(order.getValue());
                }
                return  MatchResult.stopLimitAccepted();
            }
        }
        else if (enterOrderRq.getPeakSize() == 0)
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());
        else
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize());

        return matcher.execute(order);
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null) {
            Order unactivatedStopOrder = findByStopOrderId(deleteOrderRq.getOrderId());
            if(unactivatedStopOrder == null) {
                throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
            }
            else{
                stopOrderList.remove(unactivatedStopOrder);
                if(unactivatedStopOrder.getSide() == BUY){
                    unactivatedStopOrder.getBroker().increaseCreditBy(unactivatedStopOrder.getValue());
                }
                return;
            }
        }
        if (order.getSide() == BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }

    public Order findByStopOrderId(long orderId) {
        for (Order order : stopOrderList) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public MatchResult handleUnactiveStopLimitOrder(EnterOrderRq updateOrderRq, Order order, Order stopOrder){
        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                        orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity())){
            return MatchResult.notEnoughPositions();
        }
        else{
            if(order.getSide() == Side.BUY){
                order.getBroker().increaseCreditBy(order.getValue());
                if(!order.getBroker().hasEnoughCredit((long) updateOrderRq.getPrice() *updateOrderRq.getQuantity())) {
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy((long) updateOrderRq.getPrice() *updateOrderRq.getQuantity());
            }
            stopOrder.updateFromRequest(updateOrderRq);
            return MatchResult.executed(null, List.of());
        }
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        if (order == null) {
            Order stopOrder = findByStopOrderId(updateOrderRq.getOrderId());
            if(stopOrder == null)
                throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
            else {
                return handleUnactiveStopLimitOrder(updateOrderRq, stopOrder, stopOrder);
            }
        }

        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);

        if( updateOrderRq.getStopPrice() != order.getStopPrice()) {
            if(order.getStopPrice() == 0)
                throw new InvalidRequestException(Message.CANNOT_SPECIFY_STOP_LIMIT_PRICE_FOR_A_NON_STOP_LIMIT_ORDER);
            if(order.isActive)
                throw new InvalidRequestException(Message.CANNOT_SPECIFY_STOP_LIMIT_PRICE_FOR_A_ACTIVATED_STOP_LIMIT_ORDER);
        }

        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        if (updateOrderRq.getSide() == BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }
        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) {
            if (updateOrderRq.getSide() == BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return MatchResult.executed(null, List.of());
        }
        else
            order.markAsNew();

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        MatchResult matchResult = matcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }
    private void deleteActivatedOrder(){
        Stream<Order> mustBeDelete =  stopOrderList.stream().filter(Order::isActive);
        stopOrderList.removeAll(mustBeDelete.toList());
    }
    private boolean mustBeActivated(Order order){
        if(!order.isActive() &&
                ((order.getStopPrice() <= latestPrice && order.getSide() == BUY)
                || (order.getStopPrice() >= latestPrice && order.getSide() == Side.SELL)))
            return true;
        return false;

    }
    public  boolean hasAnyActiveStopOrder(){
        return !activeStopOrderList.isEmpty();
    }

    public LinkedList<OrderActivatedEvent> checkActivatedOrderExist(){
        LinkedList<OrderActivatedEvent> activatedOrdersRecord = new LinkedList<>();
        LinkedList<Order> sortedActiveOrders = new LinkedList<>();
        for(Order order : stopOrderList){
            if (mustBeActivated(order)){
                sortedActiveOrders.add(order);
                order.isActive = true;
                activatedOrdersRecord.add(new OrderActivatedEvent(requestIDs.get(order.getOrderId()).getRequestId(),order.getOrderId()));
            }

        }
        if (!sortedActiveOrders.isEmpty()) {
            Side side = sortedActiveOrders.get(0).getSide();
            if (side == BUY)
                sortedActiveOrders.sort(Order.buyPriceComparator);
            else
                sortedActiveOrders.sort(Order.sellPriceComparator);

            activeStopOrderList.addAll(sortedActiveOrders);
        }
        deleteActivatedOrder();
        return activatedOrdersRecord;
    }

    public MatchResult runSingleStopOrder(Matcher matcher){
        Order order = activeStopOrderList.removeFirst();
        lastProcessedReqID = requestIDs.remove(order.getOrderId());
        if (order.getSide() == Side.SELL && !order.getShareholder().hasEnoughPositionsOn(this,orderBook.totalSellQuantityByShareholder(order.getShareholder()) + order.getQuantity())){
            return MatchResult.notEnoughPositions();
        }
        else {
            if(order.isActive() && order.getSide() == Side.BUY){
                order.getBroker().increaseCreditBy(order.getValue());
            }
            return matcher.execute(order);
        }
    }
}
