/*
 * Copyright (c) 2025 Brayan Oliveira <69634269+brayandso@users.noreply.github.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.worker

import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.NOTIFICATION_MIN_DELAY_MS
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.notifications.NotificationId
import com.ichi2.utils.Permissions
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import net.ankiweb.rsdroid.Backend
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncMediaWorkerTest : RobolectricTest() {
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var backend: Backend

    @After
    override fun tearDown() {
        super.tearDown()
        unmockkAll()
    }

    @Test
    @Suppress("SimplifyBooleanWithConstants")
    fun `notification update delay is not lower than min delay`() {
        assert(SyncMediaWorker.NOTIFICATION_UPDATE_RATE_MS >= NOTIFICATION_MIN_DELAY_MS)
    }

    @Test
    fun `doWork cancels notification when error happens after notification is shown`() =
        runTest {
            mockkObject(Permissions)
            every { Permissions.canPostNotifications(any()) } returns true

            mockkStatic(NotificationManagerCompat::class)
            notificationManager = mockk(relaxed = true)
            every { NotificationManagerCompat.from(any()) } returns notificationManager

            mockkObject(CollectionManager)
            backend = mockk(relaxed = true)
            every { CollectionManager.getColUnsafe().backend } returns backend
            every { CollectionManager.getBackend() } returns backend

            // Simulate an error after the notification would be shown by throwing from mediaSyncStatus()
            every { backend.mediaSyncStatus() } throws IllegalStateException("simulated failure after notification")

            val inputData =
                Data
                    .Builder()
                    .putString("hkey", "test-hkey")
                    .build()

            val worker =
                TestListenableWorkerBuilder<SyncMediaWorker>(targetContext)
                    .setInputData(inputData)
                    .build()

            worker.doWork()

            verify { notificationManager.cancel(NotificationId.SYNC_MEDIA) }
        }
}
