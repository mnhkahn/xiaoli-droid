package com.cyeam.xiaoli

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StorageFlowTest {
 @Test fun draftFilesAreAtomicAndScopedToServerDeviceAndSession(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val drafts=Drafts(context)
  val photo=File(context.filesDir,"draft-test-photo.jpg").apply{writeBytes(byteArrayOf(1,2,3))}
  val document=File(context.filesDir,"draft-test-document.md").apply{writeText("private")}
  val owner="draft-test-owner";val session="draft-test-session"
  try{
   drafts.save(owner,session,"未发送",listOf(photo.path),listOf(ChatImage("remote","/mobile/v1/media/remote")),"stable-request",listOf(document.path),emptyList(),true)
   val restored=Drafts(context).load(owner,session)!!
   assertEquals("未发送",restored.text);assertEquals("stable-request",restored.request);assertEquals(photo.path,restored.photos.single());assertEquals(document.path,restored.documents.single());assertTrue(restored.search)
   assertNull(drafts.load("another-owner",session));assertNull(drafts.load(owner,"another-session"))
   photo.delete();assertTrue(drafts.load(owner,session)!!.photos.isEmpty())
   drafts.save(owner,session,"",emptyList(),emptyList(),null,emptyList(),emptyList(),false);assertNull(drafts.load(owner,session))
  }finally{photo.delete();document.delete()}
 }
 @Test fun savesVerifiedImageIntoMediaStoreWithoutStoragePermission()=runBlocking{
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val server=MockWebServer()
  val bitmap=android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888)
  val output=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);bitmap.recycle()
  val bytes=output.toByteArray()
  server.enqueue(MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(bytes)));server.start()
  try{
   val api=Api(server.url("/").toString().trimEnd('/'),"test-device","test-token")
   val uri=saveImage(context,api,"/image.png")
   try{
    val saved=context.contentResolver.openInputStream(uri)!!.use{it.readBytes()};assertArrayEquals(bytes,saved)
    context.contentResolver.query(uri,arrayOf(android.provider.MediaStore.Images.Media.MIME_TYPE,android.provider.MediaStore.Images.Media.IS_PENDING),null,null,null)!!.use{cursor->assertTrue(cursor.moveToFirst());assertEquals("image/png",cursor.getString(0));assertEquals(0,cursor.getInt(1))}
    val request=server.takeRequest();assertEquals("Bearer test-token",request.getHeader("Authorization"));assertEquals("test-device",request.getHeader("Device-Id"))
   }finally{context.contentResolver.delete(uri,null,null)}
  }finally{server.shutdown()}
 }
}
