package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("MeTube", appName)
  }

  @Test
  fun `adblock engine detects ad urls`() {
    val doubleClickUri = android.net.Uri.parse("https://googleads.g.doubleclick.net/pagead/ads")
    val regularUri = android.net.Uri.parse("https://music.youtube.com/watch?v=12345")
    val ytTelemetryUri = android.net.Uri.parse("https://www.youtube.com/api/stats/ads?v=12345")

    org.junit.Assert.assertTrue(AdBlockEngine.shouldBlock(doubleClickUri))
    org.junit.Assert.assertTrue(AdBlockEngine.shouldBlock(ytTelemetryUri))
    org.junit.Assert.assertFalse(AdBlockEngine.shouldBlock(regularUri))
  }
}
