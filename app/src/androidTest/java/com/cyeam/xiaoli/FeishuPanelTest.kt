package com.cyeam.xiaoli

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeishuPanelTest {
    @Test fun bundledSdkOpensOnlyTheSelectedChannelInTheOfficialFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var panel: FeishuPanel
        instrumentation.runOnMainSync {
            panel = FeishuPanel(instrumentation.targetContext, FeishuChannel("测试", "example-channel"))
        }
        try {
            val result = ArrayBlockingQueue<String>(1)
            val expected = "https://aily.feishu.cn/custom_agent/cui?channelToken=example-channel&__parentOrigin=https%3A%2F%2F$FEISHU_PANEL_HOST"
            var source = ""
            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
            while (source != expected && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    (panel.getChildAt(0) as WebView).evaluateJavascript(
                        "document.querySelector('iframe')?.src || ''"
                    ) { result.offer(it) }
                }
                source = org.json.JSONTokener(result.poll(2, TimeUnit.SECONDS) ?: "null").nextValue().toString()
                if (source != expected) Thread.sleep(100)
            }
            assertEquals(expected, source)
        } finally {
            instrumentation.runOnMainSync { panel.close() }
        }
    }
}
