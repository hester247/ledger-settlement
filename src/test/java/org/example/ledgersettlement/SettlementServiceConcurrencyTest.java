package org.example.ledgersettlement;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementServiceConcurrencyTest {

    @Test
    @DisplayName("Concurrent first use across 64 virtual threads loads table once, returns identical complete table, and emits zero VirtualThreadPinned events")
    void concurrentFirstUseIsSafeAndDoesNotPin() throws Exception {
        CompletableFuture.completedFuture(null);

        PaymentEntity entity = new PaymentEntity();
        entity.setId("PAY-1");
        entity.setMerchantId("MR-4471");
        entity.setAmountMinor(128450L);
        entity.setCurrency("GBP");
        entity.setRecordedAt(LocalDateTime.of(2026, 9, 29, 12, 0));
        List<PaymentEntity> seedPayments = List.of(entity);

        PaymentRepository repository = (PaymentRepository) Proxy.newProxyInstance(
                PaymentRepository.class.getClassLoader(),
                new Class<?>[]{PaymentRepository.class},
                (proxy, method, args) -> {
                    if ("findByMerchantId".equals(method.getName())) {
                        return seedPayments;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );

        AtomicInteger loadInvocations = new AtomicInteger(0);
        Map<String, String> expectedTable = Map.of(
                "MR-4471", "GBP",
                "MR-9000", "EUR"
        );

        SettlementService service = new SettlementService(repository, () -> {
            loadInvocations.incrementAndGet();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return expectedTable;
        });

        int threadCount = 64;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicBoolean allVirtual = new AtomicBoolean(true);

        Path jfrFile = Files.createTempFile("concurrency-test-", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(20));
            recording.start();

            try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Map<String, String>>> tableFutures = new ArrayList<>(threadCount);
                List<Future<Long>> settlementFutures = new ArrayList<>(threadCount);

                for (int i = 0; i < threadCount; i++) {
                    tableFutures.add(vthreads.submit(() -> {
                        if (!Thread.currentThread().isVirtual()) {
                            allVirtual.set(false);
                        }
                        readyLatch.countDown();
                        if (!startGate.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting for start gate");
                        }
                        return service.getLookupTable();
                    }));

                    settlementFutures.add(vthreads.submit(() -> {
                        startGate.await(5, TimeUnit.SECONDS);
                        return service.calculateSettlement("MR-4471");
                    }));
                }

                assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
                startGate.countDown();

                for (Future<Map<String, String>> future : tableFutures) {
                    Map<String, String> table = future.get(10, TimeUnit.SECONDS);
                    assertThat(table)
                            .isNotNull()
                            .containsExactlyInAnyOrderEntriesOf(expectedTable);
                }

                for (Future<Long> future : settlementFutures) {
                    assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(124469L);
                }
            } finally {
                service.shutdownExecutor();
            }

            recording.stop();
            recording.dump(jfrFile);

            List<RecordedEvent> pinnedEvents = RecordingFile.readAllEvents(jfrFile).stream()
                    .filter(e -> "jdk.VirtualThreadPinned".equals(e.getEventType().getName()))
                    .toList();

            assertThat(allVirtual.get()).isTrue();
            assertThat(loadInvocations.get()).isEqualTo(1);
            assertThat(pinnedEvents).isEmpty();
        } finally {
            Files.deleteIfExists(jfrFile);
        }
    }
}
