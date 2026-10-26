import org.quietreader.core.Protocol
import java.io.File

/** Optional desktop verification; timings measure parsing only, never Android UI performance. */
fun main(args: Array<String>) {
    val files = if (args.isNotEmpty()) args.map(::File) else
        File("core/src/main/resources/protocol").listFiles()!!.filter { it.extension == "json" && !it.name.contains("schema") } +
            File("test-data/generated").listFiles().orEmpty().filter { it.extension == "json" && it.name != "manifest.json" }
    for (file in files) {
        val start = System.nanoTime()
        val result = Protocol.parse(file.readText(Charsets.UTF_8), file.name)
        check(result.valid) { "${file.name}: ${result.errors}" }
        check(result.warnings.isEmpty()) { "${file.name}: ${result.warnings}" }
        val millis = (System.nanoTime() - start) / 1_000_000
        println("PASS ${file.name}: ${file.length()} bytes, ${result.document!!.getAsJsonArray("books").size()} books, desktop parse ${millis} ms")
    }
}
