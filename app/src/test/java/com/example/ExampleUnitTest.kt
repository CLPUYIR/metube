package com.example

import com.example.data.downloader.StreamUrlResolver
import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testVideoIdExtractionFromVariousUrls() {
    val id = "dQw4w9WgXcQ"
    assertEquals(id, StreamUrlResolver.extractVideoId("https://music.youtube.com/watch?v=$id"))
    assertEquals(id, StreamUrlResolver.extractVideoId("https://www.youtube.com/watch?v=$id&list=RDAMVM$id"))
    assertEquals(id, StreamUrlResolver.extractVideoId("https://youtu.be/$id"))
    assertEquals(id, StreamUrlResolver.extractVideoId("https://www.youtube.com/embed/$id"))
    assertEquals(id, StreamUrlResolver.extractVideoId(id))
  }

  @Test
  fun testPlaybackTimeFormatting() {
    assertEquals("0:00", MediaPlaybackService.formatMs(0L))
    assertEquals("0:05", MediaPlaybackService.formatMs(5_000L))
    assertEquals("1:15", MediaPlaybackService.formatMs(75_000L))
    assertEquals("3:45", MediaPlaybackService.formatMs(225_000L))
    assertEquals("65:02", MediaPlaybackService.formatMs(3_902_000L))
  }

  @Test
  fun testTrackMetadataProgressFields() {
    val track = TrackMetadata(
      title = "Bohemian Rhapsody",
      artist = "Queen",
      currentTime = "2:30",
      duration = "5:55",
      currentTimeSec = 150f,
      durationSec = 355f,
      isPlaying = true
    )
    val fraction = track.currentTimeSec / track.durationSec
    assertTrue(fraction > 0.4f && fraction < 0.45f)
    assertTrue(track.isPlaying)
  }
}

