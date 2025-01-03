package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
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

@Service
public class OrderHandler {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;
    Matcher matcher;

    MatchOutcomePublisher matchOutcomePublisher;

    OrderValidator orderValidator;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher, MatchOutcomePublisher matchOutcomePublisher, OrderValidator orderValdiator) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
        this.matchOutcomePublisher = matchOutcomePublisher;
        this.orderValidator = orderValdiator;
    }
    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            orderValidator.validateEnterOrderRq(enterOrderRq);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            MatchResult matchResult;

            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER) {
                matchResult = security.newOrder(enterOrderRq, broker, shareholder, matcher);
            }
            else {
                matchResult = security.updateOrder(enterOrderRq, matcher);
            }

            checkForActivatedStopLimitOrders(security);
            matchOutcomePublisher.publishMatchOutComes(matchResult, enterOrderRq);

        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }
    private void checkForActivatedStopLimitOrders(Security security) {
        MatchResult matchResult;
        security.activateStopOrders();
        while(security.hasAnyActiveStopOrder()) {
            matchResult = security.matchSingleStopOrder(matcher);
            EnterOrderRq stopOrderEnterOrderRq = security.getLastProcessedReqID();
            matchOutcomePublisher.publishAfterActivationResults(matchResult,stopOrderEnterOrderRq);
            security.activateStopOrders();
        }
    }
    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            orderValidator.validateDeleteOrderRq(deleteOrderRq);
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


}
