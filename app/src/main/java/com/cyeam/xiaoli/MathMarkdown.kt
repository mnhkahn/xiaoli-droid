package com.cyeam.xiaoli

/** Normalize common model math delimiters while keeping code and currency intact. */
fun normalizeMathMarkdown(text:String):String {
 val protected=Regex("(?s)```.*?(?:```|$)|`[^`\\n]*`")
 fun math(value:String):String {
  var result=Regex("\\\\\\[([\\s\\S]*?)\\\\\\]").replace(value){"\n\$\$\n${it.groupValues[1].trim()}\n\$\$\n"}
  result=Regex("\\\\\\(([\\s\\S]*?)\\\\\\)").replace(result){"\$\$${it.groupValues[1]}\$\$"}
  return Regex("(?<!\\$)\\$(?!\\$)([^\\$\\n]+)\\$(?!\\$)").replace(result){match->if(Regex("[=^_{}+*/\\\\]").containsMatchIn(match.groupValues[1]))"\$\$${match.groupValues[1]}\$\$" else match.value}
 }
 val output=StringBuilder();var start=0
 for(block in protected.findAll(text)){output.append(math(text.substring(start,block.range.first)));output.append(block.value);start=block.range.last+1}
 output.append(math(text.substring(start)));return output.toString()
}
