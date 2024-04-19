package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

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

    int latestSellCost;
    int latestBuyCost;


    public void setLatestCost(Trade trade){
        latestBuyCost = trade.getBuy().getPrice();
        latestSellCost = trade.getSell().getPrice();
    }


    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();
        Order order;
        if (enterOrderRq.getStopPrice() > 0){
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity(),false, enterOrderRq.getStopPrice());
            if ((order.getPrice() >= latestBuyCost && enterOrderRq.getSide() == Side.BUY) || (order.getPrice() >= latestSellCost && enterOrderRq.getSide() == Side.SELL)){
                return matcher.execute(order);
            }
            else{
                stopOrderList.add(order);
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
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {

        Order order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);

        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }
        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) {
            if (updateOrderRq.getSide() == Side.BUY) {
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
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }

    private boolean mustBeActivated(Order order){
        if(!order.isActive() &&
                ((order.getPrice() >= latestBuyCost && order.getSide() == Side.BUY)
                || (order.getPrice() >= latestSellCost && order.getSide() == Side.SELL)))
            return true;
        return false;

    }

    private void checkActivatedOrderExist(LinkedList<Order>  activestopOrderList){
        for(Order order : stopOrderList){
            if (mustBeActivated(order)){
                activestopOrderList.add(order);
                order.isActive = true;
            }
        }

    }

    private void deleteActivatedOrder(){
        Stream<Order> mustBeDelete =  stopOrderList.stream().filter(Order::isActive);
        stopOrderList.removeAll(mustBeDelete.toList());
    }

    public LinkedList<MatchResult> handleStopOrders(Matcher matcher){
        LinkedList<MatchResult> stopOrderMatchResults = new LinkedList<>();
        LinkedList<Order>  activeStopOrderList = new LinkedList<>();
        checkActivatedOrderExist(activeStopOrderList);
        int i=0;
        while (i < activeStopOrderList.size()){
            Order order = activeStopOrderList.get(i);
            if (order.getSide() == Side.SELL && !order.getShareholder().hasEnoughPositionsOn(this,orderBook.totalSellQuantityByShareholder(order.getShareholder()) + order.getQuantity())){
                stopOrderMatchResults.add(MatchResult.notEnoughPositions());
            }
            else {
                stopOrderMatchResults.add(matcher.execute(order));
            }
            checkActivatedOrderExist(activeStopOrderList);
        }
        deleteActivatedOrder();
        return stopOrderMatchResults;
    }
}
