package com.yummy.transaction.service;

import com.yummy.commons.Response;
import com.yummy.offer.db.OfferRepository;
import com.yummy.offer.model.OfferEntity;
import com.yummy.offer.service.OfferService;
import com.yummy.restaurant.db.RestaurantEmployeeRepository;
import com.yummy.restaurant.db.RestaurantRepository;
import com.yummy.transaction.db.TransactionDao;
import com.yummy.transaction.db.TransactionOfferLinkRepository;
import com.yummy.transaction.db.TransactionRepository;
import com.yummy.transaction.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.yummy.transaction.model.TransactionState.CANCELED;
import static com.yummy.transaction.model.TransactionState.COMPLETED;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final TransactionDao transactionDao;
    private final OfferRepository offerRepository;
    private final TransactionOfferLinkRepository transactionOfferLinkRepository;
    private final SimpMessagingTemplate template;
    private final RestaurantEmployeeRepository restaurantEmployeeRepository;
    private final OfferService offerService;
    private final RestaurantRepository restaurantRepository;

    private static final String RESTAURANT_TOPIC = "/topic/restaurant/";

    @Autowired
    public TransactionService(TransactionRepository transactionRepository, TransactionDao transactionDao, OfferRepository offerRepository,
                              TransactionOfferLinkRepository transactionOfferLinkRepository, SimpMessagingTemplate template, RestaurantEmployeeRepository restaurantEmployeeRepository,
                              OfferService offerService, RestaurantRepository restaurantRepository) {
        this.transactionRepository = transactionRepository;
        this.transactionDao = transactionDao;
        this.offerRepository = offerRepository;
        this.transactionOfferLinkRepository = transactionOfferLinkRepository;
        this.template = template;
        this.restaurantEmployeeRepository = restaurantEmployeeRepository;
        this.offerService = offerService;
        this.restaurantRepository = restaurantRepository;
    }

    public Response getCode(TransactionRequest request) {
        Response response = new TransactionResponse();
        List<TransactionOfferLinkEntity> transactionOfferLinkEntityList = new ArrayList<>();
        Set<String> restaurantEmails = new HashSet<>();
        for (TransactionItem transaction : request.getTransactions()) {
            OfferEntity currentOffer = offerRepository.findById(transaction.getOfferId()).get();
            int currentOfferCount = currentOffer.getCount() - transaction.getCount();
            if (currentOfferCount < 0) {
                response = LackOfferStatus.builder()
                        .offer(currentOffer)
                        .difference(Math.abs(currentOfferCount))
                        .build();
                response.setMessage("Could not execute request. Request count greater than current offer count for offer" +
                        " with id: " + currentOffer.getId());
                response.setCode(200);
                return response;
            }
            TransactionOfferLinkEntity transactionOfferLinkEntity = TransactionOfferLinkEntity.builder()
                    .offerId(transaction.getOfferId())
                    .count(transaction.getCount())
                    .build();
            transactionOfferLinkEntityList.add(transactionOfferLinkEntity);
            offerRepository.findById(transaction.getOfferId()).ifPresent(o -> restaurantEmails.add(restaurantEmployeeRepository.findFirstByRestaurantId(o.getRestaurantId()).getEmail()));
        }
        updateCurrentOrders(restaurantEmails);
        updateCurrentOffers(restaurantEmails);
        String code = Integer.toString(ThreadLocalRandom.current().nextInt(10000, 99999));
        TransactionEntity transactionEntity = new TransactionEntity(code, new Date(System.currentTimeMillis()), TransactionState.PENDING.toString(), request.getReceiveTimestamp());
        TransactionEntity savedEntity = transactionRepository.save(transactionEntity);
        transactionOfferLinkEntityList.forEach(
                t -> {
                    t.setTransactionId(savedEntity.getId());
                    transactionOfferLinkRepository.save(t);
                }
        );
        ((TransactionResponse) response).setOrderId(transactionEntity.getId());
        ((TransactionResponse) response).setPaymentCode(code);
        response.setCode(200);
        response.setMessage("Code returned properly");
        return response;
    }

    private void updateCurrentOffers(Set<String> emails) {
        emails.forEach(r ->
                template.convertAndSend(RESTAURANT_TOPIC + r, offerService.getOffersByEmail(r, 0, 1000))
        );

    }

    private void updateCurrentOrders(Set<String> emails) {
        emails.forEach(r ->
                template.convertAndSend(RESTAURANT_TOPIC + r, getCurrentOrders(r, new PaginationRequest(0, 1000)))
        );

    }


    public BalanceResponse getBalance(String account) {
        BalanceResponse response = new BalanceResponse();
        response.setOrderSummary(getOrderSummary(account));
        response.setTakings(getTakingsSummary(account));
        response.setCode(200);
        response.setMessage("Success");
        return response;
    }

    private List<Takings> getTakingsSummary(String account) {
        List<Takings> takingsList = new ArrayList<>();
        Takings entire = new Takings(TakingsState.ENTIRE.name(),
                transactionDao.countTakings(account, TakingsState.ENTIRE.getTime()));
        Takings monthly = new Takings(TakingsState.MONTHLY.name(),
                transactionDao.countTakings(account, TakingsState.MONTHLY.getTime()));
        Takings weekly = new Takings(TakingsState.WEEKLY.name(),
                transactionDao.countTakings(account, TakingsState.WEEKLY.getTime()));
        takingsList.add(entire);
        takingsList.add(monthly);
        takingsList.add(weekly);
        return takingsList;
    }


    private OrderSummary getOrderSummary(String email) {
        return new OrderSummary(
                transactionDao.countAll(email),
                transactionDao.countByState(email, CANCELED.toString()),
                transactionDao.countByState(email, COMPLETED.toString()),
                transactionDao.countByState(email, TransactionState.MISSED.toString())
        );

    }

    public OrdersResponse getCurrentOrders(String email, PaginationRequest request) {
        OrdersResponse response = new OrdersResponse();
        int limit = request.getPage() == 0 ? request.getSize() : request.getPage() * request.getSize();
        int offset = request.getPage() == 0 ? 0 : request.getSize();
        List<Order> currentOrderList = transactionDao.getPendingOrdersForRestaurant(email, limit, offset);
        currentOrderList.forEach(o ->
        {
            final double[] price = {0.0};
            List<TransactionOfferLinkEntity> transactionOfferLinkEntityList = transactionOfferLinkRepository.findAllByTransactionId((long) ((CurrentOrder) o).getId());
            List<OrderItem> orderItemList = transactionOfferLinkEntityList.stream()
                    .map(t -> {
                        OfferEntity offerEntity = offerRepository.findById(t.getOfferId()).get();
                        price[0] += offerEntity.getPrice();
                        return new OrderItem(offerEntity.getId(), offerEntity.getDescription(),
                                offerEntity.getPrice(), t.getCount(), offerEntity.getDiscount(), offerEntity.getImage());
                    })
                    .collect(Collectors.toList());
            ((CurrentOrder) o).setPrice(price[0]);
            ((CurrentOrder) o).setOrderItemList(orderItemList);
        });
        response.setCurrentOrderList(currentOrderList);
        response.setMessage("Success");
        response.setCode(200);
        return response;
    }

    public Response changeOrdersState(ChangeOrderStateRequest request) {
        if (isValidState(request.getState())) {
            List<TransactionEntity> transactionsToUpdate = transactionRepository.findAllByIdIn(request.getIdList());
            transactionsToUpdate.forEach(transactionEntity -> {
                        transactionEntity.setState(request.getState().toUpperCase());
                        transactionRepository.save(transactionEntity);
                    }
            );
            return new Response("Success", 200);
        } else {
            return new Response("Wrong state requested", 200);
        }
    }

    private boolean isValidState(String state) {
        return state.equalsIgnoreCase(CANCELED.toString()) ||
                state.equalsIgnoreCase(COMPLETED.toString());
    }

    public Response getAllOrdersByEmail(String email, Integer page, Integer size) {
        int limit = page == 0 ? 1000 : page * size;
        int offset = page == 0 ? 0 : size;
        List<Order> historicOrders = transactionDao.findAllByRestaurant(email, limit, offset)
                .stream()
                .map(order ->
                {
                    final double[] price = {0.0};
                    List<TransactionOfferLinkEntity> transactionOfferLinkEntityList = transactionOfferLinkRepository.findAllByTransactionId((long) ((HistoricOrder) order).getId());
                    List<OrderItem> orderItemList = transactionOfferLinkEntityList.stream()
                            .map(t -> {
                                OfferEntity offerEntity = offerRepository.findById(t.getOfferId()).get();
                                price[0] += offerEntity.getPrice();
                                return new OrderItem(offerEntity.getId(), offerEntity.getDescription(),
                                        offerEntity.getPrice(), t.getCount(), offerEntity.getDiscount(), offerEntity.getImage());
                            })
                            .collect(Collectors.toList());
                    ((HistoricOrder) order).setOrderItemList(orderItemList);
                    ((HistoricOrder) order).setPrice(price[0]);
                    return order;
                })
                .sorted()
                .collect(Collectors.toList());
        OrdersResponse ordersResponse = new OrdersResponse();
        final int restaurantId = restaurantEmployeeRepository.findFirstByEmail(email).getRestaurantId();

        ordersResponse.setRestaurantEntity(restaurantRepository.findFirstById(restaurantId));
        ordersResponse.setCurrentOrderList(historicOrders);
        ordersResponse.setPage(page);
        ordersResponse.setPageSize(size);
        ordersResponse.setCode(200);
        ordersResponse.setMessage("Success");
        return ordersResponse;
    }
}
