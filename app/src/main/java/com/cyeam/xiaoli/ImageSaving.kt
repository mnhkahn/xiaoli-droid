package com.cyeam.xiaoli

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun saveImage(context:Context,api:Api,url:String)=withContext(Dispatchers.IO){
 val data=api.download(url)
 val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(data,0,data.size,bounds)
 require(bounds.outWidth>0&&bounds.outHeight>0){"下载的内容不是图片"}
 val mime=bounds.outMimeType?:"image/jpeg"
 val extension=when(mime){"image/png"->"png";"image/webp"->"webp";"image/gif"->"gif";else->"jpg"}
 val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"小李-${System.currentTimeMillis()}.$extension");put(MediaStore.Images.Media.MIME_TYPE,mime);put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/小李");put(MediaStore.Images.Media.IS_PENDING,1)}
 val resolver=context.contentResolver
 val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("无法创建相册文件")
 try{resolver.openOutputStream(uri)!!.use{it.write(data)};resolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null);uri}catch(e:Exception){resolver.delete(uri,null,null);throw e}
}
