/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.engine.conformance

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * The engine contract for this run's Gecko engine: GeckoView 159 (engine-gecko) on API 26 and up, or the legacy
 * GeckoView 144 (engine-gecko-legacy, locked to the dashboard's origin) on API 23 and up.
 */
@RunWith(AndroidJUnit4::class)
class GeckoEngineContractTest : EngineContract() {
    override fun subject(ha: FakeHomeAssistant) = Subjects.gecko(ha)
}
