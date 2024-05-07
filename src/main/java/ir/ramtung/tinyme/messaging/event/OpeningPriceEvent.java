package ir.ramtung.tinyme.messaging.event;

public class OpeningPriceEvent extends Event {
    String securityIsin;
    int openingPrice;
    int tradableQuantity;
}
