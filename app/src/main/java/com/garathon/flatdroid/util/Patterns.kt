package com.garathon.flatdroid.util

/** Runs of white space, to split or collapse text by. */
val WHITESPACE = Regex("\\s+")

/** A usable environment variable name: anything else could be read by `env` as an option or the command. */
val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
