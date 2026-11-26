package com.cyeam.xiaoli
import org.junit.Assert.*
import org.junit.Test
class MathMarkdownTest {
 @Test fun rendersCommonModelDelimiters(){assertEquals("\$\$x^2\$\$",normalizeMathMarkdown("\\(x^2\\)"));assertEquals("\n\$\$\nx+y=2\n\$\$\n",normalizeMathMarkdown("\\[x+y=2\\]"));assertEquals("\$\$x+y\$\$",normalizeMathMarkdown("\$x+y\$"))}
 @Test fun preservesCodeCurrencyAndExistingBlocks(){val source="\$5 and \$6\n`\\(not math\\)`\n```txt\n\\[still code\\]\n```\n\$\$\nx^2\n\$\$";assertEquals(source,normalizeMathMarkdown(source))}
}
