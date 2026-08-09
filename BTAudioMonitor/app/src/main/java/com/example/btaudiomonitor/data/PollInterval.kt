package com.example.btaudiomonitor.data

/** See CLAUDE.md, "Poll interval for live stats". Shared by the repository's actual
 * polling loop and the UI's interval control, so one slider drives both. */
const val MIN_POLL_INTERVAL_MS = 500L
const val DEFAULT_POLL_INTERVAL_MS = 1000L
const val MAX_POLL_INTERVAL_MS = 5000L
