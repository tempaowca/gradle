/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.launcher.daemon.server

import org.gradle.internal.remote.Address
import org.gradle.launcher.daemon.context.DaemonCompatibilitySpec
import org.gradle.launcher.daemon.context.DaemonContext
import org.gradle.launcher.daemon.registry.DaemonInfo
import org.gradle.launcher.daemon.registry.DaemonRegistry
import org.gradle.launcher.daemon.registry.EmbeddedDaemonRegistry
import org.gradle.launcher.daemon.server.api.DaemonStateControl
import org.gradle.util.MockTimeProvider
import spock.lang.Specification

import static org.gradle.launcher.daemon.server.DuplicateIdleDaemonExpirationStrategy.IDLE_COMPATIBLE_TIMEOUT
import static org.gradle.launcher.daemon.server.api.DaemonStateControl.State.*
import static org.gradle.launcher.daemon.server.expiry.DaemonExpirationStatus.GRACEFUL_EXPIRE

class DuplicateIdleDaemonExpirationStrategyTest extends Specification {
    DaemonRegistry registry
    DaemonCompatibilitySpec compatibilitySpec = Stub()
    def compatible = []
    def compatibleWithCurrent = []

    def setup() {
        // Start with a new registry on each test.
        registry = new EmbeddedDaemonRegistry()
        _ * compatibilitySpec.isSatisfiedBy(_) >> { DaemonContext context -> context in compatibleWithCurrent }
    }

    def "expires only compatible daemons"() {
        given:
        DaemonInfo d1 = registerDaemon(Idle)
        DaemonInfo d2 = registerDaemon(Idle)
        DaemonInfo d3 = registerDaemon(Idle)

        when:
        compatible = [ d1, d2 ]

        then:
        wouldExpire(d1)
        !wouldExpire(d2)
        !wouldExpire(d3)
    }

    def "expires more than one daemon"() {
        given:
        DaemonInfo d1 = registerDaemon(Idle)
        DaemonInfo d2 = registerDaemon(Idle)
        DaemonInfo d3 = registerDaemon(Idle)

        when:
        compatible = [ d1, d2, d3 ]

        then:
        wouldExpire(d1)
        wouldExpire(d2)
        !wouldExpire(d3)
    }

    def "does not expire when there are no compatible daemons"() {
        given:
        DaemonInfo d1 = registerDaemon(Idle)
        DaemonInfo d2 = registerDaemon(Idle)
        DaemonInfo d3 = registerDaemon(Idle)

        when:
        compatible = []

        then:
        !wouldExpire(d1)
        !wouldExpire(d2)
        !wouldExpire(d3)
    }

    def "only expires daemons that are idle"() {
        given:
        DaemonInfo d1 = registerDaemon(Busy)
        DaemonInfo d2 = registerDaemon(Idle)
        DaemonInfo d3 = registerDaemon(Idle)

        when:
        compatible = [ d1, d2, d3 ]

        then:
        !wouldExpire(d1)
        wouldExpire(d2)
        !wouldExpire(d3)
    }

    def "does not expire when there are no idle daemons"() {
        given:
        DaemonInfo d1 = registerDaemon(Busy)
        DaemonInfo d2 = registerDaemon(Busy)
        DaemonInfo d3 = registerDaemon(Busy)

        when:
        compatible = [ d1, d2, d3 ]

        then:
        !wouldExpire(d1)
        !wouldExpire(d2)
        !wouldExpire(d3)
    }

    def "does not expire when there is only one idle daemon"() {
        given:
        DaemonInfo d1 = registerDaemon(Idle)

        when:
        compatible = [ d1 ]

        then:
        !wouldExpire(d1)
    }

    def "only expires daemons that have reached the timeout"() {
        given:
        DaemonInfo d1 = registerDaemon(Idle)
        DaemonInfo d2 = registerDaemon(Idle)
        DaemonInfo d3 = registerDaemon(Idle)

        when:
        compatible = [ d1, d2, d3 ]

        then:
        wouldExpire(d1, true)
        !wouldExpire(d2, false)
        !wouldExpire(d3, true)
    }

    def "leaves one idle daemon running, even if they have the same last idle time"() {
        given:
        DaemonInfo d1 = registerDaemon(Idle, 1000)
        DaemonInfo d2 = registerDaemon(Idle, 1000)

        when:
        compatible = [ d1, d2 ]

        then:
        wouldExpire(d1) ^ wouldExpire(d2)
    }

    private DaemonInfo registerDaemon(DaemonStateControl.State state, long lastIdle=-1) {
        final String uid = UUID.randomUUID().toString()
        final int id = registry.getAll().size() + 1
        final long lastIdleTime = lastIdle == -1L ? id * 1000 : lastIdle;
        Address daemonAddress = createAddress(id)
        DaemonContext context = Mock(DaemonContext) {
            _ * getUid() >> uid
        }
        DaemonInfo info = new DaemonInfo(daemonAddress, context, "password".bytes, Busy, new MockTimeProvider(lastIdleTime))
        info.setState(state)
        registry.store(info)
        return info
    }

    private static Address createAddress(int i) {
        new Address() {
            int getNum() { i }
            String getDisplayName() { getNum() }
        }
    }

    private boolean wouldExpire(DaemonInfo info, timeoutReached = true) {
        Daemon daemon = Mock(Daemon) {
            1 * getDaemonRegistry() >> { registry }
            _ * getDaemonContext() >> { info.getContext() }
            _ * getStateCoordinator() >> Stub(DaemonStateCoordinator) {
                getState() >> info.state
                getIdleMillis(_) >> { long now -> return IDLE_COMPATIBLE_TIMEOUT + (timeoutReached ?  1 : -1) }
            }
        }

        if (info in compatible) {
            compatibleWithCurrent = compatible.collect { it.context }
        } else {
            compatibleWithCurrent = []
        }

        return new DuplicateIdleDaemonExpirationStrategy(daemon, compatibilitySpec).checkExpiration().status == GRACEFUL_EXPIRE
    }
}
