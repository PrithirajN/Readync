package io.github.prithirajn.trackersync

/**
 * Which edition this is: the full app, or the one to share (no Kindle, Libby or Audible). Both
 * are called Readync; the shared one has its own app ID and a dog-eared corner on its icon.
 */
object Edition {
    val full: Boolean = BuildConfig.FULL
    const val name: String = "Readync"
}
