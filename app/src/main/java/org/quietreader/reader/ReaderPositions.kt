package org.quietreader.reader

import org.quietreader.core.Anchor
import org.quietreader.data.LibraryStore
import java.util.concurrent.Executors

/** All reader instances share ordered checkpoints; page layout can never delay an exit save. */
object ReaderPositions {
    private val writer=Executors.newSingleThreadExecutor { task -> Thread(task,"QuietReaderPositions") }

    fun save(store:LibraryStore,anchor:Anchor,progress:Float,completedSections:Set<String> = emptySet(),onError:(Exception)->Unit){
        write({store.savePosition(anchor,progress,completedSections)},{},onError)
    }

    /** Ordered reader mutations also include settings and annotations, so Main can flush before restore. */
    fun <T> write(task:()->T,done:(T)->Unit,onError:(Exception)->Unit){
        writer.execute {try{done(task())}catch(error:Exception){android.util.Log.e("QuietReaderPositions","Reader write failed",error);onError(error)}}
    }

    /** Only call from a background thread. New/reopened readers must see the preceding exit save. */
    fun awaitPendingWrites(){writer.submit {}.get()}
}
