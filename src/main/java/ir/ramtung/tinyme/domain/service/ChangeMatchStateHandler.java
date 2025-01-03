package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.OrderBook;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Trade;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.OpeningPriceEvent;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.event.SecurityStateChangedEvent;
import ir.ramtung.tinyme.messaging.event.TradeEvent;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedList;


@Service
public class ChangeMatchStateHandler {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;
    Matcher matcher;

    MatchOutcomePublisher matchOutcomePublisher;


    private void handleActivatedStopOrders(Security security) {
        security.activateStopOrders();
        while(security.hasAnyActiveStopOrder()){
            MatchResult matchResult = security.matchSingleStopOrder(matcher);
            EnterOrderRq stopOrderEnterOrderRq = security.getLastProcessedReqID();
            matchOutcomePublisher.publishAfterActivationResults(matchResult,stopOrderEnterOrderRq);
        }
    }
    public ChangeMatchStateHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher, MatchOutcomePublisher matchOutcomePublisher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
        this.matchOutcomePublisher = matchOutcomePublisher;
    }
    public void handleChangeMatchingState(ChangeMatchingStateRq changeMatchingStateRq){
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        OrderBook orderBook = security.getOrderBook();
        if(security.isInAuctionMatchingState()){

            MatchResult matchResult = security.executeOpeningProcess(matcher);
            matchOutcomePublisher.publishTradeEvents(matchResult, security);
            if(changeMatchingStateRq.getTargetState() == MatchingState.CONTINUOUS){
                handleActivatedStopOrders(security);
            }
            else if(changeMatchingStateRq.getTargetState() == MatchingState.AUCTION){
                activateStopOrdersInStateChange(security,orderBook);
            }
        }
        security.changeSecurityStatusTo(changeMatchingStateRq.getTargetState());
        eventPublisher.publish(new SecurityStateChangedEvent(LocalDateTime.now(),security.getIsin(),changeMatchingStateRq.getTargetState()));
    }

    private void activateStopOrdersInStateChange(Security security, OrderBook orderBook){
        LinkedList<OrderActivatedEvent> activatedOrdersEvents =  security.activateStopOrders();
        for (var activated : activatedOrdersEvents){
            eventPublisher.publish(activated);
        }
        boolean anyActivatedOrderExisted = security.hasAnyActiveStopOrder();
        security.enqueueActivatedStopOrdersAfterAuction();
        HashMap<String, Long> openingPriceAndQuantity = orderBook.calcCurrentOpeningPriceAndMaxQuantity(security.getLatestPrice());
        if (anyActivatedOrderExisted) {
            eventPublisher.publish(new OpeningPriceEvent(security.getIsin(), openingPriceAndQuantity.get("price"), openingPriceAndQuantity.get("quantity")));
        }
    }
}
