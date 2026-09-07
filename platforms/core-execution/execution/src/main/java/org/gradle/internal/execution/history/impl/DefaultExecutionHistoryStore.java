/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.execution.history.impl;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Interner;
import org.gradle.cache.CacheDecorator;
import org.gradle.cache.IndexedCacheParameters;
import org.gradle.cache.MultiProcessSafeIndexedCache;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.internal.execution.history.AfterExecutionState;
import org.gradle.internal.execution.history.ExecutionHistoryStore;
import org.gradle.internal.execution.history.PreviousExecutionState;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.serialize.HashCodeSerializer;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableSortedMap.copyOfSorted;
import static com.google.common.collect.Maps.transformValues;

public class DefaultExecutionHistoryStore implements ExecutionHistoryStore {

    private static final String TRACE_FILE_ENV = "GRADLE_38985_TRACE_FILE";

    private final MultiProcessSafeIndexedCache<String, PreviousExecutionState> store;

    @SuppressWarnings("unchecked")
    public DefaultExecutionHistoryStore(
        Supplier<PersistentCache> cache,
        InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory,
        Interner<String> stringInterner,
        ClassLoaderHierarchyHasher classLoaderHasher
    ) {
        DefaultPreviousExecutionStateSerializer serializer = new DefaultPreviousExecutionStateSerializer(
            new FileCollectionFingerprintSerializer(stringInterner),
            new FileSystemSnapshotSerializer(stringInterner),
            classLoaderHasher,
            new HashCodeSerializer()
        );

        CacheDecorator inMemoryCacheDecorator = inMemoryCacheDecoratorFactory.decorator(10000, false);
        this.store = (MultiProcessSafeIndexedCache<String, PreviousExecutionState>) cache.get().createIndexedCache(
            IndexedCacheParameters.of("executionHistory", String.class, serializer)
            .withCacheDecorator(inMemoryCacheDecorator)
        );
    }

    @Override
    public Optional<PreviousExecutionState> load(String key) {
        PreviousExecutionState currentState = store.getIfPresent(key);
        trace("LOAD", key, "current=" + origin(currentState));
        return Optional.ofNullable(currentState);
    }

    @Override
    public void store(String key, AfterExecutionState executionState) {
        PreviousExecutionState newState = toPreviousExecutionState(executionState);
        trace("STORE", key, "new=" + origin(newState));
        store.put(key, newState);
    }

    @Override
    public boolean storeIfUnchanged(String key, Optional<PreviousExecutionState> expectedState, AfterExecutionState executionState) {
        PreviousExecutionState newState = toPreviousExecutionState(executionState);
        String expectedOrigin = origin(expectedState);
        String[] currentOrigin = new String[] {"<not-compared>"};
        boolean[] matches = new boolean[] {false};

        boolean stored = store.putIf(
            key,
            newState,
            currentState -> {
                currentOrigin[0] = origin(currentState);
                matches[0] = sameHistoryEntry(Optional.ofNullable(currentState), expectedState);
                return matches[0];
            }
        );

        trace(
            "CAS",
            key,
            "expected=" + expectedOrigin
                + " current=" + currentOrigin[0]
                + " new=" + origin(newState)
                + " match=" + matches[0]
                + " stored=" + stored
        );
        return stored;
    }

    @Override
    public void remove(String key) {
        trace("REMOVE_BEGIN", key, "");
        store.remove(key);
        trace("REMOVE_RETURN", key, "");
    }

    private static String origin(Optional<PreviousExecutionState> state) {
        return state.isPresent() ? origin(state.get()) : "<absent>";
    }

    private static String origin(PreviousExecutionState state) {
        return state == null ? "<absent>" : state.getOriginMetadata().getBuildInvocationId();
    }

    private static void trace(String event, String key, String details) {
        String traceFile = System.getenv(TRACE_FILE_ENV);
        if (traceFile == null || traceFile.isEmpty()) {
            return;
        }

        String line = System.currentTimeMillis()
            + "\t" + ManagementFactory.getRuntimeMXBean().getName()
            + "\t" + event
            + "\t" + key
            + "\t" + details
            + System.lineSeparator();
        try {
            Files.write(
                Paths.get(traceFile),
                line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Diagnostic branch only: tracing must never affect the build under test.
        }
    }

    private static boolean sameHistoryEntry(Optional<PreviousExecutionState> currentState, Optional<PreviousExecutionState> expectedState) {
        if (!currentState.isPresent() || !expectedState.isPresent()) {
            return !currentState.isPresent() && !expectedState.isPresent();
        }
        PreviousExecutionState current = currentState.get();
        PreviousExecutionState expected = expectedState.get();
        return current.getCacheKey().equals(expected.getCacheKey())
            && current.getOriginMetadata().equals(expected.getOriginMetadata())
            && current.isSuccessful() == expected.isSuccessful();
    }

    private static PreviousExecutionState toPreviousExecutionState(AfterExecutionState executionState) {
        return new DefaultPreviousExecutionState(
            executionState.getOriginMetadata(),
            executionState.getCacheKey(),
            executionState.getImplementation(),
            executionState.getAdditionalImplementations(),
            executionState.getInputProperties(),
            prepareForSerialization(executionState.getInputFileProperties()),
            executionState.getOutputFilesProducedByWork(),
            executionState.isSuccessful()
        );
    }

    private static ImmutableSortedMap<String, FileCollectionFingerprint> prepareForSerialization(ImmutableSortedMap<String, CurrentFileCollectionFingerprint> fingerprints) {
        return copyOfSorted(transformValues(
            fingerprints,
            value -> value.archive(SerializableFileCollectionFingerprint::new)
        ));
    }
}
