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

package org.gradle.launcher.daemon.server.exec

import org.gradle.initialization.BuildCancellationToken
import org.gradle.launcher.daemon.server.api.DaemonCommandExecution
import org.gradle.launcher.daemon.server.api.DaemonConnection
import org.gradle.launcher.daemon.server.api.DaemonStateControl
import spock.lang.Specification

class WatchForDisconnectionTest extends Specification {
    def execution = Mock(DaemonCommandExecution)
    def connection = Mock(DaemonConnection)
    def stateControl = Mock(DaemonStateControl)
    def cancellationToken = Mock(BuildCancellationToken)
    def action = new WatchForDisconnection()

    def "cancels current build token before requesting daemon cancellation on disconnect"() {
        given:
        execution.connection >> connection
        execution.daemonStateControl >> stateControl
        stateControl.cancellationToken >> cancellationToken
        connection.onDisconnect(_ as Runnable) >> { Runnable handler -> handler.run() }

        when:
        action.execute(execution)

        then:
        1 * cancellationToken.cancel()

        then:
        1 * stateControl.requestCancel()

        then:
        1 * execution.proceed()
        1 * connection.onDisconnect(null)
    }

    def "still requests daemon cancellation when immediate token cancellation fails"() {
        given:
        execution.connection >> connection
        execution.daemonStateControl >> stateControl
        stateControl.cancellationToken >> cancellationToken
        connection.onDisconnect(_ as Runnable) >> { Runnable handler -> handler.run() }
        cancellationToken.cancel() >> { throw new RuntimeException("cancel callback failed") }

        when:
        action.execute(execution)

        then:
        1 * stateControl.requestCancel()
        1 * execution.proceed()
        1 * connection.onDisconnect(null)
    }
}
