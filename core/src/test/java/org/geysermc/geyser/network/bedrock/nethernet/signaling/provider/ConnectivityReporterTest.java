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

import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.geysermc.geyser.GeyserLogger;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectivityReporterTest {
    private static final String TARGET = "[{\"address\":\"79.110.171.16\",\"port\":19133}]";
    private static ProviderTransport.HostProfileSnapshot snapshot(String offers, String probes, boolean assisted) {
        var profile = JsonParser.parseString("{\"candidates\":" + offers + "}").getAsJsonObject();
        return new ProviderTransport.HostProfileSnapshot(profile, 1, 1,
            JsonParser.parseString(probes).getAsJsonArray(), assisted ? Set.of(4, 6) : Set.of(), () -> { });
    }
    private static ProviderTransport.ConnectivityCheck check(String region, String method, ProviderTransport.ConnectivityOutcome outcome, long time) {
        return new ProviderTransport.ConnectivityCheck(region, 4, method, new InetSocketAddress("79.110.171.16", 19133), outcome, time, time + 60000);
    }
    @Test void logsEveryNewRegionalCheckOnceAndKeepsAssistanceAvailable() {
        var logger = mock(GeyserLogger.class);
        var reporter = new ConnectivityReporter(logger, true, true, true, 19133);
        var snapshot = snapshot("[]", "[]", true);
        long now = System.currentTimeMillis() - 5000;
        var lim = check("lim1", "per_join", ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now);
        var vin = check("vin1", "per_join", ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now + 1);
        reporter.checks(snapshot, List.of(lim, vin));
        reporter.checks(snapshot, List.of(lim, vin));
        verify(logger, times(2)).warning(contains("Assisted IPv4 remains available"));
        verify(logger).warning(contains("check from lim1 to 79.110.171.16:19133 failed at"));
        verify(logger).warning(contains("check from vin1 to 79.110.171.16:19133 failed at"));
        verify(logger, times(2)).warning(contains("less restrictive NAT may still connect"));
        verify(logger, times(2)).warning(contains("UDP 19133"));
        reporter.checks(snapshot, List.of(check("lim1", "per_join", ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now + 2)));
        verify(logger, times(3)).warning(contains("Assisted IPv4 remains available"));
        verify(logger, times(2)).warning(contains("Next:"));
    }
    @Test void explainsWithdrawalRecoveryAndTheCorrectConfiguration() {
        var logger = mock(GeyserLogger.class);
        var reporter = new ConnectivityReporter(logger, false, true, true, 19133);
        long now = System.currentTimeMillis() - 5000;
        reporter.checks(snapshot("[]", TARGET, false), List.of(check("lim1", "warm_stun", ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now)));
        verify(logger).warning(contains("failed public endpoint is withheld"));
        verify(logger).warning(contains("assisted-joins: true and control-transport: auto under bedrock.signaling.nxs"));
        verify(logger).warning(contains("CGNAT"));
        reporter.checks(snapshot(TARGET, TARGET, false), List.of(check("lim1", "warm_stun", ProviderTransport.ConnectivityOutcome.ESTABLISHED, now + 1)));
        verify(logger).info(contains("Warm STUN check from lim1 to 79.110.171.16:19133 passed"));
        verify(logger).info(contains("tested endpoint is offered to players"));
    }
    @Test void partialRegionalFailureDoesNotClaimWithdrawal() {
        var logger = mock(GeyserLogger.class);
        var reporter = new ConnectivityReporter(logger, false, true, true, 19133);
        reporter.checks(snapshot(TARGET, TARGET, false), List.of(check("vin1", "discovered", ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, System.currentTimeMillis() - 1)));
        verify(logger).warning(contains("another region has a successful check"));
        verify(logger, never()).warning(contains("is withheld"));
    }
    @Test void inconclusiveUnavailableAndExpiredChecksCannotClaimNetworkFailure() {
        var logger = mock(GeyserLogger.class);
        var reporter = new ConnectivityReporter(logger, false, true, false, 19133);
        long now = System.currentTimeMillis();
        reporter.checks(snapshot(TARGET, TARGET, false), List.of(
            check("lim1", "discovered", ProviderTransport.ConnectivityOutcome.UNKNOWN, now - 500),
            check("vin1", "discovered", ProviderTransport.ConnectivityOutcome.UNAVAILABLE, now - 400),
            check("old", "discovered", ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now - 60001),
            check("future", "discovered", ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now + 10000)));
        verify(logger, times(2)).warning(contains("publication decision is unchanged"));
        verify(logger, never()).warning(contains("failed at"));
    }
    @Test void ipv6FailureGivesIpv6FirewallAdvice() throws Exception {
        var logger = mock(GeyserLogger.class);
        var reporter = new ConnectivityReporter(logger, false, true, true, 19133);
        long now = System.currentTimeMillis();
        var target = new InetSocketAddress(java.net.InetAddress.getByName("2606:4700:4700::1111"), 19133);
        reporter.checks(snapshot("[]", "[]", false), List.of(new ProviderTransport.ConnectivityCheck("lim1", 6, "discovered", target,
            ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now, now + 60000)));
        verify(logger).warning(contains("IPv6 does not need IPv4 port forwarding"));
        verify(logger).warning(contains("[2606:4700:4700:"));
    }
    @Test void disabledDiagnosticsAndStaleSnapshotsAreExplicit() {
        var logger = mock(GeyserLogger.class);
        var reporter = new ConnectivityReporter(logger, false, false, false, 19133);
        var snapshot = snapshot(TARGET, TARGET, false);
        reporter.publication(snapshot);
        reporter.publication(snapshot);
        verify(logger, times(1)).warning(contains("diagnostic-admission: true"));
        var retired = new ProviderTransport.HostProfileSnapshot(snapshot.profile(), 1, () -> { throw new IllegalStateException("retired"); });
        assertThrows(IllegalStateException.class, () -> reporter.checks(retired, List.of()));
    }
}
