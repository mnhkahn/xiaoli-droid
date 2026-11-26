package com.cyeam.xiaoli

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*

/** Only writes fixture connection settings. Start the app normally afterwards,
 * so real gestures use the system frame clock, not a Compose test clock. */
class PerformanceSetupTest {
 @Test fun configureLocalRenderingFixture(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("perfFixture")=="yes")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  Credentials(context).apply{save("http://127.0.0.1:18080","performance-fixture");session("performance")}
  context.getSharedPreferences("connection",0).edit().putString("session","performance").commit()
 }
}
