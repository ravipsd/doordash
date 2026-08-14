Yes. **Find Closest Dasher** is a good DoorDash Code Craft practice problem because it looks simple initially, but the interviewer can push you into API design, extensibility, concurrency, and scalability.

I'll solve it in the way I'd recommend presenting it in a **45-minute Staff Engineer Code Craft interview**.

## 1. Problem

Given a set of Dashers and a delivery location, find the closest available Dasher.

For example:

```text
Dasher A: (1, 2)
Dasher B: (5, 5)
Dasher C: (2, 1)

Restaurant: (0, 0)
```

The closest Dasher is either A or C.

We'll assume Euclidean distance:

[
distance = \sqrt{(x_1-x_2)^2 + (y_1-y_2)^2}
]

But we **don't need `sqrt()`**. Comparing squared distances gives the same ordering.

---

# 2. First clarify requirements

In the interview I'd ask:

1. Are Dashers always available?
2. Can multiple Dashers have the same distance?
3. What should happen on a tie?
4. Is the location 2D latitude/longitude or Cartesian coordinates?
5. Do we need to support updates to Dasher locations?
6. How many Dashers can exist?
7. Is this a one-time query or a high-QPS API?
8. Do we need the closest **one** Dasher or the closest **K**?
9. Can a Dasher become unavailable while we're selecting them?

For the initial implementation, assume:

* 2D coordinates.
* Only available Dashers are considered.
* Return the closest Dasher.
* If tied, return the Dasher with the smaller ID.
* Location data is already available in memory.
* We need correct, readable code first.

---

# 3. Domain model

Use immutable objects.

```java
public record Location(double x, double y) {
}
```

```java
public record Dasher(
        String id,
        Location location,
        boolean available
) {
}
```

And our service:

```java
public interface DasherFinder {

    Optional<Dasher> findClosest(
            Location deliveryLocation,
            List<Dasher> dashers
    );
}
```

---

# 4. Simple solution — O(N)

This is what I would implement first.

```java
import java.util.*;

public class ClosestDasherFinder implements DasherFinder {

    @Override
    public Optional<Dasher> findClosest(
            Location deliveryLocation,
            List<Dasher> dashers) {

        if (deliveryLocation == null) {
            throw new IllegalArgumentException(
                    "Delivery location cannot be null");
        }

        if (dashers == null || dashers.isEmpty()) {
            return Optional.empty();
        }

        Dasher closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (Dasher dasher : dashers) {

            if (dasher == null || !dasher.available()) {
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

### Complexity

For `N` Dashers:

```text
Time:  O(N)
Space: O(1)
```

This is the correct starting point.

---

# 5. Test it

This is important for DoorDash Code Craft.

```java
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ClosestDasherFinderTest {

    private final DasherFinder finder =
            new ClosestDasherFinder();

    @Test
    void findsClosestDasher() {

        Location restaurant = new Location(0, 0);

        List<Dasher> dashers = List.of(
                new Dasher(
                        "D1",
                        new Location(1, 2),
                        true
                ),
                new Dasher(
                        "D2",
                        new Location(5, 5),
                        true
                ),
                new Dasher(
                        "D3",
                        new Location(2, 1),
                        true
                )
        );

        Optional<Dasher> result =
                finder.findClosest(restaurant, dashers);

        assertTrue(result.isPresent());
        assertEquals("D1", result.get().id());
    }

    @Test
    void ignoresUnavailableDashers() {

        Location restaurant = new Location(0, 0);

        List<Dasher> dashers = List.of(
                new Dasher(
                        "D1",
                        new Location(1, 1),
                        false
                ),
                new Dasher(
                        "D2",
                        new Location(5, 5),
                        true
                )
        );

        Optional<Dasher> result =
                finder.findClosest(restaurant, dashers);

        assertEquals("D2", result.get().id());
    }

    @Test
    void returnsEmptyWhenNoDasherAvailable() {

        Location restaurant = new Location(0, 0);

        List<Dasher> dashers = List.of(
                new Dasher(
                        "D1",
                        new Location(1, 1),
                        false
                )
        );

        assertTrue(
                finder.findClosest(
                        restaurant,
                        dashers
                ).isEmpty()
        );
    }

    @Test
    void handlesTieUsingDasherId() {

        Location restaurant = new Location(0, 0);

        List<Dasher> dashers = List.of(
                new Dasher(
                        "D2",
                        new Location(1, 0),
                        true
                ),
                new Dasher(
                        "D1",
                        new Location(0, 1),
                        true
                )
        );

        Optional<Dasher> result =
                finder.findClosest(restaurant, dashers);

        assertEquals("D1", result.get().id());
    }
}
```

---

# 6. Now comes the important DoorDash follow-up

The interviewer may say:

> "We have 10 million Dashers. We're getting thousands of requests per second. Scanning every Dasher for every request isn't going to work. What would you do?"

This is where the problem becomes interesting.

Our original:

```text
                    Query
                      |
                      v
             +----------------+
             | Scan all N     |
             | Dashers         |
             +----------------+
                      |
                      v
                   O(N)
```

doesn't scale.

We need a **spatial index**.

Possible approaches:

* Grid
* Geohash
* QuadTree
* R-tree
* KD-tree

For a practical DoorDash-style system, **geospatial indexing / geohashing** is a very natural answer.

---

# 7. Grid-based solution

For an interview, I'd actually explain a grid before jumping to a sophisticated spatial database.

Divide the map into cells.

For example:

```text
+----+----+----+----+
|    |    |    |    |
+----+----+----+----+
|    | D1 |    |    |
+----+----+----+----+
|    |    | D2 |    |
+----+----+----+----+
|    |    |    |    |
+----+----+----+----+
```

A Dasher belongs to a cell.

For a restaurant:

```text
1. Find its cell.
2. Search Dashers in that cell.
3. If insufficient, expand to neighboring cells.
4. Continue until we have the nearest Dasher.
```

This can reduce the search dramatically.

---

# 8. Java implementation with a grid

First:

```java
public record Cell(int x, int y) {
}
```

Then:

```java
public class GridDasherIndex {

    private final double cellSize;

    private final Map<Cell, Set<Dasher>> cells =
            new HashMap<>();

    public GridDasherIndex(double cellSize) {

        if (cellSize <= 0) {
            throw new IllegalArgumentException(
                    "Cell size must be positive");
        }

        this.cellSize = cellSize;
    }

    private Cell getCell(Location location) {

        int x = (int) Math.floor(
                location.x() / cellSize
        );

        int y = (int) Math.floor(
                location.y() / cellSize
        );

        return new Cell(x, y);
    }

    public void add(Dasher dasher) {

        if (!dasher.available()) {
            return;
        }

        Cell cell = getCell(dasher.location());

        cells.computeIfAbsent(
                cell,
                ignored -> new HashSet<>()
        ).add(dasher);
    }
}
```

Now the search:

```java
public Optional<Dasher> findClosest(
        Location location) {

    Cell center = getCell(location);

    Dasher closest = null;
    double closestDistance = Double.MAX_VALUE;

    for (int radius = 0; ; radius++) {

        boolean foundAny = false;

        for (int x = center.x() - radius;
             x <= center.x() + radius;
             x++) {

            for (int y = center.y() - radius;
                 y <= center.y() + radius;
                 y++) {

                Set<Dasher> candidates =
                        cells.get(new Cell(x, y));

                if (candidates == null) {
                    continue;
                }

                foundAny = true;

                for (Dasher dasher : candidates) {

                    double distance =
                            squaredDistance(
                                    location,
                                    dasher.location()
                            );

                    if (closest == null
                            || distance < closestDistance) {

                        closest = dasher;
                        closestDistance = distance;
                    }
                }
            }
        }

        /*
         * We can stop once the nearest possible point
         * outside the searched area is farther away
         * than our current closest Dasher.
         */

        if (foundAny && closest != null) {
            double maxCellDistance =
                    radius * cellSize;

            if (maxCellDistance
                    * maxCellDistance
                    > closestDistance) {

                break;
            }
        }
    }

    return Optional.ofNullable(closest);
}
```

For an interview, however, **I would not rush to implement this unless the interviewer explicitly asks for scalability**.

The first O(N) solution is much easier to make correct.

---

# 9. Another important follow-up: location updates

The interviewer might ask:

> "Dashers move continuously. How do you update their locations?"

This exposes a problem with our:

```java
Map<Cell, Set<Dasher>>
```

When a Dasher moves:

```text
D1
cell (10,10)
      |
      | moves
      v
cell (11,10)
```

we need:

```text
remove D1 from (10,10)
add D1 to (11,10)
```

I would maintain:

```java
Map<String, Cell> dasherCells;
```

So:

```java
public void updateLocation(
        String dasherId,
        Location newLocation) {

    Cell oldCell = dasherCells.get(dasherId);
    Cell newCell = getCell(newLocation);

    if (!Objects.equals(oldCell, newCell)) {

        if (oldCell != null) {
            cells.getOrDefault(
                    oldCell,
                    Collections.emptySet()
            ).remove(/* dasher */);
        }

        // Add to new cell
    }

    dasherCells.put(
            dasherId,
            newCell
    );
}
```

This is where I'd discuss the tradeoff between:

```text
read performance
       vs
write performance
```

---

# 10. Concurrency

Another very realistic Staff-level question:

> "What happens if one thread updates a Dasher's location while another thread searches?"

You need consistency.

Possible implementation:

```java
ConcurrentHashMap<Cell, Set<Dasher>>
```

with concurrent sets:

```java
ConcurrentHashMap.newKeySet()
```

But I wouldn't just say:

> "Use ConcurrentHashMap."

I'd explain the consistency model.

For example:

> "For dispatching, I don't necessarily need a globally synchronized snapshot. I can tolerate slightly stale location information as long as the final assignment validates that the Dasher is still available and within the acceptable distance."

That's a **much stronger Staff answer**.

---

# 11. The real distributed-system design

If DoorDash asks:

> "How would you build this at scale?"

I'd move beyond the Java class.

```text
             Dasher Location Updates
                       |
                       v
              +----------------+
              | Location       |
              | Service        |
              +----------------+
                       |
                       v
              +----------------+
              | Geo Index      |
              |                |
              | Geohash /      |
              | Spatial index  |
              +----------------+
                       |
                       v
                Dispatch API
                       |
                       v
              Closest Dasher
```

Location updates could be streamed:

```text
Dasher App
    |
    v
Kafka / Event Stream
    |
    v
Location Service
    |
    v
Geo Index
```

Then:

```text
Order
  |
  v
Dispatch Service
  |
  v
Geo Index
  |
  +---- nearby Dashers
  |
  v
Ranking
  |
  v
Availability validation
  |
  v
Assignment
```

---

# 12. One subtle issue: closest ≠ best

This is a very good Staff-level observation.

The closest Dasher isn't necessarily the best Dasher.

You might eventually have:

```text
score =
    distance
    + ETA
    + vehicle type
    + current workload
    + delivery priority
    + Dasher acceptance probability
```

So instead of:

```java
findClosest()
```

I'd potentially evolve the API into:

```java
List<Dasher> findCandidates(
        Location deliveryLocation,
        int limit
);
```

followed by:

```java
Dasher selectBest(
        List<Dasher> candidates,
        Delivery delivery
);
```

This separates:

```text
Candidate retrieval
        ↓
Business ranking
        ↓
Assignment
```

That's a very good architectural boundary.

---

# 13. What I'd actually submit in the interview

Don't start with the spatial index.

Start with this:

```java
public interface DasherFinder {

    Optional<Dasher> findClosest(
            Location location,
            List<Dasher> dashers
    );
}
```

Then:

```java
public class ClosestDasherFinder
        implements DasherFinder {

    @Override
    public Optional<Dasher> findClosest(
            Location location,
            List<Dasher> dashers) {

        Dasher closest = null;
        double bestDistance = Double.MAX_VALUE;

        for (Dasher dasher : dashers) {

            if (!dasher.available()) {
                continue;
            }

            double distance =
                    squaredDistance(
                            location,
                            dasher.location()
                    );

            if (closest == null
                    || distance < bestDistance) {

                closest = dasher;
                bestDistance = distance;
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

**Then stop and test it.**

After it works, ask the interviewer:

> "Would you like me to optimize for a large number of Dashers, or should I focus on additional business requirements?"

That is a fantastic interview move because you're letting the interviewer choose the direction rather than spending 20 minutes implementing something they didn't ask for.

---

## The Staff-level answer I would give verbally

If the interviewer asks:

> "How would you scale this?"

I'd say:

> "The baseline implementation is O(N), which is optimal if the Dashers are provided as an arbitrary list because we have to inspect every candidate. If the system owns the Dasher location data and we're serving a large number of spatial queries, I'd maintain a spatial index such as geohash, grid, KD-tree, or an R-tree. The query would first retrieve nearby candidates and then perform exact distance calculations on those candidates. Since Dasher locations change frequently, I'd optimize for inexpensive location updates and tolerate bounded staleness in the index. Before assigning the Dasher, I'd perform an atomic availability validation because the spatial index is only a candidate-selection mechanism and cannot guarantee that the Dasher is still available."

That answer hits:

**algorithm → scalability → data structure → updates → consistency → concurrency → correctness.**

And that's much closer to what I would expect from a **DoorDash Staff Engineer** than simply writing a `PriorityQueue`.
