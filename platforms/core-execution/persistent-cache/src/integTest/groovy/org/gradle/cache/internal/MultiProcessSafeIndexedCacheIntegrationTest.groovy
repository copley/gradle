/*
 * Copyright 2026 the original author or authors.
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

package org.gradle.cache.internal

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.gradle.test.precondition.Requires
import org.gradle.test.preconditions.TestExecutionPreconditions
import org.junit.Rule

@Requires(value = TestExecutionPreconditions.NotEmbeddedExecutor, reason = "requires two independent Gradle processes")
class MultiProcessSafeIndexedCacheIntegrationTest extends AbstractIntegrationSpec {
    @Rule
    BlockingHttpServer server = new BlockingHttpServer()

    def setup() {
        server.start()
        executer.requireOwnGradleUserHomeDir().withDaemonBaseDir(file("daemon")).requireDaemon()
    }

    def "stale conditional writer cannot overwrite an invalidation from another process"() {
        given:
        def cacheDir = file("shared-cache")
        buildFile << """
            import org.gradle.cache.FileLockManager
            import org.gradle.cache.IndexedCacheParameters
            import org.gradle.cache.MultiProcessSafeIndexedCache
            import org.gradle.cache.UnscopedCacheBuilderFactory

            abstract class CacheOperation extends DefaultTask {
                @Inject
                abstract UnscopedCacheBuilderFactory getCacheBuilderFactory()

                @Input
                abstract Property<String> getOperation()

                @Input
                abstract Property<String> getCachePath()

                @TaskAction
                void runOperation() {
                    def cache = cacheBuilderFactory
                        .cache(new File(cachePath.get()))
                        .withDisplayName("conditional update integration test cache")
                        .withInitialLockMode(FileLockManager.LockMode.OnDemand)
                        .open()
                    try {
                        def indexedCache = (MultiProcessSafeIndexedCache<String, String>) cache.createIndexedCache(
                            IndexedCacheParameters.of("entries", String.class, String.class)
                        )
                        def projectDir = new File(cachePath.get()).parentFile
                        switch (operation.get()) {
                            case "seed":
                                cache.useCache {
                                    indexedCache.put("key", "initial")
                                }
                                break
                            case "staleWriter":
                                def expected = cache.useCache {
                                    indexedCache.getIfPresent("key")
                                }
                                assert expected == "initial"
                                new File(projectDir, "writer.pid").text = ProcessHandle.current().pid().toString()
                                ${server.callFromBuild("writerLoaded")}
                                def stored = cache.useCache {
                                    indexedCache.putIf(
                                        "key",
                                        "stale",
                                        { current -> current == expected } as java.util.function.Predicate<String>
                                    )
                                }
                                println "STALE_STORE_RESULT=" + stored
                                break
                            case "invalidate":
                                new File(projectDir, "invalidator.pid").text = ProcessHandle.current().pid().toString()
                                cache.useCache {
                                    indexedCache.remove("key")
                                }
                                break
                            case "assertAbsent":
                                cache.useCache {
                                    assert indexedCache.getIfPresent("key") == null
                                }
                                break
                            default:
                                throw new GradleException("Unknown operation: " + operation.get())
                        }
                    } finally {
                        cache.close()
                    }
                }
            }

            tasks.register("seed", CacheOperation) {
                operation.set("seed")
                cachePath.set("${cacheDir.absolutePath}")
            }
            tasks.register("staleWriter", CacheOperation) {
                operation.set("staleWriter")
                cachePath.set("${cacheDir.absolutePath}")
            }
            tasks.register("invalidate", CacheOperation) {
                operation.set("invalidate")
                cachePath.set("${cacheDir.absolutePath}")
            }
            tasks.register("assertAbsent", CacheOperation) {
                operation.set("assertAbsent")
                cachePath.set("${cacheDir.absolutePath}")
            }
        """

        and:
        succeeds("seed")
        def writerLoaded = server.expectAndBlock("writerLoaded")

        when:
        def staleWriter = executer.withTasks("staleWriter").start()
        writerLoaded.waitForAllPendingCalls()
        succeeds("invalidate")
        writerLoaded.releaseAll()
        staleWriter.waitForFinish()

        then:
        staleWriter.standardOutput.contains("STALE_STORE_RESULT=false")
        file("writer.pid").text != file("invalidator.pid").text

        and:
        succeeds("assertAbsent")
    }
}
