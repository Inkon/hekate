package io.hekate.cluster.internal.gossip;

import io.hekate.cluster.ClusterAddress;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public class GossipNodeFailure {
    private final ClusterAddress address;

    private final Instant timestamp;

    public GossipNodeFailure(ClusterAddress address, Instant timestamp) {
        assert address != null : "Address is null.";
        assert timestamp != null : "Timestamp is null.";

        this.address = address;
        this.timestamp = timestamp;
    }

    public ClusterAddress address() {
        return address;
    }

    public Instant timestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof GossipNodeFailure)) {
            return false;
        }

        GossipNodeFailure failure = (GossipNodeFailure)o;

        return Objects.equals(address, failure.address);

    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "["
            + "address=" + address + ", "
            + "timestamp=" + DateTimeFormatter.ISO_INSTANT.format(timestamp)
            + ']';
    }
}
