package com.cyeam.xiaoli

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.*

class MathRenderingTest {
 @Test fun formulasProduceRealDrawableRatherThanRawSource(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val error=AtomicReference<Throwable?>()
  val markwon=Markwon.builder(context).usePlugin(MarkwonInlineParserPlugin.create()).usePlugin(JLatexMathPlugin.create(30f){builder->builder.inlinesEnabled(true).errorHandler{_,failure->error.set(failure);null}}).build()
  val rendered=markwon.toMarkdown("\$\$\nx^2 + y^2 = z^2\n\$\$")
  val spans=rendered.getSpans(0,rendered.length,AsyncDrawableSpan::class.java)
  Assert.assertTrue("No formula drawable created",spans.isNotEmpty())
  val scenario=ActivityScenario.launch(MainActivity::class.java)
  try{
   scenario.onActivity{activity->val view=android.widget.TextView(activity);view.setBackgroundColor(android.graphics.Color.WHITE);view.setTextColor(android.graphics.Color.BLACK);(activity.findViewById<android.view.ViewGroup>(android.R.id.content)).addView(view,android.widget.FrameLayout.LayoutParams(600,300));markwon.setParsedMarkdown(view,rendered)}
   val deadline=System.currentTimeMillis()+15000
   while(System.currentTimeMillis()<deadline&&spans.any{!it.drawable.hasResult()}&&error.get()==null)Thread.sleep(100)
   Assert.assertNull("Formula rendering failed",error.get())
   Assert.assertTrue("Formula remained unrendered",spans.all{it.drawable.hasResult()})
   val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
   File(context.getExternalFilesDir(null),"latex-validation.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
  }finally{scenario.close()}
 }
}
