public enum OrderState {
    TO_STORE,    // ACCEPTED — driving to the restaurant
    AT_STORE,    // ARRIVED  — waiting for the food
    IN_TRANSIT,  // PICKED_UP — carrying to the customer
    DONE         // FULFILLED or CANCELED
}