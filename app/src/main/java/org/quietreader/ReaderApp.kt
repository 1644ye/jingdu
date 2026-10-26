package org.quietreader

import android.app.Application
import org.quietreader.data.LibraryStore

class ReaderApp : Application() {
    val store: LibraryStore by lazy { LibraryStore(this) }
}
