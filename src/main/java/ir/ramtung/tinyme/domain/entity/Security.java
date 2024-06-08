package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.service.OrderValidator;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import lombok.Builder;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private
    MatchingState matchingState = MatchingState.CONTINUOUS;
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
    private int latestPrice;
    EnterOrderRq lastProcessedReqID;


    public void setLatestPrice(Trade trade){
        latestPrice = trade.getPrice();
    }

    private int calculateNewQuantityOfShareholder(EnterOrderRq enterOrderRq, Order order){
        return orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + enterOrderRq.getQuantity();
    }

    private int calculateNewQuantityOfShareholder(EnterOrderRq enterOrderRq,Shareholder shareholder){
        return orderBook.totalSellQuantityByShareholder(shareholder)  + enterOrderRq.getQuantity();
    }

    private boolean checkSellerShareholderDoesntHaveEnoughPositions(EnterOrderRq enterOrderRq,Order order) {
        return enterOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this, calculateNewQuantityOfShareholder(enterOrderRq, order));
    }

    private boolean checkSellerShareholderDoesntHaveEnoughPositions(EnterOrderRq enterOrderRq,Shareholder shareholder) {
        return enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this, calculateNewQuantityOfShareholder(enterOrderRq, shareholder));
    }

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (checkSellerShareholderDoesntHaveEnoughPositions(enterOrderRq, shareholder))
            return MatchResult.notEnoughPositions();

        Order order;

        if (isStopLimitOrder(enterOrderRq)) {
            return handleNewStopLimitOrder(enterOrderRq, broker, shareholder, matcher);
        } else if (isIcebergOrder(enterOrderRq)) {
            order = new IcebergOrder(enterOrderRq, this,  broker, shareholder);
        } else {
            order = new Order(enterOrderRq, this, broker, shareholder);
        }
        if (matchingState == MatchingState.CONTINUOUS) {
            return matcher.execute(order);
        }
        else{
            orderBook.enqueue(order);
            HashMap<String, Long> openingPriceAndQuantity = orderBook.calcCurrentOpeningPriceAndMaxQuantity(latestPrice);
            return MatchResult.queuedForAuction(openingPriceAndQuantity.get("price").intValue());
        }
    }

    private boolean isStopLimitOrder(EnterOrderRq enterOrderRq) {
        return enterOrderRq.getStopPrice() > 0;
    }

    private boolean isIcebergOrder(EnterOrderRq enterOrderRq) {
        return enterOrderRq.getPeakSize() > 0;
    }

    private MatchResult handleNewStopLimitOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        Order order;
        if(hasFailedStopLimitOrderCondition(enterOrderRq))
            return MatchResult.stopLimitOrderNotAccepted();

        order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity(),false, enterOrderRq.getStopPrice());
        if (mustBeActivated(order)){
            order.isActive = true;
            if(buyerBrokerHasNotEnoughCredit(order)){
                    return MatchResult.notEnoughCredit();
            }

            return matcher.execute(order);
        }
        else{
            return handleUnactivatedNewStopLimitOrder(enterOrderRq, order);
        }
    }

    private MatchResult handleUnactivatedNewStopLimitOrder(EnterOrderRq enterOrderRq, Order order) {
        if(buyerBrokerHasNotEnoughCredit(order)){
            return MatchResult.notEnoughCredit();
        }
        if(order.getSide() == BUY) {
            order.getBroker().decreaseCreditBy(order.getValue());
        }
        stopOrderList.add(order);
        requestIDs.put(order.getOrderId(), enterOrderRq);
        return MatchResult.stopLimitAccepted();
    }

    private boolean hasFailedStopLimitOrderCondition(EnterOrderRq enterOrderRq) {
        return enterOrderRq.getPeakSize() > 0 || enterOrderRq.getMinimumExecutionQuantity() > 0;
    }

    private boolean buyerBrokerHasNotEnoughCredit(Order order) {
        return order.getSide() == BUY && !order.getBroker().hasEnoughCredit(order.getValue());
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        Order unactivatedStopOrder = findUnactivatedStopOrderById(deleteOrderRq.getOrderId());

        if(unactivatedStopOrder != null){
            if(matchingState == MatchingState.AUCTION)
                throw new InvalidRequestException(Message.DELETE_UNACTIVATED_STOP_LIMIT_ORDER_NOT_ALLOWED_IN_AUCTION_MODE);
            stopOrderList.remove(unactivatedStopOrder);
            if(unactivatedStopOrder.getSide() == BUY){
                unactivatedStopOrder.getBroker().increaseCreditBy(unactivatedStopOrder.getValue());
            }
        }
        else if (order != null) {
            if (order.getSide() == BUY)
                order.getBroker().increaseCreditBy(order.getValue());
            orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        }
        else  {
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        }
    }

    public Order findUnactivatedStopOrderById(long orderId) {
        for (Order order : stopOrderList) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public MatchResult updateUnactivatedStopLimitOrder(EnterOrderRq updateOrderRq, Matcher matcher) {
        Order stopOrder = findUnactivatedStopOrderById(updateOrderRq.getOrderId());
        if (checkSellerShareholderDoesntHaveEnoughPositions(updateOrderRq, stopOrder)){
            return MatchResult.notEnoughPositions();
        }
        else{
            if(stopOrder.getSide() == Side.BUY){
                stopOrder.getBroker().increaseCreditBy(stopOrder.getValue());
                long valueOfTrade = (long) updateOrderRq.getPrice() *updateOrderRq.getQuantity();
                if(!stopOrder.getBroker().hasEnoughCredit(valueOfTrade)) {
                    stopOrder.getBroker().decreaseCreditBy((long) stopOrder.getPrice() *stopOrder.getQuantity());
                    return MatchResult.notEnoughCredit();
                }
                stopOrder.getBroker().decreaseCreditBy(valueOfTrade);
            }
            stopOrder.updateFromRequest(updateOrderRq);
            return MatchResult.executed(null, List.of());
        }
    }

    public void checkUpdateRequestExceptions(Order queuedOrder ,EnterOrderRq updateOrderRq) throws InvalidRequestException {
        if(queuedOrder == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((queuedOrder instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(queuedOrder instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);


        if( updateOrderRq.getStopPrice() != queuedOrder.getStopPrice()) {
            if(queuedOrder.getStopPrice() == 0)
                throw new InvalidRequestException(Message.CANNOT_SPECIFY_STOP_LIMIT_PRICE_FOR_A_NON_STOP_LIMIT_ORDER);
            if(queuedOrder.isActive)
                throw new InvalidRequestException(Message.CANNOT_SPECIFY_STOP_LIMIT_PRICE_FOR_A_ACTIVATED_STOP_LIMIT_ORDER);
        }
    }

    public  MatchResult updateOrderBookOrders(EnterOrderRq updateOrderRq, Matcher matcher, Order order){
        if (checkSellerShareholderDoesntHaveEnoughPositions(updateOrderRq,order))
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

        if(matchingState == MatchingState.CONTINUOUS) {
            MatchResult matchResult = matcher.execute(order);
            if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
                orderBook.enqueue(originalOrder);
                if (updateOrderRq.getSide() == BUY) {
                    originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
                }
            }
            return matchResult;
        }
        else{
            orderBook.enqueue(order);
            HashMap<String, Long> openingPriceAndQuantity = orderBook.calcCurrentOpeningPriceAndMaxQuantity(latestPrice);
            return MatchResult.queuedForAuction(openingPriceAndQuantity.get("price").intValue());
        }
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {

        if(findUnactivatedStopOrderById(updateOrderRq.getOrderId()) != null) {
            if (matchingState == MatchingState.AUCTION)
                throw new InvalidRequestException(Message.UPDATE_UNACTIVATED_STOP_LIMIT_ORDER_NOT_ALLOWED_IN_AUCTION_MODE);
            return updateUnactivatedStopLimitOrder(updateOrderRq, matcher);
        }
        else {
            Order order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
            checkUpdateRequestExceptions(order ,updateOrderRq);
            return updateOrderBookOrders(updateOrderRq,matcher, order);
        }
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

    public LinkedList<OrderActivatedEvent> activateStopOrders(){
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

    public MatchResult matchSingleStopOrder(Matcher matcher){
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

    public boolean isInAuctionMatchingState(){
        return matchingState == MatchingState.AUCTION;
    }

    public void changeSecurityStatusTo(MatchingState newMatchingState){
        matchingState = newMatchingState;
    }

    public MatchResult executeOpeningProcess(Matcher matcher){
        int updatedOpeningPrice = orderBook.calcCurrentOpeningPriceAndMaxQuantity(latestPrice).get("price").intValue();
        MatchResult matchResult = matcher.auctionMatch(orderBook,updatedOpeningPrice);
        return matchResult;
    }

    public void enqueueActivatedStopOrdersAfterAuction(){
        for(Order order : activeStopOrderList){
            orderBook.enqueue(order);
        }
        activeStopOrderList.clear();
    }

}
