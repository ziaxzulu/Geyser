/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.network.bedrock.nethernet.signaling.provider;

import com.google.gson.JsonArray;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityCheck;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome;
import org.cloudburstmc.netty.signaling.ProviderTransport.HostProfileSnapshot;
import org.geysermc.geyser.GeyserLogger;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Operator-facing observations of host policy; this reporter never changes serving or admission. */
final class ConnectivityReporter {
    private record Key(String region, int family, String method) { }
    private final GeyserLogger logger;
    private final boolean assisted, diagnostics, warming;
    private final String port;
    private final Map<Key, ConnectivityCheck> reported = new HashMap<>();
    private final Map<Integer, String> publications = new HashMap<>();
    private long revision;
    private boolean announced;

    ConnectivityReporter(GeyserLogger logger, boolean assisted, boolean diagnostics, boolean warming, int udpPort) {
        this.logger = logger;
        this.assisted = assisted;
        this.diagnostics = diagnostics;
        this.warming = warming;
        this.port = udpPort > 0 ? Integer.toString(udpPort) : "the NetherNet gameplay port";
    }

    synchronized void publication(HostProfileSnapshot snapshot) {
        snapshot.requireCurrent();
        if (!announced) {
            announced = true;
            logger.info("NXS connectivity policy: assisted joins " + (assisted ? "enabled" : "disabled")
                + "; maintenance checks " + (diagnostics ? "enabled" : "disabled")
                + "; background STUN " + (assisted ? "replaced by discovery during each assisted join" : warming ? "enabled for automatically discovered endpoints" : "disabled") + ".");
            if (!diagnostics) logger.warning("NXS connectivity checks are disabled; offered endpoints cannot be verified or withdrawn after probe failures. "
                + "Set diagnostic-admission: true under bedrock.signaling.nxs to enable maintenance checks.");
        }
        for (int family : List.of(4, 6)) {
            var offered = endpoints(snapshot.profile().getAsJsonArray("candidates"), family);
            var probes = endpoints(snapshot.probeCandidates(), family);
            boolean assist = assisted && snapshot.assistedFamilies().contains(family);
            String decision = "offered=" + offered + "; test targets=" + probes + "; assisted=" + assist;
            if (decision.equals(publications.put(family, decision))) continue;
            logger.info("NXS IPv" + family + " publication: " + decision + ". "
                + (assist ? "Public peers can also be discovered during assisted joins. " : "")
                + "Private addresses are usable only on reachable LAN/VPN networks. "
                + (probes.stream().anyMatch(target -> !offered.contains(target))
                    ? "Withdrawn endpoints remain under maintenance checks; a successful check restores the endpoint." : ""));
        }
    }

    synchronized void checks(HostProfileSnapshot snapshot, List<ConnectivityCheck> checks) {
        snapshot.requireCurrent();
        if (snapshot.candidateRevision() < revision) return;
        if (snapshot.candidateRevision() != revision) {
            revision = snapshot.candidateRevision();
            reported.clear();
        }
        publication(snapshot);
        long now = System.currentTimeMillis();
        for (var check : checks.stream().sorted(java.util.Comparator.comparingLong(ConnectivityCheck::checkedAt)).toList()) {
            if (check.checkedAt() > now || check.expiresAt() <= now) continue;
            var key = new Key(check.region(), check.family(), check.method());
            var previous = reported.get(key);
            if (previous != null && previous.checkedAt() >= check.checkedAt()) continue;
            reported.put(key, check);
            String result = switch (check.outcome()) {
                case ESTABLISHED -> "passed";
                case NOT_ESTABLISHED -> "failed";
                case UNKNOWN -> "was inconclusive";
                case UNAVAILABLE -> "was unavailable";
            };
            String message = "NXS IPv" + check.family() + " " + method(check.method()) + " check from " + check.region()
                + (check.target() == null ? " (no public peer recorded)" : " to " + endpoint(check.target()))
                + " " + result + " at " + Instant.ofEpochMilli(check.checkedAt()) + ". ";
            boolean assist = assisted && snapshot.assistedFamilies().contains(check.family());
            boolean offered = check.target() != null && endpoints(snapshot.profile().getAsJsonArray("candidates"), check.family()).contains(endpoint(check.target()));
            if (check.outcome() == ConnectivityOutcome.ESTABLISHED) {
                message += "Transport established from this region; other client networks can differ. "
                    + (assist ? "Assisted joins remain available." : offered ? "The tested endpoint is offered to players." : "The current publication is shown above.");
                logger.info(message);
            } else if (check.outcome() == ConnectivityOutcome.NOT_ESTABLISHED) {
                message += assist
                    ? "Assisted IPv" + check.family() + " remains available. Clients on networks similar to this probe are unlikely to connect; clients with a public address or less restrictive NAT may still connect."
                    : check.target() == null ? "No tested public endpoint was recorded, so this result does not withdraw an endpoint."
                    : offered ? "The endpoint remains offered because another region has a successful check."
                    : "The failed public endpoint is withheld from player offers. Maintenance checks continue; a successful check restores it.";
                // Every completed check is logged; repeat long configuration advice only on a result transition.
                if (previous == null || previous.outcome() != check.outcome() || !java.util.Objects.equals(previous.target(), check.target())) message += " " + guidance(check.family(), assist);
                logger.warning(message);
            } else {
                logger.warning(message + "This does not establish a connectivity failure; the publication decision is unchanged. "
                    + "Check provider/control connectivity and diagnostic-admission; wait for the next completed check.");
            }
        }
    }

    private String guidance(int family, boolean assist) {
        return "Next: check the host firewall for UDP " + port + (family == 4
            ? "; forward that UDP port through each NAT and set advertise-addresses to the reachable public endpoint when using a fixed mapping. If behind CGNAT, request a public IPv4 address from the ISP or use public IPv6."
            : "; verify that the advertised IPv6 address is globally routed and that the router allows inbound UDP. IPv6 does not need IPv4 port forwarding.")
            + (!assist ? " Alternatively, set assisted-joins: true and control-transport: auto under bedrock.signaling.nxs; assistance may help but cannot guarantee a connection." : "");
    }

    private static List<String> endpoints(JsonArray candidates, int family) {
        if (candidates == null) return List.of();
        return candidates.asList().stream().map(value -> value.getAsJsonObject()).filter(value ->
            value.has("address") && value.has("port") && (value.get("address").getAsString().contains(":") ? 6 : 4) == family)
            .map(value -> {
                String address = value.get("address").getAsString();
                return (family == 6 ? "[" + address + "]" : address) + ":" + value.get("port").getAsInt();
            }).toList();
    }
    private static String endpoint(InetSocketAddress target) {
        String address = target.getAddress().getHostAddress();
        return (address.contains(":") ? "[" + address + "]" : address) + ":" + target.getPort();
    }
    private static String method(String value) {
        return switch (value) { case "per_join" -> "Assisted"; case "warm_stun" -> "Warm STUN"; default -> "Direct"; };
    }
}
