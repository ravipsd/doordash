Exactly. **This changes the problem substantially**, and it is a very good DoorDash follow-up.

The requirement is no longer:

> Find the closest available Dasher.

It becomes:

> **Find the closest Dasher who has enough remaining capacity to accept the new order.**

For example:

* Dasher A: capacity = 3, carrying 3 → cannot accept
* Dasher B: capacity = 3, carrying 1 → can accept
* Dasher C: capacity = 2, carrying 0 → can accept

Even if A is physically closest, **B or C must be selected**.

## 1. Change the domain model

I would model capacity explicitly:

```java
public record Dasher(
        String id,
        Location location,
        int capacity,
        int activeOrders,
        boolean available
) {
    public int remainingCapacity() {
        return capacity - activeOrders;
    }

    public boolean canAcceptOrder(int orderSize) {
        return available
                && remainingCapacity() >= orderSize;
    }
}
```

And now the API becomes:

```java
public interface DasherFinder {

    Optional<Dasher> findClosest(
            Location deliveryLocation,
            List<Dasher> dashers,
            int orderSize
    );
}
```

---

# 2. Simple O(N) solution

```java
public class ClosestDasherFinder implements DasherFinder {

    @Override
    public Optional<Dasher> findClosest(
            Location deliveryLocation,
            List<Dasher> dashers,
            int orderSize) {

        if (deliveryLocation == null) {
            throw new IllegalArgumentException(
                    "Delivery location cannot be null");
        }

        if (orderSize <= 0) {
            throw new IllegalArgumentException(
                    "Order size must be positive");
        }

        if (dashers == null || dashers.isEmpty()) {
            return Optional.empty();
        }

        Dasher closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (Dasher dasher : dashers) {

            if (dasher == null) {
                continue;
            }

            // Capacity check first.
            if (!dasher.canAcceptOrder(orderSize)) {
                continue;
            }

            double distance = squaredDistance(
                    deliveryLocation,
                    dasher.location()
            );

            if (closest == null
                    || distance < closestDistance
                    || (distance == closestDistance
                        && dasher.id().compareTo(closest.id()) < 0)) {

                closest = dasher;
                closestDistance = distance;
            }
        }

        return Optional.ofNullable(closest);
    }

    private double squaredDistance(
            Location a,
            Location b) {

        double dx = a.x() - b.x();
        double dy = a.y() - b.y();

        return dx * dx + dy * dy;
    }
}
```

The important part is:

```java
if (!dasher.canAcceptOrder(orderSize)) {
    continue;
}
```

**before** distance comparison.

---

# 3. Example

Suppose:

```text
Order size = 1
```

and:

| Dasher | Distance | Capacity | Active | Remaining |
| ------ | -------: | -------: | -----: | --------: |
| D1     |     1 km |        3 |      3 |         0 |
| D2     |     2 km |        3 |      1 |         2 |
| D3     |     5 km |        5 |      1 |         4 |

The answer is:

```text
D2
```

not D1.

D1 is closer but **cannot accept the order**.

---

# 4. But here's the Staff-level issue

Suppose:

```text
D1 capacity = 3
D1 active orders = 2
```

You select D1.

Then two requests arrive simultaneously:

```text
Request A → D1
Request B → D1
```

Both see:

```text
remainingCapacity = 1
```

Both think they can accept.

Now:

```text
activeOrders = 4
capacity = 3
```

We've violated the invariant.

This is the important part of the problem.

> **Finding a Dasher and assigning an order are not the same operation.**

You need an atomic reservation/assignment step.

---

# 5. Separate selection from reservation

I would change the design to:

```text
Find candidates
       ↓
Rank candidates
       ↓
Reserve capacity
       ↓
Confirm assignment
```

Instead of:

```java
Dasher d = findClosest(...);
d.incrementOrders();
```

we need something like:

```java
Optional<Dasher> reserveClosestDasher(
        Location location,
        int orderSize
);
```

The reservation must be atomic.

---

# 6. Thread-safe implementation

For a simple in-memory interview solution, we could maintain:

```java
public class Dasher {

    private final String id;
    private final Location location;
    private final int capacity;

    private int activeOrders;

    public Dasher(
            String id,
            Location location,
            int capacity,
            int activeOrders) {

        this.id = id;
        this.location = location;
        this.capacity = capacity;
        this.activeOrders = activeOrders;
    }

    public synchronized boolean tryReserve(
            int orderSize) {

        if (activeOrders + orderSize > capacity) {
            return false;
        }

        activeOrders += orderSize;
        return true;
    }

    public synchronized void release(
            int orderSize) {

        if (orderSize > activeOrders) {
            throw new IllegalArgumentException(
                    "Cannot release more capacity than reserved");
        }

        activeOrders -= orderSize;
    }
}
```

Now:

```java
if (dasher.tryReserve(orderSize)) {
    return Optional.of(dasher);
}
```

is important.

Because:

```text
Thread A
    |
    +---- tryReserve()
              |
              +---- succeeds
              |
              +---- activeOrders++

Thread B
    |
    +---- tryReserve()
              |
              +---- sees updated capacity
              |
              +---- fails
```

The check and update happen atomically.

---

# 7. But don't blindly synchronize the entire search

I would **not** do this:

```java
synchronized (dasher) {
    calculateDistance();
    checkCapacity();
    reserve();
}
```

for every Dasher.

The lock should protect the **small critical section**, not the expensive search.

Better:

```text
Search
  ↓
find nearby candidates
  ↓
tryReserve(candidate)
  ↓
success? ---- yes → return
  |
  no
  |
  ↓
try next candidate
```

This also handles a race where the Dasher becomes full between candidate selection and reservation.

---

# 8. Very important: don't return the first candidate

Suppose:

```text
D1 = 1 km
D2 = 2 km
D3 = 3 km
```

At search time:

```text
D1 has capacity
```

But another request fills D1 before we reserve it.

Then:

```java
D1.tryReserve(...)
```

returns false.

We should **continue to D2**, not return failure.

So:

```java
public Optional<Dasher> findAndReserve(
        Location location,
        List<Dasher> dashers,
        int orderSize) {

    List<Dasher> candidates = dashers.stream()
            .filter(d -> d.canAcceptOrder(orderSize))
            .sorted(Comparator.comparingDouble(
                    d -> squaredDistance(
                            location,
                            d.location())))
            .toList();

    for (Dasher dasher : candidates) {

        if (dasher.tryReserve(orderSize)) {
            return Optional.of(dasher);
        }
    }

    return Optional.empty();
}
```

This is a much stronger solution.

---

# 9. Now introduce multiple-order capacity

Suppose a Dasher can carry **3 orders**.

Current state:

```text
D1
capacity = 3
active = 2
```

New order:

```text
size = 1
```

Allowed:

```text
2 + 1 <= 3
```

But suppose an order can consume different amounts of capacity.

For example:

```text
small order = 1
large catering order = 3
```

Then:

```java
public boolean canAcceptOrder(int requiredCapacity) {
    return available
            && remainingCapacity() >= requiredCapacity;
}
```

Now the system isn't really asking:

> "How many orders?"

It's asking:

> **"How much remaining capacity does the Dasher have?"**

That's a more general design.

---

# 10. Even better API

I'd change:

```java
findClosest(
    location,
    dashers,
    orderSize
)
```

to:

```java
findClosest(
    location,
    dashers,
    DeliveryRequest
)
```

For example:

```java
public record DeliveryRequest(
        String orderId,
        Location pickupLocation,
        int capacityRequired
) {
}
```

Then:

```java
Optional<Dasher> findAndReserve(
        DeliveryRequest request
);
```

Now you're not coupling the finder to a primitive `int`.

Later you can add:

```text
vehicleType
temperatureRequirements
maxDistance
deliveryPriority
```

without destroying the API.

---

# 11. Now the interviewer will probably push further

They might say:

> "What if the Dasher is carrying three orders, but adding this order causes a delivery route problem?"

Now capacity alone isn't enough.

For example:

```text
Dasher:
    Order A → North
    Order B → South
    Order C → East

New Order:
    West
```

Technically:

```text
capacity available = YES
```

but operationally it might be a terrible assignment.

So I'd separate:

### Candidate filtering

```text
available
capacity
vehicle type
service area
```

from:

### Candidate ranking

```text
distance
ETA
route impact
priority
Dasher workload
```

That gives:

```java
public interface DasherEligibility {

    boolean eligible(
            Dasher dasher,
            DeliveryRequest request
    );
}
```

and:

```java
public interface DasherRanker {

    int compare(
            Dasher a,
            Dasher b,
            DeliveryRequest request
    );
}
```

This is where your OOP design starts becoming valuable.

---

# 12. The architecture I'd present

For the DoorDash interview, I'd draw:

```text
                 DeliveryRequest
                        |
                        v
              +-------------------+
              | Candidate Finder  |
              +-------------------+
                        |
                        v
              +-------------------+
              | Eligibility       |
              |                   |
              | availability      |
              | capacity          |
              | vehicle type      |
              +-------------------+
                        |
                        v
              +-------------------+
              | Rank Candidates   |
              |                   |
              | distance          |
              | ETA               |
              | route impact      |
              +-------------------+
                        |
                        v
              +-------------------+
              | Atomic Reservation|
              +-------------------+
                        |
                 +------+------+
                 |             |
              success        failure
                 |             |
                 v             v
              Assign       Next candidate
```

That's much more Staff-level than simply:

```java
min(dasher.distance)
```

---

# 13. One thing I would explicitly tell the interviewer

This sentence is worth remembering:

> **"The spatial index gives me candidates; it doesn't guarantee assignment. Capacity is a mutable constraint, so I need an atomic reservation at assignment time."**

That demonstrates that you understand the distinction between:

**read-time state** and **commit-time state**.

And it naturally leads into the distributed version.

---

# 14. Distributed system version

If Dashers are distributed across multiple dispatch servers, `synchronized` isn't sufficient.

You might have:

```text
Dispatch Server A
       |
       +---- D1

Dispatch Server B
       |
       +---- D1
```

Both servers cannot independently reserve capacity.

You need a single authority for Dasher capacity, such as:

```text
             Dispatch Service
                    |
                    v
             Capacity Service
                    |
             atomic reservation
                    |
                    v
               D1 capacity
```

or use a transactional datastore / strongly consistent reservation mechanism.

The critical operation is conceptually:

```text
if remainingCapacity >= requiredCapacity
    remainingCapacity -= requiredCapacity
    return SUCCESS
else
    return FAILURE
```

**as one atomic operation.**

---

## For your DoorDash interview

If the original question is simply **"Find Closest Dasher"**, I'd start with the O(N) implementation.

Then if the interviewer adds:

> "A Dasher can have multiple orders with a capacity limit."

I'd immediately say:

> "Then capacity becomes part of candidate eligibility. I'll filter candidates by remaining capacity before ranking them. However, because capacity is mutable, I don't want selection and assignment to be separate operations. I'll add an atomic `tryReserve()` step and, if reservation fails because another request consumed the capacity, I'll continue to the next closest candidate."

That is the answer I'd want to hear from a **Staff Engineer**.
