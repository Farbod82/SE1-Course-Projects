package ir.ramtung.tinyme.messaging;

public class Message {


    public static final String NOT_VALID_MIN_EXECUTION_QUANTITY = "Minimum Trade Quantity can not be less than zero";
    public static final String INVALID_MINIMUM_TRADE_VALUE = "Minimum Trade quantity is bigger than the total quantity";
    public static final String MINIMUM_EXECUTION_QUANTITY_NOT_PASSED = "Minimum Execution quantity  not passed.";
    public static final String INVALID_ORDER_ID = "Invalid order ID";
    public static final String ORDER_QUANTITY_NOT_POSITIVE = "Order quantity is not-positive";
    public static final String ORDER_PRICE_NOT_POSITIVE = "Order price is not-positive";
    public static final String UNKNOWN_SECURITY_ISIN = "Unknown security ISIN";
    public static final String ORDER_ID_NOT_FOUND = "Order ID not found in the order book";
    public static final String INVALID_PEAK_SIZE = "Iceberg order peak size is out of range";
    public static final String CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER = "Cannot specify peak size for a non-iceberg order";
    public static final String UNKNOWN_BROKER_ID = "Unknown broker ID";
    public static final String UNKNOWN_SHAREHOLDER_ID = "Unknown shareholder ID";
    public static final String BUYER_HAS_NOT_ENOUGH_CREDIT = "Buyer has not enough credit";
    public static final String QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE = "Quantity is not a multiple of security lot size";
    public static final String PRICE_NOT_MULTIPLE_OF_TICK_SIZE = "Price is not a multiple of security tick size";
    public static final String SELLER_HAS_NOT_ENOUGH_POSITIONS = "Seller has not enough positions";
    public static final String STOP_LIMIT_PRICE_NOT_PASSED = "Stop limit order not passed";

}
