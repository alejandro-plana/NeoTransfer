package dev.duardo.neotransfer.app

/**
 * The appearances a journey walks in. Dark and 1.5× font are there for their captures: without captures
 * (`-Pcaptures=false`, as in the CI) each journey runs once, in light.
 */
internal fun journeyModes(): List<Array<String>> =
    (if (System.getProperty("roborazzi.test.record") == "true") listOf("light", "dark", "font150") else listOf("light")).map { arrayOf(it) }
