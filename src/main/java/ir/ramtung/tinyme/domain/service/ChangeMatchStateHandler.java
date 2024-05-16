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

    public void publishActivatedStopOrders(Security security){
        LinkedList<OrderActivatedEvent> activatedOrdersEvents =  security.activateStopOrders();
        for(OrderActivatedEvent orderActivatedEvent: activatedOrdersEvents){
            eventPublisher.publish(orderActivatedEvent);
        }
    }
    private void handleActivatedStopOrders(Security security) {
        MatchResult matchResult;
        matchOutcomePublisher.publishActivatedStopOrders(security);
        while(security.hasAnyActiveStopOrder()){
            matchResult = security.matchSingleStopOrder(matcher);
            EnterOrderRq stopOrderEnterOrderRq = security.getLastProcessedReqID();
            matchOutcomePublisher.publishMatchOutComes(matchResult,stopOrderEnterOrderRq);
            matchOutcomePublisher.publishActivatedStopOrders(security);
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
        if(security.isInAuctionMatchingState()){
            OrderBook orderBook = security.getOrderBook();
            HashMap<String, Long> openingPriceAndQuantity = orderBook.calcCurrentOpeningPriceAndMaxQuantity(security.getLatestPrice());
            matcher.auctionMatch(orderBook, openingPriceAndQuantity.get("price").intValue());
            MatchResult matchResult = security.executeOpeningProcess(matcher);
            for (Trade trade : matchResult.trades()){
                eventPublisher.publish(new TradeEvent(security.getIsin(),trade.getPrice(),trade.getQuantity(),trade.getBuy().getOrderId(),trade.getSell().getOrderId()));
            }
            if(changeMatchingStateRq.getTargetState() == MatchingState.CONTINUOUS){
                handleActivatedStopOrders(security);
            }
            else if(changeMatchingStateRq.getTargetState() == MatchingState.AUCTION){
                matchOutcomePublisher.publishActivatedStopOrders(security);
                security.enqueueActivatedStopOrdersAfterAuction();
                openingPriceAndQuantity = orderBook.calcCurrentOpeningPriceAndMaxQuantity(security.getLatestPrice());
                eventPublisher.publish(new OpeningPriceEvent(security.getIsin(),openingPriceAndQuantity.get("price"),openingPriceAndQuantity.get("quantity")));
            }

        }
        security.changeSecurityStatusTo(changeMatchingStateRq.getTargetState());
        eventPublisher.publish(new SecurityStateChangedEvent(LocalDateTime.now(),security.getIsin(),changeMatchingStateRq.getTargetState()));
    }



}
