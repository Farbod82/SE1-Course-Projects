package ir.ramtung.tinyme.domain.service;


import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import ir.ramtung.tinyme.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MatchOutcomePublisher {

    @Autowired
    EventPublisher eventPublisher;

    @Autowired
    SecurityRepository securityRepository;

    public MatchOutcomePublisher(EventPublisher eventPublisher, SecurityRepository securityRepository){
        this.eventPublisher = eventPublisher;
        this.securityRepository = securityRepository;
    }




    public void publishMatchOutComes(MatchResult matchResult, EnterOrderRq enterOrderRq){
        if (publishRejectedRequest(matchResult, enterOrderRq)){
            return;
        }
        if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER){
            eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            if(enterOrderRq.getStopPrice() > 0 && matchResult.outcome() == MatchingOutcome.EXECUTED){
                eventPublisher.publish(new OrderActivatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            }
        }
        else {
            eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        }
        publishExecutionResults(matchResult,enterOrderRq);
    }
    public void publishAfterActivationResults(MatchResult matchResult, EnterOrderRq enterOrderRq) {

        if (publishRejectedRequest(matchResult, enterOrderRq)){
            return;
        }
        eventPublisher.publish(new OrderActivatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        publishExecutionResults(matchResult,enterOrderRq);
    }


    private void publishExecutionResults(MatchResult matchResult, EnterOrderRq enterOrderRq){
        if (!matchResult.trades().isEmpty()) {
            eventPublisher.publish(new OrderExecutedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
        }
        if (matchResult.outcome() == MatchingOutcome.QUEUED_FOR_AUCTION){
            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            OrderBook orderBook = security.getOrderBook();
            HashMap<String, Long> openingPriceAndQuantity = orderBook.calcCurrentOpeningPriceAndMaxQuantity(security.getLatestPrice());
            eventPublisher.publish(new OpeningPriceEvent(enterOrderRq.getSecurityIsin(), openingPriceAndQuantity.get("price"), openingPriceAndQuantity.get("quantity")));
        }
    }


    private boolean publishRejectedRequest(MatchResult matchResult, EnterOrderRq enterOrderRq) {
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
            return true;
        } else if (matchResult.outcome() == MatchingOutcome.STOP_LIMIT_ORDER_NOT_ACCEPTED) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.STOP_LIMIT_ORDER_NOT_ACCEPTED)));
            return true;
        } else if (matchResult.outcome() == MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_NOT_PASSED) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_PASSED)));
            return true;
        } else if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_POSITIONS) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
            return true;
        }
        return false;
    }

    public void publishTradeEvents(MatchResult matchResult, Security security){
        for (Trade trade : matchResult.trades()){
            eventPublisher.publish(new TradeEvent(security.getIsin(),trade.getPrice(),trade.getQuantity(),trade.getBuy().getOrderId(),trade.getSell().getOrderId()));
        }
    }
}
