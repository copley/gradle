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
import org.gradle.cache.IndexedCache;
import org.gradle.cache.IndexedCacheParameters;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.internal.execution.history.AfterExecutionState;
import org.gradle.internal.execution.history.ExecutionHistoryStore;
import org.gradle.internal.execution.history.PreviousExecutionState;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.serialize.HashCodeSerializer;

import java.util.Optional;
import java.util.function.Supplier;

import static com.google.common.collect.ImmutableSortedMap.copyOfSorted;
import static com.google.common.collect.Maps.transformValues;

public class DefaultExecutionHistoryStore implements ExecutionHistoryStore {

    private final PersistentCache cache;
    private final IndexedCache<String, PreviousExecutionState> store;

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
        this.cache = cache.get();
        this.store = this.cache.createIndexedCache(
            IndexedCacheParameters.of("executionHistory", String.class, serializer)
            .withCacheDecorator(inMemoryCacheDecorator)
        );
    }

    @Override
    public Optional<PreviousExecutionState> load(String key) {
        return Optional.ofNullable(store.getIfPresent(key));
    }

    @Override
    public void store(String key, AfterExecutionState executionState) {
        store.put(key, toPreviousExecutionState(executionState));
    }

    @Override
    public boolean storeIfUnchanged(String key, Optional<PreviousExecutionState> expectedState, AfterExecutionState executionState) {
        return cache.useCache(() -> {
            Optional<PreviousExecutionState> currentState = Optional.ofNullable(store.getIfPresent(key));
            if (!sameHistoryEntry(currentState, expectedState)) {
                return false;
            }
            store.put(key, toPreviousExecutionState(executionState));
            return true;
        });
    }

    @Override
    public void remove(String key) {
        store.remove(key);
    }

    private static boolean sameHistoryEntry(Optional<PreviousExecutionState> currentState, Optional<PreviousExecutionState> expectedState) {
        if (currentState.isEmpty() || expectedState.isEmpty()) {
            return currentState.isEmpty() && expectedState.isEmpty();
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
