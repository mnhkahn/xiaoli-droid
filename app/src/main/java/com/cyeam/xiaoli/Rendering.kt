package com.cyeam.xiaoli

import android.os.Process
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/** Keep parsing and formula rasterization from competing with input and frames. */
object Rendering {
 private fun worker(name:String)=java.util.concurrent.ThreadFactory{task->Thread({Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run()},name)}
 private val parser=Executors.newSingleThreadExecutor(worker("xiaoli-markdown"))
 val dispatcher=parser.asCoroutineDispatcher()
 val formulas=Executors.newSingleThreadExecutor(worker("xiaoli-formulas"))
}
