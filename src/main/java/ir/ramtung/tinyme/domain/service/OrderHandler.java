package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

@Service
public class OrderHandler {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;
    Matcher matcher;

    MatchOutcomePublisher matchOutcomePublisher;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher, MatchOutcomePublisher matchOutcomePublisher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
        this.matchOutcomePublisher = matchOutcomePublisher;
    }

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            MatchResult matchResult;

            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER) {
                if (security.isInAuctionMatchingState()){
                    validateEnterOrderRqForNewOrderInAuctionState(enterOrderRq);
                }
                matchResult = security.newOrder(enterOrderRq, broker, shareholder, matcher);
            }
            else
                matchResult = security.updateOrder(enterOrderRq, matcher);

            if (! security.isInAuctionMatchingState()) {
                publishingInstantlyActivatedStopLimitOrders(enterOrderRq,matchResult);
                checkAllActivatedStopLimitOrders(security);
            }
            matchOutcomePublisher.publishMatchOutComes(matchResult, enterOrderRq);
            
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private void checkAllActivatedStopLimitOrders(Security security) {
        MatchResult matchResult;
        matchOutcomePublisher.publishActivatedStopOrders(security);
        while(security.hasAnyActiveStopOrder()){
            matchResult = security.matchSingleStopOrder(matcher);
            EnterOrderRq stopOrderEnterOrderRq = security.getLastProcessedReqID();
            matchOutcomePublisher.publishMatchOutComes(matchResult,stopOrderEnterOrderRq);
            matchOutcomePublisher.publishActivatedStopOrders(security);
        }
    }

    private void publishingInstantlyActivatedStopLimitOrders(EnterOrderRq enterOrderRq, MatchResult matchResult) {
        if(enterOrderRq.getStopPrice() > 0 && matchResult.outcome() != MatchingOutcome.STOP_LIMIT_ORDER_ACCEPTED && matchResult.outcome() != MatchingOutcome.NOT_ENOUGH_CREDIT && enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER){
            eventPublisher.publish(new OrderActivatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
            if(security.isInAuctionMatchingState()){
                OrderBook orderBook = security.getOrderBook();
                HashMap<String, Long> openingPriceAndQuantity = orderBook.calcCurrentOpeningPriceAndMaxQuantity(security.getLatestPrice());
                eventPublisher.publish(new OpeningPriceEvent(security.getIsin(), openingPriceAndQuantity.get("price"), openingPriceAndQuantity.get("quantity")));
            }
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }


    private void validateEnterOrderRqForNewOrderInAuctionState(EnterOrderRq enterOrderRq) throws InvalidRequestException{
        List<String> errors = new LinkedList<>();
        if(enterOrderRq.getMinimumExecutionQuantity() > 0) {
            errors.add(Message.NEW_ORDER_WITH_MINIMUM_EXECUTION_QUANTITY_NOT_ALLOWED_IN_AUCTION_MODE);
        }
        if(enterOrderRq.getStopPrice() > 0){
            errors.add(Message.NEW_STOP_LIMIT_ORDER_NOT_ALLOWED_IN_AUCTION_MODE);
        }
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);

    }
    private void validateEnterOrderRq(EnterOrderRq enterOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (enterOrderRq.getQuantity() < enterOrderRq.getMinimumExecutionQuantity()){
            errors.add(Message.INVALID_MINIMUM_TRADE_VALUE);
        }
        if (enterOrderRq.getMinimumExecutionQuantity() < 0)
            errors.add(Message.NOT_VALID_MIN_EXECUTION_QUANTITY);
        if (enterOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (enterOrderRq.getQuantity() <= 0)
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if (enterOrderRq.getPrice() <= 0)
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);
        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
                errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
            if (enterOrderRq.getPrice() % security.getTickSize() != 0)
                errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        }
        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        if (enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (deleteOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin()) == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }
}
