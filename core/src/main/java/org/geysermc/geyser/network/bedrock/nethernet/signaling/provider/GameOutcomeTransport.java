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

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.cloudburstmc.netty.signaling.control.AssistedJoin;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticHostPolicy;
import org.geysermc.geyser.GeyserLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Uses the provider client's existing signed, durable ticket-event delivery.
 */
public final class GameOutcomeTransport implements ProviderTransport {
    private final ProviderTransport delegate;
    private final GameOutcomeReporter outcomes;
    private final GeyserLogger logger;
    private final Map<Integer, ConnectivityCheck> reportedChecks = new HashMap<>();
    private long connectivityRevision;

    public GameOutcomeTransport(ProviderTransport delegate, GameOutcomeReporter outcomes, GeyserLogger logger) {
        this.delegate = delegate;
        this.outcomes = outcomes;
        this.logger = logger;
    }

    @Override
    public CompletionStage<JsonObject> hostProfile() {
        return delegate.hostProfile();
    }

    @Override
    public CompletionStage<HostProfileSnapshot> captureHostProfile() {
        return delegate.captureHostProfile();
    }

    @Override
    public long candidatePublicationVersion() {
        return delegate.candidatePublicationVersion();
    }

    @Override
    public CompletionStage<Void> reportConnectivityChecks(long candidateRevision, List<ConnectivityCheck> checks) {
        var delivered = delegate.reportConnectivityChecks(candidateRevision, checks);
        var observed = List.copyOf(checks);
        // Native delivery can successfully ignore a retired revision. Confirm it is still current before logging.
        delivered.thenCompose(ignored -> delegate.captureHostProfile()).thenAccept(snapshot -> {
            if (snapshot.candidateRevision() != candidateRevision) return;
            snapshot.requireCurrent();
            logConnectivityChecks(candidateRevision, observed);
        });
        return delivered;
    }

    private synchronized void logConnectivityChecks(long revision, List<ConnectivityCheck> checks) {
        if (revision < 1 || revision < connectivityRevision) return;
        if (revision != connectivityRevision) {
            connectivityRevision = revision;
            reportedChecks.clear();
        }
        long now = System.currentTimeMillis();
        for (int family : List.of(4, 6)) {
            var fresh = checks.stream().filter(check -> check.family() == family
                    && check.checkedAt() <= now && check.expiresAt() > now).toList();
            // One successful region establishes a usable path for this family.
            var selected = fresh.stream().filter(check -> check.outcome() == ConnectivityOutcome.ESTABLISHED)
                    .max(Comparator.comparingLong(ConnectivityCheck::checkedAt))
                    .orElseGet(() -> fresh.stream().filter(check -> check.outcome() == ConnectivityOutcome.NOT_ESTABLISHED)
                            .max(Comparator.comparingLong(ConnectivityCheck::checkedAt)).orElse(null));
            var previous = reportedChecks.get(family);
            if (selected == null || previous != null && selected.checkedAt() <= previous.checkedAt()) continue;
            reportedChecks.put(family, selected);
            if (previous != null && previous.outcome() == selected.outcome()) continue;
            if (selected.outcome() == ConnectivityOutcome.ESTABLISHED) {
                logger.info("NXS IPv" + family + " connectivity checks established the transport.");
            } else {
                logger.warning("NXS IPv" + family + " connectivity checks could not establish the transport. Assisted joins may still be available.");
            }
        }
    }

    @Override
    public boolean supportsAssistedJoins() {
        return delegate.supportsAssistedJoins();
    }

    @Override
    public CompletionStage<Void> configureStunServers(List<StunServer> servers) {
        return delegate.configureStunServers(servers);
    }

    @Override
    public CompletionStage<String> assistedJoin(AssistedJoin join, Runnable requireCurrent) {
        return delegate.assistedJoin(join, requireCurrent);
    }

    @Override
    public boolean supportsDiagnosticAdmission() {
        return delegate.supportsDiagnosticAdmission();
    }

    @Override
    public CompletionStage<Void> configureDiagnostics(DiagnosticHostPolicy policy) {
        return delegate.configureDiagnostics(policy);
    }

    @Override
    public CompletionStage<Void> configureDiagnostics(DiagnosticHostPolicy policy, long deadlineNanos) {
        return delegate.configureDiagnostics(policy, deadlineNanos);
    }

    @Override
    public CompletionStage<Void> disableDiagnostics() {
        return delegate.disableDiagnostics();
    }

    @Override
    public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) {
        return delegate.installTicketKeys(keys);
    }

    @Override
    public CompletionStage<ApplyResult> applyState(String state) {
        return delegate.applyState(state);
    }

    @Override
    public boolean supportsGameOutcomes() {
        return true;
    }

    @Override
    public List<JsonObject> pollEvents() {
        List<JsonObject> batch = new ArrayList<>(delegate.pollEvents());
        outcomes.drainTo(batch, Math.max(0, 100 - batch.size()));
        return batch;
    }

    @Override
    public CompletionStage<Void> drain() {
        return delegate.drain();
    }

    @Override
    public CompletionStage<Void> close() {
        outcomes.close();
        return delegate.close();
    }
}
