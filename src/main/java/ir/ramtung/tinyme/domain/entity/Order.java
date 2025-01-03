package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import java.util.Comparator;
import java.time.LocalDateTime;

@Builder
@EqualsAndHashCode
@ToString
@Getter
public class Order {
    protected long orderId;
    protected Security security;
    protected Side side;
    protected int quantity;
    protected int price;
    protected Broker broker;
    protected Shareholder shareholder;
    @Builder.Default
    protected LocalDateTime entryTime = LocalDateTime.now();
    @Builder.Default
    protected OrderStatus status = OrderStatus.NEW;
    @Builder.Default
    protected  int minimumExecutionQuantity = 0;
    @Builder.Default
    protected boolean isActive = false;
    @Builder.Default
    protected  int stopPrice  = 0;

    public Order(EnterOrderRq enterOrderRq,Security security,Broker broker,Shareholder shareholder) {
        this.orderId = enterOrderRq.getOrderId();
        this.security = security;
        this.side = enterOrderRq.getSide();
        this.quantity = enterOrderRq.getQuantity();
        this.price = enterOrderRq.getPrice();
        this.entryTime = enterOrderRq.getEntryTime();
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = OrderStatus.NEW;
        this.minimumExecutionQuantity = enterOrderRq.getMinimumExecutionQuantity();
        this.stopPrice = enterOrderRq.getStopPrice();
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status,int minimumExecutionQuantity,boolean isActive, int stopPrice) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
        this.minimumExecutionQuantity = minimumExecutionQuantity;
        this.stopPrice = stopPrice;
        this.isActive = isActive;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, OrderStatus status) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = status;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime) {
        this.orderId = orderId;
        this.security = security;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.entryTime = entryTime;
        this.broker = broker;
        this.shareholder = shareholder;
        this.status = OrderStatus.NEW;
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder) {
        this(orderId, security, side, quantity, price, broker, shareholder, LocalDateTime.now());
    }

    public Order(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder , int minimumExecutionQuantity) {
        this(orderId, security, side, quantity, price, broker, shareholder, LocalDateTime.now());
        this.minimumExecutionQuantity = minimumExecutionQuantity;
    }

    static Comparator<Order> sellPriceComparator = Comparator.comparingDouble(Order::getStopPrice).reversed()
            .thenComparing(Order::getEntryTime);

    static Comparator<Order> buyPriceComparator = Comparator.comparingDouble(Order::getStopPrice)
            .thenComparing(Order::getEntryTime);

    public Order snapshot() {
        return new Order(orderId, security, side, quantity, price, broker, shareholder, entryTime, OrderStatus.SNAPSHOT);
    }

    public Order snapshotWithQuantity(int newQuantity) {
        return new Order(orderId, security, side, newQuantity, price, broker, shareholder, entryTime, OrderStatus.SNAPSHOT);
    }

    public boolean matches(Order other) {
        if (side == Side.BUY)
            return price >= other.price;
        else
            return price <= other.price;
    }

    public void decreaseQuantity(int amount) {
        if (amount > quantity)
            throw new IllegalArgumentException();
        quantity -= amount;
    }

    public void makeQuantityZero() {
        quantity = 0;
    }

    public boolean queuesBefore(Order order) {
        if (order.getSide() == Side.BUY) {
            return price > order.getPrice();
        } else {
            return price < order.getPrice();
        }
    }
    public void resetMinimumExecutionQuantity(){
        minimumExecutionQuantity = 0;
    }

    public void queue() {
        status = OrderStatus.QUEUED;
    }

    public void markAsNew(){
        status = OrderStatus.NEW;
    }
    public boolean isQuantityIncreased(int newQuantity) {
        return newQuantity > quantity;
    }

    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        if(updateOrderRq.getStopPrice() > 0){
            stopPrice  = updateOrderRq.getStopPrice();
        }

        quantity = updateOrderRq.getQuantity();
        price = updateOrderRq.getPrice();

    }

    public long getValue() {
        return (long)price * quantity;
    }

    public int getTotalQuantity() { return quantity; }
}
