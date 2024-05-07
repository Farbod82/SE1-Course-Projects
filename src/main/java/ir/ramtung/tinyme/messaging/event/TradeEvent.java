package ir.ramtung.tinyme.messaging.event;

public class TradeEvent extends Event{
    String securityIsin;
    int price;
    int quantity;
    long buyId;
    long sellId;
}
