public abstract class HttpRequest { }

public abstract class HttpResponse {
    private final int statusCode;
    protected HttpResponse(int statusCode) { this.statusCode = statusCode; }
    public int getStatusCode() { return statusCode; }
    public boolean isSuccess()   { return statusCode >= 200 && statusCode < 300; }
    public boolean isRetryable() { return statusCode >= 500; }
}

// ---- UserService ----
public final class UserRequest extends HttpRequest {
    private final String userId;
    public UserRequest(String userId) { this.userId = userId; }
    public String getUserId() { return userId; }
}
public final class UserResponse extends HttpResponse {
    private final String customerId;
    public UserResponse(int status, String customerId) { super(status); this.customerId = customerId; }
    public String getCustomerId() { return customerId; }
}
public interface UserService { UserResponse getResponse(UserRequest request); }

// ---- PaymentService ----
public final class PaymentRequest extends HttpRequest {
    private final String customerId;
    public PaymentRequest(String customerId) { this.customerId = customerId; }
    public String getCustomerId() { return customerId; }
}
public final class PaymentResponse extends HttpResponse {
    private final String firstName, lastName, cardLastFour;
    public PaymentResponse(int status, String firstName, String lastName, String cardLastFour) {
        super(status); this.firstName = firstName; this.lastName = lastName; this.cardLastFour = cardLastFour;
    }
    public String getFirstName()    { return firstName; }
    public String getLastName()     { return lastName; }
    public String getCardLastFour() { return cardLastFour; }
}
public interface PaymentService { PaymentResponse getResponse(PaymentRequest request); }

// ---- AddressService ----
public final class AddressRequest extends HttpRequest {
    private final String customerId;
    public AddressRequest(String customerId) { this.customerId = customerId; }
    public String getCustomerId() { return customerId; }
}
public final class AddressResponse extends HttpResponse {
    private final String address;
    public AddressResponse(int status, String address) { super(status); this.address = address; }
    public String getAddress() { return address; }
}
public interface AddressService { AddressResponse getResponse(AddressRequest request); }