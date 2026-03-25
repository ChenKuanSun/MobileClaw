package ai.affiora.mobileclaw.data

import ai.affiora.mobileclaw.data.model.ContentSegment
import ai.affiora.mobileclaw.data.model.ToolActivity
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ToolActivityTest {

    @Test
    fun `toMarker produces correct format for pending activity`() {
        val activity = ToolActivity(
            toolName = "sms",
            input = "send hello",
            result = null,
            isError = false,
            isPending = true,
        )
        val marker = activity.toMarker()
        assertThat(marker).isEqualTo("\u0000\u001Ftool:sms|send hello|pending|\u001F\u0000")
    }

    @Test
    fun `toMarker produces correct format for success activity`() {
        val activity = ToolActivity(
            toolName = "search",
            input = "query=test",
            result = "found 3 results",
            isError = false,
            isPending = false,
        )
        val marker = activity.toMarker()
        assertThat(marker).isEqualTo("\u0000\u001Ftool:search|query=test|success|found 3 results\u001F\u0000")
    }

    @Test
    fun `toMarker produces correct format for error activity`() {
        val activity = ToolActivity(
            toolName = "http",
            input = "GET https://example.com",
            result = "timeout",
            isError = true,
            isPending = false,
        )
        val marker = activity.toMarker()
        assertThat(marker).isEqualTo("\u0000\u001Ftool:http|GET https://example.com|error|timeout\u001F\u0000")
    }

    @Test
    fun `toMarker escapes special characters`() {
        val activity = ToolActivity(
            toolName = "test",
            input = "has|pipe",
            result = "some result",
            isError = false,
            isPending = false,
        )
        val marker = activity.toMarker()
        assertThat(marker).contains("has\\|pipe")
    }

    @Test
    fun `parseMarkers round-trips correctly`() {
        val original = ToolActivity(
            toolName = "sms",
            input = "send hello",
            result = "sent",
            isError = false,
            isPending = false,
        )
        val marker = original.toMarker()
        val parsed = ToolActivity.parseMarkers(marker)
        assertThat(parsed).hasSize(1)
        val (_, activity) = parsed[0]
        assertThat(activity.toolName).isEqualTo("sms")
        assertThat(activity.input).isEqualTo("send hello")
        assertThat(activity.result).isEqualTo("sent")
        assertThat(activity.isError).isFalse()
        assertThat(activity.isPending).isFalse()
    }

    @Test
    fun `parseMarkers handles error status`() {
        val original = ToolActivity(
            toolName = "http",
            input = "GET /api",
            result = "connection refused",
            isError = true,
            isPending = false,
        )
        val marker = original.toMarker()
        val parsed = ToolActivity.parseMarkers(marker)
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].second.isError).isTrue()
        assertThat(parsed[0].second.isPending).isFalse()
    }

    @Test
    fun `parseMarkers on empty string returns empty list`() {
        val parsed = ToolActivity.parseMarkers("")
        assertThat(parsed).isEmpty()
    }

    @Test
    fun `parseMarkers on text without markers returns empty list`() {
        val parsed = ToolActivity.parseMarkers("Just some plain text here.")
        assertThat(parsed).isEmpty()
    }

    @Test
    fun `splitContent with empty string returns single Text segment`() {
        val segments = ToolActivity.splitContent("")
        assertThat(segments).hasSize(1)
        assertThat(segments[0]).isInstanceOf(ContentSegment.Text::class.java)
        assertThat((segments[0] as ContentSegment.Text).text).isEmpty()
    }

    @Test
    fun `splitContent with no markers returns single Text segment`() {
        val segments = ToolActivity.splitContent("Hello, this is plain text.")
        assertThat(segments).hasSize(1)
        assertThat(segments[0]).isInstanceOf(ContentSegment.Text::class.java)
        assertThat((segments[0] as ContentSegment.Text).text).isEqualTo("Hello, this is plain text.")
    }

    @Test
    fun `splitContent with single marker returns Tool segment`() {
        val activity = ToolActivity(
            toolName = "sms",
            input = "send",
            result = "ok",
            isError = false,
            isPending = false,
        )
        val text = activity.toMarker()
        val segments = ToolActivity.splitContent(text)
        assertThat(segments).hasSize(1)
        assertThat(segments[0]).isInstanceOf(ContentSegment.Tool::class.java)
        assertThat((segments[0] as ContentSegment.Tool).activity.toolName).isEqualTo("sms")
    }

    @Test
    fun `splitContent with text and markers returns correct segmentation`() {
        val activity1 = ToolActivity(
            toolName = "search",
            input = "cats",
            result = "3 results",
            isError = false,
            isPending = false,
        )
        val activity2 = ToolActivity(
            toolName = "sms",
            input = "send",
            result = "sent",
            isError = false,
            isPending = false,
        )
        val text = "Before ${activity1.toMarker()} middle ${activity2.toMarker()} after"
        val segments = ToolActivity.splitContent(text)

        assertThat(segments).hasSize(5)
        assertThat(segments[0]).isInstanceOf(ContentSegment.Text::class.java)
        assertThat((segments[0] as ContentSegment.Text).text).isEqualTo("Before")
        assertThat(segments[1]).isInstanceOf(ContentSegment.Tool::class.java)
        assertThat((segments[1] as ContentSegment.Tool).activity.toolName).isEqualTo("search")
        assertThat(segments[2]).isInstanceOf(ContentSegment.Text::class.java)
        assertThat((segments[2] as ContentSegment.Text).text).isEqualTo("middle")
        assertThat(segments[3]).isInstanceOf(ContentSegment.Tool::class.java)
        assertThat((segments[3] as ContentSegment.Tool).activity.toolName).isEqualTo("sms")
        assertThat(segments[4]).isInstanceOf(ContentSegment.Text::class.java)
        assertThat((segments[4] as ContentSegment.Text).text).isEqualTo("after")
    }

    @Test
    fun `splitContent with multiple adjacent markers and no text between`() {
        val a1 = ToolActivity("t1", "i1", "r1", isError = false, isPending = false)
        val a2 = ToolActivity("t2", "i2", "r2", isError = false, isPending = false)
        val text = "${a1.toMarker()}${a2.toMarker()}"
        val segments = ToolActivity.splitContent(text)

        assertThat(segments).hasSize(2)
        assertThat(segments[0]).isInstanceOf(ContentSegment.Tool::class.java)
        assertThat(segments[1]).isInstanceOf(ContentSegment.Tool::class.java)
        assertThat((segments[0] as ContentSegment.Tool).activity.toolName).isEqualTo("t1")
        assertThat((segments[1] as ContentSegment.Tool).activity.toolName).isEqualTo("t2")
    }
}
